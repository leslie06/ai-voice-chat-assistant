package com.vca.store.account;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.vca.store.auth.PasswordUtil;
import com.vca.store.auth.TokenUtil;
import com.vca.store.entity.AppUser;
import com.vca.store.mapper.AppUserMapper;

import java.time.LocalDateTime;
import java.util.regex.Pattern;

/** 注册/登录/令牌校验。用户名与邮箱各自唯一, 密码 PBKDF2 加盐哈希; 登录签发无状态 HMAC 令牌。 */
public class UserService {

    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");

    private final AppUserMapper users;
    private final TokenUtil tokens;

    public UserService(AppUserMapper users, TokenUtil tokens) {
        this.users = users;
        this.tokens = tokens;
    }

    /** 注册结果/登录结果: 失败时 {@code error} 非空, 成功时 {@code token}/{@code username} 有值。 */
    public record AuthResult(String token, String username, long userId, String error) {
        static AuthResult fail(String e) {
            return new AuthResult(null, null, 0, e);
        }

        static AuthResult ok(String token, String username, long userId) {
            return new AuthResult(token, username, userId, null);
        }
    }

    public AuthResult register(String username, String email, String password) {
        String u = username == null ? "" : username.trim();
        String mail = email == null ? "" : email.trim();
        if (u.length() < 2 || u.length() > 32) {
            return AuthResult.fail("用户名需 2-32 个字符");
        }
        if (!EMAIL.matcher(mail).matches()) {
            return AuthResult.fail("邮箱格式不正确");
        }
        if (password == null || password.length() < 6) {
            return AuthResult.fail("密码至少 6 位");
        }
        if (findByName(u) != null) {
            return AuthResult.fail("用户名已被占用");
        }
        if (findByEmail(mail) != null) {
            return AuthResult.fail("邮箱已被注册");
        }
        String salt = PasswordUtil.newSalt();
        AppUser user = new AppUser();
        user.setUsername(u);
        user.setEmail(mail);
        user.setPassSalt(salt);
        user.setPassHash(PasswordUtil.hash(password, salt));
        user.setCreatedAt(LocalDateTime.now());
        users.insert(user);   // 唯一索引兜底并发重名/重邮箱
        return AuthResult.ok(tokens.issue(user.getId()), u, user.getId());
    }

    public AuthResult login(String username, String password) {
        String u = username == null ? "" : username.trim();
        AppUser user = findByName(u);
        if (user == null || password == null
                || !PasswordUtil.verify(password, user.getPassSalt(), user.getPassHash())) {
            return AuthResult.fail("用户名或密码错误");
        }
        return AuthResult.ok(tokens.issue(user.getId()), user.getUsername(), user.getId());
    }

    /** 校验令牌, 返回 userId; 无效返回 null。 */
    public Long userIdOf(String token) {
        return tokens.verify(token);
    }

    /** 取用户名(用于前端显示); 不存在返回 null。 */
    public String usernameOf(long userId) {
        AppUser u = users.selectById(userId);
        return u == null ? null : u.getUsername();
    }

    public AppUser findById(long userId) {
        return users.selectById(userId);
    }

    /** 按用户名或邮箱定位账号(找回密码用); 找不到返回 null。 */
    public AppUser findByUsernameOrEmail(String account) {
        AppUser u = findByName(account);
        return u != null ? u : findByEmail(account);
    }

    /** 重置密码: 重新加盐哈希并落库。 */
    public void updatePassword(long userId, String newPassword) {
        AppUser u = users.selectById(userId);
        if (u == null) {
            return;
        }
        String salt = PasswordUtil.newSalt();
        u.setPassSalt(salt);
        u.setPassHash(PasswordUtil.hash(newPassword, salt));
        users.updateById(u);
    }

    private AppUser findByName(String username) {
        return users.selectOne(Wrappers.<AppUser>query().eq("username", username));
    }

    private AppUser findByEmail(String email) {
        return users.selectOne(Wrappers.<AppUser>query().eq("email", email));
    }
}
