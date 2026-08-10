package com.xi.service;

import com.xi.handler.KDJHandler;
import com.xi.orm.mapper.StockQuoteMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 全市场扫描的周期K线缓存（bars 层）。
 * key = code|adjust|kdjType，与 n/m1/m2 无关——KDJ 递推全市场仅秒级，
 * 贵的是取数与聚合，所以缓存到 bars 为止，递推按请求参数现算。
 * 窗口按最新周期锚定，历史截止周期查询直接切前缀（见 KDJServiceImpl.scanBarsFor）。
 *
 * 失效：与 ScanResultCache 同一数据水位（max(trade_date)），水位居变整表失效。
 * 条目数天然有界（股票数 × kdjType × adjust），无需淘汰策略。
 */
@Component
public class ScanBarsCache {

    @Autowired
    private StockQuoteMapper stockQuoteMapper;

    private final Map<String, Entry> entries = new ConcurrentHashMap<>();

    private volatile String watermark;
    private volatile long watermarkAt;
    /** 测试可清零，使水位每次实时查库 */
    volatile long watermarkTtlMs = 60_000;

    private static class Entry {
        final List<KDJHandler.PeriodBar> bars;
        final String watermark;

        Entry(List<KDJHandler.PeriodBar> bars, String watermark) {
            this.bars = bars;
            this.watermark = watermark;
        }
    }

    public static String key(String code, String adjust, String kdjType) {
        return code + "|" + adjust + "|" + kdjType;
    }

    /**
     * 命中返回窗口 bars（按最新周期锚定）；未命中或水位已变（整表失效）返回 null。
     */
    public List<KDJHandler.PeriodBar> get(String key) {
        Entry entry = entries.get(key);
        if (entry == null) {
            return null;
        }
        if (!Objects.equals(entry.watermark, currentWatermark())) {
            entries.clear();
            return null;
        }
        return entry.bars;
    }

    public void put(String key, List<KDJHandler.PeriodBar> bars) {
        entries.put(key, new Entry(bars, currentWatermark()));
    }

    public int size() {
        return entries.size();
    }

    /**
     * 手动清空（运维兜底）。
     *
     * @return 清掉的条数
     */
    public int clear() {
        int size = entries.size();
        entries.clear();
        watermark = null;
        return size;
    }

    private String currentWatermark() {
        long now = System.currentTimeMillis();
        String wm = watermark;
        if (wm != null && now - watermarkAt < watermarkTtlMs) {
            return wm;
        }
        wm = stockQuoteMapper.queryMaxTradeDate();
        watermark = wm;
        watermarkAt = now;
        return wm;
    }
}
