package com.vca.store.auth;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * 无状态登录令牌(HMAC-SHA256 签名, JDK 自带)。令牌 = {@code base64url(userId) + "." + base64url(HMAC(userId))}。
 * 验证时重算 HMAC 比对, 服务端无需存会话表。密钥由配置 {@code vca.store.token-secret} 提供, 重启后令牌仍有效。
 *
 * <p><b>简化取舍</b>: 不带过期时间(满足"简单登录"需求)。需登出即失效时可改用 DB 会话表或加 exp + 短期密钥轮换。
 */
public final class TokenUtil {

    private final byte[] secret;

    public TokenUtil(String secret) {
        this.secret = (secret == null || secret.isBlank() ? "vca-default-secret" : secret)
                .getBytes(StandardCharsets.UTF_8);
    }

    /** 为 userId 签发令牌。 */
    public String issue(long userId) {
        String payload = b64(Long.toString(userId).getBytes(StandardCharsets.UTF_8));
        return payload + "." + b64(hmac(payload));
    }

    /** 校验令牌, 通过返回 userId, 否则返回 null。 */
    public Long verify(String token) {
        if (token == null) {
            return null;
        }
        int dot = token.indexOf('.');
        if (dot <= 0 || dot == token.length() - 1) {
            return null;
        }
        String payload = token.substring(0, dot);
        String sig = token.substring(dot + 1);
        if (!constantTimeEquals(sig, b64(hmac(payload)))) {
            return null;
        }
        try {
            return Long.parseLong(new String(Base64.getUrlDecoder().decode(payload), StandardCharsets.UTF_8));
        } catch (Exception e) {
            return null;
        }
    }

    private byte[] hmac(String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("令牌签名失败", e);
        }
    }

    private static String b64(byte[] b) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    private static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null || a.length() != b.length()) {
            return false;
        }
        int diff = 0;
        for (int i = 0; i < a.length(); i++) {
            diff |= a.charAt(i) ^ b.charAt(i);
        }
        return diff == 0;
    }
}
