package com.pocket.agent.data.image

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The sampling arithmetic is pure, so it is checked here rather than through
 * an emulator that would have to hand a real bitmap to the compressor.
 */
class ImageCompressorTest {

    @Test
    fun `an image already under the ceiling is kept whole`() {
        assertEquals(1, computeInSampleSize(1200, 800))
        assertEquals(1, computeInSampleSize(2048, 2048))
    }

    @Test
    fun `a large image is sampled until the long edge fits`() {
        // 8000 needs two halvings: 8000 / 4 = 2000, which clears 2048.
        assertEquals(4, computeInSampleSize(8000, 6000))
        assertEquals(4, computeInSampleSize(6000, 8000))
    }

    @Test
    fun `a huge image is sampled hard`() {
        // 40000 / 16 = 2500 still overshoots, so the loop takes one more step.
        assertEquals(32, computeInSampleSize(40000, 30000))
    }

    @Test
    fun `a custom ceiling is respected`() {
        assertEquals(8, computeInSampleSize(1600, 1200, maxLongEdge = 256))
    }

    @Test
    fun `degenerate sizes fall back to no sampling`() {
        assertEquals(1, computeInSampleSize(0, 0))
        assertEquals(1, computeInSampleSize(-10, 100))
        assertEquals(1, computeInSampleSize(100, 100, maxLongEdge = 0))
    }
}
