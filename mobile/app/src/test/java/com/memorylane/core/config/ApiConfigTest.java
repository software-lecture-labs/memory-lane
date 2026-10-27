package com.memorylane.core.config;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import org.junit.Test;

public class ApiConfigTest {

    @Test
    public void addsMissingTrailingSlash() {
        assertEquals("http://10.0.2.2:8080/", ApiConfig.normalizeBaseUrl("http://10.0.2.2:8080"));
    }

    @Test
    public void keepsValidUrlAndTrimsSpaces() {
        assertEquals("http://192.168.1.20:8080/", ApiConfig.normalizeBaseUrl("  http://192.168.1.20:8080/ "));
    }

    @Test
    public void rejectsEmptyValue() {
        assertThrows(IllegalArgumentException.class, () -> ApiConfig.normalizeBaseUrl("  "));
    }

    @Test
    public void rejectsAddressWithoutScheme() {
        assertThrows(IllegalArgumentException.class, () -> ApiConfig.normalizeBaseUrl("192.168.1.20:8080/"));
    }
}
