package com.xi.orm.mapper;

import org.apache.ibatis.annotations.Param;

import java.util.List;

public interface WatchlistMapper {

    /**
     * 我的自选代码列表（创建时间升序）
     */
    List<String> queryCodes(@Param("username") String username);

    /**
     * 加入自选（重复由唯一索引兜底）
     */
    int insert(@Param("username") String username, @Param("code") String code, @Param("createdAt") String createdAt);

    /**
     * 移出自选
     */
    int delete(@Param("username") String username, @Param("code") String code);
}
