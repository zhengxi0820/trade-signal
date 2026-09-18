package com.xi.model.param;

import lombok.Data;

import java.math.BigDecimal;

@Data
public class KDJParam {

    /**
     * 股票代码，为空 = 全市场
     */
    private String code;

    /**
     * 市场标识
     */
    private String market;

    /**
     * 复权类型："0"=无复权、"1"=前复权（默认）、"2"=后复权（预留）
     */
    private String adjust;

    /**
     * 周期类型："0"=日、"1"=周、"2"=月、"3"=季，默认 "0"
     */
    private String kdjType;

    /**
     * 截止周期（日度、月度使用）yyyymmdd；月度由后端截位到 yyyyMM 定位当月
     */
    private String tradeDate;

    /**
     * 截止周期下限（周度：该周第一天 yyyymmdd；季度：季度首月 yyyymm，如 202501）
     */
    private String tradeDateMin;

    /**
     * 截止周期上限（周度：该周最后一天 yyyymmdd；季度：季度末月 yyyymm，如 202503）
     */
    private String tradeDateMax;

    /**
     * RSV 窗口周期，默认 9
     */
    private BigDecimal n;

    /**
     * K 平滑周期，默认 3
     */
    private BigDecimal m1;

    /**
     * D 平滑周期，默认 3
     */
    private BigDecimal m2;

    /**
     * 上次金叉交汇上限（仅交易位），默认 20
     */
    private BigDecimal lastGoldCrossMax;

    /**
     * 当前金叉交汇上限（仅交易位），默认 50
     */
    private BigDecimal currGoldCrossMax;

    /**
     * 上次金叉和当前金叉 中间的死叉交汇上限，默认 50
     */
    private BigDecimal lastDeathCrossMax;

    /**
     * 两次金叉之间最小间距限制（闭区间），默认 5
     */
    private BigDecimal goldInternalMin;

    /**
     * 两次金叉之间最大间距限制（闭区间），默认 15
     */
    private BigDecimal goldInternalMax;

    /**
     * 开关：要求上次金叉的周期收盘价格大于此次金叉的周期收盘价格，"1"=开（默认）、"0"=关
     */
    private String openClosePriceLimit;

    /**
     * 开关：要求上次金叉的交汇点小于这次金叉的交汇点，"1"=开（默认）、"0"=关
     */
    private String goldCrossLimit;

    /**
     * 开关：上次金叉交汇上限是否生效，"1"=开（默认）、"0"=关（不限制）
     */
    private String lastGoldCrossMaxEnabled;

    /**
     * 开关：当前金叉交汇上限是否生效，"1"=开（默认）、"0"=关（不限，各端点语义一致）
     */
    private String currGoldCrossMaxEnabled;

    /**
     * 开关：死叉交汇上限是否生效，"1"=开（默认）、"0"=关（仅停用交汇点上限，"恰好一次死叉"结构条件仍生效）
     */
    private String lastDeathCrossMaxEnabled;

    /**
     * 开关：金叉最小间距是否生效，"1"=开（默认）、"0"=关（不设下限；回看窗口仍按 goldInternalMax 计算）
     */
    private String goldInternalMinEnabled;

    /**
     * 开关：金叉最大间距是否生效，"1"=开（默认）、"0"=关（不设上限；回看窗口仍按本值计算）
     */
    private String goldInternalMaxEnabled;

}
