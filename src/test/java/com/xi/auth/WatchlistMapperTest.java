package com.xi.auth;

import com.xi.orm.mapper.WatchlistMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 自选股存储测试（H2）：按用户隔离、唯一约束、移出。
 */
@SpringBootTest
class WatchlistMapperTest {

    @Autowired
    private WatchlistMapper watchlistMapper;
    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void setup() {
        jdbc.update("delete from user_watchlist");
    }

    @Test
    void addListRemove() {
        watchlistMapper.insert("alice", "600519", "1786000000");
        watchlistMapper.insert("alice", "000001", "1786000001");
        watchlistMapper.insert("bob", "600519", "1786000002");

        // 插入时 UPDATED_AT 与 CREATED_AT 同值（无更新路径）
        assertEquals("1786000000",
                jdbc.queryForObject("select UPDATED_AT from user_watchlist where USERNAME='alice' and CODE='600519'",
                        String.class));
        assertEquals(java.util.List.of("600519", "000001"), watchlistMapper.queryCodes("alice"));
        assertEquals(java.util.List.of("600519"), watchlistMapper.queryCodes("bob"));
        assertTrue(watchlistMapper.queryCodes("nobody").isEmpty());

        // 重复加入撞唯一索引（controller 幂等吞掉）
        assertThrows(DuplicateKeyException.class,
                () -> watchlistMapper.insert("alice", "600519", "1786000003"));

        watchlistMapper.delete("alice", "600519");
        assertEquals(java.util.List.of("000001"), watchlistMapper.queryCodes("alice"));
    }
}
