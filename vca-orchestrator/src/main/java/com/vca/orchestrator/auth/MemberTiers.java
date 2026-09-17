package com.vca.orchestrator.auth;

import java.util.Locale;

/**
 * 会员等级查询端口。与 {@link TokenAuthenticator} 同为旁路 SPI, 由 {@code vca-store} 实现 ——
 * 接入层只需要知道"这个用户是不是会员", 不必依赖账号表, 更不必知道会员是怎么开通的
 * (今天是手工开, 以后接支付回调也只换实现)。
 *
 * <p>未注入实现(账号系统未启用)时, 调用方一律按 {@link Tier#FREE} 处理。
 */
@FunctionalInterface
public interface MemberTiers {

    /** 该用户当前等级。会员已过期的按 {@link Tier#FREE} 返回, 调用方不必自己判到期。 */
    Tier tierOf(long userId);

    /** 会员等级。以后加档(如 SVIP)在这里加一个枚举值, 各处的配额表跟着加一档即可。 */
    enum Tier {

        FREE,
        VIP;

        /** 库里存的是小写 code。认不出的值一律按 FREE —— 宁可少给权益, 不能白送。 */
        public static Tier of(String code) {
            if (code == null) {
                return FREE;
            }
            for (Tier t : values()) {
                if (t.code().equalsIgnoreCase(code.trim())) {
                    return t;
                }
            }
            return FREE;
        }

        public String code() {
            return name().toLowerCase(Locale.ROOT);
        }
    }
}
