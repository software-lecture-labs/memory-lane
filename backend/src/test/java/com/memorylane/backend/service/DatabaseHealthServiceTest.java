package com.memorylane.backend.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(OutputCaptureExtension.class)
class DatabaseHealthServiceTest {
    private final DataSource dataSource = mock(DataSource.class);
    private final DatabaseHealthService service = new DatabaseHealthService(dataSource);

    @Test void warnsOnceWhileDatabaseStaysDownAndReportsRecovery(CapturedOutput output) throws Exception {
        Connection connection = mock(Connection.class);
        when(connection.isValid(anyInt())).thenReturn(true);
        when(dataSource.getConnection())
                .thenThrow(new SQLException("Connection refused", "08001"))
                .thenThrow(new SQLException("Connection refused", "08001"))
                .thenThrow(new SQLException("Connection refused", "08001"))
                .thenReturn(connection);

        assertFalse(service.isDatabaseUp());
        assertFalse(service.isDatabaseUp());
        assertFalse(service.isDatabaseUp());
        assertTrue(service.isDatabaseUp());

        assertEquals(1, count(output.getOut(), "Database health check failed"));
        assertEquals(1, count(output.getOut(), "Database connection restored"));
    }

    private static int count(String text, String fragment) {
        return text.split(fragment, -1).length - 1;
    }
}
