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
     * 板块：0=上交所主板 1=科创板 2=创业板 3=北交所 4=深交所主板
     * （与 scripts/common/const.py、docs/trade-signal-schema.sql 口径一致）
     */
    private String boardType;

    private String createdAt;

    private String updatedAt;

}
