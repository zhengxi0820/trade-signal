package com.xi.auth;

import cn.hutool.crypto.digest.BCrypt;
import com.xi.orm.entity.AppUserDO;
import com.xi.orm.mapper.AppUserMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 用户体系测试：注册（邀请码/唯一性/BCrypt）、登录（含禁用）、限流、token subject 与吊销。
 * 数据源为 H2（app_user 表见 src/test/resources/schema.sql）。
 */
@SpringBootTest
class UserServiceTest {

    @Autowired
    private AppUserMapper appUserMapper;
    @Autowired
    private JdbcTemplate jdbc;

    private UserService userService;

    @BeforeEach
    void setup() {
        jdbc.update("delete from app_user");
        userService = new UserService("code-a, code-b", 0); // TTL=0：吊销检查不缓存，测试即时生效
        ReflectionTestUtils.setField(userService, "appUserMapper", appUserMapper);
    }

    @Test
    void registerAndLogin() {
        assertTrue(userService.register("127.0.0.1", "alice", "secret123", "code-a"));
        AppUserDO user = appUserMapper.findByUsername("alice");
        assertNotNull(user);
        assertEquals("1", user.getStatus());
        assertEquals("code-a", user.getInviteCode());
        // 不落明文：库里是 BCrypt 哈希且可校验
        assertTrue(user.getPassword().startsWith("$2"));
        assertTrue(BCrypt.checkpw("secret123", user.getPassword()));

        assertTrue(userService.tryUserLogin("127.0.0.1", "alice", "secret123"));
        assertFalse(userService.tryUserLogin("127.0.0.1", "alice", "wrong-pass"));
        // 用户不存在：不抛异常（时延已用 dummy BCrypt 拉平）
        assertFalse(assertDoesNotThrow(() -> userService.tryUserLogin("127.0.0.1", "nobody", "secret123")));
    }

    @Test
    void duplicateUsernameRejected() {
        assertTrue(userService.register("127.0.0.1", "bob", "secret123", "code-a"));
        assertFalse(userService.register("127.0.0.1", "bob", "other12345", "code-b"));
    }

    @Test
    void disabledUserCannotLogin() {
        userService.register("127.0.0.1", "carol", "secret123", "code-a");
        jdbc.update("update app_user set STATUS='0' where USERNAME='carol'");
        assertFalse(userService.tryUserLogin("127.0.0.1", "carol", "secret123"));
    }

    @Test
    void malformedHashFailsLoginNot500() {
        userService.register("127.0.0.1", "dan", "secret123", "code-a");
        jdbc.update("update app_user set PASSWORD='not-a-bcrypt-hash' where USERNAME='dan'");
        assertFalse(assertDoesNotThrow(() -> userService.tryUserLogin("127.0.0.1", "dan", "secret123")));
    }

    @Test
    void inviteCodeAndParamValidation() {
        assertTrue(userService.isValidInviteCode("code-a"));
        assertTrue(userService.isValidInviteCode(" code-b "));
        assertFalse(userService.isValidInviteCode("nope"));
        assertFalse(new UserService("", 0).registerEnabled());
        assertTrue(userService.registerEnabled());

        assertNotNull(userService.validateParam("ab", "secret123"));          // 用户名太短
        assertNotNull(userService.validateParam("有中文", "secret123"));        // 非法字符
        assertNotNull(userService.validateParam("alice", "short"));           // 密码太短
        // BCrypt 72 字节截断线：30 个中文 = 90 字节（字符数 30 在 8-64 内），须拒绝
        assertNotNull(userService.validateParam("alice", "密".repeat(30)));
        assertNull(userService.validateParam("alice_01", "secret123"));
        assertNull(userService.validateParam("alice_01", "密".repeat(20)));    // 60 字节，合法
    }

    @Test
    void registerRateLimit() {
        AuthService auth = new AuthService("test-key", false, 168);
        for (int i = 0; i < 4; i++) {
            auth.noteAuthFail("1.2.3.4", "inviteCode");
        }
        assertFalse(auth.isLocked("1.2.3.4"));
        auth.noteAuthFail("1.2.3.4", "inviteCode");
        assertTrue(auth.isLocked("1.2.3.4"), "5 次失败应锁定");

        AuthService auth2 = new AuthService("test-key", false, 168);
        for (int i = 0; i < 5; i++) {
            assertTrue(auth2.canRegister("5.6.7.8"));
            auth2.noteRegisterSuccess("5.6.7.8");
        }
        assertFalse(auth2.canRegister("5.6.7.8"), "当日成功注册达上限");
    }

    /** 限流 Map 容量上限（S-03）：海量伪造 IP（未锁定条目）触发溢出时先清非锁定条目——
     *  内存有界（size ≤ 上限）且既有锁定不被误删；全锁定饱和的整表清空是并发场景下的兜底阀门。 */
    @Test
    void failMapCapEvictsUnderFlood() {
        AuthService auth = new AuthService("test-key", false, 168);
        for (int i = 0; i < 5; i++) {
            auth.noteAuthFail("1.2.3.4", "inviteCode");
        }
        assertTrue(auth.isLocked("1.2.3.4"));

        // 扫描器式洪泛：每个 IP 只失败 1 次（条目未锁定），总量远超上限
        for (int i = 0; i < AuthService.MAX_TRACKED_IPS + 500; i++) {
            String ip = "10." + (i / 65536 % 256) + "." + (i / 256 % 256) + "." + (i % 256);
            auth.noteAuthFail(ip, "login");
        }
        @SuppressWarnings("unchecked")
        java.util.Map<String, long[]> failMap =
                (java.util.Map<String, long[]>) org.springframework.test.util.ReflectionTestUtils.getField(auth, "failMap");
        assertTrue(failMap.size() <= AuthService.MAX_TRACKED_IPS, "溢出清理后 Map 必须有界: " + failMap.size());
        assertTrue(auth.isLocked("1.2.3.4"), "清理只丢非锁定条目，既有锁定保留");
    }

    @Test
    void tokenSubjectRoundTrip() {
        AuthService auth = new AuthService("test-key", false, 168);
        String token = auth.issueToken("alice");
        assertEquals("alice", auth.subjectOf(token));
        assertTrue(auth.isValidToken(token));
        // 篡改 subject / 签名均拒绝
        assertNull(auth.subjectOf(token.replace("alice", "admin")));
        assertNull(auth.subjectOf(token.substring(0, token.length() - 2) + "00"));
        assertNull(auth.subjectOf("garbage"));
        assertNull(auth.subjectOf((String) null));
        // 密钥登录的 subject 固定 key
        assertEquals("key", auth.subjectOf(auth.issueToken("key")));
    }

    /** token 新四段格式（含签发时间）：issuedAt 可解析、旧三段格式整体失效、过期即失效。 */
    @Test
    void tokenIssuedAtAndExpiry() {
        AuthService auth = new AuthService("test-key", false, 168);
        long before = System.currentTimeMillis() / 1000;
        AuthService.ParsedToken parsed = auth.parseToken(auth.issueToken("alice"));
        long after = System.currentTimeMillis() / 1000;
        assertNotNull(parsed);
        assertEquals("alice", parsed.subject());
        assertTrue(parsed.issuedAt() >= before - 1 && parsed.issuedAt() <= after + 1, "issuedAt 应为当前时间");
        assertTrue(parsed.expiry() > parsed.issuedAt());

        // 旧三段格式（subject.expiry.sig，无 issuedAt）拒绝
        assertNull(auth.parseToken("alice.9999999999.deadbeef"));
        // 会话时长为负 → 签发即过期
        assertNull(auth.parseToken(new AuthService("test-key", false, -1).issueToken("alice")));
    }

    /** 反代后真实客户端 IP 取 XFF 最后一个值：首值可伪造必须忽略（S-02）。 */
    @Test
    void clientIpTakesLastXffValue() {
        AuthService auth = new AuthService("test-key", false, 168);
        MockHttpServletRequest spoofed = new MockHttpServletRequest();
        spoofed.addHeader("X-Forwarded-For", "1.2.3.4, 5.6.7.8");   // 攻击者伪造首值，Caddy 追加真实 IP
        assertEquals("5.6.7.8", auth.clientIp(spoofed));
        MockHttpServletRequest single = new MockHttpServletRequest();
        single.addHeader("X-Forwarded-For", "9.9.9.9");
        assertEquals("9.9.9.9", auth.clientIp(single));
        MockHttpServletRequest direct = new MockHttpServletRequest();
        direct.setRemoteAddr("127.0.0.1");
        assertEquals("127.0.0.1", auth.clientIp(direct));
    }

    /** 用户 token 吊销：禁用（STATUS）或 UPDATED_AT 晚于签发时间即失效（S-07）。 */
    @Test
    void tokenRevocationByStatusOrUpdatedAt() {
        userService.register("127.0.0.1", "eve", "secret123", "code-a");
        long issuedAt = System.currentTimeMillis() / 1000;
        assertTrue(userService.isTokenActive("eve", issuedAt));
        assertFalse(userService.isTokenActive("ghost", issuedAt), "不存在的用户 token 无效");

        // 禁用即失效
        jdbc.update("update app_user set STATUS='0' where USERNAME='eve'");
        assertFalse(userService.isTokenActive("eve", issuedAt));
        jdbc.update("update app_user set STATUS='1' where USERNAME='eve'");
        assertTrue(userService.isTokenActive("eve", issuedAt));

        // 改密/水位 bump（UPDATED_AT 晚于签发时间）即失效
        jdbc.update("update app_user set UPDATED_AT='" + (issuedAt + 10) + "' where USERNAME='eve'");
        assertFalse(userService.isTokenActive("eve", issuedAt));
        assertTrue(userService.isTokenActive("eve", issuedAt + 10), "bump 之后签发的新 token 有效");
    }
}
