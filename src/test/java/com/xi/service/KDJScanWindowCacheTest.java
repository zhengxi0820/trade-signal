package com.xi.service;

import com.xi.handler.KDJHandler;
import com.xi.model.dto.KDJDTO;
import com.xi.model.dto.PeriodBarDTO;
import com.xi.model.param.KDJParam;
import com.xi.model.query.StockQuoteQuery;
import com.xi.model.vo.CrossStockVO;
import com.xi.orm.entity.StockQuoteDO;
import com.xi.orm.mapper.StockQuoteMapper;
import com.xi.service.Impl.KDJServiceImpl;
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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
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
    private KDJServiceImpl kdjServiceImpl;
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
        // 物化就绪标记/周期日历短缓存（60s）跨用例会串，逐用例重置
        kdjServiceImpl.resetReadyCaches();
        jdbc.update("delete from stock_quote");
        jdbc.update("delete from stock_info");
        jdbc.update("delete from work_day");
        jdbc.update("delete from stock_period_bar");
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
        return referenceScan(kdjType, mode, endInclusive, tradeSignalDefaults());
    }

    /** 同上，trade-signal 过滤参数可覆盖（默认 goldInternalMax=15 之外的窗口用）。 */
    private Map<String, CrossStockVO> referenceScan(String kdjType, ReferenceMode mode, String endInclusive,
                                                    KDJParam tradeParams) {
        Map<String, CrossStockVO> result = new HashMap<>();
        BigDecimal n = new BigDecimal("9");
        BigDecimal m = new BigDecimal("3");
        for (String code : CODES) {
            StockQuoteQuery query = new StockQuoteQuery();
            query.setCode(code);
            query.setAdjust("1");
            List<StockQuoteDO> full = stockQuoteMapper.queryAll(query);
            List<KDJHandler.PeriodBar> allBars = handler.aggregate(full, kdjType,
                    LocalDate.parse(DATA_END, FMT), fixtureCalendar(kdjType));
            List<KDJHandler.PeriodBar> bars = allBars;
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
                if (!handler.isTradeSignal(bars, kdj, tradeParams)) {
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
            vo.setNextClose(referenceNextClose(allBars, kdjType, endInclusive));
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
     * nextClose 基准（复刻 service 严格下一期口径）：截止后第一根 bar，且其期末
     * 不超过日历上下一期的期末；无截止参数 / 无下一期 / 跳期（整期停牌）返回 null。
     */
    private BigDecimal referenceNextClose(List<KDJHandler.PeriodBar> allBars, String kdjType, String endInclusive) {
        if (endInclusive == null) {
            return null;
        }
        KDJHandler.PeriodBar next = null;
        for (KDJHandler.PeriodBar bar : allBars) {
            if (bar.endDate.compareTo(endInclusive) > 0) {
                next = bar;
                break;
            }
        }
        if (next == null) {
            return null;
        }
        // 日历上一期期末（lastDayByKey 插入序 = 时间序，期末逐期递增）
        String nextEnd = null;
        boolean cutoffFound = false;
        for (Map.Entry<String, String> e : fixtureCalendar(kdjType).lastDayByKey.entrySet()) {
            if (e.getValue().compareTo(endInclusive) <= 0) {
                cutoffFound = true;
            } else if (cutoffFound) {
                nextEnd = e.getValue();
                break;
            }
        }
        if (nextEnd == null || next.endDate.compareTo(nextEnd) > 0) {
            return null;
        }
        return next.close;
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
        seedAggTable(CODES);
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

    /** 用 KDJHandler.aggregate 全历史直算灌物化表（等价于 scripts 物化口径）。 */
    private void seedAggTable(String[] codes) {
        for (String code : codes) {
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
    }

    /**
     * 历史截止批量兜底对拍（2026-08-26 P0）：截止早于 132 根窗口可切范围时，
     * 周/月走物化表按截止上界批量读、日线批量读原始行聚合，结果仍须与全历史基准一致。
     * 月线截止 2020-12（窗口 2015-08 起，切片 ~64 根 < 82/97）→ 必然落兜底；
     * 周线/日线截止同理选老日期。
     */
    @Test
    void historicalCutoffBatchFallbackMatchesFullHistory() {
        seedAggTable(CODES);
        try {
            // 月线：gold-cross（需 82）与 trade-signal（需 97）均不足切片
            for (ReferenceMode mode : new ReferenceMode[]{ReferenceMode.GOLD, ReferenceMode.TRADE}) {
                KDJParam param = new KDJParam();
                param.setKdjType("2");
                param.setTradeDate("20201215");
                List<CrossStockVO> actual = mode == ReferenceMode.GOLD
                        ? kdjService.getGold(param) : kdjService.getTradeSignalStockList(param);
                Map<String, CrossStockVO> expected = referenceScan("2", mode, "20201231");
                assertSameContent(expected, actual, "monthly cutoff fallback mode=" + mode);
            }
            // 周线：截止 2023-06（窗口 2024-03 起，切片 0 根）
            KDJParam weekly = new KDJParam();
            weekly.setKdjType("1");
            weekly.setTradeDateMin("20230626");
            weekly.setTradeDateMax("20230630");
            assertSameContent(referenceScan("1", ReferenceMode.GOLD, "20230630"),
                    kdjService.getGold(weekly), "weekly cutoff fallback");
            // 日线：截止 2023-06（窗口 ~132 个交易日在 2026）——批量原始行 + Java 聚合路径
            KDJParam daily = new KDJParam();
            daily.setKdjType("0");
            daily.setTradeDate("20230630");
            assertSameContent(referenceScan("0", ReferenceMode.GOLD, "20230630"),
                    kdjService.getGold(daily), "daily cutoff fallback");
        } finally {
            jdbc.update("delete from stock_period_bar");
        }
    }

    /**
     * goldInternalMax > 50 时窗口需求超过 132 根缓存宽度：全部股票按锚定批量重算
     * （无截止参数 → 锚在最新、无上界），结果与全历史基准一致。
     */
    @Test
    void oversizedWindowBatchFallbackMatchesFullHistory() {
        seedAggTable(CODES);
        try {
            KDJParam param = new KDJParam();
            param.setKdjType("2");
            param.setGoldInternalMax(new BigDecimal("60"));
            List<CrossStockVO> actual = kdjService.getTradeSignalStockList(param);
            KDJParam refParams = tradeSignalDefaults();
            refParams.setGoldInternalMax(new BigDecimal("60"));
            Map<String, CrossStockVO> expected = referenceScan("2", ReferenceMode.TRADE, null, refParams);
            assertSameContent(expected, actual, "monthly oversized window fallback");
        } finally {
            jdbc.update("delete from stock_period_bar");
        }
    }

    /**
     * 长期停牌股护栏（2026-08-26 P2）：总 bar 数 > 132 但 132nd-from-last 早于 SQL 窗口下界
     * （两段行情：2019~2021 + 2026），带下界批读只拿到近期段 → 必须触发二次全量批读补齐，
     * lastN 窗口与全历史一致，扫描结果不变。
     */
    @Test
    void suspensionGapStockGetsFullReadGuard() {
        String gapCode = "T00007";
        jdbc.update("insert into stock_info(CODE, NAME, MARKET, BOARD_TYPE) values (?,?,?,?)",
                gapCode, "测试停牌", "SH", "0");
        seedGapDailies(gapCode);
        seedAggTable(new String[]{gapCode});
        try {
            KDJParam param = new KDJParam();
            param.setKdjType("1");
            List<CrossStockVO> actual = kdjService.getAllStocks(param);
            CrossStockVO vo = actual.stream()
                    .filter(v -> gapCode.equals(v.getCode())).findFirst().orElse(null);
            // 基准：全历史直算
            StockQuoteQuery query = new StockQuoteQuery();
            query.setCode(gapCode);
            query.setAdjust("1");
            List<KDJHandler.PeriodBar> full = handler.aggregate(stockQuoteMapper.queryAll(query), "1",
                    LocalDate.parse(DATA_END, FMT), fixtureCalendar("1"));
            assertTrue(full.size() > 132, "停牌股总 bar 数应 > 132（两段行情），实际 " + full.size());
            List<KDJHandler.KdjValue> kdj = handler.calculate(full,
                    new BigDecimal("9"), new BigDecimal("3"), new BigDecimal("3"));
            int last = kdj.size() - 1;
            assertNotNull(vo, "停牌股应出现在扫描结果中");
            assertBigDecimalClose(kdj.get(last).k, vo.getK(), "停牌股 K 与全历史一致（暖机容差）");
            assertBigDecimalClose(kdj.get(last).d, vo.getD(), "停牌股 D 与全历史一致（暖机容差）");
            assertEquals(0, full.get(last).close.compareTo(vo.getClose()), "close 与全历史一致");
            // bars 缓存窗口必须补满 132 根且第一根在下界之前（证明二次全量批读生效）
            List<KDJHandler.PeriodBar> window = scanBarsCache.get(ScanBarsCache.key(gapCode, "1", "1"));
            assertNotNull(window);
            assertEquals(132, window.size(), "窗口应补满 132 根");
            assertTrue(window.get(0).endDate.compareTo("20240101") < 0,
                    "窗口第一根应来自停牌前的老段，实际 " + window.get(0).endDate);
        } finally {
            jdbc.update("delete from stock_period_bar");
        }
    }

    /**
     * 「仅看涨」严格下一期口径：截止周 2026-07-10（周五），下一周 07-13~07-17 整期停牌
     * （无 bar），再下一周 07-20 起复牌——第一根 bar 已跳过下一期，nextClose 必须为 null
     * （复牌周收盘价不算"下一周期收盘价"）；同期未停牌股正常填充下一周 close。
     */
    @Test
    void nextCloseNullWhenNextPeriodFullySuspended() {
        String suspended = "T00003";
        jdbc.update("delete from stock_quote where code = ? and trade_date between '20260713' and '20260717'",
                suspended);
        KDJParam weekly = new KDJParam();
        weekly.setKdjType("1");
        weekly.setTradeDateMin("20260706");
        weekly.setTradeDateMax("20260710");
        List<CrossStockVO> actual = kdjService.getAllStocks(weekly);
        assertTrue(actual.size() >= CODES.length, "全部股票都应出现在 all-stocks 结果中");
        for (CrossStockVO vo : actual) {
            if (suspended.equals(vo.getCode())) {
                assertNull(vo.getNextClose(), "下一期整期停牌（跳期）的股 nextClose 应为 null，即使复牌周上涨");
            } else {
                KDJHandler.PeriodBar next = weeklyBar(vo.getCode(), "20260713", "20260717");
                assertNotNull(next, vo.getCode() + " 下一周 bar 应存在");
                assertNotNull(vo.getNextClose(), vo.getCode() + " 正常股 nextClose 应填充");
                assertEquals(0, next.close.compareTo(vo.getNextClose()),
                        vo.getCode() + " nextClose 应等于下一周收盘价");
            }
        }
    }

    /** 该股指定起止日期的周 bar（从全历史聚合中查找），无则返回 null。 */
    private KDJHandler.PeriodBar weeklyBar(String code, String start, String end) {
        StockQuoteQuery query = new StockQuoteQuery();
        query.setCode(code);
        query.setAdjust("1");
        for (KDJHandler.PeriodBar bar : handler.aggregate(stockQuoteMapper.queryAll(query), "1",
                LocalDate.parse(DATA_END, FMT), fixtureCalendar("1"))) {
            if (start.equals(bar.startDate) && end.equals(bar.endDate)) {
                return bar;
            }
        }
        return null;
    }

    /** 两段行情：2019-01~2021-06（约 130 根周线）+ 2026-01~2026-07（约 31 根周线）。 */
    private void seedGapDailies(String code) {
        List<Object[]> batch = new ArrayList<>();
        for (LocalDate[] range : new LocalDate[][]{
                {LocalDate.of(2019, 1, 2), LocalDate.of(2021, 6, 30)},
                {LocalDate.of(2026, 1, 1), LocalDate.of(2026, 7, 31)}}) {
            for (LocalDate d = range[0]; !d.isAfter(range[1]); d = d.plusDays(1)) {
                if (d.getDayOfWeek() == DayOfWeek.SATURDAY || d.getDayOfWeek() == DayOfWeek.SUNDAY) {
                    continue;
                }
                BigDecimal open = new BigDecimal("10");
                BigDecimal close = new BigDecimal("10.5");
                batch.add(new Object[]{code, open, new BigDecimal("11"), new BigDecimal("9.8"), close,
                        10000L, d.format(FMT), "1"});
            }
        }
        jdbc.batchUpdate("insert into stock_quote(CODE,OPEN,HIGH,LOW,CLOSE,VOLUME,TRADE_DATE,ADJUST)"
                + " values (?,?,?,?,?,?,?,?)", batch);
    }

    /**
     * 单票 series 周/月/季读物化表对拍（2026-08-26 P1）：物化覆盖请求周期时直接读表，
     * 序列（含早期 bar 与 KDJ 递推）与"全历史日线 + Java 聚合"逐字段一致。
     */
    @Test
    void seriesAggTablePathMatchesDailyAggregation() {
        seedAggTable(CODES);
        try {
            for (String kdjType : new String[]{"1", "2", "3"}) {
                assertSeriesEqualsReference(seriesParam(CODES[0], kdjType), null);
                assertSeriesEqualsReference(seriesParam(CODES[2], kdjType), null);
            }
            // 带截止周期：周（tradeDateMin/Max=周末 yyyymmdd）、月（tradeDate 截位到月）、季（末月 yyyymm）
            KDJParam weeklyCut = seriesParam(CODES[0], "1");
            weeklyCut.setTradeDateMin("20230626");
            weeklyCut.setTradeDateMax("20230630");
            assertSeriesEqualsReference(weeklyCut, "20230630");
            assertSeriesEqualsReference(seriesParam(CODES[0], "2", "20200615"), "20200630");
            KDJParam quarterCut = seriesParam(CODES[0], "3");
            quarterCut.setTradeDateMin("202110");
            quarterCut.setTradeDateMax("202112");
            assertSeriesEqualsReference(quarterCut, "20211231");
        } finally {
            jdbc.update("delete from stock_period_bar");
        }
    }

    /**
     * 物化滞后回退：物化表缺最新周期（模拟自愈空窗）时，无截止 series 应回退全历史
     * 日线聚合，最新周期不丢。
     */
    @Test
    void seriesFallsBackWhenAggTableStale() {
        seedAggTable(CODES);
        jdbc.update("delete from stock_period_bar where period_type = '2' and period_end > '20241231'");
        kdjServiceImpl.resetReadyCaches();
        try {
            List<KDJDTO> actual = kdjService.getAllKDJ(seriesParam(CODES[0], "2"));
            List<KDJDTO> expected = seriesReference(CODES[0], "2", null);
            assertEquals(expected.size(), actual.size(), "回退路径不应丢最新周期");
            assertEquals(expected.get(expected.size() - 1).getTradeDate(),
                    actual.get(actual.size() - 1).getTradeDate(), "最后一根应是最新已完结月");
        } finally {
            jdbc.update("delete from stock_period_bar");
        }
    }

    /** series 入参构造；tradeDate 非空时按月线字段填（周/季的 min/max 由调用方补设）。 */
    private KDJParam seriesParam(String code, String kdjType, String tradeDate) {
        KDJParam param = seriesParam(code, kdjType);
        param.setTradeDate(tradeDate);
        return param;
    }

    private KDJParam seriesParam(String code, String kdjType) {
        KDJParam param = new KDJParam();
        param.setCode(code);
        param.setKdjType(kdjType);
        return param;
    }

    /** series 对拍：service 出参 vs 全历史日线聚合 + handler 直算逐字段比对。 */
    private void assertSeriesEqualsReference(KDJParam param, String endInclusive) {
        String code = param.getCode();
        String kdjType = param.getKdjType();
        List<KDJDTO> actual = kdjService.getAllKDJ(param);
        List<KDJDTO> expected = seriesReference(code, kdjType, endInclusive);
        assertEquals(expected.size(), actual.size(), code + " kdjType=" + kdjType + " 序列长度");
        for (int i = 0; i < expected.size(); i++) {
            KDJDTO x = expected.get(i);
            KDJDTO a = actual.get(i);
            String label = code + " kdjType=" + kdjType + " bar[" + i + "]";
            assertEquals(x.getTradeDate(), a.getTradeDate(), label + " tradeDate");
            assertEquals(x.getTradeDateMin(), a.getTradeDateMin(), label + " tradeDateMin");
            assertEquals(x.getTradeDateMax(), a.getTradeDateMax(), label + " tradeDateMax");
            assertEquals(0, x.getOpen().compareTo(a.getOpen()), label + " open");
            assertEquals(0, x.getHigh().compareTo(a.getHigh()), label + " high");
            assertEquals(0, x.getLow().compareTo(a.getLow()), label + " low");
            assertEquals(0, x.getClose().compareTo(a.getClose()), label + " close");
            assertEquals(0, x.getK().compareTo(a.getK()), label + " k");
            assertEquals(0, x.getD().compareTo(a.getD()), label + " d");
            assertEquals(0, x.getJ().compareTo(a.getJ()), label + " j");
            assertEquals(x.getCrossType(), a.getCrossType(), label + " crossType");
            if (x.getCrossValue() != null) {
                assertEquals(0, x.getCrossValue().compareTo(a.getCrossValue()), label + " crossValue");
            } else {
                assertNull(a.getCrossValue(), label + " crossValue");
            }
        }
    }

    /** series 基准：全历史日线 → handler 聚合 → KDJ → 交叉标注（复刻 getAllKDJ 组装）。 */
    private List<KDJDTO> seriesReference(String code, String kdjType, String endInclusive) {
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
        List<KDJHandler.KdjValue> kdj = handler.calculate(bars,
                new BigDecimal("9"), new BigDecimal("3"), new BigDecimal("3"));
        List<KDJDTO> result = new ArrayList<>(bars.size());
        for (int i = 0; i < bars.size(); i++) {
            KDJDTO dto = new KDJDTO();
            dto.setOpen(bars.get(i).open);
            dto.setHigh(bars.get(i).high);
            dto.setLow(bars.get(i).low);
            dto.setClose(bars.get(i).close);
            dto.setK(kdj.get(i).k);
            dto.setD(kdj.get(i).d);
            dto.setJ(kdj.get(i).j);
            switch (kdjType) {
                case "1":
                    dto.setTradeDateMin(bars.get(i).startDate);
                    dto.setTradeDateMax(bars.get(i).endDate);
                    break;
                case "3":
                    dto.setTradeDateMin(bars.get(i).startDate.substring(0, 6));
                    dto.setTradeDateMax(bars.get(i).endDate.substring(0, 6));
                    break;
                default:
                    dto.setTradeDate(bars.get(i).endDate);
            }
            KDJHandler.CrossPoint gold = handler.goldenCrossAt(kdj, i);
            KDJHandler.CrossPoint death = handler.deathCrossAt(kdj, i);
            if (gold != null) {
                dto.setCrossType("gold");
                dto.setCrossValue(gold.crossValue);
            } else if (death != null) {
                dto.setCrossType("death");
                dto.setCrossValue(death.crossValue);
            }
            result.add(dto);
        }
        return result;
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
            assertBigDecimalEquals(x.getNextClose(), a.getNextClose(), label + " " + e.getKey() + " nextClose");
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
