package com.travel.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.stp.StpUtil;
import com.travel.business.auth.service.AuthService;
import com.travel.controller.request.LoginRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 认证接口：登录、退出、获取当前用户信息。
 * <p>/api/auth/login 无需鉴权，其余接口需登录态。
 *
 * @author Hollis
 */
@RestController
@RequestMapping("/api/auth")
@CrossOrigin(origins = "*")
public class LoginController {

    @Autowired
    private AuthService authService;

    /**
     * 用户登录。
     * 成功后返回 Token，前端后续所有请求需在 Header 中携带：
     * {@code Authorization: <token>}
     */
    @PostMapping("/login")
    public Map<String, Object> login(@RequestBody LoginRequest request) {
        String token = authService.login(request.getUsername(), request.getPassword());
        return Map.of(
                "token",     token,
                "tokenName", StpUtil.getTokenName()
        );
    }

    /**
     * 退出登录，销毁当前 Token 对应的 sa-token 会话。
     */
    @PostMapping("/logout")
    @SaCheckLogin
    public Map<String, String> logout() {
        authService.logout();
        return Map.of("message", "退出成功");
    }

    /**
     * 获取当前已登录用户信息。
     */
    @GetMapping("/info")
    @SaCheckLogin
    public Map<String, Object> info() {
        String userId = StpUtil.getLoginIdAsString();
        return Map.of(
                "userId", userId,
                "admin",  authService.isAdmin(userId)
        );
    }
}
