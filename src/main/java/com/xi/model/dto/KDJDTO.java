package com.xi.model.dto;

import lombok.Data;

import java.math.BigDecimal;

@Data
public class KDJDTO {

    /**
     * 该周期开盘价
     */
    private BigDecimal open;

    /**
     * 该周期最高价
     */
    private BigDecimal high;

    /**
     * 该周期最低价
     */
    private BigDecimal low;

    /**
     * 该周期收盘价
     */
    private BigDecimal close;

    /**
     * K值
     */
    private BigDecimal k;

    /**
     * D值
     */
    private BigDecimal d;

    /**
     * J值
     */
    private BigDecimal j;

    /**
     * 交叉类型：gold=金叉、death=死叉、null=无交叉
     */
    private String crossType;

    /**
     * 交汇点数值（该根发生交叉时）
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
