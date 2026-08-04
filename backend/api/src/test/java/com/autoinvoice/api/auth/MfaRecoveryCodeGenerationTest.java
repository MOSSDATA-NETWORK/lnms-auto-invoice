package com.autoinvoice.api.auth;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MfaRecoveryCodeGenerationTest {
    @Test
    void generatedCodesUseAnUnambiguousAlphabetThatSurvivesSeparatorRemoval() {
        MfaManagementController controller = new MfaManagementController(
                null, null, null, null, null, null, null, null, null, null);

        assertThat(controller.generateRecoveryCodes())
                .hasSize(10)
                .allMatch(code -> code.matches("[A-Z2-7]{4}-[A-Z2-7]{4}-[A-Z2-7]{4}"));
    }
}
