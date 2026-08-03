package com.xi.orm.mapper;

import com.xi.orm.entity.StockInfoDO;

import java.util.List;

public interface StockInfoMapper {

    /**
     * 全量查询股票基础信息（name/market 的唯一来源）
     */
    List<StockInfoDO> queryAll();

}
