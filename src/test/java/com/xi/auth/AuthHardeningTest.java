package com.xi.auth;

import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 认证与端点安全加固集成测试（MockMvc + H2）：
 * 注册默认关闭（S-01）、cache/refresh 仅密钥登录（S-05）、/auth/check 与 AuthFilter 的用户 token 吊销（S-07）。
 * user-token-cache-seconds=0：吊销检查不缓存，禁用即时生效可断言。
 */
@SpringBootTest(properties = {
        "trade-signal.auth.invite-codes=test-invite",
        "trade-signal.auth.user-token-cache-seconds=0"
})
@AutoConfigureMockMvc
class AuthHardeningTest {

    private static final String AUTH_COOKIE = "TS_AUTH";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private AuthService authService;
    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void setup() {
        jdbc.update("delete from app_user");
    }

    @Test
    void cacheRefreshRequiresKeySubject() throws Exception {
        // 未认证 401
        mockMvc.perform(post("/kdj/cache/refresh")).andExpect(status().isUnauthorized());

        // 密钥登录（subject="key"）可清
        mockMvc.perform(post("/kdj/cache/refresh").cookie(keyCookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cleared").value(0));

        // 注册用户（已认证但非 key）403
        String userToken = registerUserViaEndpoint("dave");
        mockMvc.perform(post("/kdj/cache/refresh").cookie(new Cookie(AUTH_COOKIE, userToken)))
                .andExpect(status().isForbidden());
    }

    @Test
    void authCheckEnforcesRevocation() throws Exception {
        String userToken = registerUserViaEndpoint("erin");
        mockMvc.perform(get("/auth/check").cookie(new Cookie(AUTH_COOKIE, userToken)))
                .andExpect(status().isNoContent());

        // 禁用后（bump 水位到未来 +100s，避开与签发同秒的粒度碰撞）token 立即失效
        jdbc.update("update app_user set STATUS='0', UPDATED_AT='" + (epoch() + 100) + "' where USERNAME='erin'");
        mockMvc.perform(get("/auth/check").cookie(new Cookie(AUTH_COOKIE, userToken)))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/kdj/gold-cross").cookie(new Cookie(AUTH_COOKIE, userToken)))
                .andExpect(status().isUnauthorized());

        // 重新启用（再 bump）后，老 token 因签发早于水位仍失效——须重新登录
        jdbc.update("update app_user set STATUS='1' where USERNAME='erin'");
        mockMvc.perform(get("/auth/check").cookie(new Cookie(AUTH_COOKIE, userToken)))
                .andExpect(status().isUnauthorized());
    }

    /** 走 /auth/register 全链路拿用户 Cookie（顺带覆盖：注册成功自动登录、负缓存不误伤）。 */
    private String registerUserViaEndpoint(String username) throws Exception {
        MvcResult result = mockMvc.perform(post("/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"secret123\",\"inviteCode\":\"test-invite\"}"))
                .andExpect(status().isNoContent())
                .andReturn();
        Cookie cookie = result.getResponse().getCookie(AUTH_COOKIE);
        assertTrue(cookie != null && !cookie.getValue().isBlank(), "注册成功应下发认证 Cookie");
        return cookie.getValue();
    }

    private Cookie keyCookie() {
        return new Cookie(AUTH_COOKIE, authService.issueToken("key"));
    }

    private static long epoch() {
        return System.currentTimeMillis() / 1000;
    }
}
