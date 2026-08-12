package com.xi.service.Impl;

import com.xi.handler.KDJHandler;
import com.xi.model.dto.KDJDTO;
import com.xi.model.dto.PeriodBarDTO;
import com.xi.model.param.KDJParam;
import com.xi.model.param.WorkDayParam;
import com.xi.model.query.StockQuoteQuery;
import com.xi.model.query.WorkDayQuery;
import com.xi.model.vo.CrossStockVO;
import com.xi.model.vo.WorkDayVO;
import com.xi.orm.entity.PeriodBarDO;
import com.xi.orm.entity.StockInfoDO;
import com.xi.orm.entity.StockQuoteDO;
import com.xi.orm.entity.WorkDayDO;
import com.xi.orm.mapper.PeriodBarMapper;
import com.xi.orm.mapper.StockInfoMapper;
import com.xi.orm.mapper.StockQuoteMapper;
import com.xi.orm.mapper.WorkDayMapper;
import com.xi.service.KDJService;
import com.xi.service.ScanBarsCache;
import com.xi.service.ScanResultCache;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
public class KDJServiceImpl implements KDJService {

    private static final String DEFAULT_ADJUST = "1";
    private static final String DEFAULT_KDJ_TYPE = "0";
    private static final BigDecimal DEFAULT_N = new BigDecimal("9");
    private static final BigDecimal DEFAULT_M = new BigDecimal("3");
    private static final BigDecimal DEFAULT_LAST_GOLD_CROSS_MAX = new BigDecimal("20");
    private static final BigDecimal DEFAULT_CURR_GOLD_CROSS_MAX = new BigDecimal("50");
    private static final BigDecimal DEFAULT_LAST_DEATH_CROSS_MAX = new BigDecimal("50");
    private static final BigDecimal DEFAULT_GOLD_INTERNAL_MIN = new BigDecimal("5");
    private static final BigDecimal DEFAULT_GOLD_INTERNAL_MAX = new BigDecimal("15");
    private static final String SWITCH_ON = "1";
    /**
     * 扫描窗口暖机周期数。KDJ 递推误差每周期衰减 (1-1/m1)，m1=3 时 80 周期后
     * 初始条件残差 < 1e-13，叠加每步 1e-10 舍入再量子化，窗口与全历史结果
     * 偏差不超 1e-9 量级——远低于交叉判断的 K-D 差与展示精度（2 位小数），
     * 信号判定（哪些股票入选）与全历史完全一致。
     */
    private static final int SCAN_WARMUP_BARS = 80;
    /** 金叉/死叉判断需用到当前根及其前一根，基础窗口 = 暖机 + 2 */
    private static final int SCAN_BASE_BARS = SCAN_WARMUP_BARS + 2;
    /**
     * bars 缓存的固定窗口：暖机 80 + 回看余量 50 + 2 = 132。
     * 回看余量决定可走缓存的 goldInternalMax 上限（≤50），超出按实际参数重算不缓存。
     */
    private static final int SCAN_CACHE_WINDOW = 132;
    /** 全市场扫描批量加载的每批股票数（按索引顺序读，避免单股随机 IO） */
    private static final int SCAN_BATCH_SIZE = 200;

    // 入参白名单（SECURITY.md 2.4：外部输入校验类型/长度/白名单）
    private static final Set<String> ADJUST_VALUES = Set.of("0", "1", "2");
    private static final Set<String> KDJ_TYPE_VALUES = Set.of("0", "1", "2", "3");
    private static final Set<String> SWITCH_VALUES = Set.of("0", "1");
    private static final Pattern CODE_PATTERN = Pattern.compile("^[0-9A-Za-z]{1,12}$");
    private static final Pattern MARKET_PATTERN = Pattern.compile("^[0-9A-Za-z]{1,10}$");
    // yyyymm 或 yyyymmdd
    private static final Pattern DATE_PATTERN = Pattern.compile("^\\d{6}(\\d{2})?$");

    @Autowired
    private StockQuoteMapper stockQuoteMapper;

    @Autowired
    private WorkDayMapper workDayMapper;

    @Autowired
    private StockInfoMapper stockInfoMapper;

    @Autowired
    private PeriodBarMapper periodBarMapper;

    @Autowired
    private ScanResultCache scanResultCache;

    @Autowired
    private ScanBarsCache scanBarsCache;

    private final KDJHandler kdjHandler = new KDJHandler();

    /** 周期物化就绪标记用的短缓存（60s）：type -> [maxPeriodEnd | 最新已完结周期key, 时间戳] */
    private static final long READY_CACHE_TTL_MS = 60_000;
    private final Map<String, String[]> periodBarMaxCache = new ConcurrentHashMap<>();
    private final Map<String, String[]> latestPeriodEndCache = new ConcurrentHashMap<>();

    @Override
    public List<KDJDTO> getAllKDJ(KDJParam kdjParam) {
        fillCommonDefaults(kdjParam);
        List<KDJHandler.PeriodBar> bars = loadPeriodBars(kdjParam, loadDailies(kdjParam));
        List<KDJHandler.KdjValue> kdjList = kdjHandler.calculate(bars, kdjParam.getN(), kdjParam.getM1(), kdjParam.getM2());
        List<KDJDTO> result = new ArrayList<>(bars.size());
        for (int i = 0; i < bars.size(); i++) {
            KDJDTO dto = new KDJDTO();
            KDJHandler.PeriodBar bar = bars.get(i);
            KDJHandler.KdjValue value = kdjList.get(i);
            dto.setOpen(bar.open);
            dto.setHigh(bar.high);
            dto.setLow(bar.low);
            dto.setClose(bar.close);
            dto.setK(value.k);
            dto.setD(value.d);
            dto.setJ(value.j);
            fillDateFields(dto::setTradeDate, dto::setTradeDateMin, dto::setTradeDateMax,
                    bar, kdjParam.getKdjType());
            KDJHandler.CrossPoint gold = kdjHandler.goldenCrossAt(kdjList, i);
            KDJHandler.CrossPoint death = kdjHandler.deathCrossAt(kdjList, i);
            if (gold != null && withinCurrGoldCrossMax(gold, kdjParam.getCurrGoldCrossMax())) {
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

    @Override
    public List<WorkDayVO> getPeriods(WorkDayParam param) {
        requireEnum("kdjType", param.getKdjType(), KDJ_TYPE_VALUES);
        requirePattern("market", param.getMarket(), MARKET_PATTERN);
        if (!StringUtils.hasText(param.getKdjType())) {
            param.setKdjType(DEFAULT_KDJ_TYPE);
        }
        WorkDayQuery query = new WorkDayQuery();
        query.setMarket(param.getMarket());
        List<String> tradeDates = workDayMapper.queryAll(query).stream()
                .map(WorkDayDO::getTradeDate)
                .toList();
        List<KDJHandler.PeriodBar> bars = kdjHandler.aggregateDates(tradeDates, param.getKdjType(), LocalDate.now());
        // 时间倒序（最新周期在前），供前端选择器展示
        List<WorkDayVO> result = new ArrayList<>(bars.size());
        for (int i = bars.size() - 1; i >= 0; i--) {
            WorkDayVO vo = new WorkDayVO();
            fillDateFields(vo::setTradeDate, vo::setTradeDateMin, vo::setTradeDateMax,
                    bars.get(i), param.getKdjType());
            result.add(vo);
        }
        return result;
    }

    @Override
    public boolean isScanDataReady(KDJParam kdjParam) {
        fillCommonDefaults(kdjParam);
        String type = kdjParam.getKdjType();
        // 日线直读 stock_quote，无物化依赖，恒就绪
        if ("0".equals(type)) {
            return true;
        }
        String maxEnd = periodBarMaxEnd(type);
        if (maxEnd == null) {
            // 物化表为空（首启前）→ 未就绪（扫描此时走现场聚合兜底）
            return false;
        }
        String requested = requestedPeriodEndKey(kdjParam);
        if (requested == null) {
            return true;
        }
        return requested.compareTo(periodKey(maxEnd, type)) <= 0;
    }

    /** 物化表该周期最新周期末（短缓存 60s；表空返回 null） */
    private String periodBarMaxEnd(String kdjType) {
        long now = System.currentTimeMillis();
        String[] cached = periodBarMaxCache.get(kdjType);
        if (cached != null && now - Long.parseLong(cached[1]) < READY_CACHE_TTL_MS) {
            return cached[0];
        }
        String max = periodBarMapper.queryMaxPeriodEnd(kdjType);
        periodBarMaxCache.put(kdjType, new String[]{max, String.valueOf(now)});
        return max;
    }

    /** 请求的有效截止周期 key：周=周末 yyyymmdd；月=yyyymm；季=季末月 yyyymm */
    private String requestedPeriodEndKey(KDJParam p) {
        switch (p.getKdjType()) {
            case "1":
                if (StringUtils.hasText(p.getTradeDateMax())) {
                    return p.getTradeDateMax();
                }
                break;
            case "2":
                if (StringUtils.hasText(p.getTradeDate())) {
                    return p.getTradeDate().substring(0, 6);
                }
                break;
            case "3":
                if (StringUtils.hasText(p.getTradeDateMax())) {
                    return p.getTradeDateMax();
                }
                break;
            default:
                return null;
        }
        return latestCompletedEndKey(p.getKdjType());
    }

    /** 周期末 → 比较 key：周直接比 yyyymmdd，月/季比 yyyymm（季度末月单调可序） */
    private static String periodKey(String periodEnd, String kdjType) {
        return "1".equals(kdjType) ? periodEnd : periodEnd.substring(0, 6);
    }

    /** 最新已完结周期末 key（基于 work_day，与 /kdj/periods 同口径；短缓存 60s） */
    private String latestCompletedEndKey(String kdjType) {
        long now = System.currentTimeMillis();
        String[] cached = latestPeriodEndCache.get(kdjType);
        if (cached != null && now - Long.parseLong(cached[1]) < READY_CACHE_TTL_MS) {
            return cached[0];
        }
        List<String> tradeDates = workDayMapper.queryAll(new WorkDayQuery()).stream()
                .map(WorkDayDO::getTradeDate)
                .toList();
        List<KDJHandler.PeriodBar> bars = kdjHandler.aggregateDates(tradeDates, kdjType, LocalDate.now());
        String key = null;
        if (!bars.isEmpty()) {
            String end = bars.get(bars.size() - 1).endDate;
            key = "1".equals(kdjType) ? end : end.substring(0, 6);
        }
        latestPeriodEndCache.put(kdjType, new String[]{key, String.valueOf(now)});
        return key;
    }

    /** 测试用：清空就绪标记短缓存（生产由 60s TTL 自动过期） */
    public void resetReadyCaches() {
        periodBarMaxCache.clear();
        latestPeriodEndCache.clear();
    }

    @Override
    public List<CrossStockVO> getAllStocks(KDJParam kdjParam) {
        fillCommonDefaults(kdjParam);
        String cacheKey = scanCacheKey("all-stocks", kdjParam);
        return scanResultCache.computeIfAbsent(cacheKey, () -> computeAllStocks(kdjParam));
    }

    private List<CrossStockVO> computeAllStocks(KDJParam kdjParam) {
        Map<String, StockInfoDO> infoMap = stockInfoMap();
        List<CrossStockVO> result = new ArrayList<>();
        if (!StringUtils.hasText(kdjParam.getCode())) {
            ensureScanBarsLoaded(kdjParam);
        }
        for (String code : targetCodes(kdjParam)) {
            kdjParam.setCode(code);
            List<KDJHandler.PeriodBar> bars = scanBarsFor(kdjParam, SCAN_BASE_BARS);
            if (bars.isEmpty()) {
                continue;
            }
            List<KDJHandler.KdjValue> kdjList = kdjHandler.calculate(bars, kdjParam.getN(), kdjParam.getM1(), kdjParam.getM2());
            int last = kdjList.size() - 1;
            // 截止周期有交叉才填交汇点：金叉（受 currGoldCrossMax 过滤）优先，其次死叉
            KDJHandler.CrossPoint cross = null;
            KDJHandler.CrossPoint gold = kdjHandler.goldenCrossAt(kdjList, last);
            KDJHandler.CrossPoint death = kdjHandler.deathCrossAt(kdjList, last);
            if (gold != null && withinCurrGoldCrossMax(gold, kdjParam.getCurrGoldCrossMax())) {
                cross = gold;
            } else if (death != null) {
                cross = death;
            }
            result.add(buildCrossStockVO(bars, kdjList, cross, kdjParam, infoMap.get(code)));
        }
        return result;
    }

    @Override
    public List<CrossStockVO> getGold(KDJParam kdjParam) {
        fillCommonDefaults(kdjParam);
        String cacheKey = scanCacheKey("gold-cross", kdjParam);
        return scanResultCache.computeIfAbsent(cacheKey, () -> computeGold(kdjParam));
    }

    private List<CrossStockVO> computeGold(KDJParam kdjParam) {
        Map<String, StockInfoDO> infoMap = stockInfoMap();
        List<CrossStockVO> result = new ArrayList<>();
        if (!StringUtils.hasText(kdjParam.getCode())) {
            ensureScanBarsLoaded(kdjParam);
        }
        for (String code : targetCodes(kdjParam)) {
            kdjParam.setCode(code);
            List<KDJHandler.PeriodBar> bars = scanBarsFor(kdjParam, SCAN_BASE_BARS);
            if (bars.isEmpty()) {
                continue;
            }
            List<KDJHandler.KdjValue> kdjList = kdjHandler.calculate(bars, kdjParam.getN(), kdjParam.getM1(), kdjParam.getM2());
            KDJHandler.CrossPoint gold = kdjHandler.goldenCrossAt(kdjList, kdjList.size() - 1);
            if (gold != null && withinCurrGoldCrossMax(gold, kdjParam.getCurrGoldCrossMax())) {
                result.add(buildCrossStockVO(bars, kdjList, gold, kdjParam, infoMap.get(code)));
            }
        }
        return result;
    }

    @Override
    public List<CrossStockVO> getTradeSignalStockList(KDJParam kdjParam) {
        fillCommonDefaults(kdjParam);
        fillTradeSignalDefaults(kdjParam);
        String cacheKey = scanCacheKey("trade-signal", kdjParam);
        return scanResultCache.computeIfAbsent(cacheKey, () -> computeTradeSignal(kdjParam));
    }

    private List<CrossStockVO> computeTradeSignal(KDJParam kdjParam) {
        Map<String, StockInfoDO> infoMap = stockInfoMap();
        List<CrossStockVO> result = new ArrayList<>();
        // 交易位需回看上次金叉，窗口 = 间距上限 + 暖机；间距参数可配，窗口随参数放大
        int periodBars = kdjParam.getGoldInternalMax().intValue() + SCAN_BASE_BARS;
        if (!StringUtils.hasText(kdjParam.getCode())) {
            ensureScanBarsLoaded(kdjParam);
        }
        for (String code : targetCodes(kdjParam)) {
            kdjParam.setCode(code);
            List<KDJHandler.PeriodBar> bars = scanBarsFor(kdjParam, periodBars);
            if (bars.isEmpty()) {
                continue;
            }
            List<KDJHandler.KdjValue> kdjList = kdjHandler.calculate(bars, kdjParam.getN(), kdjParam.getM1(), kdjParam.getM2());
            if (kdjHandler.isTradeSignal(bars, kdjList, kdjParam)) {
                KDJHandler.CrossPoint gold = kdjHandler.goldenCrossAt(kdjList, kdjList.size() - 1);
                result.add(buildCrossStockVO(bars, kdjList, gold, kdjParam, infoMap.get(code)));
            }
        }
        return result;
    }

    @Override
    public int clearScanCache() {
        return scanResultCache.clear() + scanBarsCache.clear();
    }

    /**
     * 全市场扫描的周期K线获取：优先走 ScanBarsCache（按最新周期锚定的 132 根窗口，
     * key = code|adjust|kdjType），再按截止周期切前缀；截止太早导致前缀不足以覆盖
     * 暖机+回看，或所需窗口超过缓存窗口（goldInternalMax > 50）时，按截止周期锚定
     * 重算且不写缓存。
     */
    private List<KDJHandler.PeriodBar> scanBarsFor(KDJParam kdjParam, int periodBars) {
        if (periodBars <= SCAN_CACHE_WINDOW) {
            String key = ScanBarsCache.key(kdjParam.getCode(), kdjParam.getAdjust(), kdjParam.getKdjType());
            List<KDJHandler.PeriodBar> window = scanBarsCache.get(key);
            if (window == null) {
                window = loadLatestWindowBars(kdjParam);
                scanBarsCache.put(key, window);
            }
            List<KDJHandler.PeriodBar> sliced = truncateAtEndPeriod(window, kdjParam);
            // window 未满 = 该股全历史都在窗口内（新股），切前缀即全历史截断，直接用；
            // 否则要求切片覆盖 暖机+回看 才走缓存，不足则按截止锚定重算
            if (sliced.size() >= periodBars || window.size() < SCAN_CACHE_WINDOW) {
                return sliced;
            }
        }
        return truncateAtEndPeriod(loadScanBars(kdjParam, periodBars, scanAnchor(kdjParam)), kdjParam);
    }

    /**
     * 全市场扫描前的批量装载：找出 bars 缓存未命中的股票，每 200 只一批查库写入缓存。
     * 周/月/季线读物化表 stock_period_bar（未启用时退回逐股 SQL 现场聚合，仅过渡期）；
     * 日线批量读窗口原始行（原始行即 bar）。云盘随机 IO 是冷算瓶颈，批量顺序读可省约 90% 耗时。
     */
    private void ensureScanBarsLoaded(KDJParam kdjParam) {
        String adjust = kdjParam.getAdjust();
        String kdjType = kdjParam.getKdjType();
        String originCode = kdjParam.getCode();
        try {
            List<String> missing = new ArrayList<>();
            for (String code : targetCodes(kdjParam)) {
                if (scanBarsCache.get(ScanBarsCache.key(code, adjust, kdjType)) == null) {
                    missing.add(code);
                }
            }
            if (missing.isEmpty()) {
                return;
            }
            boolean monthlyOrQuarterly = "2".equals(kdjType) || "3".equals(kdjType);
            // 周/月/季都优先读物化表（周 period_type='1' 与 kdjType 一致）；日线原始行即 bar 不入表
            boolean useAggTable = !"0".equals(kdjType) && aggTableAvailable();
            for (int i = 0; i < missing.size(); i += SCAN_BATCH_SIZE) {
                List<String> chunk = missing.subList(i, Math.min(i + SCAN_BATCH_SIZE, missing.size()));
                if (useAggTable) {
                    // 周/月/季：读物化表（周为 period_type='1'，与 kdjType 一致）
                    Map<String, List<PeriodBarDO>> byCode = periodBarMapper.queryBatch(kdjType, chunk, adjust)
                            .stream().collect(Collectors.groupingBy(PeriodBarDO::getCode,
                                    LinkedHashMap::new, Collectors.toList()));
                    for (String code : chunk) {
                        scanBarsCache.put(ScanBarsCache.key(code, adjust, kdjType),
                                lastN(toBars(byCode.getOrDefault(code, List.of())), SCAN_CACHE_WINDOW));
                    }
                } else if (monthlyOrQuarterly) {
                    // 物化表未启用（首次物化前）：退回逐股 SQL 现场聚合（慢但正确，仅过渡期）
                    for (String code : chunk) {
                        kdjParam.setCode(code);
                        scanBarsCache.put(ScanBarsCache.key(code, adjust, kdjType),
                                lastN(loadScanAggregatedBars(kdjParam, SCAN_CACHE_WINDOW, LocalDate.now()),
                                        SCAN_CACHE_WINDOW));
                    }
                } else {
                    // 日/周线：批量窗口原始行 → Java 聚合
                    int daysPerBar = "1".equals(kdjType) ? 8 : 3;
                    String tradeDateMin = LocalDate.now()
                            .minusDays((long) SCAN_CACHE_WINDOW * daysPerBar)
                            .format(DateTimeFormatter.BASIC_ISO_DATE);
                    Map<String, List<StockQuoteDO>> byCode = stockQuoteMapper
                            .queryWindowBatch(chunk, adjust, tradeDateMin)
                            .stream().collect(Collectors.groupingBy(StockQuoteDO::getCode,
                                    LinkedHashMap::new, Collectors.toList()));
                    for (String code : chunk) {
                        List<StockQuoteDO> dailies = byCode.getOrDefault(code, List.of());
                        scanBarsCache.put(ScanBarsCache.key(code, adjust, kdjType),
                                lastN(kdjHandler.aggregate(dailies, kdjType, LocalDate.now()), SCAN_CACHE_WINDOW));
                    }
                }
            }
        } finally {
            kdjParam.setCode(originCode);
        }
    }

    /** 物化表是否已启用（首次物化前为 0，周/月/季扫描退回现场聚合兜底）。小表计数，毫秒级。 */
    private boolean aggTableAvailable() {
        return periodBarMapper.countAll() > 0;
    }

    /** 单股最新窗口 bars：周/月/季优先读物化表，否则走原窗口加载。 */
    private List<KDJHandler.PeriodBar> loadLatestWindowBars(KDJParam kdjParam) {
        String kdjType = kdjParam.getKdjType();
        if (!"0".equals(kdjType) && aggTableAvailable()) {
            return lastN(toBars(periodBarMapper.queryByCode(kdjType, kdjParam.getCode(), kdjParam.getAdjust())),
                    SCAN_CACHE_WINDOW);
        }
        return loadScanBars(kdjParam, SCAN_CACHE_WINDOW, LocalDate.now());
    }

    private static List<KDJHandler.PeriodBar> toBars(List<PeriodBarDO> rows) {
        List<KDJHandler.PeriodBar> bars = new ArrayList<>(rows.size());
        for (PeriodBarDO row : rows) {
            KDJHandler.PeriodBar bar = new KDJHandler.PeriodBar();
            bar.startDate = row.getPeriodStart();
            bar.endDate = row.getPeriodEnd();
            bar.open = row.getOpen();
            bar.high = row.getHigh();
            bar.low = row.getLow();
            bar.close = row.getClose();
            bars.add(bar);
        }
        return bars;
    }

    private static List<KDJHandler.PeriodBar> lastN(List<KDJHandler.PeriodBar> bars, int n) {
        return bars.size() <= n ? bars : new ArrayList<>(bars.subList(bars.size() - n, bars.size()));
    }

    /**
     * 加载窗口周期K线：日/周线走「窗口日线 + Java 聚合」（窗口已裁得够小），
     * 月/季线走 SQL 预聚合（StockQuoteMapper.queryMonthlyBars/queryQuarterlyBars，
     * 传输量从数千行/股降到数十行/股）。
     */
    private List<KDJHandler.PeriodBar> loadScanBars(KDJParam kdjParam, int periodBars, LocalDate anchor) {
        if ("2".equals(kdjParam.getKdjType()) || "3".equals(kdjParam.getKdjType())) {
            return loadScanAggregatedBars(kdjParam, periodBars, anchor);
        }
        List<StockQuoteDO> dailies = loadScanDailies(kdjParam, periodBars, anchor);
        return kdjHandler.aggregate(dailies, kdjParam.getKdjType(), LocalDate.now());
    }

    /**
     * 月/季线 SQL 预聚合：聚合口径与 KDJHandler.aggregate 一致（KDJScanWindowCacheTest 对拍保证），
     * 未完结周期由 currentPeriod 参数剔除。
     */
    private List<KDJHandler.PeriodBar> loadScanAggregatedBars(KDJParam kdjParam, int periodBars, LocalDate anchor) {
        boolean monthly = "2".equals(kdjParam.getKdjType());
        int daysPerBar = monthly ? 32 : 100;
        String tradeDateMin = anchor
                .minusDays((long) periodBars * daysPerBar)
                .format(DateTimeFormatter.BASIC_ISO_DATE);
        List<PeriodBarDTO> rows = monthly
                ? stockQuoteMapper.queryMonthlyBars(kdjParam.getCode(), kdjParam.getAdjust(),
                        tradeDateMin, currentMonthKey())
                : stockQuoteMapper.queryQuarterlyBars(kdjParam.getCode(), kdjParam.getAdjust(),
                        tradeDateMin, currentQuarterKey());
        List<KDJHandler.PeriodBar> bars = new ArrayList<>(rows.size());
        for (PeriodBarDTO row : rows) {
            KDJHandler.PeriodBar bar = new KDJHandler.PeriodBar();
            bar.startDate = row.getStartDate();
            bar.endDate = row.getEndDate();
            bar.open = row.getOpen();
            bar.high = row.getHigh();
            bar.low = row.getLow();
            bar.close = row.getClose();
            bars.add(bar);
        }
        return bars;
    }

    /** 当前月周期 key（yyyyMM），供 SQL 剔除未完结当月 */
    private static String currentMonthKey() {
        return YearMonth.now().format(DateTimeFormatter.ofPattern("yyyyMM"));
    }

    /** 当前季周期 key（yyyyQn），供 SQL 剔除未完结当季 */
    private static String currentQuarterKey() {
        LocalDate now = LocalDate.now();
        return now.getYear() + "Q" + ((now.getMonthValue() - 1) / 3 + 1);
    }

    /**
     * 全市场扫描用的日线加载（日/周线）：把所需周期 bar 数换算成日历天下界，
     * 只加载窗口内的日线（uk_code_adjust_date 索引范围扫描）。
     * 窗口含 80 周期暖机，扫描的信号判定与全历史完全一致（见 SCAN_WARMUP_BARS 注释）。
     * 单票序列（getAllKDJ）仍走 loadDailies 全历史。
     *
     * @param periodBars 需要的周期 bar 数（含暖机与信号回看）
     * @param anchor     窗口锚点（最新周期=今天；历史截止=截止周期末）
     */
    private List<StockQuoteDO> loadScanDailies(KDJParam kdjParam, int periodBars, LocalDate anchor) {
        int daysPerBar = "1".equals(kdjParam.getKdjType()) ? 8 : 3;
        String tradeDateMin = anchor
                .minusDays((long) periodBars * daysPerBar)
                .format(DateTimeFormatter.BASIC_ISO_DATE);
        StockQuoteQuery query = new StockQuoteQuery();
        query.setCode(kdjParam.getCode());
        query.setAdjust(kdjParam.getAdjust());
        query.setTradeDateMin(tradeDateMin);
        return stockQuoteMapper.queryAll(query);
    }

    /**
     * 扫描窗口的锚点：指定了截止周期则锚在截止周期末，否则锚在今天。
     * 与 truncateAtEndPeriod 同一套入参规则。
     */
    private LocalDate scanAnchor(KDJParam kdjParam) {
        switch (kdjParam.getKdjType()) {
            case "1":
                if (StringUtils.hasText(kdjParam.getTradeDateMax())) {
                    return LocalDate.parse(kdjParam.getTradeDateMax(), DateTimeFormatter.BASIC_ISO_DATE);
                }
                break;
            case "2":
                if (StringUtils.hasText(kdjParam.getTradeDate())) {
                    return YearMonth.parse(kdjParam.getTradeDate().substring(0, 6),
                            DateTimeFormatter.ofPattern("yyyyMM")).atEndOfMonth();
                }
                break;
            case "3":
                if (StringUtils.hasText(kdjParam.getTradeDateMax())) {
                    return YearMonth.parse(kdjParam.getTradeDateMax(),
                            DateTimeFormatter.ofPattern("yyyyMM")).atEndOfMonth();
                }
                break;
            default:
                if (StringUtils.hasText(kdjParam.getTradeDate())) {
                    return LocalDate.parse(kdjParam.getTradeDate(), DateTimeFormatter.BASIC_ISO_DATE);
                }
                break;
        }
        return LocalDate.now();
    }

    /**
     * 扫描结果缓存 key：接口名 + 全部生效参数（默认值已填充）。
     */
    private String scanCacheKey(String endpoint, KDJParam p) {
        return String.join("|", endpoint,
                str(p.getAdjust()), str(p.getKdjType()), str(p.getCode()),
                str(p.getTradeDate()), str(p.getTradeDateMin()), str(p.getTradeDateMax()),
                str(p.getN()), str(p.getM1()), str(p.getM2()),
                str(p.getLastGoldCrossMax()), str(p.getCurrGoldCrossMax()), str(p.getLastDeathCrossMax()),
                str(p.getGoldInternalMin()), str(p.getGoldInternalMax()),
                str(p.getOpenClosePriceLimit()), str(p.getGoldCrossLimit()));
    }

    private static String str(Object value) {
        return value == null ? "-" : value.toString();
    }

    /**
     * 加载指定股票的日线行情（tradeDate 升序）。
     */
    private List<StockQuoteDO> loadDailies(KDJParam kdjParam) {
        StockQuoteQuery query = new StockQuoteQuery();
        query.setCode(kdjParam.getCode());
        query.setAdjust(kdjParam.getAdjust());
        return stockQuoteMapper.queryAll(query);
    }

    /**
     * 日线聚合为周期K线，并按截止周期截断。
     */
    private List<KDJHandler.PeriodBar> loadPeriodBars(KDJParam kdjParam, List<StockQuoteDO> dailies) {
        List<KDJHandler.PeriodBar> bars = kdjHandler.aggregate(dailies, kdjParam.getKdjType(), LocalDate.now());
        return truncateAtEndPeriod(bars, kdjParam);
    }

    /**
     * 按入参的截止周期截断序列；未指定时保留全部（聚合已剔除未完结周期）。
     */
    private List<KDJHandler.PeriodBar> truncateAtEndPeriod(List<KDJHandler.PeriodBar> bars, KDJParam kdjParam) {
        String kdjType = kdjParam.getKdjType();
        String endInclusive = null;
        switch (kdjType) {
            case "1":
                endInclusive = kdjParam.getTradeDateMax();
                break;
            case "2":
                if (StringUtils.hasText(kdjParam.getTradeDate())) {
                    endInclusive = kdjParam.getTradeDate().substring(0, 6) + "31";
                }
                break;
            case "3":
                if (StringUtils.hasText(kdjParam.getTradeDateMax())) {
                    // 季度末月 yyyymm -> 该月最后一天
                    endInclusive = kdjParam.getTradeDateMax() + "31";
                }
                break;
            default:
                endInclusive = kdjParam.getTradeDate();
                break;
        }
        if (!StringUtils.hasText(endInclusive)) {
            return bars;
        }
        List<KDJHandler.PeriodBar> truncated = new ArrayList<>();
        for (KDJHandler.PeriodBar bar : bars) {
            if (bar.endDate.compareTo(endInclusive) <= 0) {
                truncated.add(bar);
            }
        }
        return truncated;
    }

    private CrossStockVO buildCrossStockVO(List<KDJHandler.PeriodBar> bars, List<KDJHandler.KdjValue> kdjList,
                                           KDJHandler.CrossPoint cross, KDJParam kdjParam, StockInfoDO info) {
        KDJHandler.PeriodBar lastBar = bars.get(bars.size() - 1);
        KDJHandler.KdjValue lastKdj = kdjList.get(kdjList.size() - 1);
        CrossStockVO vo = new CrossStockVO();
        vo.setCode(kdjParam.getCode());
        if (info != null) {
            vo.setName(info.getName());
            vo.setMarket(info.getMarket());
        }
        vo.setOpen(lastBar.open);
        vo.setHigh(lastBar.high);
        vo.setLow(lastBar.low);
        vo.setClose(lastBar.close);
        vo.setK(lastKdj.k);
        vo.setD(lastKdj.d);
        vo.setJ(lastKdj.j);
        if (cross != null) {
            vo.setCrossValue(cross.crossValue);
        }
        fillDateFields(vo::setTradeDate, vo::setTradeDateMin, vo::setTradeDateMax,
                lastBar, kdjParam.getKdjType());
        return vo;
    }

    /**
     * 日期字段与入参规则一致：日/月度填 tradeDate；周度填首/末交易日；季度填首/末月 yyyymm。
     */
    private void fillDateFields(java.util.function.Consumer<String> tradeDateSetter,
                                java.util.function.Consumer<String> minSetter,
                                java.util.function.Consumer<String> maxSetter,
                                KDJHandler.PeriodBar bar, String kdjType) {
        switch (kdjType) {
            case "1":
                minSetter.accept(bar.startDate);
                maxSetter.accept(bar.endDate);
                break;
            case "3":
                minSetter.accept(bar.startDate.substring(0, 6));
                maxSetter.accept(bar.endDate.substring(0, 6));
                break;
            default:
                tradeDateSetter.accept(bar.endDate);
                break;
        }
    }

    private List<String> targetCodes(KDJParam kdjParam) {
        if (StringUtils.hasText(kdjParam.getCode())) {
            return List.of(kdjParam.getCode());
        }
        return stockQuoteMapper.queryDistinctCodes(kdjParam.getAdjust());
    }

    /**
     * 股票基础信息（name/market 唯一来源），按 code 索引
     */
    private Map<String, StockInfoDO> stockInfoMap() {
        return stockInfoMapper.queryAll().stream()
                .collect(Collectors.toMap(StockInfoDO::getCode, Function.identity(), (a, b) -> a));
    }

    private boolean withinCurrGoldCrossMax(KDJHandler.CrossPoint gold, BigDecimal currGoldCrossMax) {
        return currGoldCrossMax == null || gold.crossValue.compareTo(currGoldCrossMax) <= 0;
    }

    private void fillCommonDefaults(KDJParam kdjParam) {
        validateParam(kdjParam);
        if (!StringUtils.hasText(kdjParam.getAdjust())) {
            kdjParam.setAdjust(DEFAULT_ADJUST);
        }
        if (!StringUtils.hasText(kdjParam.getKdjType())) {
            kdjParam.setKdjType(DEFAULT_KDJ_TYPE);
        }
        if (kdjParam.getN() == null) {
            kdjParam.setN(DEFAULT_N);
        }
        if (kdjParam.getM1() == null) {
            kdjParam.setM1(DEFAULT_M);
        }
        if (kdjParam.getM2() == null) {
            kdjParam.setM2(DEFAULT_M);
        }
    }

    /**
     * 入参白名单校验：枚举值、字符集、日期格式。不合法直接 400，不进查询。
     */
    private void validateParam(KDJParam p) {
        requireEnum("adjust", p.getAdjust(), ADJUST_VALUES);
        requireEnum("kdjType", p.getKdjType(), KDJ_TYPE_VALUES);
        requireEnum("openClosePriceLimit", p.getOpenClosePriceLimit(), SWITCH_VALUES);
        requireEnum("goldCrossLimit", p.getGoldCrossLimit(), SWITCH_VALUES);
        requirePattern("code", p.getCode(), CODE_PATTERN);
        requirePattern("market", p.getMarket(), MARKET_PATTERN);
        requirePattern("tradeDate", p.getTradeDate(), DATE_PATTERN);
        requirePattern("tradeDateMin", p.getTradeDateMin(), DATE_PATTERN);
        requirePattern("tradeDateMax", p.getTradeDateMax(), DATE_PATTERN);
        requirePositive("n", p.getN());
        requirePositive("m1", p.getM1());
        requirePositive("m2", p.getM2());
    }

    private void requireEnum(String field, String value, Set<String> allowed) {
        if (StringUtils.hasText(value) && !allowed.contains(value)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    field + " 非法取值: " + value);
        }
    }

    private void requirePattern(String field, String value, Pattern pattern) {
        if (StringUtils.hasText(value) && !pattern.matcher(value).matches()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    field + " 格式非法: " + value);
        }
    }

    private void requirePositive(String field, BigDecimal value) {
        if (value != null && value.compareTo(BigDecimal.ZERO) <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    field + " 必须为正数: " + value);
        }
    }

    private void fillTradeSignalDefaults(KDJParam kdjParam) {
        if (kdjParam.getCurrGoldCrossMax() == null) {
            kdjParam.setCurrGoldCrossMax(DEFAULT_CURR_GOLD_CROSS_MAX);
        }
        if (kdjParam.getLastGoldCrossMax() == null) {
            kdjParam.setLastGoldCrossMax(DEFAULT_LAST_GOLD_CROSS_MAX);
        }
        if (kdjParam.getLastDeathCrossMax() == null) {
            kdjParam.setLastDeathCrossMax(DEFAULT_LAST_DEATH_CROSS_MAX);
        }
        if (kdjParam.getGoldInternalMin() == null) {
            kdjParam.setGoldInternalMin(DEFAULT_GOLD_INTERNAL_MIN);
        }
        if (kdjParam.getGoldInternalMax() == null) {
            kdjParam.setGoldInternalMax(DEFAULT_GOLD_INTERNAL_MAX);
        }
        if (!StringUtils.hasText(kdjParam.getOpenClosePriceLimit())) {
            kdjParam.setOpenClosePriceLimit(SWITCH_ON);
        }
        if (!StringUtils.hasText(kdjParam.getGoldCrossLimit())) {
            kdjParam.setGoldCrossLimit(SWITCH_ON);
        }
    }
}
