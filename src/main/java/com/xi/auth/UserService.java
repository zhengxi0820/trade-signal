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

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 注册用户体系：邀请码注册制（邀请码由环境变量配置，不落库）。
 * 安全口径见 docs/SECURITY.md：邀请码错误计入 IP 失败次数（与登录共用锁定）；
 * 密码只存 BCrypt 哈希；用户名白名单 + 唯一索引兜底（ci 排序规则下大小写不敏感）。
 * 用户 token 的服务端吊销检查（isTokenActive）：禁用（STATUS≠1）或 UPDATED_AT 晚于
 * token 签发时间即失效——禁用/改密时同步 bump UPDATED_AT 即全量吊销（key token 不查库）。
 */
@Component
public class UserService {

    private static final Logger log = LoggerFactory.getLogger(UserService.class);
    private static final Pattern USERNAME_PATTERN = Pattern.compile("^[a-zA-Z0-9_]{3,20}$");
    private static final int PASSWORD_MIN = 8;
    private static final int PASSWORD_MAX = 64;
    /** BCrypt 只取输入前 72 字节，超长部分静默截断；直接按字节数拒绝 */
    private static final int PASSWORD_MAX_UTF8_BYTES = 72;
    /** 用户不存在时也跑一次同代价比较，抹平登录时延差（防用户名枚举，S-06） */
    private static final String DUMMY_BCRYPT_HASH = BCrypt.hashpw("ts-dummy-never-matches");

    @Autowired
    private AppUserMapper appUserMapper;

    /** 配置的邀请码集合；空 = 注册关闭 */
    private final Set<String> inviteCodes;

    /** token 吊销检查缓存：username → {缓存截止 millis, active 标记}；TTL 秒数 0 = 不缓存 */
    private final Map<String, long[]> tokenCheckCache = new ConcurrentHashMap<>();
    private final long tokenCacheMillis;

    public UserService(@Value("${trade-signal.auth.invite-codes:}") String inviteCodes,
                       @Value("${trade-signal.auth.user-token-cache-seconds:60}") long tokenCacheSeconds) {
        this.inviteCodes = inviteCodes == null || inviteCodes.isBlank()
                ? Set.of()
                : Arrays.stream(inviteCodes.split(",")).map(String::trim)
                        .filter(s -> !s.isEmpty()).collect(Collectors.toSet());
        this.tokenCacheMillis = Math.max(0, tokenCacheSeconds) * 1000L;
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
        if (password.getBytes(StandardCharsets.UTF_8).length > PASSWORD_MAX_UTF8_BYTES) {
            return "密码过长（UTF-8 编码不超过 " + PASSWORD_MAX_UTF8_BYTES + " 字节）";
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
        tokenCheckCache.remove(username); // 注册成功后清吊销缓存（此前"用户不存在"的负缓存会让自动登录 401）
        log.info("AUTH register success username={} ip={}", username, ip);
        return true;
    }

    /**
     * 用户登录校验：用户存在 + 未禁用 + BCrypt 比对通过。
     * 用户不存在时也做一次 dummy BCrypt 比较拉平时延（防用户名枚举）；库内哈希畸形按失败处理不抛 500。
     */
    public boolean tryUserLogin(String ip, String username, String password) {
        if (username == null || password == null) {
            return false;
        }
        AppUserDO user = appUserMapper.findByUsername(username);
        if (user == null) {
            BCrypt.checkpw(password, DUMMY_BCRYPT_HASH);
            return false;
        }
        if (!"1".equals(user.getStatus())) {
            log.warn("AUTH login disabled user={} ip={}", username, ip);
            return false;
        }
        try {
            return BCrypt.checkpw(password, user.getPassword());
        } catch (RuntimeException e) {
            // 库内哈希畸形（截断/非法盐值等）按登录失败处理，不抛 500
            log.warn("AUTH login malformed bcrypt hash user={} ip={}", username, ip);
            return false;
        }
    }

    /**
     * 用户 token 吊销检查（AuthFilter / auth/check 在签名校验通过后调用）：
     * 用户存在 + STATUS=1 + UPDATED_AT ≤ token 签发时间（epoch 秒）才有效。
     * 禁用或改密时 UPDATE 对应行并 bump UPDATED_AT 即全量吊销；结果带 TTL 内存缓存
     * （默认 60s，防扫描接口毫秒级缓存命中被每请求一次 DB 查询拖垮），0 = 不缓存。
     */
    public boolean isTokenActive(String username, long issuedAtSec) {
        if (username == null || username.isBlank()) {
            return false;
        }
        long now = System.currentTimeMillis();
        long[] cached = tokenCheckCache.get(username);
        if (cached != null && cached[0] > now) {
            return cached[1] == 1;
        }
        boolean active;
        try {
            AppUserDO user = appUserMapper.findByUsername(username);
            active = user != null && "1".equals(user.getStatus()) && updatedAtSec(user) <= issuedAtSec;
        } catch (Exception e) {
            log.warn("AUTH token check db error username={}", username, e);
            active = false;
        }
        tokenCheckCache.put(username, new long[]{now + tokenCacheMillis, active ? 1 : 0});
        return active;
    }

    private static long updatedAtSec(AppUserDO user) {
        try {
            return user.getUpdatedAt() == null ? 0 : Long.parseLong(user.getUpdatedAt());
        } catch (NumberFormatException e) {
            return 0; // 非法水位视为极早，不误伤
        }
    }
}
