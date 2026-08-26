package com.xi.orm.mapper;

import com.xi.orm.entity.PeriodBarDO;
import org.apache.ibatis.annotations.Param;

import java.util.List;

public interface PeriodBarMapper {

    /**
     * 单股某周期物化 bars（period_end 升序）。periodEndMin 非空时只取窗口内行
     * （null = 全历史；窗口内行数不足窗口宽度时调用方需全量重读兜底，见 KDJServiceImpl）
     */
    List<PeriodBarDO> queryByCode(@Param("periodType") String periodType,
                                  @Param("code") String code,
                                  @Param("adjust") String adjust,
                                  @Param("periodEndMin") String periodEndMin);

    /**
     * 批量查询（全市场扫描：每批 ~200 只，小表索引读，秒级）。
     * periodEndMin/periodEndMax 非空时按 period_end 过滤：
     * min = 扫描窗口下界（砍读取量），max = 历史截止上界（锚定截止周期）；null = 不设界
     */
    List<PeriodBarDO> queryBatch(@Param("periodType") String periodType,
                                 @Param("codes") List<String> codes,
                                 @Param("adjust") String adjust,
                                 @Param("periodEndMin") String periodEndMin,
                                 @Param("periodEndMax") String periodEndMax);

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
