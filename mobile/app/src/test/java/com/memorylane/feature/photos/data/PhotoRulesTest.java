package com.memorylane.feature.photos.data;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.charset.StandardCharsets;

public class PhotoRulesTest {
    private static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE1};
    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'};
    // HEIC files start with a size and "ftypheic"; phones commonly save camera photos this way.
    private static final byte[] HEIC = {0, 0, 0, 0x18, 'f', 't', 'y', 'p', 'h', 'e', 'i', 'c'};

    @Test
    public void recognisesJpegAndPngByTheirFirstBytes() {
        assertEquals(PhotoRules.JPEG, PhotoRules.detect(JPEG, JPEG.length));
        assertEquals(PhotoRules.PNG, PhotoRules.detect(PNG, PNG.length));
    }

    @Test
    public void otherFormatsAndShortReadsAreUnknown() {
        assertNull(PhotoRules.detect(HEIC, HEIC.length));
        assertNull(PhotoRules.detect("not an image".getBytes(StandardCharsets.US_ASCII), 12));
        assertNull(PhotoRules.detect(JPEG, 2));
        assertNull(PhotoRules.detect(PNG, 7));
    }

    @Test
    public void onlyUnknownTypesAndFilesOverTheLimitAreConverted() {
        assertFalse(PhotoRules.needsConversion(PhotoRules.JPEG, 5_000_000));
        assertFalse(PhotoRules.needsConversion(PhotoRules.PNG, 1));
        assertTrue(PhotoRules.needsConversion(PhotoRules.JPEG, 5_000_001));
        assertTrue(PhotoRules.needsConversion(null, 100));
    }

    @Test
    public void sampleSizeKeepsTheLongerSideAtLeastTheTarget() {
        assertEquals(1, PhotoRules.sampleSize(2000, 1500, 2560));
        assertEquals(1, PhotoRules.sampleSize(4000, 3000, 2560));
        assertEquals(2, PhotoRules.sampleSize(6000, 4000, 2560));
        assertEquals(4, PhotoRules.sampleSize(8160, 6120, 2000));
        assertEquals(2, PhotoRules.sampleSize(3000, 8000, 2560));
    }

    @Test
    public void sampleSizeLimitsDecodedPixelsEvenWhenTheSidesLookSmallEnough() {
        // 5000 x 5000 passes the side check but would decode to 100 MB.
        assertEquals(2, PhotoRules.sampleSize(5000, 5000, 2560));
        // A file claiming absurd dimensions still decodes within the pixel budget.
        int sample = PhotoRules.sampleSize(65_000, 65_000, 2560);
        long pixels = (long) (65_000 / sample) * (65_000 / sample);
        assertTrue(pixels <= PhotoRules.MAX_DECODE_PIXELS);
    }
}
