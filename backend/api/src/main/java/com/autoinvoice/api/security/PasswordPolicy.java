package com.autoinvoice.api.security;

import com.autoinvoice.platform.DomainException;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.Map;

@Component
public class PasswordPolicy {
    static final int MIN_LENGTH = 12;
    static final int PASSPHRASE_LENGTH = 20;
    static final int MAX_LENGTH = 200;

    public void validate(String username, String password) {
        if (password == null || password.length() < MIN_LENGTH || password.length() > MAX_LENGTH) {
            throw violation();
        }
        String normalizedUsername = username == null ? "" : username.trim().toLowerCase(Locale.ROOT);
        if (normalizedUsername.length() >= 3
                && password.toLowerCase(Locale.ROOT).contains(normalizedUsername)) {
            throw violation();
        }
        int characterClasses = 0;
        if (password.chars().anyMatch(Character::isLowerCase)) {
            characterClasses++;
        }
        if (password.chars().anyMatch(Character::isUpperCase)) {
            characterClasses++;
        }
        if (password.chars().anyMatch(Character::isDigit)) {
            characterClasses++;
        }
        if (password.chars().anyMatch(value -> !Character.isLetterOrDigit(value))) {
            characterClasses++;
        }
        int requiredClasses = password.length() >= PASSPHRASE_LENGTH ? 2 : 3;
        if (characterClasses < requiredClasses) {
            throw violation();
        }
    }

    private DomainException violation() {
        return new DomainException("PASSWORD_POLICY_VIOLATION",
                "The new password does not meet the password policy", 422,
                Map.of("requirements", List.of(
                        "Use 12 to 200 characters",
                        "Use at least three character types, or two for a passphrase of 20 characters or more",
                        "Do not include the username"
                )));
    }
}
