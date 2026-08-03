package com.xi.service.Impl;

import com.xi.handler.KDJHandler;
import com.xi.model.dto.KDJDTO;
import com.xi.model.param.KDJParam;
import com.xi.model.param.WorkDayParam;
import com.xi.model.query.StockQuoteQuery;
import com.xi.model.query.WorkDayQuery;
import com.xi.model.vo.CrossStockVO;
import com.xi.model.vo.WorkDayVO;
import com.xi.orm.entity.StockInfoDO;
import com.xi.orm.entity.StockQuoteDO;
import com.xi.orm.entity.WorkDayDO;
import com.xi.orm.mapper.StockInfoMapper;
import com.xi.orm.mapper.StockQuoteMapper;
import com.xi.orm.mapper.WorkDayMapper;
import com.xi.service.KDJService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
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

    @Autowired
    private StockQuoteMapper stockQuoteMapper;

    @Autowired
    private WorkDayMapper workDayMapper;

    @Autowired
    private StockInfoMapper stockInfoMapper;

    private final KDJHandler kdjHandler = new KDJHandler();

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
    public List<CrossStockVO> getAllStocks(KDJParam kdjParam) {
        fillCommonDefaults(kdjParam);
        Map<String, StockInfoDO> infoMap = stockInfoMap();
        List<CrossStockVO> result = new ArrayList<>();
        for (String code : targetCodes(kdjParam)) {
            kdjParam.setCode(code);
            List<StockQuoteDO> dailies = loadDailies(kdjParam);
            List<KDJHandler.PeriodBar> bars = loadPeriodBars(kdjParam, dailies);
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
        Map<String, StockInfoDO> infoMap = stockInfoMap();
        List<CrossStockVO> result = new ArrayList<>();
        for (String code : targetCodes(kdjParam)) {
            kdjParam.setCode(code);
            List<StockQuoteDO> dailies = loadDailies(kdjParam);
            List<KDJHandler.PeriodBar> bars = loadPeriodBars(kdjParam, dailies);
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
        Map<String, StockInfoDO> infoMap = stockInfoMap();
        List<CrossStockVO> result = new ArrayList<>();
        for (String code : targetCodes(kdjParam)) {
            kdjParam.setCode(code);
            List<StockQuoteDO> dailies = loadDailies(kdjParam);
            List<KDJHandler.PeriodBar> bars = loadPeriodBars(kdjParam, dailies);
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
