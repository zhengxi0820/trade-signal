package com.xi.service;

import com.xi.handler.KDJHandler;
import com.xi.model.dto.PeriodBarDTO;
import com.xi.model.param.KDJParam;
import com.xi.model.query.StockQuoteQuery;
import com.xi.model.vo.CrossStockVO;
import com.xi.orm.entity.StockQuoteDO;
import com.xi.orm.mapper.StockQuoteMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 全市场扫描的窗口裁剪 + 结果缓存对拍测试。
 * 合成 6 只股票 × 7000 个工作日（约 27 年）的确定性行情，
 * 用 KDJHandler 全历史直算的结果作为基准，逐字段对拍窗口扫描结果。
 */
@SpringBootTest
class KDJScanWindowCacheTest {

    private static final String[] CODES = {"T00001", "T00002", "T00003", "T00004", "T00005", "T00006"};
    /** 固定数据末日（周五），保证周期完结判定与测试运行日期无关 */
    private static final String DATA_END = "20260731";
    private static final int DAYS = 7000;
    private static final DateTimeFormatter FMT = DateTimeFormatter.BASIC_ISO_DATE;

    @Autowired
    private KDJService kdjService;
    @Autowired
    private StockQuoteMapper stockQuoteMapper;
    @Autowired
    private ScanResultCache scanResultCache;
    @Autowired
    private ScanBarsCache scanBarsCache;
    @Autowired
    private JdbcTemplate jdbc;

    private final KDJHandler handler = new KDJHandler();

    @BeforeEach
    void setup() {
        // 水位实时查库，保证失效行为立即可见
        scanResultCache.watermarkTtlMs = 0;
        scanResultCache.clear();
        scanBarsCache.watermarkTtlMs = 0;
        scanBarsCache.clear();
        jdbc.update("delete from stock_quote");
        jdbc.update("delete from stock_info");
        jdbc.update("delete from work_day");
        for (String code : CODES) {
            jdbc.update("insert into stock_info(CODE, NAME, MARKET, BOARD_TYPE) values (?,?,?,?)",
                    code, "测试" + code, "SH", "0");
        }
        seedQuotes();
        // work_day 交易日历：与行情同 7000 个工作日 + 未来日历锚点（DATA_END 下一周周一），
        // 保证 DATA_END 所在周/月可通过"截断兜底"判定完结，且与 service 侧 periodCalendar 同源
        List<LocalDate> workDates = new ArrayList<>(DAYS);
        LocalDate wd = LocalDate.parse(DATA_END, FMT);
        while (workDates.size() < DAYS) {
            if (wd.getDayOfWeek() != DayOfWeek.SATURDAY && wd.getDayOfWeek() != DayOfWeek.SUNDAY) {
                workDates.add(wd);
            }
            wd = wd.minusDays(1);
        }
        for (int i = workDates.size() - 1; i >= 0; i--) {
            jdbc.update("insert into work_day (MARKET, TRADE_DATE) values ('SH', ?)",
                    workDates.get(i).format(FMT));
        }
        jdbc.update("insert into work_day (MARKET, TRADE_DATE) values ('SH', ?)", "20260803");
    }

    /** 与 service 侧 periodCalendar 同口径的日历（基于同一 work_day）。 */
    private KDJHandler.PeriodCalendar fixtureCalendar(String kdjType) {
        List<String> dates = jdbc.queryForList("select TRADE_DATE from work_day", String.class);
        Map<String, String> lastByKey = new HashMap<>();
        String maxDate = null;
        for (String d : dates) {
            String key = KDJHandler.periodKey(LocalDate.parse(d, FMT), kdjType);
            lastByKey.merge(key, d, (a, b) -> a.compareTo(b) >= 0 ? a : b);
            if (maxDate == null || d.compareTo(maxDate) > 0) {
                maxDate = d;
            }
        }
        return new KDJHandler.PeriodCalendar(lastByKey, maxDate);
    }

    /**
     * 确定性伪随机游走行情（LCG，纯 BigDecimal），价格夹在 [2,100]。
     * 6 只股票不同种子，覆盖各种 K/D 交叉形态。
     */
    private void seedQuotes() {
        List<Object[]> batch = new ArrayList<>(CODES.length * DAYS);
        for (int c = 0; c < CODES.length; c++) {
            long seed = 88172645463325252L + c * 2862933555777941757L;
            BigDecimal close = new BigDecimal("10").add(BigDecimal.valueOf(c * 7L));
            // 倒序生成日期，正序生成价格：先收集日期
            List<LocalDate> dates = new ArrayList<>(DAYS);
            LocalDate date = LocalDate.parse(DATA_END, FMT);
            while (dates.size() < DAYS) {
                if (date.getDayOfWeek() != DayOfWeek.SATURDAY && date.getDayOfWeek() != DayOfWeek.SUNDAY) {
                    dates.add(date);
                }
                date = date.minusDays(1);
            }
            for (int i = dates.size() - 1; i >= 0; i--) {
                seed = seed * 6364136223846793005L + 1442695040888963407L;
                int r = (int) ((seed >>> 16) % 1000);
                BigDecimal delta = BigDecimal.valueOf(r - 500).movePointLeft(3);
                BigDecimal open = close;
                close = close.add(delta);
                if (close.compareTo(new BigDecimal("2")) < 0) {
                    close = close.add(new BigDecimal("0.8"));
                } else if (close.compareTo(new BigDecimal("100")) > 0) {
                    close = close.subtract(new BigDecimal("0.8"));
                }
                BigDecimal spread = BigDecimal.valueOf(20 + r % 60).movePointLeft(3);
                BigDecimal high = close.max(open).add(spread);
                BigDecimal low = close.min(open).subtract(spread);
                batch.add(new Object[]{CODES[c], open, high, low, close, 10000L + r,
                        dates.get(i).format(FMT), "1"});
            }
        }
        jdbc.batchUpdate(
                "insert into stock_quote(CODE,OPEN,HIGH,LOW,CLOSE,VOLUME,TRADE_DATE,ADJUST)"
                        + " values (?,?,?,?,?,?,?,?)", batch);
    }

    @Test
    void goldCrossWindowEqualsFullHistory() {
        int hits = 0;
        for (String kdjType : new String[]{"0", "1", "2", "3"}) {
            KDJParam param = new KDJParam();
            param.setKdjType(kdjType);
            List<CrossStockVO> actual = kdjService.getGold(param);
            Map<String, CrossStockVO> expected = referenceScan(kdjType, ReferenceMode.GOLD, null);
            assertSameContent(expected, actual, "gold-cross kdjType=" + kdjType);
            hits += expected.size();
        }
        assertTrue(hits > 0, "基准扫描应至少命中一只金叉股，否则对拍是空集空转");
    }

    @Test
    void tradeSignalWindowEqualsFullHistory() {
        for (String kdjType : new String[]{"0", "1", "2", "3"}) {
            KDJParam param = new KDJParam();
            param.setKdjType(kdjType);
            List<CrossStockVO> actual = kdjService.getTradeSignalStockList(param);
            Map<String, CrossStockVO> expected = referenceScan(kdjType, ReferenceMode.TRADE, null);
            assertSameContent(expected, actual, "trade-signal kdjType=" + kdjType);
        }
    }

    @Test
    void allStocksWindowEqualsFullHistory() {
        for (String kdjType : new String[]{"0", "1", "2", "3"}) {
            KDJParam param = new KDJParam();
            param.setKdjType(kdjType);
            List<CrossStockVO> actual = kdjService.getAllStocks(param);
            Map<String, CrossStockVO> expected = referenceScan(kdjType, ReferenceMode.ALL, null);
            assertSameContent(expected, actual, "all-stocks kdjType=" + kdjType);
        }
    }

    /**
     * 指定历史截止周期：窗口锚点应锚在截止周期末而非今天，结果仍与全历史一致。
     */
    @Test
    void endPeriodAnchoredWindowEqualsFullHistory() {
        String endDate = "20260331";
        KDJParam param = new KDJParam();
        param.setKdjType("0");
        param.setTradeDate(endDate);
        List<CrossStockVO> actual = kdjService.getGold(param);
        Map<String, CrossStockVO> expected = referenceScan("0", ReferenceMode.GOLD, endDate);
        assertSameContent(expected, actual, "gold-cross anchored at " + endDate);
    }

    @Test
    void cacheHitAndWatermarkInvalidation() {
        KDJParam p1 = new KDJParam();
        p1.setKdjType("0");
        List<CrossStockVO> first = kdjService.getGold(p1);

        KDJParam p2 = new KDJParam();
        p2.setKdjType("0");
        List<CrossStockVO> second = kdjService.getGold(p2);
        assertSame(first, second, "同参数第二次请求应命中缓存");

        // 水位变化（新交易日数据入库）→ 旧缓存自动失效，重新计算
        jdbc.update("insert into stock_quote(CODE,OPEN,HIGH,LOW,CLOSE,VOLUME,TRADE_DATE,ADJUST)"
                        + " values (?,?,?,?,?,?,?,?)",
                "T00001", new BigDecimal("11"), new BigDecimal("11.5"), new BigDecimal("10.5"),
                new BigDecimal("11.2"), 9000L, "20260803", "1");
        KDJParam p3 = new KDJParam();
        p3.setKdjType("0");
        List<CrossStockVO> third = kdjService.getGold(p3);
        assertNotSame(first, third, "新数据入库后缓存应自动失效并重算");
        assertEquals(referenceScan("0", ReferenceMode.GOLD, null).keySet(),
                third.stream().map(CrossStockVO::getCode).collect(Collectors.toSet()),
                "重算结果仍应与全历史一致");

        // 手动清空后重算
        KDJParam p4 = new KDJParam();
        p4.setKdjType("1");
        List<CrossStockVO> before = kdjService.getGold(p4);
        assertTrue(kdjService.clearScanCache() > 0);
        KDJParam p5 = new KDJParam();
        p5.setKdjType("1");
        assertNotSame(before, kdjService.getGold(p5), "手动清空后应重算");
    }

    // ---------- 基准：全历史直算（不经过 service 的窗口与缓存） ----------

    private enum ReferenceMode {GOLD, TRADE, ALL}

    /**
     * 用 KDJHandler 对全历史数据直算扫描结果，复刻 service 的过滤规则。
     *
     * @param endInclusive 截止周期（日度 yyyymmdd），null = 不截断
     */
    private Map<String, CrossStockVO> referenceScan(String kdjType, ReferenceMode mode, String endInclusive) {
        Map<String, CrossStockVO> result = new HashMap<>();
        BigDecimal n = new BigDecimal("9");
        BigDecimal m = new BigDecimal("3");
        for (String code : CODES) {
            StockQuoteQuery query = new StockQuoteQuery();
            query.setCode(code);
            query.setAdjust("1");
            List<StockQuoteDO> full = stockQuoteMapper.queryAll(query);
            List<KDJHandler.PeriodBar> bars = handler.aggregate(full, kdjType,
                    LocalDate.parse(DATA_END, FMT), fixtureCalendar(kdjType));
            if (endInclusive != null) {
                bars = bars.stream().filter(b -> b.endDate.compareTo(endInclusive) <= 0)
                        .collect(Collectors.toList());
            }
            if (bars.isEmpty()) {
                continue;
            }
            List<KDJHandler.KdjValue> kdj = handler.calculate(bars, n, m, m);
            int last = kdj.size() - 1;
            KDJHandler.CrossPoint cross = null;
            if (mode == ReferenceMode.GOLD) {
                cross = handler.goldenCrossAt(kdj, last);
                if (cross == null) {
                    continue;
                }
            } else if (mode == ReferenceMode.TRADE) {
                if (!handler.isTradeSignal(bars, kdj, tradeSignalDefaults())) {
                    continue;
                }
                cross = handler.goldenCrossAt(kdj, last);
            } else {
                KDJHandler.CrossPoint gold = handler.goldenCrossAt(kdj, last);
                KDJHandler.CrossPoint death = handler.deathCrossAt(kdj, last);
                cross = gold != null ? gold : death;
            }
            CrossStockVO vo = new CrossStockVO();
            vo.setCode(code);
            KDJHandler.PeriodBar lastBar = bars.get(bars.size() - 1);
            vo.setClose(lastBar.close);
            vo.setK(kdj.get(last).k);
            vo.setD(kdj.get(last).d);
            vo.setJ(kdj.get(last).j);
            if (cross != null) {
                vo.setCrossValue(cross.crossValue);
            }
            result.put(code, vo);
        }
        return result;
    }

    /**
     * 与 KDJServiceImpl.fillTradeSignalDefaults 一致的默认参数。
     */
    private KDJParam tradeSignalDefaults() {
        KDJParam p = new KDJParam();
        p.setKdjType("0");
        p.setN(new BigDecimal("9"));
        p.setM1(new BigDecimal("3"));
        p.setM2(new BigDecimal("3"));
        p.setCurrGoldCrossMax(new BigDecimal("50"));
        p.setLastGoldCrossMax(new BigDecimal("20"));
        p.setLastDeathCrossMax(new BigDecimal("50"));
        p.setGoldInternalMin(new BigDecimal("5"));
        p.setGoldInternalMax(new BigDecimal("15"));
        p.setOpenClosePriceLimit("1");
        p.setGoldCrossLimit("1");
        return p;
    }

    /**
     * SQL 预聚合 vs KDJHandler.aggregate 全历史逐 bar 对拍（月/季）：
     * 覆盖 startDate/endDate/OHLC 全字段，保证两个聚合实现口径一致。
     */
    @Test
    void sqlAggregatedBarsMatchHandlerAggregation() {
        LocalDate today = LocalDate.now();
        String currentMonth = java.time.YearMonth.from(today)
                .format(java.time.format.DateTimeFormatter.ofPattern("yyyyMM"));
        String currentQuarter = today.getYear() + "Q" + ((today.getMonthValue() - 1) / 3 + 1);
        for (String code : new String[]{CODES[0], CODES[3]}) {
            StockQuoteQuery query = new StockQuoteQuery();
            query.setCode(code);
            query.setAdjust("1");
            List<StockQuoteDO> full = stockQuoteMapper.queryAll(query);
            for (String kdjType : new String[]{"2", "3"}) {
                List<KDJHandler.PeriodBar> expected = handler.aggregate(full, kdjType,
                        LocalDate.parse(DATA_END, FMT), fixtureCalendar(kdjType));
                List<PeriodBarDTO> actual = "2".equals(kdjType)
                        ? stockQuoteMapper.queryMonthlyBars(code, "1", "19900101", currentMonth)
                        : stockQuoteMapper.queryQuarterlyBars(code, "1", "19900101", currentQuarter);
                assertEquals(expected.size(), actual.size(),
                        code + " kdjType=" + kdjType + " bar 数不一致");
                for (int i = 0; i < expected.size(); i++) {
                    KDJHandler.PeriodBar e = expected.get(i);
                    PeriodBarDTO a = actual.get(i);
                    String label = code + " kdjType=" + kdjType + " bar[" + i + "] " + e.endDate;
                    assertEquals(e.startDate, a.getStartDate(), label + " startDate");
                    assertEquals(e.endDate, a.getEndDate(), label + " endDate");
                    assertBigDecimalEquals(e.open, a.getOpen(), label + " open");
                    assertBigDecimalEquals(e.high, a.getHigh(), label + " high");
                    assertBigDecimalEquals(e.low, a.getLow(), label + " low");
                    assertBigDecimalEquals(e.close, a.getClose(), label + " close");
                }
            }
        }
    }

    /**
     * 物化表路径对拍：stock_period_bar 有数据时月/季扫描走批量读物化表，
     * 结果必须与全历史基准一致。物化表内容用 KDJHandler.aggregate 全历史直算灌入。
     */
    @Test
    void aggTablePathMatchesFullHistory() {
        // 灌物化表：周 + 月 + 季，bars 由 handler 全历史聚合（等价于 scripts 物化口径）
        for (String code : CODES) {
            StockQuoteQuery query = new StockQuoteQuery();
            query.setCode(code);
            query.setAdjust("1");
            List<StockQuoteDO> full = stockQuoteMapper.queryAll(query);
            for (String kdjType : new String[]{"1", "2", "3"}) {
                for (KDJHandler.PeriodBar bar : handler.aggregate(full, kdjType,
                        LocalDate.parse(DATA_END, FMT), fixtureCalendar(kdjType))) {
                    jdbc.update("insert into stock_period_bar(PERIOD_TYPE,CODE,ADJUST,PERIOD_START,PERIOD_END,OPEN,HIGH,LOW,CLOSE)"
                                    + " values (?,?,?,?,?,?,?,?,?)",
                            kdjType, code, "1", bar.startDate, bar.endDate,
                            bar.open, bar.high, bar.low, bar.close);
                }
            }
        }
        try {
            for (String kdjType : new String[]{"1", "2", "3"}) {
                KDJParam param = new KDJParam();
                param.setKdjType(kdjType);
                List<CrossStockVO> actual = kdjService.getAllStocks(param);
                Map<String, CrossStockVO> expected = referenceScan(kdjType, ReferenceMode.ALL, null);
                assertSameContent(expected, actual, "agg-table all-stocks kdjType=" + kdjType);
            }
        } finally {
            jdbc.update("delete from stock_period_bar");
        }
    }

    /**
     * 数值字段对拍：窗口暖机残差理论上限 ~1e-13，叠加每步 1e-10 舍入再量子化，
     * 容差取 1e-9；股票集合（信号判定）与 close（直接取自行情）必须精确一致。
     */
    private static final BigDecimal TOLERANCE = new BigDecimal("1e-9");

    private void assertSameContent(Map<String, CrossStockVO> expected, List<CrossStockVO> actual, String label) {
        Map<String, CrossStockVO> actualMap = actual.stream()
                .collect(Collectors.toMap(CrossStockVO::getCode, v -> v));
        assertEquals(expected.keySet(), actualMap.keySet(), label + " 股票集合不一致");
        for (Map.Entry<String, CrossStockVO> e : expected.entrySet()) {
            CrossStockVO a = actualMap.get(e.getKey());
            CrossStockVO x = e.getValue();
            assertBigDecimalClose(x.getK(), a.getK(), label + " " + e.getKey() + " K");
            assertBigDecimalClose(x.getD(), a.getD(), label + " " + e.getKey() + " D");
            assertBigDecimalClose(x.getJ(), a.getJ(), label + " " + e.getKey() + " J");
            assertBigDecimalEquals(x.getClose(), a.getClose(), label + " " + e.getKey() + " close");
            assertBigDecimalClose(x.getCrossValue(), a.getCrossValue(), label + " " + e.getKey() + " crossValue");
        }
    }

    private void assertBigDecimalClose(BigDecimal expected, BigDecimal actual, String label) {
        if (expected == null || actual == null) {
            assertEquals(expected, actual, label);
        } else {
            assertTrue(expected.subtract(actual).abs().compareTo(TOLERANCE) < 0,
                    label + " expected=" + expected + " actual=" + actual);
        }
    }

    private void assertBigDecimalEquals(BigDecimal expected, BigDecimal actual, String label) {
        if (expected == null || actual == null) {
            assertEquals(expected, actual, label);
        } else {
            assertEquals(0, expected.compareTo(actual),
                    label + " expected=" + expected + " actual=" + actual);
        }
    }
}
