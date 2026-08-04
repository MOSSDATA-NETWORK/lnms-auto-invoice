package com.autoinvoice.invoice;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AssignmentValidatorTest {
    private static final Instant START = Instant.parse("2026-07-01T00:00:00Z");
    private static final Instant END = Instant.parse("2026-08-01T00:00:00Z");
    private final AssignmentValidator validator = new AssignmentValidator();

    @Test
    void rejectsDuplicateChargeAssignments() {
        UUID itemId = UUID.randomUUID();
        List<ProfileAssignment> assignments = List.of(
                assignment(itemId, AssignmentMode.CHARGE, null),
                assignment(itemId, AssignmentMode.CHARGE, null)
        );

        assertThatThrownBy(() -> validator.validate(assignments, START, END, null))
                .hasMessageContaining("multiple active invoice profiles");
    }

    @Test
    void rejectsIncompletePercentageAllocation() {
        UUID itemId = UUID.randomUUID();
        List<ProfileAssignment> assignments = List.of(
                assignment(itemId, AssignmentMode.ALLOCATE_PERCENT, new BigDecimal("60")),
                assignment(itemId, AssignmentMode.ALLOCATE_PERCENT, new BigDecimal("30"))
        );

        assertThatThrownBy(() -> validator.validate(assignments, START, END, null))
                .hasMessageContaining("exactly 100%");
    }

    private ProfileAssignment assignment(UUID itemId, AssignmentMode mode, BigDecimal value) {
        return new ProfileAssignment(UUID.randomUUID(), itemId, mode, value, START, null);
    }
}
