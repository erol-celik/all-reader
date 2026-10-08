package com.erol.allreader.engine.page

import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Dikey sayfa listesinin geometrisi. Sayfa yüksekliği = genişlik * (h/w) olduğundan, zoom değişince
 * (yani genişlik değişince) mutlak kaydırma konumu önbelleğe alınmış oranlardan hesaplanır.
 *
 * @param aspects her sayfanın yükseklik/genişlik oranı
 * @param gapPx sayfalar arası sabit boşluk (piksel)
 */
class PageLayoutMath(aspects: FloatArray, private val gapPx: Float) {
    val count: Int = aspects.size

    /** prefix[i] = ilk i sayfanın oranları toplamı. */
    private val prefix = FloatArray(count + 1).also { p ->
        for (i in 0 until count) p[i + 1] = p[i] + aspects[i].coerceAtLeast(MIN_ASPECT)
    }
    private val aspects = aspects.map { it.coerceAtLeast(MIN_ASPECT) }

    fun pageHeight(index: Int, widthPx: Float): Float = widthPx * aspects[index]

    /** Sayfanın liste içindeki üst kenarının mutlak konumu. */
    fun pageTop(index: Int, widthPx: Float): Float = widthPx * prefix[index.coerceIn(0, count)] + gapPx * index

    fun totalHeight(widthPx: Float): Float =
        if (count == 0) 0f else widthPx * prefix[count] + gapPx * (count - 1)

    /** (sayfa, sayfa içi kaydırma) → mutlak konum. */
    fun absoluteY(index: Int, offsetPx: Float, widthPx: Float): Float =
        pageTop(index.coerceIn(0, max(count - 1, 0)), widthPx) + offsetPx

    /** Mutlak konum → (sayfa, sayfa içi kaydırma). Sınır dışı değerler uca sıkıştırılır. */
    fun locate(absoluteY: Float, widthPx: Float): Pair<Int, Int> {
        if (count == 0) return 0 to 0
        val y = absoluteY.coerceAtLeast(0f)
        var lo = 0
        var hi = count - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) ushr 1
            if (pageTop(mid, widthPx) <= y) lo = mid else hi = mid - 1
        }
        val offset = (y - pageTop(lo, widthPx)).coerceAtLeast(0f)
        return lo to offset.toInt()
    }

    companion object {
        const val MIN_ASPECT = 0.05f

        /**
         * Hedef genişlikte bir sayfa bitmap'i çok büyük olacaksa (bellek), ölçeği küçültür.
         * @return (genişlik, yükseklik) piksel; her biri en az 1
         */
        fun renderSize(widthPx: Int, aspect: Float, maxPixels: Long = MAX_BITMAP_PIXELS): Pair<Int, Int> {
            val w = max(widthPx, 1).toDouble()
            val h = w * aspect.coerceAtLeast(MIN_ASPECT)
            val pixels = w * h
            val shrink = if (pixels > maxPixels) sqrt(maxPixels / pixels) else 1.0
            return max((w * shrink).toInt(), 1) to max((h * shrink).toInt(), 1)
        }

        /** Zoom sonrası yatay kaydırmayı, ekran noktası (focusX) sabit kalacak şekilde ve sınırlar içinde hesaplar. */
        fun panAfterZoom(panX: Float, focusX: Float, ratio: Float, viewportW: Float, newZoom: Float): Float {
            val raw = focusX - (focusX - panX) * ratio
            return clampPan(raw, viewportW, newZoom)
        }

        fun clampPan(panX: Float, viewportW: Float, zoom: Float): Float {
            val minPan = min(0f, viewportW - viewportW * zoom)
            return panX.coerceIn(minPan, 0f)
        }

        const val MAX_BITMAP_PIXELS = 12_000_000L
        const val MIN_ZOOM = 1f
        const val MAX_ZOOM = 4f
    }
}
