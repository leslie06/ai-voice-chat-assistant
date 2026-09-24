package com.vca.store.account;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.vca.store.auth.PasswordUtil;
import com.vca.store.auth.TokenUtil;
import com.vca.orchestrator.auth.MemberTiers;
import com.vca.store.entity.AppUser;
import com.vca.store.mapper.AppUserMapper;

import java.time.LocalDateTime;
import java.util.List;
import java.util.regex.Pattern;

/** 注册/登录/令牌校验。用户名与邮箱各自唯一, 密码 PBKDF2 加盐哈希; 登录签发无状态 HMAC 令牌。 */
public class UserService {

    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");
    /** 中国大陆手机号：11 位，1 开头，第二位为 3-9。 */
    private static final Pattern PHONE = Pattern.compile("^1[3-9]\\d{9}$");

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

    public AuthResult register(String username, String email, String password, String registerIp) {
        String u = username == null ? "" : username.trim();
        String mail = email == null ? "" : email.trim();
        if (!isValidPhone(u)) {
            return AuthResult.fail("请输入正确的11位手机号");
        }
        if (!EMAIL.matcher(mail).matches()) {
            return AuthResult.fail("邮箱格式不正确");
        }
        if (password == null || password.length() < 6) {
            return AuthResult.fail("密码至少 6 位");
        }
        if (findByName(u) != null) {
            return AuthResult.fail("该手机号已注册");
        }
        if (findByEmail(mail) != null) {
            return AuthResult.fail("邮箱已被注册");
        }
        String salt = PasswordUtil.newSalt();
        AppUser user = new AppUser();
        user.setUsername(u);
        user.setEmail(mail);
        user.setRegisterIp(registerIp);
        user.setPassSalt(salt);
        user.setPassHash(PasswordUtil.hash(password, salt));
        user.setCreatedAt(LocalDateTime.now());
        users.insert(user);   // 唯一索引兜底并发重名/重邮箱
        return AuthResult.ok(tokens.issue(user.getId()), displayName(u), user.getId());
    }

    public AuthResult login(String username, String password) {
        String u = username == null ? "" : username.trim();
        AppUser user = findByName(u);
        if (user == null || password == null
                || !PasswordUtil.verify(password, user.getPassSalt(), user.getPassHash())) {
            return AuthResult.fail("用户名或密码错误");
        }
        user.setLastLoginAt(LocalDateTime.now());
        users.updateById(user);
        return AuthResult.ok(tokens.issue(user.getId()), displayName(user.getUsername()), user.getId());
    }

    /** 校验令牌, 返回 userId; 无效返回 null。 */
    public Long userIdOf(String token) {
        return tokens.verify(token);
    }

    /** 取用户名(用于前端显示); 不存在返回 null。 */
    public String usernameOf(long userId) {
        AppUser u = users.selectById(userId);
        return u == null ? null : displayName(u.getUsername());
    }

    public AppUser findById(long userId) {
        return users.selectById(userId);
    }

    // ---- 会员 ----

    /** 该用户当前等级; 用户不存在或会员已过期都按 FREE。 */
    public MemberTiers.Tier tierOf(long userId) {
        return tierOf(users.selectById(userId));
    }

    /**
     * 到期即降级 —— 等级只认 {@code member_tier} 会漏掉过期这一半, 到期后权益还在。
     * 这里判一次, 调用方(配额等)就不必各自记得判。
     */
    public static MemberTiers.Tier tierOf(AppUser user) {
        if (user == null) {
            return MemberTiers.Tier.FREE;
        }
        MemberTiers.Tier tier = MemberTiers.Tier.of(user.getMemberTier());
        if (tier == MemberTiers.Tier.FREE) {
            return MemberTiers.Tier.FREE;
        }
        LocalDateTime expires = user.getMemberExpiresAt();
        return expires == null || expires.isAfter(LocalDateTime.now()) ? tier : MemberTiers.Tier.FREE;
    }

    /**
     * 开通/续期会员。{@code expiresAt} 传 null = 不过期(手工开通的长期会员)。
     *
     * <p>支付接通后由订单回调调用这里, 别的地方不用改 —— 权益都是照 {@link #tierOf} 发的。
     */
    public void grantVip(long userId, LocalDateTime expiresAt) {
        setTier(userId, MemberTiers.Tier.VIP, expiresAt);
    }

    /** 降回免费档(退款/违规处理)。 */
    public void revokeVip(long userId) {
        setTier(userId, MemberTiers.Tier.FREE, null);
    }

    /**
     * 用 UpdateWrapper 而不是 updateById: MyBatis-Plus 的 updateById 默认跳过值为 null 的字段,
     * 那样"改成长期会员(到期时间清空)"和 revokeVip 都会把旧的到期时间留在库里 —— 人已经降级了,
     * 或者明明是长期会员, 却按一个过期时间继续算。
     */
    private void setTier(long userId, MemberTiers.Tier tier, LocalDateTime expiresAt) {
        users.update(null, Wrappers.<AppUser>update()
                .eq("id", userId)
                .set("member_tier", tier.code())
                .set("member_expires_at", expiresAt));
    }

    // ---- 运营后台 ----

    /** 运营管理员角色(在后台里授予的; 配置文件里的超级管理员不在这里) */
    public static final String ROLE_ADMIN = "admin";
    public static final String ROLE_USER = "user";

    public boolean isAdminRole(long userId) {
        AppUser u = users.selectById(userId);
        return u != null && ROLE_ADMIN.equals(u.getRole());
    }

    public void setRole(long userId, String role) {
        users.update(null, Wrappers.<AppUser>update().eq("id", userId).set("role", role));
    }

    public List<AppUser> listByRole(String role) {
        return users.selectList(Wrappers.<AppUser>query().eq("role", role).orderByAsc("id"));
    }

    public List<AppUser> findByIds(java.util.Collection<Long> ids) {
        return ids.isEmpty() ? List.of() : users.selectBatchIds(ids);
    }

    /** 按手机号找账号; 找不到返回 null */
    public AppUser findByPhone(String phone) {
        return phone == null ? null : findByName(phone.trim());
    }

    /**
     * 运营在后台替商家开账号。和自助注册的区别: 邮箱可以不填(小诊所未必有, 忘了密码由运营在后台重置),
     * 不填时用一个 .invalid 保留域名的占位地址(永远收不到信, 只是为了满足邮箱唯一的约束)。
     *
     * @return 新账号; 失败抛 {@link IllegalArgumentException}(手机号不对、已注册、密码太短)
     */
    public AppUser createByAdmin(String phone, String email, String password) {
        String u = phone == null ? "" : phone.trim();
        if (!isValidPhone(u)) {
            throw new IllegalArgumentException("请输入正确的11位手机号");
        }
        if (findByName(u) != null) {
            throw new IllegalArgumentException("该手机号已注册");
        }
        String mail = email == null || email.isBlank() ? "m" + u + "@noemail.invalid" : email.trim();
        if (!EMAIL.matcher(mail).matches()) {
            throw new IllegalArgumentException("邮箱格式不正确");
        }
        if (findByEmail(mail) != null) {
            throw new IllegalArgumentException("邮箱已被其他账号使用");
        }
        if (password == null || password.length() < 8) {
            throw new IllegalArgumentException("初始密码至少 8 位");
        }
        String salt = PasswordUtil.newSalt();
        AppUser user = new AppUser();
        user.setUsername(u);
        user.setEmail(mail);
        user.setPassSalt(salt);
        user.setPassHash(PasswordUtil.hash(password, salt));
        user.setRole(ROLE_USER);
        user.setCreatedAt(LocalDateTime.now());
        users.insert(user);
        return user;
    }

    /** 已登录用户自己改密码: 必须提供旧密码 */
    public void changePassword(long userId, String oldPassword, String newPassword) {
        AppUser u = users.selectById(userId);
        if (u == null || oldPassword == null
                || !PasswordUtil.verify(oldPassword, u.getPassSalt(), u.getPassHash())) {
            throw new IllegalArgumentException("原密码不对");
        }
        if (newPassword == null || newPassword.length() < 8) {
            throw new IllegalArgumentException("新密码至少 8 位");
        }
        updatePassword(userId, newPassword);
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

    static boolean isValidPhone(String phone) {
        return phone != null && PHONE.matcher(phone).matches();
    }

    static String displayName(String phone) {
        if (isValidPhone(phone)) {
            return "用户" + phone.substring(phone.length() - 4);
        }
        // 兼容数据库中可能已存在的旧用户名，但绝不把疑似手机号的完整值回传给前端。
        return phone == null ? "" : phone;
    }
}
