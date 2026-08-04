package com.autoinvoice.api.security;

import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.PlatformTransactionManager;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class BootstrapAdminInitializerTest {

    @Test
    void disabledBootstrapDoesNotOpenDatabaseTransaction() throws Exception {
        JdbcClient jdbc = mock(JdbcClient.class);
        PasswordEncoder passwordEncoder = mock(PasswordEncoder.class);
        PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
        BootstrapAdminInitializer initializer = new BootstrapAdminInitializer(
                jdbc, passwordEncoder, transactionManager, false, "");

        initializer.run(new DefaultApplicationArguments(new String[0]));

        verifyNoInteractions(jdbc, passwordEncoder, transactionManager);
    }
}
