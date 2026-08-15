package com.xi.service;

import com.xi.model.param.KDJParam;
import com.xi.service.Impl.KDJServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 周/月/季扫描数据就绪标记（X-Data-Not-Ready）测试：
 * 物化表最新周期末 < 请求截止周期 → 未就绪；已覆盖/日线 → 就绪。
 */
@SpringBootTest
class ScanDataReadyTest {

    @Autowired
    private KDJService kdjService;
    @Autowired
    private KDJServiceImpl kdjServiceImpl;
    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void setup() {
        kdjServiceImpl.resetReadyCaches();
        jdbc.update("delete from stock_quote");
        jdbc.update("delete from stock_period_bar");
        jdbc.update("delete from work_day");
        // 本周（20260803-20260807，周一至周五）为最新已完结周
        for (String d : List.of("20260803", "20260804", "20260805", "20260806", "20260807")) {
            jdbc.update("insert into work_day (MARKET, TRADE_DATE) values ('SH', ?)", d);
        }
        // 未来日历锚点：截断兜底要求日历覆盖该周之后才能确认完结
        jdbc.update("insert into work_day (MARKET, TRADE_DATE) values ('SH', ?)", "20260810");
    }

    private void insertWeeklyBar(String start, String end) {
        jdbc.update("insert into stock_period_bar "
                + "(PERIOD_TYPE, CODE, ADJUST, PERIOD_START, PERIOD_END, OPEN, HIGH, LOW, CLOSE) "
                + "values ('1', '600519', '1', ?, ?, 100, 110, 90, 105)", start, end);
    }

    @Test
    void dailyAlwaysReady() {
        KDJParam p = new KDJParam();
        p.setKdjType("0");
        assertTrue(kdjService.isScanDataReady(p));
    }

    @Test
    void weeklyBehindWhenLatestPeriodMissing() {
        // 物化只到上周（20260727-20260731），最新周缺 → 未就绪
        insertWeeklyBar("20260727", "20260731");
        KDJParam p = new KDJParam();
        p.setKdjType("1");
        p.setTradeDateMin("20260803");
        p.setTradeDateMax("20260807");
        assertFalse(kdjService.isScanDataReady(p));
        // 选上周 → 已覆盖 → 就绪
        p.setTradeDateMin("20260727");
        p.setTradeDateMax("20260731");
        assertTrue(kdjService.isScanDataReady(p));
    }

    @Test
    void weeklyBehindOnDefaultLatestPeriod() {
        // 不传日期 = 默认最新已完结周（work_day 推导 20260807）；物化只到上周 → 未就绪
        insertWeeklyBar("20260727", "20260731");
        KDJParam p = new KDJParam();
        p.setKdjType("1");
        assertFalse(kdjService.isScanDataReady(p));
    }

    @Test
    void weeklyReadyWhenMaterialized() {
        // 物化已含最新周 → 默认最新周期就绪
        insertWeeklyBar("20260727", "20260731");
        insertWeeklyBar("20260803", "20260807");
        KDJParam p = new KDJParam();
        p.setKdjType("1");
        assertTrue(kdjService.isScanDataReady(p));
    }
}
