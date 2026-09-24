package com.vca.store.account;

import java.util.HashSet;
import java.util.Set;

/**
 * 谁是运营管理员。名单来自配置 {@code vca.store.admin-user-ids}(账号 id, 逗号分隔), 启动时定死。
 *
 * <p>为什么要有这一层: 注册是开放的, 而门店资料里有几项是<b>直通电话线路</b>的 —— 接入号决定哪家店的来电
 * 归谁, 转人工拨号串会原样交给 FreeSWITCH 执行。这些只能由装网关的运营来填, 不能让任何注册用户自助。
 */
public final class AdminPolicy {

    /** 没有管理员: 所有需要管理员的操作一律拒绝 */
    public static final AdminPolicy NONE = new AdminPolicy(Set.of(), id -> false);

    private final Set<Long> superAdmins;
    private final java.util.function.LongPredicate granted;

    private AdminPolicy(Set<Long> superAdmins, java.util.function.LongPredicate granted) {
        this.superAdmins = Set.copyOf(superAdmins);
        this.granted = granted;
    }

    /** 解析 "11, 12" 这样的名单(配置文件里的超级管理员); 认不出的项忽略 */
    public static AdminPolicy parse(String csv) {
        Set<Long> ids = new HashSet<>();
        if (csv != null) {
            for (String part : csv.split(",")) {
                String s = part.strip();
                if (s.isEmpty()) {
                    continue;
                }
                try {
                    ids.add(Long.parseLong(s));
                } catch (NumberFormatException ignored) {
                    // 配错一项不该让启动失败, 少一个管理员而已
                }
            }
        }
        return new AdminPolicy(ids, id -> false);
    }

    /**
     * 再加上在运营后台里授予的管理员(账号表的 role)。配置文件里的是<b>超级管理员</b>: 页面上撤不掉,
     * 保证总有人能进后台 —— 否则把自己也撤了, 就只能上服务器改配置。
     */
    public AdminPolicy withGranted(java.util.function.LongPredicate granted) {
        return new AdminPolicy(superAdmins, granted == null ? id -> false : granted);
    }

    public boolean isAdmin(Long userId) {
        return userId != null && (superAdmins.contains(userId) || granted.test(userId));
    }

    public boolean isSuperAdmin(Long userId) {
        return userId != null && superAdmins.contains(userId);
    }

    public Set<Long> superAdmins() {
        return superAdmins;
    }

    /** 配置文件里的超级管理员人数 */
    public int size() {
        return superAdmins.size();
    }
}
