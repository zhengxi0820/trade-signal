package com.xi.model.param;

import lombok.Data;

/**
 * 可选周期列表（/kdj/periods）入参。
 */
@Data
public class WorkDayParam {

    /**
     * 市场标识（为空 = 全部市场）
     */
    private String market;

    /**
     * 日度 / 周度 / 月度 / 季度 -> 0 / 1 / 2 / 3，默认 0
     */
    private String kdjType;

}
