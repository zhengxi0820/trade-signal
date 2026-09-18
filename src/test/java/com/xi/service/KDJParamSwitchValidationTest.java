package com.xi.service;

import com.xi.model.param.KDJParam;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 五个信号限制项开关的入参白名单校验（validateParam → requireEnum SWITCH_VALUES）：
 * 仅接受 "0"/"1"/不传，非法值 400，与既有两个开关（openClosePriceLimit/goldCrossLimit）同口径。
 */
@SpringBootTest
class KDJParamSwitchValidationTest {

    @Autowired
    private KDJService kdjService;

    private static final String[] SWITCH_FIELDS = {
            "lastGoldCrossMaxEnabled", "currGoldCrossMaxEnabled", "lastDeathCrossMaxEnabled",
            "goldInternalMinEnabled", "goldInternalMaxEnabled"
    };

    @Test
    void invalidSwitchValueRejectedWith400() {
        for (String field : SWITCH_FIELDS) {
            for (String bad : new String[]{"2", "true", "on"}) {
                KDJParam p = new KDJParam();
                p.setKdjType("0");
                setSwitch(p, field, bad);
                ResponseStatusException e = assertThrows(ResponseStatusException.class,
                        () -> kdjService.getGold(p),
                        field + "=" + bad + " 应 400");
                assertEquals(400, e.getStatusCode().value(), field);
            }
        }
    }

    /** 合法值（"0"/"1"/不传）放行：校验通过后走正常扫描路径（stock_info 空表 → 空结果）。 */
    @Test
    void legalSwitchValuesAccepted() {
        for (String field : SWITCH_FIELDS) {
            for (String value : new String[]{"0", "1", null}) {
                KDJParam p = new KDJParam();
                p.setKdjType("0");
                if (value != null) {
                    setSwitch(p, field, value);
                }
                assertNotNull(assertDoesNotThrow(() -> kdjService.getGold(p)),
                        field + "=" + value + " 应放行");
            }
        }
    }

    private void setSwitch(KDJParam p, String field, String value) {
        switch (field) {
            case "lastGoldCrossMaxEnabled" -> p.setLastGoldCrossMaxEnabled(value);
            case "currGoldCrossMaxEnabled" -> p.setCurrGoldCrossMaxEnabled(value);
            case "lastDeathCrossMaxEnabled" -> p.setLastDeathCrossMaxEnabled(value);
            case "goldInternalMinEnabled" -> p.setGoldInternalMinEnabled(value);
            case "goldInternalMaxEnabled" -> p.setGoldInternalMaxEnabled(value);
            default -> throw new IllegalArgumentException(field);
        }
    }
}
