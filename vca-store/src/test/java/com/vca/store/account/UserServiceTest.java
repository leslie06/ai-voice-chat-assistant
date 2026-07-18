package com.vca.store.account;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class UserServiceTest {

    @Test
    void validatesMainlandChinaPhoneNumber() {
        assertThat(UserService.isValidPhone("13812345678")).isTrue();
        assertThat(UserService.isValidPhone("19912345678")).isTrue();
        assertThat(UserService.isValidPhone("12812345678")).isFalse();
        assertThat(UserService.isValidPhone("1381234567")).isFalse();
        assertThat(UserService.isValidPhone("1381234567a")).isFalse();
    }

    @Test
    void masksPhoneForDisplay() {
        assertThat(UserService.displayName("13812345678")).isEqualTo("用户5678");
    }
}
