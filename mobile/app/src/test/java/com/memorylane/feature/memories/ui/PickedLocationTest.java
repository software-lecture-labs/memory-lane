package com.memorylane.feature.memories.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import com.memorylane.feature.memories.data.MemoryApi;

import org.junit.Test;

/** ML-15 "Koordinat sınırlarını doğrula": what a tap on the map turns into. */
public class PickedLocationTest {
    @Test
    public void pointInRangeIsKeptAndRoundedToSixDecimals() {
        MemoryApi.Location location = PickedLocation.fromMap(41.00823456789, 28.97843219);

        assertNotNull(location);
        assertEquals(41.008235, location.latitude(), 0);
        assertEquals(28.978432, location.longitude(), 0);
    }

    @Test
    public void longitudeOfAMapPannedAroundTheGlobeIsWrappedIntoRange() {
        assertEquals(-170.0, PickedLocation.fromMap(10, 190).longitude(), 1e-9);
        assertEquals(170.0, PickedLocation.fromMap(10, -190).longitude(), 1e-9);
        assertEquals(28.9784, PickedLocation.fromMap(10, 28.9784 + 720).longitude(), 1e-9);
        assertEquals(-180.0, PickedLocation.fromMap(10, 180).longitude(), 1e-9);
    }

    @Test
    public void latitudeOutOfRangeOrNotANumberIsRefused() {
        assertNull(PickedLocation.fromMap(90.0001, 0));
        assertNull(PickedLocation.fromMap(-91, 0));
        assertNull(PickedLocation.fromMap(Double.NaN, 0));
        assertNull(PickedLocation.fromMap(0, Double.POSITIVE_INFINITY));
        assertNotNull(PickedLocation.fromMap(90, 0));
        assertNotNull(PickedLocation.fromMap(-90, 0));
    }

    @Test
    public void textIsReadBackUnchangedByTheFormRules() {
        for (double value : new double[] {41.0082, -73.985428, 0, 90, -180, 28.5}) {
            String text = PickedLocation.text(value);
            Double parsed = MemoryFormRules.coordinate(text, MemoryFormRules.LONGITUDE_LIMIT);
            assertNotNull(text, parsed);
            assertEquals(text, value, parsed, 0);
        }
        assertEquals("41.0082", PickedLocation.text(41.0082));
        assertEquals("0", PickedLocation.text(0));
    }

    @Test
    public void resultCarriesTheChosenPointOrItsRemoval() {
        MemoryApi.Location chosen = new MemoryApi.Location(41.0082, 28.9784);

        assertEquals(chosen, PickedLocation.read(PickedLocation.chosen(chosen)));
        assertNull(PickedLocation.read(PickedLocation.removed()));
        assertNull(PickedLocation.read(null));
        assertNull(PickedLocation.read(new double[] {95, 10}));
    }
}
