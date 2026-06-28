package com.vca.store.auth;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * 密码加盐哈希(PBKDF2WithHmacSHA256, JDK 自带, 无第三方依赖)。每个用户独立随机盐, 只存盐与哈希、不存明文。
 */
public final class PasswordUtil {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int ITERATIONS = 120_000;
    private static final int KEY_BITS = 256;

    private PasswordUtil() {
    }

    /** 生成随机盐(base64)。 */
    public static String newSalt() {
        byte[] salt = new byte[16];
        RANDOM.nextBytes(salt);
        return Base64.getEncoder().encodeToString(salt);
    }

    /** 用给定盐算密码哈希(base64)。 */
    public static String hash(String password, String saltB64) {
        try {
            byte[] salt = Base64.getDecoder().decode(saltB64);
            PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, ITERATIONS, KEY_BITS);
            SecretKeyFactory f = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
            byte[] hash = f.generateSecret(spec).getEncoded();
            return Base64.getEncoder().encodeToString(hash);
        } catch (Exception e) {
            throw new IllegalStateException("密码哈希失败", e);
        }
    }

    /** 校验密码: 用存储的盐重算哈希并定长比较。 */
    public static boolean verify(String password, String saltB64, String expectedHashB64) {
        String actual = hash(password, saltB64);
        return constantTimeEquals(actual, expectedHashB64);
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
