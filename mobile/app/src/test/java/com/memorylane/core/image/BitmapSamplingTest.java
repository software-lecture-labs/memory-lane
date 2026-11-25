package com.memorylane.core.image;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Decode step for showing photos. The decoding itself needs Android's BitmapFactory and is checked
 * on the emulator; the size rule and the input guards run on the JVM.
 */
public class BitmapSamplingTest {
    @Test
    public void smallerOrEqualImagesAreDecodedAtFullSize() {
        assertEquals(1, BitmapSampling.inSampleSize(500, 400, 1000, 1000));
        assertEquals(1, BitmapSampling.inSampleSize(1000, 1000, 1000, 1000));
        assertEquals(1, BitmapSampling.inSampleSize(1999, 1999, 1000, 1000));
    }

    @Test
    public void largerImagesAreHalvedWhileBothSidesStayAtLeastTheTarget() {
        assertEquals(2, BitmapSampling.inSampleSize(4000, 3000, 1000, 1000));
        assertEquals(4, BitmapSampling.inSampleSize(4000, 4000, 1000, 1000));
        assertEquals(8, BitmapSampling.inSampleSize(4000, 4000, 500, 500));
        // The shorter side decides: a panorama is not shrunk below the card's height.
        assertEquals(2, BitmapSampling.inSampleSize(8000, 1000, 360, 360));
    }

    @Test
    public void decodedImageNeverDropsBelowTheTarget() {
        int[][] cases = {{4032, 3024, 360, 240}, {3024, 4032, 1080, 1920}, {6000, 4000, 1440, 1440}};
        for (int[] c : cases) {
            int sample = BitmapSampling.inSampleSize(c[0], c[1], c[2], c[3]);
            assertTrue(Integer.bitCount(sample) == 1); // a power of two
            assertTrue(c[0] / sample >= c[2] && c[1] / sample >= c[3]);
        }
    }

    @Test
    public void hugeTargetsStillStayWithinTheDecodeBudget() {
        // A file claiming 20000 x 20000 is decoded small enough even for a huge target.
        int sample = BitmapSampling.inSampleSize(20_000, 20_000, 20_000, 20_000);
        assertTrue((long) (20_000 / sample) * (20_000 / sample) <= BitmapSampling.MAX_DECODE_PIXELS);
        assertEquals(8, sample); // 2500 x 2500 = 6.25 MP; 4 would still be 25 MP
    }

    @Test
    public void zeroOrNegativeSizesAreRejected() {
        int[][] invalid = {{0, 100, 10, 10}, {100, -1, 10, 10}, {100, 100, 0, 10}, {100, 100, 10, -5}};
        for (int[] c : invalid) {
            assertThrows(IllegalArgumentException.class, () -> BitmapSampling.inSampleSize(c[0], c[1], c[2], c[3]));
        }
    }

    @Test
    public void emptyInputOrInvalidTargetDecodesToNothingWithoutTouchingBitmapFactory() {
        assertNull(BitmapSampling.decode(null, 100, 100));
        assertNull(BitmapSampling.decode(new byte[0], 100, 100));
        assertNull(BitmapSampling.decode(new byte[] {1, 2, 3}, 0, 100));
        assertNull(BitmapSampling.decode(new byte[] {1, 2, 3}, 100, -1));
    }
}
