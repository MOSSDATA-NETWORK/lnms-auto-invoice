package com.autoinvoice.api.auth;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AuthenticationDtoValidationTest {
    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void rejectsOversizedCredentialsBeforePasswordHashing() {
        AuthController.SignInRequest request = new AuthController.SignInRequest(
                "t".repeat(65), "u".repeat(321), "p".repeat(201));

        assertThat(validator.validate(request)).hasSize(3);
    }

    @Test
    void acceptsOnlySixDigitTotpOrGeneratedRecoveryCodeShapes() {
        assertThat(validator.validate(new AuthController.MfaRequest("123456"))).isEmpty();
        assertThat(validator.validate(new AuthController.MfaRequest("ABCD-EFGH-JK23"))).isEmpty();
        assertThat(validator.validate(new AuthController.MfaRequest("12345678901234"))).isNotEmpty();
    }

    @Test
    void capsReauthenticationPasswordsReasonsAndEnrollmentProofs() {
        assertThat(validator.validate(new MfaManagementController.EnrollmentRequest(
                "p".repeat(201), "r".repeat(1001)))).hasSize(2);
        assertThat(validator.validate(new MfaManagementController.ConfirmEnrollmentRequest(
                "123456", "x".repeat(129), "reason"))).isNotEmpty();
        assertThat(validator.validate(new PasswordController.ChangePasswordRequest(
                "p".repeat(201), "Permanent!Pass456", "reason"))).isNotEmpty();
    }
}
