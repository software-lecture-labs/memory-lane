package com.memorylane.backend.auth;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(AuthController.class)
@Import(AuthMethodErrors.class)
class AuthHttpContractTest {
    @Autowired MockMvc mvc;
    @MockitoBean AuthService service;
    @MockitoBean AuthLimiter limiter;

    @Test
    void wrongMethodUsesContractErrorAndPreservesAllowHeader() throws Exception {
        mvc.perform(get("/api/v1/auth/login"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(header().string("Allow", "POST"))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.code").value("HTTP_ERROR"))
                .andExpect(jsonPath("$.message").isString());
    }

    @Test
    void wrongMediaTypeUsesContractError() throws Exception {
        mvc.perform(post("/api/v1/auth/login").contentType("text/plain").content("hello"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.code").value("HTTP_ERROR"));
    }

    @Test
    void controlCharactersAndEmbeddedWhitespaceAreInvalidEmail() {
        for (String character : new String[] {"\u0000", "\n", "\u007f", "\u00a0", " "}) {
            AuthFailure failure =
                    assertThrows(
                            AuthFailure.class,
                            () -> AuthService.email("user" + character + "@example.test"));
            assertEquals(400, failure.status);
            assertEquals("INVALID_INPUT", failure.code);
        }
        assertEquals("user@example.test", AuthService.email("  USER@example.test  "));
    }
}
