package com.xi.model.vo;

import lombok.Data;

/**
 * 可选周期出参（/kdj/periods）。
 * 日期字段规则与 KDJ 三字段规则一致：日/月度填 tradeDate（月度为该月最后一个交易日），
 * 周度填 tradeDateMin/tradeDateMax（该周首/末交易日 yyyymmdd），
 * 季度填 tradeDateMin/tradeDateMax（季度首/末月 yyyymm）。
 */
@Data
public class WorkDayVO {

    /**
     * 交易日期（日度、月度使用）yyyymmdd
     */
    private String tradeDate;

    /**
     * 周期首日（周度 yyyymmdd；季度首月 yyyymm）
     */
    private String tradeDateMin;

    /**
     * 周期末日（周度 yyyymmdd；季度末月 yyyymm）
     */
    private String tradeDateMax;

}
