package com.vca.store;

import com.vca.orchestrator.auth.MemberTiers;
import com.vca.store.account.UserMemberTiers;
import com.vca.store.account.UserService;
import com.vca.store.auth.TokenUtil;
import com.vca.store.mapper.AppUserMapper;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.sql.Connection;
import java.sql.Statement;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/** 会员等级: 开通、到期自动降级、撤销。配额(如声音复刻)全按 tierOf 的结果发。 */
class MemberTierTest {

    @Test
    void grantsExpiresAndRevokesVip() throws Exception {
        DriverManagerDataSource ds = new DriverManagerDataSource(
                "jdbc:h2:mem:member-tier;MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        ds.setDriverClassName("org.h2.Driver");
        try (Connection connection = ds.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE app_user (
                      id BIGINT AUTO_INCREMENT PRIMARY KEY,
                      username VARCHAR(64) NOT NULL UNIQUE,
                      email VARCHAR(128) NOT NULL UNIQUE,
                      register_ip VARCHAR(45),
                      pass_salt VARCHAR(64) NOT NULL,
                      pass_hash VARCHAR(128) NOT NULL,
                      last_login_at TIMESTAMP,
                      member_tier VARCHAR(16) NOT NULL DEFAULT 'free',
                      member_expires_at TIMESTAMP,
                      created_at TIMESTAMP NOT NULL
                    )
                    """);
        }
        SqlSessionFactory factory = MyBatisSupport.sqlSessionFactory(ds);
        AppUserMapper mapper = MyBatisSupport.mapper(factory, AppUserMapper.class);
        UserService users = new UserService(mapper, new TokenUtil("test-secret"));
        MemberTiers tiers = new UserMemberTiers(users);

        long uid = users.register("13812345678", "user@example.com", "secret1", "127.0.0.1").userId();
        assertThat(tiers.tierOf(uid)).isEqualTo(MemberTiers.Tier.FREE);

        users.grantVip(uid, LocalDateTime.now().plusDays(30));
        assertThat(tiers.tierOf(uid)).isEqualTo(MemberTiers.Tier.VIP);

        // 到期即降级: 权益不能靠"记得去改 member_tier"来收回
        users.grantVip(uid, LocalDateTime.now().minusMinutes(1));
        assertThat(tiers.tierOf(uid)).isEqualTo(MemberTiers.Tier.FREE);

        // 不填到期时间 = 长期会员(手工开通)
        users.grantVip(uid, null);
        assertThat(tiers.tierOf(uid)).isEqualTo(MemberTiers.Tier.VIP);

        users.revokeVip(uid);
        assertThat(tiers.tierOf(uid)).isEqualTo(MemberTiers.Tier.FREE);
        assertThat(tiers.tierOf(999999L)).isEqualTo(MemberTiers.Tier.FREE);
    }
}
