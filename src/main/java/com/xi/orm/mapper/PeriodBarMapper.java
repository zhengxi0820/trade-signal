package com.xi.orm.mapper;

import com.xi.orm.entity.PeriodBarDO;
import org.apache.ibatis.annotations.Param;

import java.util.List;

public interface PeriodBarMapper {

    /**
     * 单股某周期物化 bars（period_end 升序）
     */
    List<PeriodBarDO> queryByCode(@Param("periodType") String periodType,
                                  @Param("code") String code,
                                  @Param("adjust") String adjust);

    /**
     * 批量查询（全市场扫描：每批 ~200 只，小表索引读，秒级）
     */
    List<PeriodBarDO> queryBatch(@Param("periodType") String periodType,
                                 @Param("codes") List<String> codes,
                                 @Param("adjust") String adjust);

    /**
     * 物化表是否已启用（行数；首次物化前为 0，扫描走 stock_quote 现场聚合兜底）
     */
    long countAll();

    /**
     * 某周期物化表最新周期末（yyyymmdd）；表空返回 null。
     * 供扫描数据就绪标记（X-Data-Not-Ready）使用。
     */
    String queryMaxPeriodEnd(@Param("periodType") String periodType);
}
