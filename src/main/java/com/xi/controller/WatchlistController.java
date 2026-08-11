package com.xi.controller;

import com.xi.auth.AuthService;
import com.xi.orm.mapper.WatchlistMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 自选股端点：挂在认证 token 的 subject（用户名）上，每人只见自己的。
 * 密钥登录（subject="key"）是脚本/管理入口，同样可用但不与其他用户共享。
 */
@RestController
@RequestMapping("/watchlist")
public class WatchlistController {

    private static final Pattern CODE_PATTERN = Pattern.compile("^[0-9A-Za-z]{1,12}$");

    private final WatchlistMapper watchlistMapper;
    private final AuthService authService;

    public WatchlistController(WatchlistMapper watchlistMapper, AuthService authService) {
        this.watchlistMapper = watchlistMapper;
        this.authService = authService;
    }

    /** 我的自选代码列表（创建时间升序） */
    @GetMapping
    public List<String> list(HttpServletRequest request) {
        return watchlistMapper.queryCodes(subject(request));
    }

    /** 加入自选（重复加入幂等 204） */
    @PostMapping
    public ResponseEntity<Void> add(@RequestBody CodeParam param, HttpServletRequest request) {
        String code = validCode(param);
        try {
            watchlistMapper.insert(subject(request), code, String.valueOf(Instant.now().getEpochSecond()));
        } catch (DuplicateKeyException e) {
            // 已在自选，幂等
        }
        return ResponseEntity.noContent().build();
    }

    /** 移出自选 */
    @PostMapping("/remove")
    public ResponseEntity<Void> remove(@RequestBody CodeParam param, HttpServletRequest request) {
        watchlistMapper.delete(subject(request), validCode(param));
        return ResponseEntity.noContent().build();
    }

    private String subject(HttpServletRequest request) {
        String subject = authService.subjectOf(request);
        if (subject == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        }
        return subject;
    }

    private String validCode(CodeParam param) {
        String code = param == null ? null : param.code();
        if (!StringUtils.hasText(code) || !CODE_PATTERN.matcher(code).matches()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "code 非法");
        }
        return code;
    }

    public record CodeParam(String code) {
    }
}
