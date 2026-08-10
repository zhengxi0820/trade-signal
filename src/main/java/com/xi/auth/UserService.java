package com.xi.auth;

import cn.hutool.crypto.digest.BCrypt;
import com.xi.orm.entity.AppUserDO;
import com.xi.orm.mapper.AppUserMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Arrays;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 注册用户体系：邀请码注册制（邀请码由环境变量配置，不落库）。
 * 安全口径见 docs/SECURITY.md：邀请码错误计入 IP 失败次数（与登录共用锁定）；
 * 密码只存 BCrypt 哈希；用户名白名单 + 唯一索引兜底（ci 排序规则下大小写不敏感）。
 */
@Component
public class UserService {

    private static final Logger log = LoggerFactory.getLogger(UserService.class);
    private static final Pattern USERNAME_PATTERN = Pattern.compile("^[a-zA-Z0-9_]{3,20}$");
    private static final int PASSWORD_MIN = 8;
    private static final int PASSWORD_MAX = 64;

    @Autowired
    private AppUserMapper appUserMapper;

    /** 配置的邀请码集合；空 = 注册关闭 */
    private final Set<String> inviteCodes;

    public UserService(@Value("${trade-signal.auth.invite-codes:}") String inviteCodes) {
        this.inviteCodes = inviteCodes == null || inviteCodes.isBlank()
                ? Set.of()
                : Arrays.stream(inviteCodes.split(",")).map(String::trim)
                        .filter(s -> !s.isEmpty()).collect(Collectors.toSet());
    }

    public boolean registerEnabled() {
        return !inviteCodes.isEmpty();
    }

    public boolean isValidInviteCode(String inviteCode) {
        return inviteCode != null && inviteCodes.contains(inviteCode.trim());
    }

    /** 参数静态校验，返回错误文案；合法返回 null */
    public String validateParam(String username, String password) {
        if (username == null || !USERNAME_PATTERN.matcher(username).matches()) {
            return "用户名需为 3-20 位字母/数字/下划线";
        }
        if (password == null || password.length() < PASSWORD_MIN || password.length() > PASSWORD_MAX) {
            return "密码需为 " + PASSWORD_MIN + "-" + PASSWORD_MAX + " 位";
        }
        return null;
    }

    /**
     * 注册。邀请码/参数已在 Controller 层校验并计入限流，这里只做唯一性 + 入库。
     *
     * @return true=成功；false=用户名已存在
     */
    public boolean register(String ip, String username, String password, String inviteCode) {
        AppUserDO user = new AppUserDO();
        user.setUsername(username);
        user.setPassword(BCrypt.hashpw(password));
        user.setStatus("1");
        user.setInviteCode(inviteCode.trim());
        String now = String.valueOf(Instant.now().getEpochSecond());
        user.setCreatedAt(now);
        user.setUpdatedAt(now);
        try {
            appUserMapper.insert(user);
        } catch (DuplicateKeyException e) {
            log.warn("AUTH register duplicate username={} ip={}", username, ip);
            return false;
        }
        log.info("AUTH register success username={} ip={}", username, ip);
        return true;
    }

    /**
     * 用户登录校验：用户存在 + 未禁用 + BCrypt 比对通过。
     */
    public boolean tryUserLogin(String ip, String username, String password) {
        if (username == null || password == null) {
            return false;
        }
        AppUserDO user = appUserMapper.findByUsername(username);
        if (user == null) {
            return false;
        }
        if (!"1".equals(user.getStatus())) {
            log.warn("AUTH login disabled user={} ip={}", username, ip);
            return false;
        }
        return BCrypt.checkpw(password, user.getPassword());
    }
}
