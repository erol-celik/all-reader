package com.erol.allreader.engine.reflow.office

import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.util.Base64
import java.util.zip.ZipInputStream
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory

/** Kullanıcıya olduğu gibi gösterilebilecek Türkçe bir hata mesajı taşır. */
class OfficeFormatException(message: String) : IOException(message)

/** Hafif XML ağacı. Eleman adlarında ad alanı öneki atılır (`w:p` → `p`); öznitelikler ham adla tutulur (`r:embed`). */
class XmlNode(val name: String, val attrs: Map<String, String>) {
    val children = ArrayList<XmlNode>()
    private val textBuilder = StringBuilder()
    val text: String get() = textBuilder.toString()

    fun attr(key: String): String? = attrs[key]
    fun child(name: String): XmlNode? = children.firstOrNull { it.name == name }
    fun childrenNamed(name: String): List<XmlNode> = children.filter { it.name == name }

    /** Soyundaki ilk [name] elemanı (derinlik öncelikli). */
    fun find(name: String): XmlNode? {
        for (c in children) {
            if (c.name == name) return c
            c.find(name)?.let { return it }
        }
        return null
    }

    internal fun appendText(s: String) {
        textBuilder.append(s)
    }

    companion object {
        private const val MAX_DEPTH = 200

        fun parse(bytes: ByteArray): XmlNode = parse(ByteArrayInputStream(bytes))

        fun parse(input: InputStream): XmlNode {
            try {
                val parser = newParser()
                parser.setInput(input, null)
                val stack = ArrayList<XmlNode>()
                var root: XmlNode? = null
                var event = parser.eventType
                while (event != XmlPullParser.END_DOCUMENT) {
                    when (event) {
                        XmlPullParser.START_TAG -> {
                            if (stack.size >= MAX_DEPTH) throw OfficeFormatException("Belge çok iç içe, açılamadı")
                            val attrs = HashMap<String, String>(parser.attributeCount)
                            for (i in 0 until parser.attributeCount) attrs[parser.getAttributeName(i)] = parser.getAttributeValue(i)
                            val node = XmlNode(parser.name.substringAfter(':'), attrs)
                            stack.lastOrNull()?.children?.add(node)
                            if (root == null) root = node
                            stack.add(node)
                        }
                        XmlPullParser.END_TAG -> if (stack.isNotEmpty()) stack.removeAt(stack.lastIndex)
                        XmlPullParser.TEXT, XmlPullParser.CDSECT -> stack.lastOrNull()?.appendText(parser.text)
                    }
                    event = parser.next()
                }
                if (stack.isNotEmpty()) throw OfficeFormatException("Belgenin içeriği bozuk")
                return root ?: throw OfficeFormatException("Belge içeriği boş")
            } catch (e: OfficeFormatException) {
                throw e
            } catch (e: Exception) {
                throw OfficeFormatException("Belgenin içeriği bozuk")
            }
        }

        fun newParser(): XmlPullParser {
            val factory = XmlPullParserFactory.newInstance()
            factory.isNamespaceAware = false
            return factory.newPullParser()
        }
    }
}

/** Bir OOXML (ZIP) paketinin bellekteki hâli: XML/ilişki dosyaları ve görseller. */
class OfficePackage private constructor(private val entries: Map<String, ByteArray>) {
    fun has(path: String): Boolean = normalize(path) in entries
    fun bytes(path: String): ByteArray? = entries[normalize(path)]
    fun xml(path: String): XmlNode? = bytes(path)?.let { XmlNode.parse(it) }

    /** `ppt/slides/slide1.xml` → `ppt/slides/_rels/slide1.xml.rels` ilişkileri: id → hedef (tam yol). */
    fun relationships(partPath: String): Map<String, Relationship> {
        val dir = partPath.substringBeforeLast('/', "")
        val file = partPath.substringAfterLast('/')
        val relsPath = (if (dir.isEmpty()) "" else "$dir/") + "_rels/$file.rels"
        val root = xml(relsPath) ?: return emptyMap()
        val out = LinkedHashMap<String, Relationship>()
        for (r in root.childrenNamed("Relationship")) {
            val id = r.attr("Id") ?: continue
            val target = r.attr("Target") ?: continue
            val external = r.attr("TargetMode").equals("External", ignoreCase = true)
            val resolved = if (external) target else resolve(dir, target)
            out[id] = Relationship(id, r.attr("Type").orEmpty(), resolved, external)
        }
        return out
    }

    class Relationship(val id: String, val type: String, val target: String, val external: Boolean)

    companion object {
        /** Tek bir girdinin ve toplamın açılmış boyut sınırları (zip bombası koruması). */
        private const val MAX_XML_ENTRY = 64L * 1024 * 1024
        private const val MAX_IMAGE_ENTRY = 8L * 1024 * 1024
        /** Pakette bellekte tutulan görsellerin toplamı; fazlası okunmadan atlanır (yer tutucu gösterilir). */
        private const val MAX_IMAGES_TOTAL = 24L * 1024 * 1024
        private const val MAX_TOTAL = 200L * 1024 * 1024
        private val imageExt = setOf("png", "jpg", "jpeg", "gif", "bmp", "webp")

        fun normalize(path: String): String = path.trimStart('/')

        fun resolve(dir: String, target: String): String {
            if (target.startsWith("/")) return target.trimStart('/')
            val parts = ArrayList<String>()
            if (dir.isNotEmpty()) parts.addAll(dir.split('/'))
            for (seg in target.split('/')) {
                when (seg) {
                    "", "." -> Unit
                    ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.lastIndex)
                    else -> parts.add(seg)
                }
            }
            return parts.joinToString("/")
        }

        fun open(input: InputStream, kind: String): OfficePackage {
            val buffered = input.buffered()
            buffered.mark(8)
            val head = ByteArray(8)
            val n = buffered.read(head)
            buffered.reset()
            if (n >= 8 && head[0] == 0xD0.toByte() && head[1] == 0xCF.toByte() && head[2] == 0x11.toByte() && head[3] == 0xE0.toByte()) {
                throw OfficeFormatException("Dosya parolalı ya da eski ikili biçimde (.doc/.xls/.ppt); desteklenmiyor")
            }
            if (n < 4 || head[0] != 'P'.code.toByte() || head[1] != 'K'.code.toByte()) {
                throw OfficeFormatException("Geçerli bir $kind dosyası değil")
            }
            val map = HashMap<String, ByteArray>()
            var total = 0L
            var imagesTotal = 0L
            try {
                ZipInputStream(buffered).use { zip ->
                    while (true) {
                        val entry = zip.nextEntry ?: break
                        if (entry.isDirectory) continue
                        val name = normalize(entry.name)
                        val ext = name.substringAfterLast('.', "").lowercase()
                        val limit = when {
                            ext == "xml" || ext == "rels" -> MAX_XML_ENTRY
                            ext in imageExt -> MAX_IMAGE_ENTRY
                            else -> continue
                        }
                        val isImage = ext in imageExt
                        if (isImage && imagesTotal >= MAX_IMAGES_TOTAL) continue
                        val data = readLimited(zip, limit) ?: continue
                        if (isImage) {
                            if (imagesTotal + data.size > MAX_IMAGES_TOTAL) continue
                            imagesTotal += data.size
                        }
                        total += data.size
                        if (total > MAX_TOTAL) throw OfficeFormatException("Belge çok büyük")
                        map[name] = data
                    }
                }
            } catch (e: OfficeFormatException) {
                throw e
            } catch (e: Exception) {
                throw OfficeFormatException("Dosya bozuk ya da geçerli bir $kind dosyası değil")
            }
            if (map.isEmpty()) throw OfficeFormatException("Geçerli bir $kind dosyası değil")
            return OfficePackage(map)
        }

        /** [limit] aşılırsa girdiyi atlar (null döner) ama akışı sonuna kadar tüketir. */
        private fun readLimited(input: InputStream, limit: Long): ByteArray? {
            val out = java.io.ByteArrayOutputStream()
            val buf = ByteArray(16 * 1024)
            var size = 0L
            var tooBig = false
            while (true) {
                val r = input.read(buf)
                if (r < 0) break
                size += r
                if (size > limit) {
                    tooBig = true
                    out.reset()
                    // Kalanını at; bellek tutma.
                    continue
                }
                if (!tooBig) out.write(buf, 0, r)
            }
            return if (tooBig) null else out.toByteArray()
        }
    }
}

/** Belgedeki görselleri `data:` URI'sine çevirir; toplam boyutu sınırlar ki HTML şişmesin. */
class ImageEmbedder(private val pkg: OfficePackage, private val budgetBytes: Long = 8L * 1024 * 1024) {
    private var used = 0L

    /** Görsel gömülemezse (yok, desteklenmeyen tür, bütçe doldu) yer tutucu metin döner. */
    fun imgTag(path: String?, alt: String = ""): String {
        if (path == null) return placeholder()
        val data = pkg.bytes(path) ?: return placeholder()
        val mime = when (path.substringAfterLast('.', "").lowercase()) {
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            "gif" -> "image/gif"
            "bmp" -> "image/bmp"
            "webp" -> "image/webp"
            else -> return placeholder()
        }
        if (used + data.size > budgetBytes) return placeholder()
        used += data.size
        val b64 = Base64.getEncoder().encodeToString(data)
        return "<img src=\"data:$mime;base64,$b64\" alt=\"${esc(alt)}\">"
    }

    private fun placeholder() = "<span class=\"missing-img\">[görsel gösterilemiyor]</span>"
}

internal fun esc(text: String): String = com.erol.allreader.engine.reflow.PlainTextConverter.escape(text)
