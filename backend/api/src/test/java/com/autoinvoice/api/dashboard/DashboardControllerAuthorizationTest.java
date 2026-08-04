package com.autoinvoice.api.dashboard;

import com.autoinvoice.api.security.AuthenticatedUser;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;

import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class DashboardControllerAuthorizationTest {
    private static final UUID USER_ID = UUID.fromString("01900000-0000-7000-8000-000000000001");
    private static final UUID TENANT_ID = UUID.fromString("01900000-0000-7000-8000-000000000002");

    @Test
    void userWithoutDashboardDataPermissionsDoesNotTriggerQueriesOrReceiveMetrics() throws Exception {
        JdbcClient jdbc = mock(JdbcClient.class);
        DashboardController controller = new DashboardController(jdbc);
        AuthenticatedUser principal = principal(Set.of("template.publish"));

        DashboardController.DashboardSummary summary = controller.summary(
                UsernamePasswordAuthenticationToken.authenticated(principal, "", principal.getAuthorities()));

        verifyNoInteractions(jdbc);
        assertThat(new ObjectMapper().writeValueAsString(summary)).isEqualTo("{}");
    }

    @Test
    void metricGroupsFollowTheSamePermissionsAsTheirSourceResources() {
        assertThat(DashboardController.accessFor(Set.of("customer.read")))
                .isEqualTo(new DashboardController.DashboardAccess(true, false, false, false, false));
        assertThat(DashboardController.accessFor(Set.of("preview.approve.finance")))
                .isEqualTo(new DashboardController.DashboardAccess(false, true, false, false, false));
        assertThat(DashboardController.accessFor(Set.of("invoice.send")))
                .isEqualTo(new DashboardController.DashboardAccess(false, false, true, false, false));
        assertThat(DashboardController.accessFor(Set.of("audit.read")))
                .isEqualTo(new DashboardController.DashboardAccess(false, false, true, true, true));
        assertThat(DashboardController.accessFor(Set.of("system.admin")))
                .isEqualTo(new DashboardController.DashboardAccess(false, false, false, true, true));
    }

    private AuthenticatedUser principal(Set<String> permissions) {
        return new AuthenticatedUser(USER_ID, TENANT_ID, "tenant", "user", "User", "", false,
                null, false, 1, permissions, true);
    }
}
