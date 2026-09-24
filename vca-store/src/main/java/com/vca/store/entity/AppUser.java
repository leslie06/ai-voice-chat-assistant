package com.vca.store.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/** 用户账号(对应表 {@code app_user})。密码以 PBKDF2 加盐哈希存储, 不存明文。 */
@TableName("app_user")
public class AppUser {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String username;
    private String email;
    private String registerIp;
    private String passSalt;
    private String passHash;
    private LocalDateTime lastLoginAt;
    /** 会员等级 code: free / vip。 */
    private String memberTier;
    /** 会员到期时间; NULL = 不过期(手工开通的长期会员)。 */
    private LocalDateTime memberExpiresAt;
    /** user / admin。admin = 在运营后台里被授予的运营管理员(配置文件里的管理员不在这里) */
    private String role;
    private LocalDateTime createdAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getRegisterIp() {
        return registerIp;
    }

    public void setRegisterIp(String registerIp) {
        this.registerIp = registerIp;
    }

    public String getPassSalt() {
        return passSalt;
    }

    public void setPassSalt(String passSalt) {
        this.passSalt = passSalt;
    }

    public String getPassHash() {
        return passHash;
    }

    public void setPassHash(String passHash) {
        this.passHash = passHash;
    }

    public LocalDateTime getLastLoginAt() {
        return lastLoginAt;
    }

    public void setLastLoginAt(LocalDateTime lastLoginAt) {
        this.lastLoginAt = lastLoginAt;
    }

    public String getMemberTier() {
        return memberTier;
    }

    public void setMemberTier(String memberTier) {
        this.memberTier = memberTier;
    }

    public LocalDateTime getMemberExpiresAt() {
        return memberExpiresAt;
    }

    public void setMemberExpiresAt(LocalDateTime memberExpiresAt) {
        this.memberExpiresAt = memberExpiresAt;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public String getRole() {
        return role;
    }

    public void setRole(String role) {
        this.role = role;
    }
}
