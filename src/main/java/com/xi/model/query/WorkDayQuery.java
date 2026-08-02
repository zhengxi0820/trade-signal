package com.xi.model.query;

import lombok.Data;

/**
 * work_day 查询条件。
 */
@Data
public class WorkDayQuery {

    /**
     * 市场（为空 = 全部市场）
     */
    private String market;

    /**
     * 交易日期 yyyymmdd
     */
    private String tradeDate;

}
