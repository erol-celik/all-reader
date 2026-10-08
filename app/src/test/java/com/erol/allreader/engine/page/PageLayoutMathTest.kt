package com.erol.allreader.engine.page

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PageLayoutMathTest {
    private val math = PageLayoutMath(floatArrayOf(1.5f, 1.0f, 2.0f), gapPx = 10f)

    @Test
    fun pageTopsAccountForGaps() {
        assertEquals(0f, math.pageTop(0, 100f), 0.001f)
        assertEquals(160f, math.pageTop(1, 100f), 0.001f) // 150 + 10
        assertEquals(270f, math.pageTop(2, 100f), 0.001f) // 150 + 100 + 20
        assertEquals(470f, math.totalHeight(100f), 0.001f) // 450 + 20
    }

    @Test
    fun locateIsInverseOfAbsoluteY() {
        for (index in 0..2) {
            for (offset in listOf(0, 7, 40)) {
                val y = math.absoluteY(index, offset.toFloat(), 100f)
                assertEquals(index to offset, math.locate(y, 100f))
            }
        }
    }

    @Test
    fun locateClampsOutOfRange() {
        assertEquals(0 to 0, math.locate(-50f, 100f))
        assertEquals(2, math.locate(99_999f, 100f).first)
    }

    @Test
    fun emptyDocumentIsSafe() {
        val empty = PageLayoutMath(floatArrayOf(), 10f)
        assertEquals(0f, empty.totalHeight(100f), 0f)
        assertEquals(0 to 0, empty.locate(123f, 100f))
        assertEquals(0f, empty.absoluteY(0, 0f, 100f), 0f)
    }

    @Test
    fun singlePageHasNoGap() {
        val one = PageLayoutMath(floatArrayOf(1.4f), 10f)
        assertEquals(140f, one.totalHeight(100f), 0.001f)
    }

    @Test
    fun degenerateAspectIsClamped() {
        val weird = PageLayoutMath(floatArrayOf(0f, -3f, Float.MIN_VALUE), 0f)
        assertTrue(weird.totalHeight(100f) > 0f)
    }

    @Test
    fun zoomKeepsPositionProportional() {
        // 2x zoom: aynı belge noktası iki katı mutlak konuma gelir (boşluklar hariç aspect kısmı).
        val math = PageLayoutMath(floatArrayOf(1.5f, 1.5f), gapPx = 0f)
        val before = math.absoluteY(1, 30f, 100f)
        val after = math.absoluteY(1, 60f, 200f)
        assertEquals(before * 2, after, 0.001f)
    }

    @Test
    fun renderSizeShrinksHugeBitmaps() {
        val (w, h) = PageLayoutMath.renderSize(8000, 1.5f)
        assertTrue(w.toLong() * h <= PageLayoutMath.MAX_BITMAP_PIXELS)
        assertEquals(1.5f, h.toFloat() / w, 0.01f)
    }

    @Test
    fun renderSizeKeepsNormalBitmapsAndNeverZero() {
        assertEquals(1080 to 1620, PageLayoutMath.renderSize(1080, 1.5f))
        val (w, h) = PageLayoutMath.renderSize(0, 0f)
        assertTrue(w >= 1 && h >= 1)
    }

    @Test
    fun panStaysInsideBounds() {
        // Görünüm 1000 px, zoom 3 → pan en fazla -2000 .. 0
        assertEquals(0f, PageLayoutMath.clampPan(300f, 1000f, 3f), 0f)
        assertEquals(-2000f, PageLayoutMath.clampPan(-9000f, 1000f, 3f), 0f)
        assertEquals(0f, PageLayoutMath.clampPan(-50f, 1000f, 1f), 0f)
    }

    @Test
    fun panAfterZoomKeepsFocusPointStable() {
        // zoom 1 → 2, odak ekranın ortasında (500): içerik noktası 500 yine 500'de kalmalı
        val pan = PageLayoutMath.panAfterZoom(panX = 0f, focusX = 500f, ratio = 2f, viewportW = 1000f, newZoom = 2f)
        assertEquals(-500f, pan, 0.001f)
        // içerik noktası x=500 → 500*2 + pan = 500
        assertEquals(500f, 500f * 2 + pan, 0.001f)
    }
}
