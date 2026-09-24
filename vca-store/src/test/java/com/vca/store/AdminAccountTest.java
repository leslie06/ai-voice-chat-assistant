package com.vca.store;

import com.vca.store.account.AdminPolicy;
import com.vca.store.account.UserService;
import com.vca.store.entity.AppUser;
import com.vca.store.auth.TokenUtil;
import com.vca.store.mapper.AppUserMapper;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 运营后台里的账号操作: 替商家开户、改密码、授予管理员。 */
class AdminAccountTest {

    private UserService users() throws Exception {
        DriverManagerDataSource ds = new DriverManagerDataSource(
                "jdbc:h2:mem:adminacct" + System.nanoTime() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        ds.setDriverClassName("org.h2.Driver");
        try (var c = ds.getConnection(); var st = c.createStatement()) {
            st.execute("""
                    CREATE TABLE app_user (
                      id BIGINT AUTO_INCREMENT PRIMARY KEY, username VARCHAR(64) NOT NULL UNIQUE,
                      email VARCHAR(128) NOT NULL UNIQUE, register_ip VARCHAR(45), pass_salt VARCHAR(64) NOT NULL,
                      pass_hash VARCHAR(128) NOT NULL, last_login_at TIMESTAMP,
                      member_tier VARCHAR(16) NOT NULL DEFAULT 'free', member_expires_at TIMESTAMP,
                      role VARCHAR(16) NOT NULL DEFAULT 'user', created_at TIMESTAMP NOT NULL)""");
        }
        var factory = MyBatisSupport.sqlSessionFactory(ds);
        return new UserService(MyBatisSupport.mapper(factory, AppUserMapper.class), new TokenUtil("test-secret"));
    }

    @Test
    void adminCreatesAccountWithoutEmailAndMerchantCanLogIn() throws Exception {
        UserService u = users();
        AppUser a = u.createByAdmin("13800138000", "", "initpass8");

        assertThat(a.getEmail()).as("没填邮箱用占位地址, 满足唯一约束").isEqualTo("m13800138000@noemail.invalid");
        assertThat(u.login("13800138000", "initpass8").error()).isNull();
        assertThatThrownBy(() -> u.createByAdmin("13800138000", "", "initpass8")).hasMessageContaining("已注册");
        assertThatThrownBy(() -> u.createByAdmin("13800138001", "", "short")).hasMessageContaining("8 位");
    }

    @Test
    void changePasswordNeedsTheOldOne() throws Exception {
        UserService u = users();
        AppUser a = u.createByAdmin("13800138000", "", "initpass8");

        assertThatThrownBy(() -> u.changePassword(a.getId(), "wrong-old", "newpass88")).hasMessageContaining("原密码");
        u.changePassword(a.getId(), "initpass8", "newpass88");
        assertThat(u.login("13800138000", "newpass88").error()).isNull();
        assertThat(u.login("13800138000", "initpass8").error()).isNotNull();
    }

    @Test
    void grantedAdminsCountAlongsideSuperAdmins() throws Exception {
        UserService u = users();
        AppUser a = u.createByAdmin("13800138000", "", "initpass8");
        AdminPolicy policy = AdminPolicy.parse("99").withGranted(u::isAdminRole);

        assertThat(policy.isAdmin(a.getId())).isFalse();
        u.setRole(a.getId(), UserService.ROLE_ADMIN);
        assertThat(policy.isAdmin(a.getId())).isTrue();
        assertThat(policy.isSuperAdmin(a.getId())).isFalse();
        assertThat(policy.isAdmin(99L)).as("配置文件里的超级管理员").isTrue();
        assertThat(policy.isSuperAdmin(99L)).isTrue();
    }
}
