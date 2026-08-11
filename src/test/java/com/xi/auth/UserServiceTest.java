package com.xi.auth;

import cn.hutool.crypto.digest.BCrypt;
import com.xi.orm.entity.AppUserDO;
import com.xi.orm.mapper.AppUserMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 用户体系测试：注册（邀请码/唯一性/BCrypt）、登录（含禁用）、限流、token subject。
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
        userService = new UserService("code-a, code-b");
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
        assertFalse(userService.tryUserLogin("127.0.0.1", "nobody", "secret123"));
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
    void inviteCodeAndParamValidation() {
        assertTrue(userService.isValidInviteCode("code-a"));
        assertTrue(userService.isValidInviteCode(" code-b "));
        assertFalse(userService.isValidInviteCode("nope"));
        assertFalse(new UserService("").registerEnabled());
        assertTrue(userService.registerEnabled());

        assertNotNull(userService.validateParam("ab", "secret123"));          // 用户名太短
        assertNotNull(userService.validateParam("有中文", "secret123"));        // 非法字符
        assertNotNull(userService.validateParam("alice", "short"));           // 密码太短
        assertNull(userService.validateParam("alice_01", "secret123"));
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
}
