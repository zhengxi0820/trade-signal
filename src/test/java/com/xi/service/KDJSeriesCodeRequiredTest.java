package com.xi.service;

import com.xi.model.param.KDJParam;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * series 端点 code 必填（S-04）：缺省/空白 code 直接 400，
 * 不再退化为全市场原始行聚合成单序列（重查询压 DB）。
 */
@SpringBootTest
class KDJSeriesCodeRequiredTest {

    @Autowired
    private KDJService kdjService;

    @Test
    void blankOrMissingCodeRejectedWith400() {
        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> kdjService.getAllKDJ(new KDJParam()));
        assertEquals(400, e.getStatusCode().value());

        KDJParam blank = new KDJParam();
        blank.setCode("  ");
        assertNotNull(assertThrows(ResponseStatusException.class, () -> kdjService.getAllKDJ(blank)));
    }

    /** 带合法 code 的正常路径不受影响（H2 空库 → 空序列，不触发布局校验后的查询异常）。 */
    @Test
    void validCodeStillWorks() {
        KDJParam param = new KDJParam();
        param.setCode("600519");
        List<?> result = kdjService.getAllKDJ(param);
        assertTrue(result.isEmpty());
    }
}
