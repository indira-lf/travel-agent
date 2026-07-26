package com.gogo.travel.business.auth.service;

import cn.dev33.satoken.stp.StpUtil;
import com.gogo.travel.business.auth.entity.UserAccount;
import com.gogo.travel.business.auth.mapper.UserAccountMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * 登录认证服务，使用 Sa-Token 管理会话。
 * loginId 存储为 userId（关联 user_profile.user_id），
 * 后续通过 StpUtil.getLoginIdAsString() 即可在任意层获取当前用户 ID。
 *
 * @author Hollis
 */
@Service
public class AuthService {

    private static final Logger logger = LoggerFactory.getLogger(AuthService.class);

    @Autowired
    private UserAccountMapper userAccountMapper;

    /**
     * 登录：校验账密，通过后执行 sa-token 登录并返回 Token。
     *
     * @param username 登录账号
     * @param password 登录密码
     * @return sa-token 生成的 Token 字符串
     */
    public String login(String username, String password) {
        UserAccount account = userAccountMapper.findByUsername(username);
        if (account == null || !account.getPassword().equals(password)) {
            throw new IllegalArgumentException("用户名或密码错误");
        }
        // 以 userId 作为 loginId 写入 sa-token 会话（存储于 Redis）
        StpUtil.login(account.getUserId());
        logger.info("[AUTH] 用户登录成功: username={}, userId={}", username, account.getUserId());
        return StpUtil.getTokenValue();
    }

    /**
     * 退出当前登录会话。
     */
    public void logout() {
        String userId = StpUtil.getLoginIdAsString();
        StpUtil.logout();
        logger.info("[AUTH] 用户退出登录: userId={}", userId);
    }

    /**
     * 判断指定用户是否为管理员（user_account.role = ADMIN）。
     *
     * @param userId 用户ID（sa-token loginId）
     * @return true 表示管理员
     */
    public boolean isAdmin(String userId) {
        if (userId == null || userId.isBlank()) {
            return false;
        }
        UserAccount account = userAccountMapper.findByUserId(userId);
        return account != null && account.isAdmin();
    }

    /**
     * 获取指定用户的真实姓名，无记录或未填写时返回 null。
     *
     * @param userId 用户ID（sa-token loginId）
     * @return 真实姓名，可能为 null
     */
    public String getRealName(String userId) {
        if (userId == null || userId.isBlank()) {
            return null;
        }
        UserAccount account = userAccountMapper.findByUserId(userId);
        return account != null ? account.getRealName() : null;
    }
}
