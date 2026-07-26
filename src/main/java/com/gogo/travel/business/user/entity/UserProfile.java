package com.gogo.travel.business.user.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.github.houbb.sensitive.annotation.strategy.SensitiveStrategyCardId;
import com.github.houbb.sensitive.annotation.strategy.SensitiveStrategyChineseName;
import com.github.houbb.sensitive.annotation.strategy.SensitiveStrategyEmail;
import com.github.houbb.sensitive.annotation.strategy.SensitiveStrategyPhone;

/**
 * @author Hollis
 */
@TableName("user_profile")
public class UserProfile {

    @TableId(value = "user_id", type = IdType.INPUT)
    private String userId;
    private String baseCity;
    /** 用户职级，如 P5 / P6 / P7 / P8，对应差旅政策中的前置条件 */
    private String level;
    /** 姓名拼音（大写，姓在前名在后，中间空格分隔），如 "ZHANG SAN"，用于酒店预订联系人 */
    private String namePinyin;
    /** 用户邮箱，用于酒店预订下单时的联系人信息 */
    @SensitiveStrategyEmail
    private String email;
    /** 中文姓名，如 "张三"，用于机票预订乘客信息 */
    @SensitiveStrategyChineseName
    private String chineseName;
    /** 证件类型（0-身份证 1-护照 2-其他 3-回乡证 4-军官证 5-警官证 6-港澳通行证 7-台胞证 8-台湾通行证 9-外国人永久居留身份证） */
    private Integer idType;
    /** 证件号码（身份证 / 护照等），用于机票预订 */
    @SensitiveStrategyCardId
    private String idNumber;
    /** 手机号，用于机票预订联系人 */
    @SensitiveStrategyPhone
    private String phone;
    /** 性别：M-男，F-女 */
    private String gender;

    public UserProfile() {
    }

    public UserProfile(String userId, String baseCity) {
        this.userId   = userId;
        this.baseCity = baseCity;
    }

    public UserProfile(String userId, String baseCity, String level) {
        this.userId   = userId;
        this.baseCity = baseCity;
        this.level    = level;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public String getBaseCity() {
        return baseCity;
    }

    public void setBaseCity(String baseCity) {
        this.baseCity = baseCity;
    }

    public String getLevel() {
        return level;
    }

    public void setLevel(String level) {
        this.level = level;
    }

    public String getNamePinyin() {
        return namePinyin;
    }

    public void setNamePinyin(String namePinyin) {
        this.namePinyin = namePinyin;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getChineseName() {
        return chineseName;
    }

    public void setChineseName(String chineseName) {
        this.chineseName = chineseName;
    }

    public Integer getIdType() {
        return idType;
    }

    public void setIdType(Integer idType) {
        this.idType = idType;
    }

    public String getIdNumber() {
        return idNumber;
    }

    public void setIdNumber(String idNumber) {
        this.idNumber = idNumber;
    }

    public String getPhone() {
        return phone;
    }

    public void setPhone(String phone) {
        this.phone = phone;
    }

    public String getGender() {
        return gender;
    }

    public void setGender(String gender) {
        this.gender = gender;
    }
}
