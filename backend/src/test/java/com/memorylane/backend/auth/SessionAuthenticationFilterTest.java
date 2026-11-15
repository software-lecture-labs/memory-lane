package com.memorylane.backend.auth;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.transaction.CannotCreateTransactionException;

import java.util.List;
import java.util.Map;

/** When the session cannot be checked because the database is down, the filter answers 503. */
class SessionAuthenticationFilterTest {
    private final tools.jackson.databind.ObjectMapper mapper = new tools.jackson.databind.ObjectMapper();

    @Test
    void unavailableDatabaseGets503WithTheSameBodyAsAuthEndpoints() throws Exception {
        var expected = new AuthErrors().database();
        for (RuntimeException failure : List.of(
                new CannotCreateTransactionException("connection refused"),
                new DataAccessResourceFailureException("connection refused"),
                new TransientDataAccessResourceException("connection refused"))) {
            AuthService auth = mock(AuthService.class);
            when(auth.me(any())).thenThrow(failure);
            var filter = new SessionAuthenticationFilter(
                    new StaticListableBeanFactory(Map.of("auth", auth)).getBeanProvider(AuthService.class), mapper);
            var request = new MockHttpServletRequest("GET", "/api/v1/memories");
            request.setServletPath("/api/v1/memories");
            request.addHeader("Authorization", "Bearer token");
            var response = new MockHttpServletResponse();
            var chain = new MockFilterChain();

            filter.doFilter(request, response, chain);

            String name = failure.getClass().getSimpleName();
            assertEquals(503, response.getStatus(), name);
            assertEquals("no-store", response.getHeader("Cache-Control"), name);
            assertNull(response.getHeader("WWW-Authenticate"), name);
            assertEquals(expected.getBody(), mapper.readValue(response.getContentAsByteArray(), Map.class), name);
            assertNull(chain.getRequest(), name + " must not reach the controller");
        }
    }
}
