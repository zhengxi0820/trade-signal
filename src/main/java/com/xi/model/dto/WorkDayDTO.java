package com.xi.model.dto;

import lombok.Data;

/**
 * 交易日（work_day）。
 */
@Data
public class WorkDayDTO {

    /**
     * ID
     */
    private String id;

    /**
     * 市场
     */
    private String market;

    /**
     * 交易日期 yyyymmdd
     */
    private String tradeDate;

}
