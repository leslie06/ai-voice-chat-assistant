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
    public static final AdminPolicy NONE = new AdminPolicy(Set.of());

    private final Set<Long> adminIds;

    private AdminPolicy(Set<Long> adminIds) {
        this.adminIds = Set.copyOf(adminIds);
    }

    /** 解析 "11, 12" 这样的名单; 认不出的项忽略 */
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
        return new AdminPolicy(ids);
    }

    public boolean isAdmin(Long userId) {
        return userId != null && adminIds.contains(userId);
    }

    public int size() {
        return adminIds.size();
    }
}
