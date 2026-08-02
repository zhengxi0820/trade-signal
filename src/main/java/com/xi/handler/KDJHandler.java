package com.xi.handler;

import com.xi.model.param.KDJParam;
import com.xi.orm.entity.StockQuoteDO;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * KDJ 核心计算。纯函数，不依赖 Spring 与数据库。
 * 口径见 docs/kdj-trade-signal-requirements.md。
 */
public class KDJHandler {

    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.BASIC_ISO_DATE;
    private static final BigDecimal HUNDRED = new BigDecimal("100");
    private static final BigDecimal FIFTY = new BigDecimal("50");
    private static final BigDecimal THREE = new BigDecimal("3");
    private static final BigDecimal TWO = new BigDecimal("2");
    private static final int CALC_SCALE = 10;

    /**
     * 一个周期的聚合K线（日/周/月/季统一用此结构承载）。
     */
    public static class PeriodBar {
        /** 周期首个交易日 yyyymmdd */
        public String startDate;
        /** 周期最后一个交易日 yyyymmdd */
        public String endDate;
        public BigDecimal open;
        public BigDecimal high;
        public BigDecimal low;
        public BigDecimal close;
    }

    /**
     * 单根K线的KDJ值。
     */
    public static class KdjValue {
        public BigDecimal k;
        public BigDecimal d;
        public BigDecimal j;

        public KdjValue(BigDecimal k, BigDecimal d, BigDecimal j) {
            this.k = k;
            this.d = d;
            this.j = j;
        }
    }

    /**
     * K、D交汇点。
     */
    public static class CrossPoint {
        /** 0=上根K，1=当前K，0~1之间代表交叉位置 */
        public BigDecimal t;
        /** K、D相交时的指标数值 */
        public BigDecimal crossValue;

        public CrossPoint(BigDecimal t, BigDecimal crossValue) {
            this.t = t;
            this.crossValue = crossValue;
        }
    }

    /**
     * 日线聚合为指定周期的K线序列。
     * 周=ISO周（周一起），月=自然月，季=自然季；
     * high=周期内最大high，low=周期内最小low，close=周期最后交易日close；
     * 未完结周期（自然结束日未过）剔除；周期内无交易日自然跳过。
     *
     * @param dailies 日线序列，tradeDate升序
     * @param kdjType "0"=日、"1"=周、"2"=月、"3"=季
     * @param today   当前自然日（判断周期是否完结，便于测试注入）
     * @return 周期K线序列，时间升序
     */
    public List<PeriodBar> aggregate(List<StockQuoteDO> dailies, String kdjType, LocalDate today) {
        Map<String, List<StockQuoteDO>> groups = new LinkedHashMap<>();
        for (StockQuoteDO daily : dailies) {
            LocalDate date = LocalDate.parse(daily.getTradeDate(), DATE_FORMAT);
            groups.computeIfAbsent(periodKey(date, kdjType), k -> new ArrayList<>()).add(daily);
        }
        List<PeriodBar> bars = new ArrayList<>();
        for (Map.Entry<String, List<StockQuoteDO>> entry : groups.entrySet()) {
            List<StockQuoteDO> days = entry.getValue();
            if (!"0".equals(kdjType) && !isPeriodFinished(days, kdjType, today)) {
                continue;
            }
            PeriodBar bar = new PeriodBar();
            bar.startDate = days.get(0).getTradeDate();
            bar.endDate = days.get(days.size() - 1).getTradeDate();
            bar.open = days.get(0).getOpen();
            bar.close = days.get(days.size() - 1).getClose();
            bar.high = days.stream().map(StockQuoteDO::getHigh).max(BigDecimal::compareTo).orElseThrow();
            bar.low = days.stream().map(StockQuoteDO::getLow).min(BigDecimal::compareTo).orElseThrow();
            bars.add(bar);
        }
        return bars;
    }

    /**
     * 由交易日历（work_day）推导可选周期列表。与 aggregate 同一套周期口径：
     * 周=ISO周、月=自然月、季=自然季；未完结周期剔除；周期内无交易日自然跳过。
     * 交易日历可能包含未来日期，晚于 today 的一律剔除（日线也受此影响）。
     * 返回的 PeriodBar 只填 startDate/endDate，不带价格。
     *
     * @param tradeDates 交易日序列，yyyymmdd 升序
     * @param kdjType    "0"=日、"1"=周、"2"=月、"3"=季
     * @param today      当前自然日
     * @return 已完结周期序列，时间升序
     */
    public List<PeriodBar> aggregateDates(List<String> tradeDates, String kdjType, LocalDate today) {
        Map<String, List<String>> groups = new LinkedHashMap<>();
        for (String tradeDate : tradeDates) {
            LocalDate date = LocalDate.parse(tradeDate, DATE_FORMAT);
            if (date.isAfter(today)) {
                continue;
            }
            groups.computeIfAbsent(periodKey(date, kdjType), k -> new ArrayList<>()).add(tradeDate);
        }
        List<PeriodBar> bars = new ArrayList<>();
        for (List<String> days : groups.values()) {
            if (!"0".equals(kdjType) && !isPeriodFinished(days.get(days.size() - 1), kdjType, today)) {
                continue;
            }
            PeriodBar bar = new PeriodBar();
            bar.startDate = days.get(0);
            bar.endDate = days.get(days.size() - 1);
            bars.add(bar);
        }
        return bars;
    }

    /**
     * 计算KDJ序列。种子K=D=50；RSV取最近N个周期LLV/HHV（不足N根有几个算几个）；
     * 分母为0（窗口内最高=最低）时RSV取50。
     *
     * @return 与bars等长的KDJ序列
     */
    public List<KdjValue> calculate(List<PeriodBar> bars, BigDecimal n, BigDecimal m1, BigDecimal m2) {
        int window = n.intValue();
        List<KdjValue> result = new ArrayList<>(bars.size());
        BigDecimal k = FIFTY;
        BigDecimal d = FIFTY;
        for (int i = 0; i < bars.size(); i++) {
            int from = Math.max(0, i - window + 1);
            BigDecimal hhv = null;
            BigDecimal llv = null;
            for (int p = from; p <= i; p++) {
                hhv = hhv == null ? bars.get(p).high : hhv.max(bars.get(p).high);
                llv = llv == null ? bars.get(p).low : llv.min(bars.get(p).low);
            }
            BigDecimal rsv;
            if (hhv.compareTo(llv) == 0) {
                rsv = FIFTY;
            } else {
                rsv = bars.get(i).close.subtract(llv)
                        .multiply(HUNDRED)
                        .divide(hhv.subtract(llv), CALC_SCALE, RoundingMode.HALF_UP);
            }
            k = k.multiply(m1.subtract(BigDecimal.ONE)).add(rsv).divide(m1, CALC_SCALE, RoundingMode.HALF_UP);
            d = d.multiply(m2.subtract(BigDecimal.ONE)).add(k).divide(m2, CALC_SCALE, RoundingMode.HALF_UP);
            BigDecimal j = THREE.multiply(k).subtract(TWO.multiply(d));
            result.add(new KdjValue(k, d, j));
        }
        return result;
    }

    /**
     * 计算K、D线段之间的交叉点数值（附A修正版）。
     *
     * @return 交叉信息，无交叉（含t=0、t=1端点触碰、平行）返回null
     */
    public CrossPoint calcKdCrossValue(BigDecimal preK, BigDecimal preD, BigDecimal currK, BigDecimal currD) {
        BigDecimal a = preD.subtract(preK);
        BigDecimal b = preD.subtract(currD).subtract(preK.subtract(currK));

        // B=0 K、D平行，无交点
        if (b.compareTo(BigDecimal.ZERO) == 0) {
            return null;
        }

        BigDecimal t = a.divide(b, 16, RoundingMode.HALF_UP);
        // t不在开区间(0,1)，线段不相交
        if (t.compareTo(BigDecimal.ZERO) <= 0 || t.compareTo(BigDecimal.ONE) >= 0) {
            return null;
        }

        BigDecimal crossVal = preK.add(t.multiply(currK.subtract(preK)));
        return new CrossPoint(t, crossVal);
    }

    /**
     * 判断index处是否发生金叉：上一根K&lt;D且当前根K&gt;D。
     *
     * @return 金叉交汇点，非金叉返回null
     */
    public CrossPoint goldenCrossAt(List<KdjValue> kdj, int index) {
        if (index < 1 || index >= kdj.size()) {
            return null;
        }
        KdjValue pre = kdj.get(index - 1);
        KdjValue curr = kdj.get(index);
        if (curr.k.compareTo(curr.d) <= 0) {
            return null;
        }
        return calcKdCrossValue(pre.k, pre.d, curr.k, curr.d);
    }

    /**
     * 判断index处是否发生死叉：上一根K&gt;D且当前根K&lt;D。
     *
     * @return 死叉交汇点，非死叉返回null
     */
    public CrossPoint deathCrossAt(List<KdjValue> kdj, int index) {
        if (index < 1 || index >= kdj.size()) {
            return null;
        }
        KdjValue pre = kdj.get(index - 1);
        KdjValue curr = kdj.get(index);
        if (curr.k.compareTo(curr.d) >= 0) {
            return null;
        }
        return calcKdCrossValue(pre.k, pre.d, curr.k, curr.d);
    }

    /**
     * 交易位判断。金叉必须发生在截止周期（序列最后一根），
     * 再按需求4.2的六条规则逐条过滤。param须已填充默认值。
     *
     * @param bars 周期K线序列（时间升序，最后一根=截止周期）
     * @param kdj  与bars等长的KDJ序列
     * @param param 已解析默认值的参数
     * @return 是否交易位
     */
    public boolean isTradeSignal(List<PeriodBar> bars, List<KdjValue> kdj, KDJParam param) {
        int y = kdj.size() - 1;
        CrossPoint yCross = goldenCrossAt(kdj, y);
        if (yCross == null) {
            return false;
        }
        // 条件2：当前金叉交汇点 ≤ currGoldCrossMax
        if (yCross.crossValue.compareTo(param.getCurrGoldCrossMax()) > 0) {
            return false;
        }
        // 上一次金叉x
        int x = -1;
        CrossPoint xCross = null;
        for (int i = y - 1; i >= 1; i--) {
            CrossPoint cp = goldenCrossAt(kdj, i);
            if (cp != null) {
                x = i;
                xCross = cp;
                break;
            }
        }
        if (x < 0) {
            return false;
        }
        // 条件1：上次金叉交汇点 ≤ lastGoldCrossMax
        if (xCross.crossValue.compareTo(param.getLastGoldCrossMax()) > 0) {
            return false;
        }
        // 条件3：间距闭区间 [goldInternalMin, goldInternalMax]
        BigDecimal gap = BigDecimal.valueOf(y - x);
        if (gap.compareTo(param.getGoldInternalMin()) < 0 || gap.compareTo(param.getGoldInternalMax()) > 0) {
            return false;
        }
        // 条件4：x、y之间恰好一次死叉，且其交汇点 ≤ lastDeathCrossMax
        int deathCount = 0;
        CrossPoint deathCross = null;
        for (int i = x + 1; i < y; i++) {
            CrossPoint cp = deathCrossAt(kdj, i);
            if (cp != null) {
                deathCount++;
                deathCross = cp;
            }
        }
        if (deathCount != 1 || deathCross.crossValue.compareTo(param.getLastDeathCrossMax()) > 0) {
            return false;
        }
        // 条件5（开关）：y周期收盘价 < x周期收盘价
        if ("1".equals(param.getOpenClosePriceLimit())
                && bars.get(y).close.compareTo(bars.get(x).close) >= 0) {
            return false;
        }
        // 条件6（开关）：y交汇点 > x交汇点
        if ("1".equals(param.getGoldCrossLimit())
                && yCross.crossValue.compareTo(xCross.crossValue) <= 0) {
            return false;
        }
        return true;
    }

    private String periodKey(LocalDate date, String kdjType) {
        switch (kdjType) {
            case "1":
                LocalDate monday = date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
                return monday.format(DATE_FORMAT);
            case "2":
                return date.format(DATE_FORMAT).substring(0, 6);
            case "3":
                int quarter = (date.getMonthValue() - 1) / 3 + 1;
                return date.getYear() + "Q" + quarter;
            default:
                return date.format(DATE_FORMAT);
        }
    }

    private boolean isPeriodFinished(List<StockQuoteDO> days, String kdjType, LocalDate today) {
        return isPeriodFinished(days.get(days.size() - 1).getTradeDate(), kdjType, today);
    }

    private boolean isPeriodFinished(String lastTradeDate, String kdjType, LocalDate today) {
        LocalDate lastDay = LocalDate.parse(lastTradeDate, DATE_FORMAT);
        LocalDate periodEnd;
        switch (kdjType) {
            case "1":
                periodEnd = lastDay.with(TemporalAdjusters.nextOrSame(DayOfWeek.SUNDAY));
                break;
            case "2":
                periodEnd = lastDay.with(TemporalAdjusters.lastDayOfMonth());
                break;
            case "3":
                int quarterEndMonth = ((lastDay.getMonthValue() - 1) / 3 + 1) * 3;
                periodEnd = LocalDate.of(lastDay.getYear(), quarterEndMonth, 1)
                        .with(TemporalAdjusters.lastDayOfMonth());
                break;
            default:
                return true;
        }
        return today.isAfter(periodEnd);
    }
}
