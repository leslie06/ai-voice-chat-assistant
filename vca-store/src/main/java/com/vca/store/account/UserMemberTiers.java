package com.vca.store.account;

import com.vca.orchestrator.auth.MemberTiers;

/** 用 {@link UserService} 查会员等级的 {@link MemberTiers} 实现; 与 {@link UserTokenAuthenticator} 同一角色。 */
public class UserMemberTiers implements MemberTiers {

    private final UserService users;

    public UserMemberTiers(UserService users) {
        this.users = users;
    }

    @Override
    public Tier tierOf(long userId) {
        return users.tierOf(userId);
    }
}
