package com.vca.store.account;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AdminPolicyTest {

    @Test
    void parsesCommaSeparatedIdsAndIgnoresJunk() {
        AdminPolicy p = AdminPolicy.parse(" 11, 12 ,x,,");
        assertThat(p.isAdmin(11L)).isTrue();
        assertThat(p.isAdmin(12L)).isTrue();
        assertThat(p.isAdmin(13L)).isFalse();
        assertThat(p.isAdmin(null)).isFalse();
        assertThat(p.size()).isEqualTo(2);
    }

    /** 没配管理员 = 谁都不是, 而不是谁都是 */
    @Test
    void emptyMeansNobody() {
        assertThat(AdminPolicy.parse("").isAdmin(1L)).isFalse();
        assertThat(AdminPolicy.parse(null).size()).isZero();
    }
}
