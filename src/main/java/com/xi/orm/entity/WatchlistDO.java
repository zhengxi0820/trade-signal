package com.xi.orm.entity;

import lombok.Data;

/**
 * 用户自选股（user_watchlist）。
 */
@Data
public class WatchlistDO {

    /**
     * ID
     */
    private Long id;

    /**
     * 用户名（token subject）
     */
    private String username;

    /**
     * 股票代码
     */
    private String code;

    private String createdAt;
}
