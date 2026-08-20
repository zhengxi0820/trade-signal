package com.xi.auth;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 简单密钥认证：登录换 HMAC 签名 Cookie（无服务端会话状态，重启不失效）。
 * 安全口径见 docs/SECURITY.md 2.1/2.2：
 * - 密钥只走环境变量 TRADE_SIGNAL_ACCESS_KEY，不设代码默认值；
 *   未配置时生成随机密钥并打印日志（本机开发模式），永远不存在"已知默认值"。
 * - 同一 IP 连续失败 5 次锁定 15 分钟；审计日志带真实客户端 IP。
 *   反代后取 X-Forwarded-For 的**最后一个**值——Caddy 等追加式反代把真实客户端
 *   IP 追加在末尾，首值可被客户端伪造（绕过限流 + 撑爆内存，S-02）。
 * - 限流/注册计数 Map 设容量上限：溢出时先清非锁定/非当日条目，仍满则整表清空
 *   （攻击下丢锁换内存有界，S-03）。
 */
@Component
public class AuthService {

    public static final String COOKIE_NAME = "TS_AUTH";

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);
    private static final String HMAC_ALGO = "HmacSHA256";
    private static final int MAX_FAILS = 5;
    private static final long LOCK_MILLIS = 15 * 60 * 1000L;
    /** 限流/注册计数的最大追踪 IP 数（防伪造/分布式请求把 Map 撑爆） */
    static final int MAX_TRACKED_IPS = 10_000;

    private final String accessKey;
    private final boolean secureCookie;
    private final long sessionSeconds;

    /** ip → 失败记录（count 连续失败次数，lockUntil 锁定截止 epochMillis） */
    private final Map<String, long[]> failMap = new ConcurrentHashMap<>();

    /** 注册限流：ip → [当日成功数, 当日零点 epochSeconds]（内存计数，跨零点清零） */
    private final Map<String, long[]> registerSuccessMap = new ConcurrentHashMap<>();
    /** 同 IP 每日成功注册上限 */
    private static final int REGISTER_DAILY_CAP = 5;

    public AuthService(@Value("${trade-signal.auth.access-key:}") String accessKey,
                       @Value("${trade-signal.auth.secure-cookie:true}") boolean secureCookie,
                       @Value("${trade-signal.auth.session-hours:168}") long sessionHours) {
        if (accessKey == null || accessKey.isBlank()) {
            this.accessKey = UUID.randomUUID().toString();
            log.warn("TRADE_SIGNAL_ACCESS_KEY 未配置，已生成本机开发用随机密钥（重启失效）: {}", this.accessKey);
        } else {
            this.accessKey = accessKey;
        }
        this.secureCookie = secureCookie;
        this.sessionSeconds = sessionHours * 3600L;
    }

    // ---- 登录与锁定 ----

    public boolean isLocked(String ip) {
        long[] rec = failMap.get(ip);
        if (rec == null) {
            return false;
        }
        if (rec[1] > System.currentTimeMillis()) {
            return true;
        }
        if (rec[1] > 0) {
            failMap.remove(ip); // 锁定期已满，清除
        }
        return false;
    }

    /** 密钥比对（常量时间）。成功清除失败记录，失败累计并到阈值上锁。 */
    public boolean tryLogin(String ip, String key) {
        boolean ok = key != null && MessageDigest.isEqual(
                accessKey.getBytes(StandardCharsets.UTF_8), key.getBytes(StandardCharsets.UTF_8));
        if (ok) {
            failMap.remove(ip);
            log.info("AUTH login success ip={}", ip);
            return true;
        }
        evictFailMapIfOverflow();
        long[] rec = failMap.computeIfAbsent(ip, k -> new long[2]);
        rec[0]++;
        if (rec[0] >= MAX_FAILS) {
            rec[0] = 0;
            rec[1] = System.currentTimeMillis() + LOCK_MILLIS;
            log.warn("AUTH login locked ip={} ({} 次连续失败，锁 15 分钟)", ip, MAX_FAILS);
        } else {
            log.warn("AUTH login fail ip={}", ip);
        }
        return false;
    }

    // ---- 注册限流 ----

    /** 距锁定还剩几次尝试（未锁定时供前端展示）。 */
    public int remainingAttempts(String ip) {
        long[] rec = failMap.get(ip);
        return rec == null ? MAX_FAILS : Math.max(0, MAX_FAILS - (int) rec[0]);
    }

    /** 认证相关失败计数（登录失败/注册失败），与登录共用 5 次锁 15 分钟口径。 */
    public void noteAuthFail(String ip, String reason) {
        evictFailMapIfOverflow();
        long[] rec = failMap.computeIfAbsent(ip, k -> new long[2]);
        rec[0]++;
        if (rec[0] >= MAX_FAILS) {
            rec[0] = 0;
            rec[1] = System.currentTimeMillis() + LOCK_MILLIS;
            log.warn("AUTH locked ip={} ({} 次连续失败，锁 15 分钟)", ip, MAX_FAILS);
        } else {
            log.warn("AUTH fail ip={} reason={}", ip, reason);
        }
    }

    /** 同 IP 每日成功注册上限（内存计数，跨零点清零）。 */
    public boolean canRegister(String ip) {
        long[] rec = registerSuccessMap.get(ip);
        long todayStart = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.DAYS).getEpochSecond();
        return rec == null || rec[1] != todayStart || rec[0] < REGISTER_DAILY_CAP;
    }

    public void noteRegisterSuccess(String ip) {
        long todayStart = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.DAYS).getEpochSecond();
        if (registerSuccessMap.size() >= MAX_TRACKED_IPS) {
            // 溢出先清非当日条目，仍满则整表清空（保内存有界）
            registerSuccessMap.entrySet().removeIf(e -> e.getValue()[1] != todayStart);
            if (registerSuccessMap.size() >= MAX_TRACKED_IPS) {
                registerSuccessMap.clear();
                log.warn("AUTH register counter overflow ({}), cleared", MAX_TRACKED_IPS);
            }
        }
        long[] rec = registerSuccessMap.computeIfAbsent(ip, k -> new long[]{0, todayStart});
        if (rec[1] != todayStart) {
            rec[0] = 0;
            rec[1] = todayStart;
        }
        rec[0]++;
    }

    /** 失败计数 Map 溢出处理：先清"未处于锁定期"的条目，仍满则整表清空（攻击下丢锁换内存有界）。 */
    private void evictFailMapIfOverflow() {
        if (failMap.size() < MAX_TRACKED_IPS) {
            return;
        }
        long now = System.currentTimeMillis();
        failMap.entrySet().removeIf(e -> e.getValue()[1] <= now);
        if (failMap.size() >= MAX_TRACKED_IPS) {
            failMap.clear();
            log.warn("AUTH fail counter overflow ({}), cleared", MAX_TRACKED_IPS);
        }
    }

    // ---- token 签发与校验 ----

    /** token = subject + "." + issuedAt + "." + expiry + "." + HmacSHA256(subject.issuedAt.expiry)。
     *  subject：密钥登录固定 "key"，用户登录为用户名（白名单字符集，无点号，分隔安全）。
     *  issuedAt 用于服务端吊销检查（禁用/改密后旧 token 失效，见 UserService.isTokenActive）。 */
    public String issueToken(String subject) {
        long now = Instant.now().getEpochSecond();
        String issuedAt = String.valueOf(now);
        String expiry = String.valueOf(now + sessionSeconds);
        return subject + "." + issuedAt + "." + expiry + "." + hmacHex(subject + "." + issuedAt + "." + expiry);
    }

    public boolean isValidToken(String token) {
        return parseToken(token) != null;
    }

    /** 校验签名/过期并解析；不合法/过期/签名不符/旧三段格式返回 null。 */
    public ParsedToken parseToken(String token) {
        if (token == null) {
            return null;
        }
        int first = token.indexOf('.');
        int last = token.lastIndexOf('.');
        if (first <= 0 || last <= first) {
            return null;
        }
        String subject = token.substring(0, first);
        String mid = token.substring(first + 1, last);   // issuedAt.expiry
        int dot = mid.indexOf('.');
        if (dot <= 0 || dot == mid.length() - 1) {
            return null;                                  // 旧三段格式（无 issuedAt）整体失效
        }
        long issuedAt;
        long expiry;
        try {
            issuedAt = Long.parseLong(mid.substring(0, dot));
            expiry = Long.parseLong(mid.substring(dot + 1));
        } catch (NumberFormatException e) {
            return null;
        }
        if (expiry < Instant.now().getEpochSecond()) {
            return null;
        }
        String expect = hmacHex(subject + "." + mid);
        if (!MessageDigest.isEqual(expect.getBytes(StandardCharsets.UTF_8),
                token.substring(last + 1).getBytes(StandardCharsets.UTF_8))) {
            return null;
        }
        return new ParsedToken(subject, issuedAt, expiry);
    }

    /** 已签名未过期 token 的解析结果（subject + 签发/过期时间，epoch 秒）。 */
    public record ParsedToken(String subject, long issuedAt, long expiry) {
    }

    /** 兼容入口：只取 subject（签名与过期校验同 parseToken）。 */
    public String subjectOf(String token) {
        ParsedToken parsed = parseToken(token);
        return parsed == null ? null : parsed.subject();
    }

    /** 从请求 Cookie 解析认证主体（用户名或 "key"）；未认证返回 null。不含服务端吊销检查。 */
    public String subjectOf(HttpServletRequest request) {
        ParsedToken parsed = parse(request);
        return parsed == null ? null : parsed.subject();
    }

    /** 从请求 Cookie 解析已签名未过期的 token；无/非法返回 null。不含服务端吊销检查。 */
    public ParsedToken parse(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        for (Cookie c : cookies) {
            if (COOKIE_NAME.equals(c.getName())) {
                return parseToken(c.getValue());
            }
        }
        return null;
    }

    /** 构造认证 Cookie（HttpOnly + SameSite=Strict；Secure 按配置，默认开）。 */
    public Cookie buildCookie(String token) {
        Cookie cookie = new Cookie(COOKIE_NAME, token);
        cookie.setHttpOnly(true);
        cookie.setSecure(secureCookie);
        cookie.setPath("/");
        cookie.setMaxAge((int) sessionSeconds);
        cookie.setAttribute("SameSite", "Strict");
        return cookie;
    }

    /** 构造立即失效的同名 Cookie（登出用）。 */
    public Cookie buildLogoutCookie() {
        Cookie cookie = new Cookie(COOKIE_NAME, "");
        cookie.setHttpOnly(true);
        cookie.setSecure(secureCookie);
        cookie.setPath("/");
        cookie.setMaxAge(0);
        cookie.setAttribute("SameSite", "Strict");
        return cookie;
    }

    /** 真实客户端 IP：反代后取 X-Forwarded-For 的**最后一个**值（Caddy 等追加式反代把真实
     *  客户端 IP 追加在末尾；首值可被客户端伪造，不能用于限流与审计）。 */
    public String clientIp(HttpServletRequest request) {
        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            String[] parts = xff.split(",");
            return parts[parts.length - 1].trim();
        }
        return request.getRemoteAddr();
    }

    private String hmacHex(String data) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGO);
            mac.init(new SecretKeySpec(accessKey.getBytes(StandardCharsets.UTF_8), HMAC_ALGO));
            byte[] out = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(out);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
