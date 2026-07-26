package com.travel.apikey.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/**
 * 用户第三方 API Key 实体（加密存储）。
 * 业务键为 (userId, provider)。
 *
 * @author Hollis
 */
@TableName("user_api_key")
public class UserApiKeyEntry {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private String userId;
    private String provider;

    /** AES 加密后的 API Key（Base64 编码） */
    private String apiKeyEnc;

    public UserApiKeyEntry() {
    }

    public UserApiKeyEntry(String userId, String provider, String apiKeyEnc) {
        this.userId = userId;
        this.provider = provider;
        this.apiKeyEnc = apiKeyEnc;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public String getProvider() {
        return provider;
    }

    public void setProvider(String provider) {
        this.provider = provider;
    }

    public String getApiKeyEnc() {
        return apiKeyEnc;
    }

    public void setApiKeyEnc(String apiKeyEnc) {
        this.apiKeyEnc = apiKeyEnc;
    }
}
