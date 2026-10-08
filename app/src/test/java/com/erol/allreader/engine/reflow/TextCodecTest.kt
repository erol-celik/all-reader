package com.erol.allreader.engine.reflow

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TextCodecTest {
    private fun roundTrip(bytes: ByteArray) {
        val d = TextCodec.decode(bytes)
        val back = TextCodec.encode(d.text, d.encoding, d.lineEnding)
        assertNotNull(back)
        assertArrayEquals("gidiş-dönüş aynı baytları vermeli", bytes, back)
    }

    @Test
    fun utf8WithoutBom() {
        val d = TextCodec.decode("Merhaba dünya İıŞşĞğ".toByteArray(Charsets.UTF_8))
        assertEquals("Merhaba dünya İıŞşĞğ", d.text)
        assertEquals(TextEncoding("UTF-8", false), d.encoding)
    }

    @Test
    fun utf8WithBomIsDetectedAndStripped() {
        val bytes = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "çay".toByteArray()
        val d = TextCodec.decode(bytes)
        assertEquals("çay", d.text)
        assertEquals(TextEncoding("UTF-8", true), d.encoding)
        roundTrip(bytes)
    }

    @Test
    fun utf16LittleAndBigEndianWithBom() {
        val le = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + "Şişe".toByteArray(Charsets.UTF_16LE)
        val be = byteArrayOf(0xFE.toByte(), 0xFF.toByte()) + "Şişe".toByteArray(Charsets.UTF_16BE)
        assertEquals("Şişe", TextCodec.decode(le).text)
        assertEquals("Şişe", TextCodec.decode(be).text)
        roundTrip(le)
        roundTrip(be)
    }

    @Test
    fun invalidUtf8FallsBackToWindows1254() {
        // 0xDD = İ, 0xFD = ı, 0xFE = ş, 0xF0 = ğ  (windows-1254)
        val bytes = byteArrayOf(0xDD.toByte(), 0xFD.toByte(), 0xFE.toByte(), 0xF0.toByte(), 0x20, 0x61)
        val d = TextCodec.decode(bytes)
        assertEquals("İış" + "ğ a", d.text)
        assertEquals("windows-1254", d.encoding.charsetName)
        roundTrip(bytes)
    }

    @Test
    fun windows1254CannotEncodeEmoji() {
        val enc = TextEncoding("windows-1254", false)
        assertNull(TextCodec.encode("selam 😀", enc, LineEnding.LF))
        assertNotNull(TextCodec.encode("selam ğüşiöç", enc, LineEnding.LF))
    }

    @Test
    fun emojiSurvivesUtf8AndUtf16() {
        assertEquals("a😀b", TextCodec.decode("a😀b".toByteArray()).text)
        val bytes = TextCodec.encode("a😀b", TextEncoding("UTF-16LE", true), LineEnding.LF)!!
        assertEquals("a😀b", TextCodec.decode(bytes).text)
    }

    @Test
    fun emptyFile() {
        val d = TextCodec.decode(ByteArray(0))
        assertEquals("", d.text)
        assertEquals(TextEncoding.UTF8, d.encoding)
        assertEquals(LineEnding.LF, d.lineEnding)
        assertArrayEquals(ByteArray(0), TextCodec.encode("", d.encoding, d.lineEnding))
    }

    @Test
    fun crlfIsNormalizedForEditingAndRestoredOnSave() {
        val bytes = "a\r\nb\r\nc".toByteArray()
        val d = TextCodec.decode(bytes)
        assertEquals("a\nb\nc", d.text)
        assertEquals(LineEnding.CRLF, d.lineEnding)
        roundTrip(bytes)
    }

    @Test
    fun lineEndingDetection() {
        assertEquals(LineEnding.LF, TextCodec.detectLineEnding("a\nb\n"))
        assertEquals(LineEnding.CRLF, TextCodec.detectLineEnding("a\r\nb\r\n"))
        assertEquals(LineEnding.CR, TextCodec.detectLineEnding("a\rb\rc"))
        assertEquals(LineEnding.LF, TextCodec.detectLineEnding("tek satır"))
        // karışık: çoğunluk kazanır
        assertEquals(LineEnding.CRLF, TextCodec.detectLineEnding("a\r\nb\r\nc\nd"))
    }

    @Test
    fun lonelyCarriageReturnAtEndIsNotCrlf() {
        assertEquals(LineEnding.CR, TextCodec.detectLineEnding("abc\r"))
        assertEquals("abc\n", TextCodec.normalize("abc\r"))
    }

    @Test
    fun veryLongSingleLine() {
        val long = "x".repeat(2_000_000)
        val d = TextCodec.decode(long.toByteArray())
        assertEquals(2_000_000, d.text.length)
        assertTrue(TextCodec.encode(d.text, d.encoding, d.lineEnding)!!.size == 2_000_000)
    }

    @Test
    fun loneSurrogateCannotBeEncodedAsUtf8() {
        assertNull(TextCodec.encode("a\uD83Db", TextEncoding.UTF8, LineEnding.LF))
        assertFalse(TextCodec.decode("ok".toByteArray()).text.isEmpty())
    }
}
