package com.vca.store;

import com.vca.store.account.UserService;
import com.vca.store.auth.TokenUtil;
import com.vca.store.entity.AppUser;
import com.vca.store.mapper.AppUserMapper;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.sql.Connection;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;

class UserLastLoginTest {

    @Test
    void successfulLoginUpdatesLastLoginTime() throws Exception {
        DriverManagerDataSource ds = new DriverManagerDataSource(
                "jdbc:h2:mem:last-login;MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
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
                      created_at TIMESTAMP NOT NULL
                    )
                    """);
        }
        SqlSessionFactory factory = MyBatisSupport.sqlSessionFactory(ds);
        AppUserMapper mapper = MyBatisSupport.mapper(factory, AppUserMapper.class);
        UserService users = new UserService(mapper, new TokenUtil("test-secret"));

        UserService.AuthResult registered =
                users.register("13812345678", "user@example.com", "secret1", "127.0.0.1");
        assertThat(registered.error()).isNull();
        assertThat(mapper.selectById(registered.userId()).getLastLoginAt()).isNull();

        UserService.AuthResult loggedIn = users.login("13812345678", "secret1");

        assertThat(loggedIn.error()).isNull();
        AppUser stored = mapper.selectById(registered.userId());
        assertThat(stored.getLastLoginAt()).isNotNull();
    }
}
