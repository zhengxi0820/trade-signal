package com.xi.orm.entity;

import lombok.Data;

import java.math.BigDecimal;

@Data
public class StockQuoteDO {

    /**
     * ID
     */
    private String id;

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
     * 开盘价
     */
    private BigDecimal open;

    /**
     * 最高价
     */
    private BigDecimal high;

    /**
     * 最低价
     */
    private BigDecimal low;

    /**
     * 收盘价
     */
    private BigDecimal close;

    /**
     * 成交量
     */
    private Long volume;

    /**
     * 交易日期
     */
    private String tradeDate;

    /**
     * 复权类型
     */
    private String adjust;

    private String createdAt;

    private String updatedAt;

}
