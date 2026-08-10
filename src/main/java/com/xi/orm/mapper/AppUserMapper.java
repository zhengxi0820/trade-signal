package com.xi.orm.mapper;

import com.xi.orm.entity.AppUserDO;
import org.apache.ibatis.annotations.Param;

public interface AppUserMapper {

    /**
     * 按登录名查用户（uk_username 唯一索引；MySQL ci 排序规则下大小写不敏感）
     */
    AppUserDO findByUsername(@Param("username") String username);

    /**
     * 注册插入（用户名重复由唯一索引兜底，抛 DuplicateKeyException）
     */
    int insert(AppUserDO user);
}
