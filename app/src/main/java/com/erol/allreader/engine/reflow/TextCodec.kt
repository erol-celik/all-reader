package com.erol.allreader.engine.reflow

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

data class TextEncoding(val charsetName: String, val bom: Boolean) {
    val label: String
        get() = charsetName + if (bom) " (BOM)" else ""

    companion object {
        val UTF8 = TextEncoding("UTF-8", bom = false)
    }
}

enum class LineEnding(val sequence: String) { LF("\n"), CRLF("\r\n"), CR("\r") }

/** [text] her zaman `\n` ile normalize edilmiştir; kaydederken [lineEnding] geri uygulanır. */
data class DecodedText(val text: String, val encoding: TextEncoding, val lineEnding: LineEnding)

/** TXT/MD dosyaları için kodlama ve satır sonu algılama; kaydederken aynılarını korur. */
object TextCodec {
    private const val WINDOWS_1254 = "windows-1254"

    fun decode(bytes: ByteArray): DecodedText {
        val (encoding, offset) = detectEncoding(bytes)
        val charset = Charset.forName(encoding.charsetName)
        val buffer = ByteBuffer.wrap(bytes, offset, bytes.size - offset)
        val raw = charset.newDecoder()
            .onMalformedInput(CodingErrorAction.REPLACE)
            .onUnmappableCharacter(CodingErrorAction.REPLACE)
            .decode(buffer)
            .toString()
        val lineEnding = detectLineEnding(raw)
        return DecodedText(normalize(raw), encoding, lineEnding)
    }

    /** Kodlayamıyorsa (örn. Windows-1254'e emoji) null döner. */
    fun encode(text: String, encoding: TextEncoding, lineEnding: LineEnding): ByteArray? {
        val charset = Charset.forName(encoding.charsetName)
        val withEndings = if (lineEnding == LineEnding.LF) text else text.replace("\n", lineEnding.sequence)
        val body = try {
            val encoder = charset.newEncoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
            val out = encoder.encode(java.nio.CharBuffer.wrap(withEndings))
            ByteArray(out.remaining()).also { out.get(it) }
        } catch (e: CharacterCodingException) {
            return null
        }
        return if (encoding.bom) bomFor(encoding.charsetName) + body else body
    }

    private fun detectEncoding(bytes: ByteArray): Pair<TextEncoding, Int> {
        fun at(i: Int) = bytes[i].toInt() and 0xFF
        return when {
            bytes.size >= 3 && at(0) == 0xEF && at(1) == 0xBB && at(2) == 0xBF -> TextEncoding("UTF-8", true) to 3
            bytes.size >= 2 && at(0) == 0xFF && at(1) == 0xFE -> TextEncoding("UTF-16LE", true) to 2
            bytes.size >= 2 && at(0) == 0xFE && at(1) == 0xFF -> TextEncoding("UTF-16BE", true) to 2
            isValidUtf8(bytes) -> TextEncoding.UTF8 to 0
            else -> TextEncoding(WINDOWS_1254, false) to 0
        }
    }

    private fun isValidUtf8(bytes: ByteArray): Boolean = try {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
        true
    } catch (e: CharacterCodingException) {
        false
    }

    private fun bomFor(charsetName: String): ByteArray = when (charsetName) {
        "UTF-8" -> byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
        "UTF-16LE" -> byteArrayOf(0xFF.toByte(), 0xFE.toByte())
        "UTF-16BE" -> byteArrayOf(0xFE.toByte(), 0xFF.toByte())
        else -> ByteArray(0)
    }

    /** En sık görülen satır sonu; hiç yoksa LF. */
    fun detectLineEnding(text: String): LineEnding {
        var crlf = 0
        var lf = 0
        var cr = 0
        var i = 0
        while (i < text.length) {
            when (text[i]) {
                '\r' -> if (i + 1 < text.length && text[i + 1] == '\n') { crlf++; i++ } else cr++
                '\n' -> lf++
            }
            i++
        }
        return when {
            crlf == 0 && lf == 0 && cr == 0 -> LineEnding.LF
            crlf >= lf && crlf >= cr -> LineEnding.CRLF
            cr > lf -> LineEnding.CR
            else -> LineEnding.LF
        }
    }

    fun normalize(text: String): String =
        if ('\r' in text) text.replace("\r\n", "\n").replace('\r', '\n') else text
}
