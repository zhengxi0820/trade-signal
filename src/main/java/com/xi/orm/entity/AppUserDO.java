package com.xi.orm.entity;

import lombok.Data;

/**
 * 注册用户（邀请码注册制）。PASSWORD 为 BCrypt 哈希，永不存明文。
 */
@Data
public class AppUserDO {

    /**
     * ID
     */
    private Long id;

    /**
     * 登录名
     */
    private String username;

    /**
     * 密码 BCrypt 哈希
     */
    private String password;

    /**
     * 状态：1=正常 0=禁用
     */
    private String status;

    /**
     * 注册时使用的邀请码（滥用追溯）
     */
    private String inviteCode;

    private String createdAt;

    private String updatedAt;
}
