package com.xi.orm.mapper;

import com.xi.model.dto.PeriodBarDTO;
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

    /**
     * 全库最新交易日（扫描结果缓存的数据水位，走 idx_trade_date）
     */
    String queryMaxTradeDate();

    /**
     * 月线预聚合（SQL 侧 GROUP BY 月份，全市场扫描用；未完结当月由 currentPeriod 剔除）
     */
    List<PeriodBarDTO> queryMonthlyBars(@Param("code") String code,
                                        @Param("adjust") String adjust,
                                        @Param("tradeDateMin") String tradeDateMin,
                                        @Param("currentPeriod") String currentPeriod);

    /**
     * 季线预聚合（SQL 侧 GROUP BY 季度，全市场扫描用；未完结当季由 currentPeriod 剔除）
     */
    List<PeriodBarDTO> queryQuarterlyBars(@Param("code") String code,
                                          @Param("adjust") String adjust,
                                          @Param("tradeDateMin") String tradeDateMin,
                                          @Param("currentPeriod") String currentPeriod);

    /**
     * 批量加载窗口内日线（日/周线全市场扫描：每批 ~200 只，按 code 顺序读索引，避免单股随机 IO）
     */
    List<StockQuoteDO> queryWindowBatch(@Param("codes") List<String> codes,
                                        @Param("adjust") String adjust,
                                        @Param("tradeDateMin") String tradeDateMin);
}
