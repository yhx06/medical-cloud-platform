package com.medcloud.platform.identity.infrastructure;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class DemoPasswordHashTest {
    private final UserAccountRepository users;

    @Autowired
    DemoPasswordHashTest(UserAccountRepository users) {
        this.users = users;
    }

    @Test
    void verifiesStoredDemoHash() {
        PasswordEncoder encoder = new BCryptPasswordEncoder(10);
        String hash = users.findByUsername("patient_demo").orElseThrow().getPasswordHash();

        assertThat(encoder.matches("Demo@123456", hash)).isTrue();
    }
}
