package com.xi.service;

import com.xi.model.vo.CrossStockVO;
import com.xi.orm.mapper.StockQuoteMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.function.Supplier;

/**
 * 全市场扫描结果缓存（gold-cross / trade-signal / all-stocks）。
 * 只缓存结果列表，不缓存行情与 KDJ 序列。
 *
 * 失效靠数据水位：每次请求比对 stock_quote 的 max(trade_date)，
 * 与缓存写入时不一致则整表失效——每日增量灌数后无需任何调用，自动失效。
 * 水位本身短缓存 60s（毫秒级 SQL，防并发请求每趟都查库）。
 */
@Component
public class ScanResultCache {

    private static final int MAX_ENTRIES = 256;
    private static final long WATERMARK_TTL_MS = 60_000;

    @Autowired
    private StockQuoteMapper stockQuoteMapper;

    private final Map<String, Entry> entries = new ConcurrentHashMap<>();
    /** 单飞：同 key 并发未命中时共享一次计算，防冷缓存并发风暴（2026-08-12 OOM 事故根因） */
    private final Map<String, CompletableFuture<List<CrossStockVO>>> inflight = new ConcurrentHashMap<>();

    private volatile String watermark;
    private volatile long watermarkAt;
    /** 测试可清零，使水位每次实时查库 */
    volatile long watermarkTtlMs = WATERMARK_TTL_MS;

    private static class Entry {
        final List<CrossStockVO> result;
        final String watermark;

        Entry(List<CrossStockVO> result, String watermark) {
            this.result = result;
            this.watermark = watermark;
        }
    }

    /**
     * 命中返回结果；未命中或水位已变（整表失效）返回 null。
     */
    public List<CrossStockVO> get(String key) {
        Entry entry = entries.get(key);
        if (entry == null) {
            return null;
        }
        if (!Objects.equals(entry.watermark, currentWatermark())) {
            entries.clear();
            return null;
        }
        return entry.result;
    }

    public void put(String key, List<CrossStockVO> result) {
        // 参数组合不受控，防无限增长：超容量整表清空
        if (entries.size() >= MAX_ENTRIES) {
            entries.clear();
        }
        entries.put(key, new Entry(result, currentWatermark()));
    }

    /**
     * 单飞取数：命中直接返回；未命中则同 key 的并发请求共享一次 supplier 计算。
     * 计算完成后按"开始计算时水位"校验，水位已变则跳过写入（下个请求自然重算）。
     */
    public List<CrossStockVO> computeIfAbsent(String key, Supplier<List<CrossStockVO>> supplier) {
        List<CrossStockVO> hit = get(key);
        if (hit != null) {
            return hit;
        }
        String wm = currentWatermark();
        CompletableFuture<List<CrossStockVO>> future = inflight.computeIfAbsent(
                key, k -> CompletableFuture.supplyAsync(supplier));
        try {
            List<CrossStockVO> result = future.get();
            if (Objects.equals(wm, currentWatermark())) {
                put(key, result);
            }
            return result;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("扫描被中断: " + key, e);
        } catch (ExecutionException e) {
            throw new IllegalStateException("扫描失败: " + key, e.getCause());
        } finally {
            inflight.remove(key);
        }
    }

    /**
     * 手动清空（/kdj/cache/refresh 运维兜底）。
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
