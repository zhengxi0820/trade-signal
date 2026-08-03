package com.xi.orm.entity;

import lombok.Data;

@Data
public class StockInfoDO {

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
     * 板块：0=沪深主板 1=科创板 2=创业板 3=北交所
     */
    private String boardType;

    private String createdAt;

    private String updatedAt;

}
