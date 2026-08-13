package com.xi.service;

import com.xi.model.param.KDJParam;
import com.xi.model.vo.CrossStockVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * BOARD_TYPE 字典变更回归（2026-08-13）：
 * 0=上交所主板、1=科创板、2=创业板、3=北交所、4=深交所主板。
 * 全市场扫描出参 CrossStockVO 必须带出 boardType；stock_info 无记录时 boardType 为 null 不崩。
 */
@SpringBootTest
class KDJBoardTypeTest {

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private KDJService kdjService;

    @Autowired
    private ScanResultCache scanResultCache;

    @Autowired
    private ScanBarsCache scanBarsCache;

    private static final String[] CODES = {"600519", "000002", "688981", "300750", "920185", "601000"};
    private static final String[] DATES = {"20260807", "20260810", "20260811", "20260812", "20260813"};

    @BeforeEach
    void setup() {
        // 水位实时查库，保证失效与重算行为立即可见
        scanResultCache.watermarkTtlMs = 0;
        scanResultCache.clear();
        scanBarsCache.watermarkTtlMs = 0;
        scanBarsCache.clear();
        jdbc.update("delete from stock_quote");
        jdbc.update("delete from stock_info");
        // 五类板块各一（601000 刻意不登记 stock_info，验证 null 分支）
        Object[][] infos = {
                {"600519", "贵州茅台", "SH", "0"},
                {"000002", "万科A", "SZ", "4"},
                {"688981", "中芯国际", "SH", "1"},
                {"300750", "宁德时代", "SZ", "2"},
                {"920185", "贝特瑞", "BJ", "3"},
        };
        for (Object[] info : infos) {
            jdbc.update("insert into stock_info(CODE, NAME, MARKET, BOARD_TYPE) values (?,?,?,?)", info);
        }
        int seq = 0;
        for (String code : CODES) {
            for (int i = 0; i < DATES.length; i++) {
                double base = 10 + seq * 7;
                jdbc.update("insert into stock_quote(CODE, OPEN, HIGH, LOW, CLOSE, VOLUME, TRADE_DATE, ADJUST) "
                                + "values (?,?,?,?,?,?,?,?)",
                        code, base + i, base + i + 1, base + i - 1, base + i + 0.5, 100000L + seq, DATES[i], "1");
            }
            seq++;
        }
    }

    private Map<String, CrossStockVO> scanAll() {
        KDJParam param = new KDJParam();
        param.setAdjust("1");
        param.setKdjType("0");
        List<CrossStockVO> list = kdjService.getAllStocks(param);
        assertNotNull(list);
        return list.stream().collect(Collectors.toMap(CrossStockVO::getCode, Function.identity()));
    }

    @Test
    void allStocksCarryBoardTypeFromStockInfo() {
        Map<String, CrossStockVO> byCode = scanAll();
        assertEquals("0", byCode.get("600519").getBoardType(), "沪主板");
        assertEquals("4", byCode.get("000002").getBoardType(), "深主板");
        assertEquals("1", byCode.get("688981").getBoardType(), "科创板");
        assertEquals("2", byCode.get("300750").getBoardType(), "创业板");
        assertEquals("3", byCode.get("920185").getBoardType(), "北交所");
    }

    @Test
    void missingStockInfoYieldsNullBoardType() {
        Map<String, CrossStockVO> byCode = scanAll();
        CrossStockVO noInfo = byCode.get("601000");
        assertNotNull(noInfo, "有行情但无 stock_info 的股票仍应出现在扫描列表");
        assertNull(noInfo.getBoardType(), "无 stock_info 记录时 boardType 应为 null");
        assertNull(noInfo.getName());
        assertNull(noInfo.getMarket());
    }

    @Test
    void goldCrossRowsCarryBoardType() {
        KDJParam param = new KDJParam();
        param.setAdjust("1");
        param.setKdjType("0");
        List<CrossStockVO> list = kdjService.getGold(param);
        for (CrossStockVO vo : list) {
            assertNotNull(vo.getBoardType(), vo.getCode() + " 金叉行应带出 boardType");
        }
    }
}
