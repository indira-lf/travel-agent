package com.gogo.travel.business.auth.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * 用户登录账号实体，对应 user_account 表。
 * userId 关联 user_profile.user_id，是 sa-token 登录后存储的 loginId。
 *
 * @author Hollis
 */
@TableName("user_account")
public class UserAccount {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 关联 user_profile.user_id，也是 sa-token 的 loginId */
    private String userId;

    /** 登录账号 */
    private String username;

    /** 登录密码（生产环境应使用 BCrypt 存储） */
    private String password;

    /** 用户真实姓名 */
    private String realName;

    /** 角色：USER 普通用户 / ADMIN 管理员 */
    private String role;

    private LocalDateTime createdTime;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }

    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }

    public String getPassword() { return password; }
    public void setPassword(String password) { this.password = password; }

    public String getRealName() { return realName; }
    public void setRealName(String realName) { this.realName = realName; }

    public String getRole() { return role; }
    public void setRole(String role) { this.role = role; }

    /** 是否为管理员（role = ADMIN，忽略大小写） */
    public boolean isAdmin() { return "ADMIN".equalsIgnoreCase(role); }

    public LocalDateTime getCreatedTime() { return createdTime; }
    public void setCreatedTime(LocalDateTime createdTime) { this.createdTime = createdTime; }
}
