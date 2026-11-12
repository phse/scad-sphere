package app.heckit.ide.scadsphere.mesh

import org.w3c.dom.Document
import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.OutputKeys
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult

/**
 * Kleine Bearbeitungen an OBJ-Dateien: Teil (Gruppe `g`/`o`) umbenennen oder löschen, das ganze Modell verschieben und
 * gleichmäßig skalieren, eine zweite OBJ anhängen. Alles zeilenweise - was nicht betroffen ist, bleibt byte-gleich.
 */
object ObjEdits {
    private fun isGroup(line: String) = line.startsWith("g ") || line.startsWith("o ")
    private fun eol(raw: String) = if (raw.endsWith("\r")) "\r" else ""

    fun rename(obj: String, part: String, name: String): String = obj.split("\n").joinToString("\n") { raw ->
        val line = raw.trim()
        if (isGroup(line) && line.substring(2).trim() == part) raw.substring(0, raw.indexOf(line[0])) + line[0] + " " + name + eol(raw) else raw
    }

    /** Flächen, Linien und Punkte des Teils entfernen (samt `g`-Zeile); Eckpunkte bleiben, damit die Indizes stimmen. */
    fun delete(obj: String, part: String): String {
        var inPart = false
        val out = mutableListOf<String>()
        for (raw in obj.split("\n")) {
            val line = raw.trim()
            if (isGroup(line)) {
                inPart = line.substring(2).trim() == part
                if (inPart) continue
            }
            if (inPart && (line.startsWith("f ") || line.startsWith("l ") || line.startsWith("p ") || line.startsWith("usemtl ") || line.startsWith("s "))) continue
            out += raw
        }
        return out.joinToString("\n")
    }

    /**
     * Teil [part] (bzw. alles bei null) mit [m] umrechnen: 3×4 zeilenweise, `x' = m0·x + m1·y + m2·z + m3` usw.
     * Normalen drehen mit (normiert). Eckpunkte, die das Teil mit anderen Gruppen teilt, bekommt es als Kopie am Ende,
     * damit die anderen bleiben, wo sie sind.
     */
    fun transformPart(obj: String, part: String?, m: DoubleArray): String {
        val lines = obj.split("\n").toMutableList()
        fun point(x: Double, y: Double, z: Double) = doubleArrayOf(m[0] * x + m[1] * y + m[2] * z + m[3], m[4] * x + m[5] * y + m[6] * z + m[7], m[8] * x + m[9] * y + m[10] * z + m[11])
        // Normalen mit der inversen Transponierten (stimmt auch bei ungleichmäßigem Skalieren), danach normiert
        val a = m[0]; val b = m[1]; val c = m[2]; val d = m[4]; val e = m[5]; val f = m[6]; val g = m[8]; val h = m[9]; val k = m[10]
        val cof = doubleArrayOf(e * k - f * h, f * g - d * k, d * h - e * g, c * h - b * k, a * k - c * g, b * g - a * h, b * f - c * e, c * d - a * f, a * e - b * d)
        fun normal(x: Double, y: Double, z: Double): DoubleArray {
            val n = doubleArrayOf(cof[0] * x + cof[1] * y + cof[2] * z, cof[3] * x + cof[4] * y + cof[5] * z, cof[6] * x + cof[7] * y + cof[8] * z)
            val length = Math.sqrt(n[0] * n[0] + n[1] * n[1] + n[2] * n[2]).takeIf { it > 0 } ?: 1.0
            return DoubleArray(3) { n[it] / length }
        }
        // v/vn-Zeile umrechnen; null: keine Zahlen
        fun mapped(raw: String, isNormal: Boolean): String? {
            val line = raw.trim()
            val values = line.substringAfter(' ').trim().split(Regex("\\s+"))
            val xyz = values.take(3).map { it.toDoubleOrNull() ?: return null }
            if (xyz.size < 3) return null
            val result = if (isNormal) normal(xyz[0], xyz[1], xyz[2]) else point(xyz[0], xyz[1], xyz[2])
            val key = if (isNormal) "vn" else "v"
            return raw.substring(0, raw.indexOf(key[0])) + key + " " + (result.map { number(it) } + values.drop(3)).joinToString(" ") + eol(raw)
        }
        // Erster Durchlauf: welche Eckpunkte/Normalen nutzt das Teil, welche die übrigen Gruppen?
        val vLine = mutableListOf<Int>()
        val nLine = mutableListOf<Int>()
        val inV = mutableSetOf<Int>(); val outV = mutableSetOf<Int>(); val inN = mutableSetOf<Int>(); val outN = mutableSetOf<Int>()
        val partFaces = mutableListOf<Int>()
        var inPart = part == null
        for ((i, raw) in lines.withIndex()) {
            val line = raw.trim()
            when {
                isGroup(line) -> if (part != null) inPart = line.substring(2).trim() == part
                line.startsWith("v ") -> vLine += i
                line.startsWith("vn ") -> nLine += i
                line.startsWith("f ") || line.startsWith("l ") || line.startsWith("p ") -> {
                    if (inPart) partFaces += i
                    for (ref in line.split(Regex("\\s+")).drop(1)) {
                        val parts = ref.split("/")
                        parts[0].toIntOrNull()?.let { n -> (if (inPart) inV else outV) += if (n < 0) vLine.size + n + 1 else n }
                        parts.getOrNull(2)?.toIntOrNull()?.let { n -> (if (inPart) inN else outN) += if (n < 0) nLine.size + n + 1 else n }
                    }
                }
            }
        }
        if (part == null) {
            vLine.forEach { i -> mapped(lines[i], false)?.let { lines[i] = it } }
            nLine.forEach { i -> mapped(lines[i], true)?.let { lines[i] = it } }
            return lines.joinToString("\n")
        }
        if (partFaces.isEmpty()) return obj
        val copies = mutableListOf<String>()
        val vMap = mutableMapOf<Int, Int>()
        val nMap = mutableMapOf<Int, Int>()
        for (index in inV.sorted()) {
            val i = vLine.getOrNull(index - 1) ?: continue
            val moved = mapped(lines[i], false) ?: continue
            if (index in outV) { copies += moved.trim(); vMap[index] = vLine.size + copies.count { it.startsWith("v ") } } else lines[i] = moved
        }
        for (index in inN.sorted()) {
            val i = nLine.getOrNull(index - 1) ?: continue
            val moved = mapped(lines[i], true) ?: continue
            if (index in outN) { copies += moved.trim(); nMap[index] = nLine.size + copies.count { it.startsWith("vn ") } } else lines[i] = moved
        }
        // Flächen des Teils auf feste Indizes (und die Kopien) umstellen - relative Angaben stimmen nach dem Anhängen nicht mehr
        var vSeen = 0; var nSeen = 0
        val faceSet = partFaces.toSet()
        for ((i, raw) in lines.withIndex()) {
            val line = raw.trim()
            if (line.startsWith("v ")) vSeen++
            if (line.startsWith("vn ")) nSeen++
            if (i !in faceSet) continue
            val tokens = line.split(Regex("\\s+"))
            lines[i] = raw.substring(0, raw.indexOf(tokens[0])) + tokens[0] + " " + tokens.drop(1).joinToString(" ") { ref ->
                ref.split("/").mapIndexed { k, text ->
                    val n = text.toIntOrNull() ?: return@mapIndexed text
                    when (k) {
                        0 -> { val abs = if (n < 0) vSeen + n + 1 else n; (vMap[abs] ?: abs).toString() }
                        2 -> { val abs = if (n < 0) nSeen + n + 1 else n; (nMap[abs] ?: abs).toString() }
                        else -> text
                    }
                }.joinToString("/")
            } + eol(raw)
        }
        if (copies.isEmpty()) return lines.joinToString("\n")
        val body = lines.joinToString("\n").trimEnd('\n', '\r')
        return body + "\n# moved copies of shared vertices: $part\n" + copies.joinToString("\n") + "\n"
    }

    /**
     * [other] hinten anhängen: Indizes in `f`/`l`/`p` um die Zahl der Eckpunkte, Texturkoordinaten und Normalen von [obj]
     * versetzt (negative, relative Indizes bleiben). `mtllib` von [other] kommt nach oben, sofern noch nicht vorhanden.
     */
    fun merge(obj: String, other: String, label: String): String {
        val counts = IntArray(3)
        for (raw in obj.split("\n")) {
            val line = raw.trim()
            when {
                line.startsWith("v ") -> counts[0]++
                line.startsWith("vt ") -> counts[1]++
                line.startsWith("vn ") -> counts[2]++
            }
        }
        val libraries = ObjColors.mtlLibraries(obj).toSet()
        val newLibraries = ObjColors.mtlLibraries(other).filter { it !in libraries }
        val appended = other.split("\n").mapNotNull { raw ->
            val line = raw.trim()
            when {
                line.startsWith("mtllib ") -> null
                line.startsWith("f ") || line.startsWith("l ") || line.startsWith("p ") -> {
                    val parts = line.split(Regex("\\s+"))
                    parts[0] + " " + parts.drop(1).joinToString(" ") { ref ->
                        ref.split("/").mapIndexed { i, index ->
                            val n = index.toIntOrNull()
                            if (n == null || n < 0) index else (n + counts[i]).toString()
                        }.joinToString("/")
                    }
                }
                else -> raw.removeSuffix("\r")
            }
        }
        val head = obj.removeSuffix("\n")
        val lines = head.split("\n").toMutableList()
        if (newLibraries.isNotEmpty()) {
            val at = lines.indexOfLast { it.trim().startsWith("mtllib ") }.takeIf { it >= 0 }?.plus(1)
                ?: lines.takeWhile { it.trim().startsWith("#") }.size
            lines.addAll(at, newLibraries.map { "mtllib $it" })
        }
        // Ohne eigene Gruppe am Anfang landeten die Flächen sonst in der letzten Gruppe von [obj]
        val needsGroup = appended.firstOrNull { val t = it.trim(); t.startsWith("f ") || isGroup(t) }?.trim()?.let { !isGroup(it) } ?: false
        return (lines + listOf("", "# merged: $label") + (if (needsGroup) listOf("g $label") else emptyList()) + appended)
            .joinToString("\n").trimEnd('\n') + "\n"
    }

    internal fun number(value: Double): String {
        val text = String.format(Locale.ROOT, "%.6f", value).trimEnd('0').trimEnd('.')
        return if (text == "-0") "0" else text
    }
}

/**
 * 3MF: Objekt umbenennen (`name`), aus dem Druck nehmen (Eintrag in `build` löschen), das ganze Modell über die
 * `transform` der Build-Einträge verschieben/skalieren und eine zweite 3MF anhängen (Ressourcen mit neuen IDs).
 */
object ThreeMfEdits {
    /** Name des Objekts mit dem Namen [part] in allen Modelldateien ändern. Null: nicht gefunden. */
    fun rename(bytes: ByteArray, part: String, name: String): ByteArray? = editRootModel(bytes) { doc ->
        val objects = elements(doc, "object").filter { it.getAttribute("name") == part }
        objects.forEach { it.setAttribute("name", name) }
        objects.isNotEmpty()
    }

    /**
     * Objekt [part] dem Palette-Eintrag [key] (`b|pfad|id|index` bzw. `c|…`) zuordnen: `pid`/`pindex` am Objekt setzen,
     * Farbangaben einzelner Dreiecke entfernen. Null: Objekt oder Eintrag nicht in der Hauptmodelldatei.
     */
    fun assignMaterial(bytes: ByteArray, part: String, key: String): ByteArray? {
        val (_, path, id, index) = key.split("|").takeIf { it.size == 4 } ?: return null
        if (rootModelPath(entries(bytes)) != path) return null
        return editRootModel(bytes) { doc ->
            val objects = elements(doc, "object").filter { it.getAttribute("name") == part }
            for (obj in objects) {
                obj.setAttribute("pid", id)
                obj.setAttribute("pindex", index)
                val triangles = obj.getElementsByTagNameNS("*", "triangle")
                for (t in 0 until triangles.length) {
                    val tri = triangles.item(t) as Element
                    listOf("pid", "p1", "p2", "p3").forEach { tri.removeAttribute(it) }
                }
            }
            objects.isNotEmpty()
        }
    }

    /** Build-Einträge der Objekte namens [part] entfernen. Null: kein Build-Eintrag (z. B. nur als Bauteil verwendet). */
    fun delete(bytes: ByteArray, part: String): ByteArray? = editRootModel(bytes) { doc ->
        val ids = elements(doc, "object").filter { it.getAttribute("name") == part }.map { it.getAttribute("id") }.toSet()
        val items = elements(doc, "item").filter { it.getAttribute("objectid") in ids }
        items.forEach { it.parentNode.removeChild(it) }
        items.isNotEmpty()
    }

    /**
     * Build-Einträge des Objekts [part] (bzw. alle bei null) mit [m] (3×4 zeilenweise, Spaltenvektoren) umrechnen.
     * 3MF speichert `transform` als Zeilenvektor-Matrix `m00 m01 m02 m10 … m32` (Punkt · Matrix).
     */
    fun transformPart(bytes: ByteArray, part: String?, m: DoubleArray): ByteArray? = editRootModel(bytes) { doc ->
        val ids = if (part == null) null else elements(doc, "object").filter { it.getAttribute("name") == part }.map { it.getAttribute("id") }.toSet()
        val items = elements(doc, "item").filter { ids == null || it.getAttribute("objectid") in ids }
        for (item in items) {
            val t = item.getAttribute("transform").trim().split(Regex("\\s+")).mapNotNull { it.toDoubleOrNull() }
                .takeIf { it.size == 12 } ?: listOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0)
            // Spaltenform A: Zeile r = (t[r], t[3+r], t[6+r], t[9+r])
            val a = Array(3) { r -> doubleArrayOf(t[r], t[3 + r], t[6 + r], t[9 + r]) }
            val b = Array(3) { r -> DoubleArray(4) { c -> (0 until 3).sumOf { k -> m[r * 4 + k] * a[k][c] } + if (c == 3) m[r * 4 + 3] else 0.0 } }
            val out = (0 until 4).flatMap { c -> (0 until 3).map { r -> b[r][c] } }
            item.setAttribute("transform", out.joinToString(" ") { ObjEdits.number(it) })
        }
        items.isNotEmpty()
    }

    /**
     * Ressourcen und Build-Einträge der Hauptmodelldatei von [other] anhängen; deren IDs werden hinter die höchste ID
     * von [bytes] verschoben. Null: [other] hat keine Hauptmodelldatei oder nutzt weitere Modelldateien (Produktionserweiterung).
     */
    fun merge(bytes: ByteArray, other: ByteArray): ByteArray? {
        val otherEntries = entries(other)
        val otherPath = rootModelPath(otherEntries) ?: return null
        if (otherEntries.keys.count { it.lowercase().endsWith(".model") } > 1) return null
        val otherDoc = parse(otherEntries[otherPath]!!)
        return editRootModel(bytes) { doc ->
            val resources = elements(doc, "resources").firstOrNull() ?: return@editRootModel false
            val build = elements(doc, "build").firstOrNull() ?: return@editRootModel false
            val shift = elements(doc, "*").mapNotNull { if (it.parentNode == resources) it.getAttribute("id").toIntOrNull() else null }.maxOrNull() ?: 0
            fun moved(value: String) = value.toIntOrNull()?.let { (it + shift).toString() } ?: value
            val otherResources = elements(otherDoc, "resources").firstOrNull() ?: return@editRootModel false
            for (element in elements(otherDoc, "*")) {
                for (attribute in listOf("id", "pid", "objectid")) {
                    if (element.hasAttribute(attribute) && (attribute != "id" || element.parentNode == otherResources)) {
                        element.setAttribute(attribute, moved(element.getAttribute(attribute)))
                    }
                }
            }
            var node = otherResources.firstChild
            while (node != null) {
                if (node is Element) resources.appendChild(doc.importNode(node, true))
                node = node.nextSibling
            }
            elements(otherDoc, "item").forEach { build.appendChild(doc.importNode(it, true)) }
            // Namensräume der Erweiterungen (z. B. m: für Farbgruppen) am Wurzelelement bekanntmachen
            val root = doc.documentElement
            val otherRoot = otherDoc.documentElement
            for (i in 0 until otherRoot.attributes.length) {
                val attribute = otherRoot.attributes.item(i)
                if (attribute.nodeName.startsWith("xmlns:") && !root.hasAttribute(attribute.nodeName)) {
                    root.setAttributeNS("http://www.w3.org/2000/xmlns/", attribute.nodeName, attribute.nodeValue)
                }
            }
            true
        }
    }

    private fun editRootModel(bytes: ByteArray, change: (Document) -> Boolean): ByteArray? {
        val all = entries(bytes)
        val path = rootModelPath(all) ?: return null
        val doc = parse(all[path]!!)
        if (!change(doc)) return null
        val out = ByteArrayOutputStream()
        TransformerFactory.newInstance().newTransformer().apply {
            setOutputProperty(OutputKeys.ENCODING, "UTF-8")
        }.transform(DOMSource(doc), StreamResult(out))
        return rewrite(bytes, path, out.toByteArray())
    }

    /** Hauptmodelldatei laut `_rels/.rels`, sonst die übliche `3D/3dmodel.model`. */
    private fun rootModelPath(all: Map<String, ByteArray>): String? {
        val rels = all["_rels/.rels"]?.toString(Charsets.UTF_8)
        val target = rels?.let { Regex("Target\\s*=\\s*\"/?([^\"]+\\.model)\"", RegexOption.IGNORE_CASE).find(it)?.groupValues?.get(1) }
        return target?.takeIf { it in all } ?: all.keys.firstOrNull { it.equals("3D/3dmodel.model", true) }
            ?: all.keys.firstOrNull { it.lowercase().endsWith(".model") }
    }

    private fun elements(doc: Document, localName: String): List<Element> {
        val nodes = doc.getElementsByTagNameNS("*", localName)
        return (0 until nodes.length).map { nodes.item(it) as Element }
    }

    private fun parse(bytes: ByteArray) = DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = true
        setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        isExpandEntityReferences = false
    }.newDocumentBuilder().parse(ByteArrayInputStream(bytes))

    private fun entries(bytes: ByteArray): LinkedHashMap<String, ByteArray> {
        val result = linkedMapOf<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (!entry.isDirectory) result[entry.name] = zip.readBytes()
            }
        }
        return result
    }

    private fun rewrite(bytes: ByteArray, path: String, content: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { target ->
            ZipInputStream(ByteArrayInputStream(bytes)).use { source ->
                while (true) {
                    val entry = source.nextEntry ?: break
                    val data = if (entry.name == path) content else source.readBytes()
                    target.putNextEntry(ZipEntry(entry.name).apply { if (entry.time >= 0) time = entry.time })
                    if (!entry.isDirectory) target.write(data)
                    target.closeEntry()
                }
            }
        }
        return out.toByteArray()
    }
}
