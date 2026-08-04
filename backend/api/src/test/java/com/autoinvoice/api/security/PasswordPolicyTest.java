package com.autoinvoice.api.security;

import com.autoinvoice.platform.DomainException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatCode;

class PasswordPolicyTest {
    private final PasswordPolicy policy = new PasswordPolicy();

    @Test
    void acceptsStrongPasswordsAndLongPassphrases() {
        assertThatCode(() -> policy.validate("alice", "Ferry-Cedar-482"))
                .doesNotThrowAnyException();
        assertThatCode(() -> policy.validate("alice", "correct horse battery staple"))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsShortLowVarietyOrUsernameDerivedPasswords() {
        assertViolation("alice", "Short1!");
        assertViolation("alice", "alllowercase12");
        assertViolation("alice", "Alice-Strong-482!");
    }

    private void assertViolation(String username, String password) {
        assertThatThrownBy(() -> policy.validate(username, password))
                .isInstanceOfSatisfying(DomainException.class,
                        exception -> org.assertj.core.api.Assertions.assertThat(exception.code())
                                .isEqualTo("PASSWORD_POLICY_VIOLATION"));
    }
}
