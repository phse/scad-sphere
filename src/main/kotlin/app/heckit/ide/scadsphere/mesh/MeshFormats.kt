package app.heckit.ide.scadsphere.mesh

import org.w3c.dom.Document
import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.OutputKeys
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult

/** Matrix 3×4 (zeilenweise, Spaltenvektoren) auf Punkte und Normalen anwenden. */
internal class Affine(private val m: DoubleArray) {
    private val cof = run {
        val a = m[0]; val b = m[1]; val c = m[2]; val d = m[4]; val e = m[5]; val f = m[6]; val g = m[8]; val h = m[9]; val k = m[10]
        doubleArrayOf(e * k - f * h, f * g - d * k, d * h - e * g, c * h - b * k, a * k - c * g, b * g - a * h, b * f - c * e, c * d - a * f, a * e - b * d)
    }

    fun point(x: Double, y: Double, z: Double) = doubleArrayOf(
        m[0] * x + m[1] * y + m[2] * z + m[3], m[4] * x + m[5] * y + m[6] * z + m[7], m[8] * x + m[9] * y + m[10] * z + m[11],
    )

    fun normal(x: Double, y: Double, z: Double): DoubleArray {
        val n = doubleArrayOf(cof[0] * x + cof[1] * y + cof[2] * z, cof[3] * x + cof[4] * y + cof[5] * z, cof[6] * x + cof[7] * y + cof[8] * z)
        val length = Math.sqrt(n[0] * n[0] + n[1] * n[1] + n[2] * n[2]).takeIf { it > 0 } ?: 1.0
        return DoubleArray(3) { n[it] / length }
    }
}

/** STL (binär oder ASCII): das ganze Modell umrechnen - Teile gibt es in STL nicht. */
object StlEdits {
    fun isBinary(bytes: ByteArray): Boolean {
        if (bytes.size < 84) return false
        val count = ByteBuffer.wrap(bytes, 80, 4).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xffffffffL
        return 84 + count * 50 == bytes.size.toLong()
    }

    fun transform(bytes: ByteArray, m: DoubleArray): ByteArray {
        val affine = Affine(m)
        if (isBinary(bytes)) {
            val out = bytes.copyOf()
            val buffer = ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN)
            val count = buffer.getInt(80)
            for (i in 0 until count) {
                val base = 84 + i * 50
                fun read(offset: Int) = DoubleArray(3) { buffer.getFloat(base + offset + it * 4).toDouble() }
                fun write(offset: Int, v: DoubleArray) = v.forEachIndexed { k, value -> buffer.putFloat(base + offset + k * 4, value.toFloat()) }
                val n = read(0)
                write(0, affine.normal(n[0], n[1], n[2]))
                for (corner in 0 until 3) {
                    val p = read(12 + corner * 12)
                    write(12 + corner * 12, affine.point(p[0], p[1], p[2]))
                }
            }
            return out
        }
        return bytes.toString(Charsets.UTF_8).split("\n").joinToString("\n") { raw ->
            val line = raw.trim()
            val (keyword, values) = when {
                line.startsWith("facet normal ") -> "facet normal" to line.removePrefix("facet normal")
                line.startsWith("vertex ") -> "vertex" to line.removePrefix("vertex")
                else -> return@joinToString raw
            }
            val xyz = values.trim().split(Regex("\\s+")).mapNotNull { it.toDoubleOrNull() }
            if (xyz.size < 3) return@joinToString raw
            val result = if (keyword == "vertex") affine.point(xyz[0], xyz[1], xyz[2]) else affine.normal(xyz[0], xyz[1], xyz[2])
            raw.substring(0, raw.indexOf(line)) + keyword + " " + result.joinToString(" ") { String.format(Locale.ROOT, "%e", it) } +
                if (raw.endsWith("\r")) "\r" else ""
        }.toByteArray(Charsets.UTF_8)
    }
}

/** OFF: die Eckpunkte hinter der Kopfzeile umrechnen (Farben am Ende der Zeile bleiben). */
object OffEdits {
    fun transform(text: String, m: DoubleArray): String {
        val affine = Affine(m)
        val lines = text.split("\n").toMutableList()
        var vertices = -1
        var done = 0
        var headerSeen = false
        for ((i, raw) in lines.withIndex()) {
            val content = raw.substringBefore('#').trim()
            if (content.isEmpty()) continue
            var tokens = content.split(Regex("\\s+"))
            if (!headerSeen) {
                headerSeen = true
                if (tokens[0].endsWith("OFF")) {
                    tokens = tokens.drop(1)
                    if (tokens.isEmpty()) continue
                }
            }
            if (vertices < 0) {
                vertices = tokens[0].toIntOrNull() ?: return text
                continue
            }
            if (done >= vertices) break
            val xyz = tokens.take(3).map { it.toDoubleOrNull() ?: return text }
            val p = affine.point(xyz[0], xyz[1], xyz[2])
            lines[i] = (p.map { ObjEdits.number(it) } + tokens.drop(3)).joinToString(" ") + if (raw.endsWith("\r")) "\r" else ""
            done++
        }
        return lines.joinToString("\n")
    }
}

/**
 * AMF: Objekte (`metadata type="name"`) als Teile, Materialien und Objektfarben als Palette; umbenennen, löschen,
 * Material zuweisen, Farbe ändern, umrechnen. Gepackte AMF (ZIP) bleiben gepackt.
 */
object AmfEdits {
    fun palette(bytes: ByteArray): Palette {
        val doc = parse(unpack(bytes))
        val objects = children(doc.documentElement, "object")
        val result = mutableListOf<PaletteEntry>()
        for (material in children(doc.documentElement, "material")) {
            val id = material.getAttribute("id")
            val users = objects.filter { obj -> volumes(obj).any { it.getAttribute("materialid") == id } }.map { name(it) }
            val name = metadataName(material)
            result += PaletteEntry("m|$id", name ?: id, color(material) ?: "#ffffff", users, name ?: "AMF Material", false)
        }
        for (obj in objects) {
            val color = color(obj) ?: continue
            result += PaletteEntry("o|${obj.getAttribute("id")}", name(obj), color, listOf(name(obj)), null, true)
        }
        return Palette(result)
    }

    fun setColor(bytes: ByteArray, key: String, color: String): ByteArray? = edit(bytes) { doc ->
        val (kind, id) = key.split("|", limit = 2).takeIf { it.size == 2 } ?: return@edit false
        val element = children(doc.documentElement, if (kind == "m") "material" else "object").firstOrNull { it.getAttribute("id") == id }
            ?: return@edit false
        val node = children(element, "color").firstOrNull() ?: doc.createElement("color").also { element.insertBefore(it, element.firstChild) }
        val hex = color.removePrefix("#")
        for ((i, channel) in listOf("r", "g", "b").withIndex()) {
            val child = children(node, channel).firstOrNull() ?: doc.createElement(channel).also { node.appendChild(it) }
            child.textContent = String.format(Locale.ROOT, "%.4f", hex.substring(i * 2, i * 2 + 2).toInt(16) / 255.0)
        }
        true
    }

    fun rename(bytes: ByteArray, part: String, newName: String): ByteArray? = edit(bytes) { doc ->
        val objects = children(doc.documentElement, "object").filter { name(it) == part }
        for (obj in objects) {
            val meta = children(obj, "metadata").firstOrNull { it.getAttribute("type") == "name" }
                ?: doc.createElement("metadata").also { it.setAttribute("type", "name"); obj.insertBefore(it, obj.firstChild) }
            meta.textContent = newName
        }
        objects.isNotEmpty()
    }

    fun delete(bytes: ByteArray, part: String): ByteArray? = edit(bytes) { doc ->
        val objects = children(doc.documentElement, "object").filter { name(it) == part }
        val ids = objects.map { it.getAttribute("id") }.toSet()
        objects.forEach { it.parentNode.removeChild(it) }
        // Verweise in Konstellationen gleich mit
        val instances = doc.getElementsByTagName("instance")
        (0 until instances.length).map { instances.item(it) as Element }.filter { it.getAttribute("objectid") in ids }
            .forEach { it.parentNode.removeChild(it) }
        objects.isNotEmpty()
    }

    fun assignMaterial(bytes: ByteArray, part: String, key: String): ByteArray? = edit(bytes) { doc ->
        val (kind, id) = key.split("|", limit = 2).takeIf { it.size == 2 } ?: return@edit false
        if (kind != "m") return@edit false
        val objects = children(doc.documentElement, "object").filter { name(it) == part }
        objects.flatMap { volumes(it) }.forEach { it.setAttribute("materialid", id) }
        objects.isNotEmpty()
    }

    fun transform(bytes: ByteArray, part: String?, m: DoubleArray): ByteArray? = edit(bytes) { doc ->
        val affine = Affine(m)
        val objects = children(doc.documentElement, "object").filter { part == null || name(it) == part }
        for (obj in objects) {
            updateTriples(obj, "coordinates", listOf("x", "y", "z")) { affine.point(it[0], it[1], it[2]) }
            updateTriples(obj, "normal", listOf("nx", "ny", "nz")) { affine.normal(it[0], it[1], it[2]) }
        }
        objects.isNotEmpty()
    }

    /** Alle Elemente [tag] unter [obj] mit drei Zahlen-Kindern [axes] umrechnen; unvollständige bleiben, wie sie sind. */
    private fun updateTriples(obj: Element, tag: String, axes: List<String>, change: (DoubleArray) -> DoubleArray) {
        val nodes = obj.getElementsByTagName(tag)
        for (i in 0 until nodes.length) {
            val node = nodes.item(i) as Element
            val elements = axes.mapNotNull { children(node, it).firstOrNull() }
            if (elements.size != 3) continue
            val values = elements.mapNotNull { it.textContent.trim().toDoubleOrNull() }
            if (values.size != 3) continue
            val result = change(values.toDoubleArray())
            elements.forEachIndexed { k, element -> element.textContent = ObjEdits.number(result[k]) }
        }
    }

    /** Name des Objekts wie im Viewer: Metadaten-Name, sonst `#id`. */
    private fun name(obj: Element) = metadataName(obj) ?: "#${obj.getAttribute("id")}"

    private fun metadataName(element: Element) = children(element, "metadata").firstOrNull { it.getAttribute("type") == "name" }?.textContent?.trim()

    private fun volumes(obj: Element) = children(obj, "mesh").flatMap { children(it, "volume") }

    private fun color(element: Element): String? {
        val node = children(element, "color").firstOrNull() ?: return null
        val values = listOf("r", "g", "b").map { channel -> children(node, channel).firstOrNull()?.textContent?.trim()?.toDoubleOrNull() ?: return null }
        return "#%02x%02x%02x".format(*values.map { (it.coerceIn(0.0, 1.0) * 255).toInt() }.toTypedArray())
    }

    private fun children(parent: Element, name: String): List<Element> {
        val list = mutableListOf<Element>()
        var node = parent.firstChild
        while (node != null) {
            if (node is Element && node.tagName == name) list += node
            node = node.nextSibling
        }
        return list
    }

    private fun edit(bytes: ByteArray, change: (Document) -> Boolean): ByteArray? {
        val doc = parse(unpack(bytes))
        if (!change(doc)) return null
        val out = ByteArrayOutputStream()
        TransformerFactory.newInstance().newTransformer().apply { setOutputProperty(OutputKeys.ENCODING, "UTF-8") }
            .transform(DOMSource(doc), StreamResult(out))
        return repack(bytes, out.toByteArray())
    }

    private fun isZip(bytes: ByteArray) = bytes.size > 4 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte()

    private fun unpack(bytes: ByteArray): ByteArray {
        if (!isZip(bytes)) return bytes
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (!entry.isDirectory) return zip.readBytes()
            }
        }
        return bytes
    }

    private fun repack(original: ByteArray, xml: ByteArray): ByteArray {
        if (!isZip(original)) return xml
        val name = ZipInputStream(ByteArrayInputStream(original)).use { it.nextEntry?.name } ?: "model.amf"
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { it.putNextEntry(ZipEntry(name)); it.write(xml); it.closeEntry() }
        return out.toByteArray()
    }

    private fun parse(bytes: ByteArray) = DocumentBuilderFactory.newInstance().apply {
        setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        isExpandEntityReferences = false
    }.newDocumentBuilder().parse(ByteArrayInputStream(bytes))
}
