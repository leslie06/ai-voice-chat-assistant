package com.vca.store.account;

import com.vca.store.entity.AppUser;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 邮件找回/修改密码。重置令牌存内存(短时有效, 单实例足够; 重启丢失无妨, 反正会过期), 一次性消费。
 * 找回: 按用户名或邮箱定位账号, 发重置令牌(及链接)到其邮箱; 重置: 凭令牌设新密码。
 * {@code devEcho=true} 时把令牌一并回给前端, 供无真实邮件通道时联调。
 */
public class PasswordResetService {

    private static final SecureRandom RANDOM = new SecureRandom();

    private record Entry(long userId, long expiresAt) {
    }

    /** 发起结果: {@code error} 非空表示失败; 成功且 devEcho 时 {@code devToken} 为重置令牌。 */
    public record RequestResult(String error, String devToken) {
        static RequestResult ok(String devToken) {
            return new RequestResult(null, devToken);
        }

        static RequestResult fail(String error) {
            return new RequestResult(error, null);
        }
    }

    private final ConcurrentHashMap<String, Entry> tokens = new ConcurrentHashMap<>();
    private final UserService users;
    private final EmailSender email;
    private final String baseUrl;
    private final int ttlSeconds;
    private final boolean devEcho;

    public PasswordResetService(UserService users, EmailSender email, String baseUrl,
                                int ttlSeconds, boolean devEcho) {
        this.users = users;
        this.email = email;
        this.baseUrl = baseUrl == null ? "" : baseUrl.trim();
        this.ttlSeconds = ttlSeconds;
        this.devEcho = devEcho;
    }

    /** 按用户名或邮箱发起找回。 */
    public RequestResult request(String account) {
        AppUser u = users.findByUsernameOrEmail(account == null ? "" : account.trim());
        if (u == null) {
            return RequestResult.fail("该用户名/邮箱不存在");
        }
        return issue(u.getId(), u.getEmail());
    }

    /** 已登录用户发起修改密码(发到本人邮箱)。 */
    public RequestResult requestForUser(long userId) {
        AppUser u = users.findById(userId);
        if (u == null || u.getEmail() == null || u.getEmail().isBlank()) {
            return RequestResult.fail("账号无可用邮箱");
        }
        return issue(u.getId(), u.getEmail());
    }

    private RequestResult issue(long userId, String to) {
        byte[] buf = new byte[24];
        RANDOM.nextBytes(buf);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(buf);
        tokens.put(token, new Entry(userId, System.currentTimeMillis() + ttlSeconds * 1000L));

        String link = baseUrl.isBlank() ? null : baseUrl + "/?reset=" + token;
        StringBuilder body = new StringBuilder();
        body.append("您正在重置 Flying Fish 的登录密码。\n\n重置令牌：").append(token).append('\n');
        if (link != null) {
            body.append("或直接点击链接重置：").append(link).append('\n');
        }
        body.append('\n').append(ttlSeconds / 60).append(" 分钟内有效。若非本人操作，请忽略本邮件。");
        email.send(to, "Flying Fish 密码重置", body.toString());

        return RequestResult.ok(devEcho ? token : null);
    }

    /** 用令牌设新密码: 成功返回 null, 否则返回错误信息。令牌一次性。 */
    public String reset(String token, String newPassword) {
        if (newPassword == null || newPassword.length() < 6) {
            return "新密码至少 6 位";
        }
        Entry e = token == null ? null : tokens.get(token);
        if (e == null || System.currentTimeMillis() > e.expiresAt()) {
            return "重置链接无效或已过期";
        }
        tokens.remove(token);
        users.updatePassword(e.userId(), newPassword);
        return null;
    }
}
