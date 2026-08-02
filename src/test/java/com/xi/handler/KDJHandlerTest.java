package com.xi.handler;

import com.xi.model.param.KDJParam;
import com.xi.orm.entity.StockQuoteDO;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KDJHandlerTest {

    private final KDJHandler core = new KDJHandler();

    // ---------- aggregate ----------

    @Test
    void aggregateDayKeepsOneBarPerDay() {
        List<StockQuoteDO> dailies = List.of(
                daily("20240102", 10, 5, 8),
                daily("20240103", 12, 6, 11));
        List<KDJHandler.PeriodBar> bars = core.aggregate(dailies, "0", LocalDate.of(2024, 1, 3));
        assertEquals(2, bars.size());
        assertEquals("20240102", bars.get(0).endDate);
    }

    @Test
    void aggregateWeekMergesDaysAndPicksExtremes() {
        List<StockQuoteDO> dailies = List.of(
                daily("20240102", 10, 5, 8),
                daily("20240104", 12, 6, 11));
        // 下一周的周一，本周已完结
        List<KDJHandler.PeriodBar> bars = core.aggregate(dailies, "1", LocalDate.of(2024, 1, 8));
        assertEquals(1, bars.size());
        KDJHandler.PeriodBar bar = bars.get(0);
        assertEquals("20240102", bar.startDate);
        assertEquals("20240104", bar.endDate);
        assertEquals(0, bar.high.compareTo(new BigDecimal("12")));
        assertEquals(0, bar.low.compareTo(new BigDecimal("5")));
        assertEquals(0, bar.close.compareTo(new BigDecimal("11")));
    }

    @Test
    void aggregateWeekExcludesUnfinishedWeek() {
        List<StockQuoteDO> dailies = List.of(
                daily("20240102", 10, 5, 8),
                daily("20240104", 12, 6, 11));
        // 本周三，本周未完结
        List<KDJHandler.PeriodBar> bars = core.aggregate(dailies, "1", LocalDate.of(2024, 1, 3));
        assertTrue(bars.isEmpty());
    }

    @Test
    void aggregateMonthAndQuarter() {
        List<StockQuoteDO> dailies = List.of(
                daily("20240131", 10, 5, 8),
                daily("20240229", 12, 6, 11),
                daily("20240328", 14, 7, 13));
        LocalDate today = LocalDate.of(2024, 4, 1);
        List<KDJHandler.PeriodBar> months = core.aggregate(dailies, "2", today);
        assertEquals(3, months.size());
        List<KDJHandler.PeriodBar> quarters = core.aggregate(dailies, "3", today);
        assertEquals(1, quarters.size());
        assertEquals(0, quarters.get(0).high.compareTo(new BigDecimal("14")));
        assertEquals(0, quarters.get(0).low.compareTo(new BigDecimal("5")));
        assertEquals(0, quarters.get(0).close.compareTo(new BigDecimal("13")));
        // 季末前一天：Q1未完结，剔除
        assertTrue(core.aggregate(dailies, "3", LocalDate.of(2024, 3, 31)).isEmpty());
    }

    // ---------- calculate ----------

    @Test
    void calculateFirstBarUsesSeedFifty() {
        List<KDJHandler.PeriodBar> bars = List.of(bar(10, 5, 10));
        List<KDJHandler.KdjValue> kdj = core.calculate(bars, bd("9"), bd("3"), bd("3"));
        // RSV=100，K=(50*2+100)/3，D=(50*2+K)/3，J=3K-2D
        assertEquals(0, kdj.get(0).k.compareTo(new BigDecimal("66.6666666667")));
        assertEquals(0, kdj.get(0).d.compareTo(new BigDecimal("55.5555555556")));
        assertEquals(0, kdj.get(0).j.compareTo(new BigDecimal("88.8888888889")));
    }

    @Test
    void calculateRsvFiftyWhenHighEqualsLow() {
        List<KDJHandler.PeriodBar> bars = List.of(bar(7, 7, 7));
        List<KDJHandler.KdjValue> kdj = core.calculate(bars, bd("9"), bd("3"), bd("3"));
        assertEquals(0, kdj.get(0).k.compareTo(new BigDecimal("50")));
        assertEquals(0, kdj.get(0).d.compareTo(new BigDecimal("50")));
        assertEquals(0, kdj.get(0).j.compareTo(new BigDecimal("50")));
    }

    @Test
    void calculateRsvWindowRespectsN() {
        List<KDJHandler.PeriodBar> bars = List.of(bar(10, 5, 10), bar(12, 8, 9));
        // n=1：第二根只看自己，RSV=(9-8)/(12-8)*100=25
        List<KDJHandler.KdjValue> n1 = core.calculate(bars, bd("1"), bd("3"), bd("3"));
        // n=2：窗口含第一根，RSV=(9-5)/(12-5)*100≈57.14
        List<KDJHandler.KdjValue> n2 = core.calculate(bars, bd("2"), bd("3"), bd("3"));
        assertTrue(n1.get(1).k.compareTo(n2.get(1).k) < 0);
    }

    // ---------- calcKdCrossValue / cross ----------

    @Test
    void crossGoldenReturnsCrossPoint() {
        KDJHandler.CrossPoint cp = core.calcKdCrossValue(bd("20"), bd("30"), bd("40"), bd("35"));
        assertNotNull(cp);
        // t=10/15≈0.667，crossValue=20+t*20≈33.33
        assertTrue(cp.t.compareTo(BigDecimal.ZERO) > 0 && cp.t.compareTo(BigDecimal.ONE) < 0);
        assertTrue(cp.crossValue.compareTo(bd("33")) > 0 && cp.crossValue.compareTo(bd("34")) < 0);
    }

    @Test
    void crossTouchAtEndpointsReturnsNull() {
        // t=1：当前根K=D，端点触碰不算交叉
        assertNull(core.calcKdCrossValue(bd("20"), bd("30"), bd("30"), bd("30")));
        // t=0：上一根K=D
        assertNull(core.calcKdCrossValue(bd("30"), bd("30"), bd("40"), bd("35")));
    }

    @Test
    void crossParallelReturnsNull() {
        assertNull(core.calcKdCrossValue(bd("20"), bd("30"), bd("30"), bd("40")));
    }

    @Test
    void goldenAndDeathCrossAt() {
        List<KDJHandler.KdjValue> kdj = List.of(kdj(30, 20), kdj(35, 40));
        assertNull(core.goldenCrossAt(kdj, 1));
        assertNotNull(core.deathCrossAt(kdj, 1));
        List<KDJHandler.KdjValue> kdj2 = List.of(kdj(20, 30), kdj(40, 35));
        assertNotNull(core.goldenCrossAt(kdj2, 1));
        assertNull(core.deathCrossAt(kdj2, 1));
        assertNull(core.goldenCrossAt(kdj2, 0));
    }

    // ---------- isTradeSignal ----------

    /**
     * 通过用例：x在下标4（crossValue≈11.36），y在下标10（crossValue≈14.29），
     * 间距6∈[5,15]，之间恰好一次死叉（下标7，crossValue≈13.43），y收盘90 &lt; x收盘100。
     */
    @Test
    void tradeSignalPasses() {
        List<KDJHandler.KdjValue> kdj = List.of(
                kdj(8, 10), kdj(9, 10), kdj(9, 11), kdj(5, 15), kdj(12, 11),
                kdj(14, 12), kdj(16, 13), kdj(10, 14), kdj(12, 13), kdj(12, 14),
                kdj(20, 15));
        List<KDJHandler.PeriodBar> bars = barsWithCloses(100, 100, 100, 100, 100, 100, 100, 100, 100, 100, 90);
        assertTrue(core.isTradeSignal(bars, kdj, tradeSignalParam()));
    }

    @Test
    void tradeSignalFailsWithoutGoldenCrossAtEnd() {
        List<KDJHandler.KdjValue> kdj = List.of(
                kdj(8, 10), kdj(9, 10), kdj(9, 11), kdj(5, 15), kdj(12, 11),
                kdj(14, 12), kdj(16, 13), kdj(10, 14), kdj(12, 13), kdj(12, 14),
                kdj(13, 15));
        List<KDJHandler.PeriodBar> bars = barsWithCloses(100, 100, 100, 100, 100, 100, 100, 100, 100, 100, 90);
        assertFalse(core.isTradeSignal(bars, kdj, tradeSignalParam()));
    }

    @Test
    void tradeSignalGapBoundaryClosedInterval() {
        List<KDJHandler.KdjValue> kdj = List.of(
                kdj(8, 10), kdj(9, 10), kdj(9, 11), kdj(5, 15), kdj(12, 11),
                kdj(14, 12), kdj(16, 13), kdj(10, 14), kdj(12, 13), kdj(12, 14),
                kdj(20, 15));
        List<KDJHandler.PeriodBar> bars = barsWithCloses(100, 100, 100, 100, 100, 100, 100, 100, 100, 100, 90);
        // 间距=6，闭区间：min=6、max=6 都应通过
        KDJParam p6 = tradeSignalParam();
        p6.setGoldInternalMin(bd("6"));
        p6.setGoldInternalMax(bd("6"));
        assertTrue(core.isTradeSignal(bars, kdj, p6));
        // min=7 排除
        KDJParam p7 = tradeSignalParam();
        p7.setGoldInternalMin(bd("7"));
        assertFalse(core.isTradeSignal(bars, kdj, p7));
        // max=5 排除
        KDJParam p5 = tradeSignalParam();
        p5.setGoldInternalMax(bd("5"));
        assertFalse(core.isTradeSignal(bars, kdj, p5));
    }

    @Test
    void tradeSignalFailsWhenLastGoldCrossAboveLimit() {
        List<KDJHandler.KdjValue> kdj = List.of(
                kdj(8, 10), kdj(9, 10), kdj(9, 11), kdj(5, 15), kdj(12, 11),
                kdj(14, 12), kdj(16, 13), kdj(10, 14), kdj(12, 13), kdj(12, 14),
                kdj(20, 15));
        List<KDJHandler.PeriodBar> bars = barsWithCloses(100, 100, 100, 100, 100, 100, 100, 100, 100, 100, 90);
        // x的crossValue≈11.36 > 10
        KDJParam p = tradeSignalParam();
        p.setLastGoldCrossMax(bd("10"));
        assertFalse(core.isTradeSignal(bars, kdj, p));
    }

    @Test
    void tradeSignalFailsWhenDeathCrossAboveLimit() {
        List<KDJHandler.KdjValue> kdj = List.of(
                kdj(8, 10), kdj(9, 10), kdj(9, 11), kdj(5, 15), kdj(12, 11),
                kdj(14, 12), kdj(16, 13), kdj(10, 14), kdj(12, 13), kdj(12, 14),
                kdj(20, 15));
        List<KDJHandler.PeriodBar> bars = barsWithCloses(100, 100, 100, 100, 100, 100, 100, 100, 100, 100, 90);
        // 死叉crossValue≈13.43 > 10
        KDJParam p = tradeSignalParam();
        p.setLastDeathCrossMax(bd("10"));
        assertFalse(core.isTradeSignal(bars, kdj, p));
    }

    @Test
    void tradeSignalClosePriceSwitch() {
        List<KDJHandler.KdjValue> kdj = List.of(
                kdj(8, 10), kdj(9, 10), kdj(9, 11), kdj(5, 15), kdj(12, 11),
                kdj(14, 12), kdj(16, 13), kdj(10, 14), kdj(12, 13), kdj(12, 14),
                kdj(20, 15));
        // y收盘100 不低于 x收盘100
        List<KDJHandler.PeriodBar> bars = barsWithCloses(100, 100, 100, 100, 100, 100, 100, 100, 100, 100, 100);
        KDJParam on = tradeSignalParam();
        assertFalse(core.isTradeSignal(bars, kdj, on));
        KDJParam off = tradeSignalParam();
        off.setOpenClosePriceLimit("0");
        assertTrue(core.isTradeSignal(bars, kdj, off));
    }

    @Test
    void tradeSignalGoldCrossLimitSwitch() {
        // x在下标4，crossValue=20；y在下标10，crossValue≈10.38 < x
        List<KDJHandler.KdjValue> kdj = List.of(
                kdj(8, 10), kdj(9, 10), kdj(9, 11), kdj(10, 20), kdj(30, 20),
                kdj(32, 21), kdj(34, 22), kdj(10, 25), kdj(12, 20), kdj(5, 10),
                kdj(12, 10.5));
        List<KDJHandler.PeriodBar> bars = barsWithCloses(100, 100, 100, 100, 100, 100, 100, 100, 100, 100, 90);
        KDJParam on = tradeSignalParam();
        assertFalse(core.isTradeSignal(bars, kdj, on));
        KDJParam off = tradeSignalParam();
        off.setGoldCrossLimit("0");
        assertTrue(core.isTradeSignal(bars, kdj, off));
    }

    // ---------- helpers ----------

    private StockQuoteDO daily(String tradeDate, double high, double low, double close) {
        StockQuoteDO daily = new StockQuoteDO();
        daily.setTradeDate(tradeDate);
        daily.setOpen(BigDecimal.valueOf(close));
        daily.setHigh(BigDecimal.valueOf(high));
        daily.setLow(BigDecimal.valueOf(low));
        daily.setClose(BigDecimal.valueOf(close));
        return daily;
    }

    private KDJHandler.PeriodBar bar(double high, double low, double close) {
        KDJHandler.PeriodBar bar = new KDJHandler.PeriodBar();
        bar.high = BigDecimal.valueOf(high);
        bar.low = BigDecimal.valueOf(low);
        bar.close = BigDecimal.valueOf(close);
        return bar;
    }

    private List<KDJHandler.PeriodBar> barsWithCloses(double... closes) {
        List<KDJHandler.PeriodBar> bars = new ArrayList<>();
        for (double close : closes) {
            bars.add(bar(close + 1, close - 1, close));
        }
        return bars;
    }

    private KDJHandler.KdjValue kdj(double k, double d) {
        return new KDJHandler.KdjValue(BigDecimal.valueOf(k), BigDecimal.valueOf(d), BigDecimal.ZERO);
    }

    private BigDecimal bd(String value) {
        return new BigDecimal(value);
    }

    // ---------- aggregateDates（交易日历 → 可选周期） ----------

    @Test
    void aggregateDatesDayExcludesFutureDates() {
        List<String> dates = List.of("20240102", "20240103", "20240104");
        // 日历含未来日期（20240104），剔除
        List<KDJHandler.PeriodBar> bars = core.aggregateDates(dates, "0", LocalDate.of(2024, 1, 3));
        assertEquals(2, bars.size());
        assertEquals("20240103", bars.get(1).endDate);
    }

    @Test
    void aggregateDatesWeekSkipsEmptyWeekAndUnfinishedWeek() {
        // 20240101~0105 一周、下一周无交易日（自然跳过）、0115~0119 一周、0122 本周未完结
        List<String> dates = List.of("20240102", "20240103", "20240115", "20240117", "20240122");
        List<KDJHandler.PeriodBar> bars = core.aggregateDates(dates, "1", LocalDate.of(2024, 1, 24));
        assertEquals(2, bars.size());
        assertEquals("20240102", bars.get(0).startDate);
        assertEquals("20240103", bars.get(0).endDate);
        assertEquals("20240115", bars.get(1).startDate);
        assertEquals("20240117", bars.get(1).endDate);
    }

    @Test
    void aggregateDatesMonthAndQuarter() {
        List<String> dates = List.of("20240131", "20240229", "20240328");
        LocalDate today = LocalDate.of(2024, 4, 1);
        List<KDJHandler.PeriodBar> months = core.aggregateDates(dates, "2", today);
        assertEquals(3, months.size());
        assertEquals("20240229", months.get(1).endDate);
        List<KDJHandler.PeriodBar> quarters = core.aggregateDates(dates, "3", today);
        assertEquals(1, quarters.size());
        assertEquals("20240131", quarters.get(0).startDate);
        assertEquals("20240328", quarters.get(0).endDate);
        // 季末前一天：Q1 未完结，剔除
        assertTrue(core.aggregateDates(dates, "3", LocalDate.of(2024, 3, 31)).isEmpty());
    }

    private KDJParam tradeSignalParam() {
        KDJParam param = new KDJParam();
        param.setCurrGoldCrossMax(bd("50"));
        param.setLastGoldCrossMax(bd("20"));
        param.setLastDeathCrossMax(bd("50"));
        param.setGoldInternalMin(bd("5"));
        param.setGoldInternalMax(bd("15"));
        param.setOpenClosePriceLimit("1");
        param.setGoldCrossLimit("1");
        return param;
    }
}
