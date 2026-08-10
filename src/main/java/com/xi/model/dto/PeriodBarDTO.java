package com.xi.model.dto;

import lombok.Data;

import java.math.BigDecimal;

/**
 * SQL 预聚合的周期K线（月/季），与 KDJHandler.PeriodBar 字段一一对应。
 * 由 StockQuoteMapper 的聚合查询直接产出，跳过 Java 侧逐行聚合。
 */
@Data
public class PeriodBarDTO {

    /** 周期首个交易日 yyyymmdd */
    private String startDate;

    /** 周期最后一个交易日 yyyymmdd */
    private String endDate;

    private BigDecimal open;

    private BigDecimal high;

    private BigDecimal low;

    private BigDecimal close;
}
