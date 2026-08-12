package com.xi.service;

import com.xi.model.vo.CrossStockVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * 扫描结果缓存单飞测试：同 key 并发未命中时共享一次计算（2026-08-12 冷缓存并发风暴根因的回归）。
 */
@SpringBootTest
class ScanResultCacheSingleFlightTest {

    @Autowired
    private ScanResultCache scanResultCache;

    @BeforeEach
    void setup() {
        scanResultCache.clear();
    }

    @Test
    void concurrentSameKeyComputesOnce() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        Supplier<List<CrossStockVO>> supplier = () -> {
            calls.incrementAndGet();
            try {
                Thread.sleep(200);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            return List.of(new CrossStockVO());
        };
        String key = "single-flight|test";
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            List<Future<List<CrossStockVO>>> futures = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                futures.add(pool.submit(() -> scanResultCache.computeIfAbsent(key, supplier)));
            }
            for (Future<List<CrossStockVO>> f : futures) {
                assertFalse(f.get().isEmpty());
            }
        } finally {
            pool.shutdownNow();
        }
        assertEquals(1, calls.get(), "同 key 并发 4 个请求应只计算一次");
        // 已入缓存：再次调用直接命中，不再触发 supplier
        assertNotNull(scanResultCache.computeIfAbsent(key, supplier));
        assertEquals(1, calls.get(), "命中缓存后不应再计算");
    }
}
