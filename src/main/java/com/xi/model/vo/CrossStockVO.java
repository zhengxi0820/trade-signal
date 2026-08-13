package com.xi.model.vo;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 金叉 / 交易位股票列表出参。
 * 日期字段规则与入参一致：日/月度填 tradeDate，周度填 tradeDateMin/tradeDateMax（yyyymmdd），
 * 季度填 tradeDateMin/tradeDateMax（yyyymm）。
 */
@Data
public class CrossStockVO {

    /**
     * 股票代码
     */
    private String code;

    /**
     * 股票名称
     */
    private String name;

    /**
     * 市场标识
     */
    private String market;

    /**
     * 板块：0=上交所主板 1=科创板 2=创业板 3=北交所 4=深交所主板
     */
    private String boardType;

    /**
     * 截止周期开盘价
     */
    private BigDecimal open;

    /**
     * 截止周期最高价
     */
    private BigDecimal high;

    /**
     * 截止周期最低价
     */
    private BigDecimal low;

    /**
     * 截止周期收盘价
     */
    private BigDecimal close;

    /**
     * 截止周期K值
     */
    private BigDecimal k;

    /**
     * 截止周期D值
     */
    private BigDecimal d;

    /**
     * 截止周期J值
     */
    private BigDecimal j;

    /**
     * 截止周期金叉交汇点数值
     */
    private BigDecimal crossValue;

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
