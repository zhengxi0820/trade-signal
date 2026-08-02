package com.xi.orm.entity;

import lombok.Data;

@Data
public class WorkDayDO {

    /**
     * ID
     */
    private String id;

    /**
     * 市场
     */
    private String market;

    /**
     * 交易日期
     */
    private String tradeDate;

    private String createDate;

    private String createdAt;

    private String updatedAt;

}
