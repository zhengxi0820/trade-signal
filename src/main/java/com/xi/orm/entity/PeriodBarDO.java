package com.xi.orm.entity;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 周期K线物化（stock_period_bar，scripts 周频物化；月/季，周线预留）。
 * 全市场扫描的月/季线从本表读取，不再现场聚合 stock_quote。
 */
@Data
public class PeriodBarDO {

    /**
     * ID
     */
    private Long id;

    /**
     * 周期类型：1=周(预留) 2=月 3=季
     */
    private String periodType;

    /**
     * 股票代码
     */
    private String code;

    /**
     * 复权类型
     */
    private String adjust;

    /**
     * 周期首个交易日 yyyymmdd
     */
    private String periodStart;

    /**
     * 周期最后交易日 yyyymmdd
     */
    private String periodEnd;

    private BigDecimal open;

    private BigDecimal high;

    private BigDecimal low;

    private BigDecimal close;

    private String createdAt;

    private String updatedAt;
}
