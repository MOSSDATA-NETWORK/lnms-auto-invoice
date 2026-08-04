package com.autoinvoice.api.storage;

import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class FileControllerAuthorizationTest {
    private static final UUID ACTOR = UUID.fromString("01900000-0000-7000-8000-000000000001");
    private static final UUID OTHER = UUID.fromString("01900000-0000-7000-8000-000000000002");

    @Test
    void onlyCreatorCanReadAnUnattachedUpload() {
        assertThat(FileController.isReadAllowed(Set.of("customer.write"), ACTOR, ACTOR, Set.of())).isTrue();
        assertThat(FileController.isReadAllowed(Set.of("customer.write"), OTHER, ACTOR, Set.of())).isFalse();
    }

    @Test
    void linkedFilesRequireThePermissionForTheirResourceType() {
        assertThat(FileController.isReadAllowed(Set.of("customer.read"), ACTOR, OTHER,
                Set.of(FileController.FileUse.CONTRACT))).isTrue();
        assertThat(FileController.isReadAllowed(Set.of("customer.read"), ACTOR, ACTOR,
                Set.of(FileController.FileUse.PAYMENT_ATTACHMENT))).isFalse();
        assertThat(FileController.isReadAllowed(Set.of("payment.record"), ACTOR, OTHER,
                Set.of(FileController.FileUse.PAYMENT_ATTACHMENT))).isTrue();
    }

    @Test
    void creatorDoesNotBypassAuthorizationAfterAFileIsLinked() {
        assertThat(FileController.isReadAllowed(Set.of("customer.write"), ACTOR, ACTOR,
                Set.of(FileController.FileUse.INVOICE_FILE))).isFalse();
    }

    @Test
    void systemAdministratorCanReadEveryResourceType() {
        assertThat(FileController.isReadAllowed(Set.of("system.admin"), ACTOR, OTHER,
                EnumSet.allOf(FileController.FileUse.class))).isTrue();
    }
}
