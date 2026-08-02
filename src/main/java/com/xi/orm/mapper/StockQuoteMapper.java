package com.xi.orm.mapper;

import com.xi.model.query.StockQuoteQuery;
import com.xi.orm.entity.StockQuoteDO;
import org.apache.ibatis.annotations.Param;

import java.util.List;

public interface StockQuoteMapper {

    List<StockQuoteDO> queryAll(StockQuoteQuery stockQuoteQuery);

    /**
     * 全市场股票代码清单（全市场扫描用）
     */
    List<String> queryDistinctCodes(@Param("adjust") String adjust);
}
