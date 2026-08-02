package com.xi.service;

import com.xi.model.dto.KDJDTO;
import com.xi.model.param.KDJParam;
import com.xi.model.param.WorkDayParam;
import com.xi.model.vo.CrossStockVO;
import com.xi.model.vo.WorkDayVO;

import java.util.List;

public interface KDJService {

    /**
     * 获取某只股票 指定周期完整的KDJ序列（含交叉点标注）
     *
     * @param kdjParam code 必填
     * @return KDJ序列，时间升序
     */
    List<KDJDTO> getAllKDJ(KDJParam kdjParam);

    /**
     * 获取某个周期 截止周期出现金叉的所有股票 不进行交易位判断
     *
     * @param kdjParam code 为空 = 全市场
     * @return 金叉股票列表
     */
    List<CrossStockVO> getGold(KDJParam kdjParam);

    /**
     * 获取某个周期 截止周期出现交易位的所有股票
     *
     * @param kdjParam code 为空 = 全市场
     * @return 交易位股票列表
     */
    List<CrossStockVO> getTradeSignalStockList(KDJParam kdjParam);

    /**
     * 获取全部股票的截止周期行情与 KDJ（不做金叉/交易位过滤），供「所有股票」列表
     *
     * @param kdjParam code 为空 = 全市场
     * @return 全部股票列表；截止周期有交叉时 crossValue 有值，否则为空
     */
    List<CrossStockVO> getAllStocks(KDJParam kdjParam);

    /**
     * 获取可选周期列表（已完结周期），基于 work_day 交易日历推导，
     * 供前端截止周期选择器（日线置灰、周线选项、月/季可选范围）
     *
     * @param param kdjType 必填（缺省按日线），market 选填
     * @return 周期列表，时间倒序
     */
    List<WorkDayVO> getPeriods(WorkDayParam param);
}
