package com.memorylane.feature.memories.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import com.memorylane.feature.memories.data.MemoryApi;

import org.junit.Test;

/** The location rule shared by the create and edit forms (docs/api-contract.md, "Anı alanları"). */
public class MemoryFormRulesTest {
    @Test
    public void noCoordinatesIsNoLocation() {
        assertEquals(MemoryCreateUiState.LocationError.NONE, MemoryFormRules.locationError("", ""));
        assertEquals(MemoryCreateUiState.LocationError.NONE, MemoryFormRules.locationError(" \t", "　"));
        assertNull(MemoryFormRules.location("", ""));
    }

    @Test
    public void oneCoordinateAloneIsIncomplete() {
        assertEquals(MemoryCreateUiState.LocationError.INCOMPLETE, MemoryFormRules.locationError("40", ""));
        assertEquals(MemoryCreateUiState.LocationError.INCOMPLETE, MemoryFormRules.locationError("  ", "29"));
    }

    @Test
    public void rangeLimitsAreInclusive() {
        assertEquals(MemoryCreateUiState.LocationError.NONE, MemoryFormRules.locationError("-90", "180"));
        assertEquals(MemoryCreateUiState.LocationError.NONE, MemoryFormRules.locationError("90,0", "-180.0"));
        assertEquals(MemoryCreateUiState.LocationError.INVALID, MemoryFormRules.locationError("90.0001", "0"));
        assertEquals(MemoryCreateUiState.LocationError.INVALID, MemoryFormRules.locationError("0", "-180.5"));
    }

    @Test
    public void onlyPlainDecimalsAreCoordinates() {
        for (String text : new String[] {"abc", "NaN", "Infinity", "0x10", "1e1", "4,0,1", "٤٠", "+", "."}) {
            assertNull(text, MemoryFormRules.coordinate(text, MemoryFormRules.LATITUDE_LIMIT));
        }
        assertEquals(40.5, MemoryFormRules.coordinate("+40,5", MemoryFormRules.LATITUDE_LIMIT), 0);
        assertEquals(-0.25, MemoryFormRules.coordinate("-.25", MemoryFormRules.LONGITUDE_LIMIT), 0);
    }

    @Test
    public void validCoordinatesBecomeTheLocationToSend() {
        assertEquals(new MemoryApi.Location(40.1885, -29.061), MemoryFormRules.location(" 40,1885 ", "-29.061"));
        assertNull("Invalid input is never sent", MemoryFormRules.location("400", "29"));
    }
}
