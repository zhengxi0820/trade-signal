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
 * - 同一 IP 连续失败 5 次锁定 15 分钟；审计日志带真实客户端 IP（反代后取 X-Forwarded-For）。
 */
@Component
public class AuthService {

    public static final String COOKIE_NAME = "TS_AUTH";

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);
    private static final String HMAC_ALGO = "HmacSHA256";
    private static final int MAX_FAILS = 5;
    private static final long LOCK_MILLIS = 15 * 60 * 1000L;

    private final String accessKey;
    private final boolean secureCookie;
    private final long sessionSeconds;

    /** ip → 失败记录（count 连续失败次数，lockUntil 锁定截止 epochMillis） */
    private final Map<String, long[]> failMap = new ConcurrentHashMap<>();

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

    // ---- token 签发与校验 ----

    /** token = expiryEpochSeconds + "." + HmacSHA256(expiry, accessKey) */
    public String issueToken() {
        String expiry = String.valueOf(Instant.now().getEpochSecond() + sessionSeconds);
        return expiry + "." + hmacHex(expiry);
    }

    public boolean isValidToken(String token) {
        if (token == null) {
            return false;
        }
        int dot = token.indexOf('.');
        if (dot <= 0) {
            return false;
        }
        String expiry = token.substring(0, dot);
        try {
            if (Long.parseLong(expiry) < Instant.now().getEpochSecond()) {
                return false;
            }
        } catch (NumberFormatException e) {
            return false;
        }
        return MessageDigest.isEqual(hmacHex(expiry).getBytes(StandardCharsets.UTF_8),
                token.substring(dot + 1).getBytes(StandardCharsets.UTF_8));
    }

    /** 从请求里取认证 Cookie 并校验。 */
    public boolean hasValidCookie(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return false;
        }
        for (Cookie c : cookies) {
            if (COOKIE_NAME.equals(c.getName()) && isValidToken(c.getValue())) {
                return true;
            }
        }
        return false;
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

    /** 真实客户端 IP：反代后取 X-Forwarded-For 第一个值。 */
    public String clientIp(HttpServletRequest request) {
        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            return xff.split(",")[0].trim();
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
