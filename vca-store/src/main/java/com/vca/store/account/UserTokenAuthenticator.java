package com.vca.store.account;

import com.vca.orchestrator.auth.TokenAuthenticator;

/** 用 {@link UserService} 校验登录令牌的 {@link TokenAuthenticator} 实现; 通过返回 userId 字符串。 */
public class UserTokenAuthenticator implements TokenAuthenticator {

    private final UserService users;

    public UserTokenAuthenticator(UserService users) {
        this.users = users;
    }

    @Override
    public String authenticate(String token) {
        Long uid = users.userIdOf(token);
        return uid == null ? null : String.valueOf(uid);
    }
}
