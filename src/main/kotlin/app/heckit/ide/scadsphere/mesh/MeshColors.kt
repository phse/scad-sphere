package app.heckit.ide.scadsphere.mesh

import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Farben einer Modelldatei, wie sie die Seitenleiste „Teile“ im Viewer zeigt.
 *
 * [key] adressiert den Eintrag beim Zurückschreiben, [matchName] und [matchColor] ordnen ihm im Viewer die
 * Materialien von three.js zu (OBJ: Materialname; 3MF: Name und Farbe der `base`, bzw. bei Farbgruppen nur die Farbe).
 */
data class PaletteEntry(
    val key: String,
    val name: String,
    val color: String,
    val parts: List<String>,
    val matchName: String?,
    val matchColor: Boolean,
)

/**
 * [hint]: Hinweis über der Liste (z. B. Projekt-3MF eines Slicers). [canSeparate]: einzelnen Teilen lässt sich ein
 * eigenes Material geben (OBJ).
 */
data class Palette(val entries: List<PaletteEntry>, val hint: String? = null, val canSeparate: Boolean = false, val editable: Boolean = true, val parts: Boolean = true)

/** OBJ mit MTL: Gruppen (`g`/`o`) und ihre Materialien (`usemtl`), Farben aus `Kd`. */
object ObjColors {
    data class Group(val name: String, val materials: List<String>)

    /** Namen hinter `mtllib` (mehrere je Zeile möglich, Leerzeichen in Namen sind dann nicht eindeutig - wie three.js). */
    fun mtlLibraries(obj: String): List<String> = obj.lineSequence()
        .map { it.trim() }
        .filter { it.startsWith("mtllib ") }
        .map { it.removePrefix("mtllib").trim() }
        .filter { it.isNotEmpty() }
        .toList()

    fun groups(obj: String): List<Group> {
        val result = linkedMapOf<String, MutableList<String>>()
        var current = ""
        for (raw in obj.lineSequence()) {
            val line = raw.trim()
            when {
                line.startsWith("g ") || line.startsWith("o ") -> current = line.substring(2).trim()
                line.startsWith("usemtl ") -> {
                    val list = result.getOrPut(current) { mutableListOf() }
                    val name = line.removePrefix("usemtl").trim()
                    if (name !in list) list += name
                }
            }
        }
        return result.map { (name, materials) -> Group(name, materials) }
    }

    /** `newmtl` → Farbe `#rrggbb` aus `Kd` (fehlt sie: grau wie three.js). */
    fun materials(mtl: String): LinkedHashMap<String, String> {
        val result = linkedMapOf<String, String>()
        var current: String? = null
        for (raw in mtl.lineSequence()) {
            val line = raw.trim()
            if (line.startsWith("newmtl ")) {
                current = line.removePrefix("newmtl").trim()
                result[current] = "#cccccc"
            } else if (current != null && line.startsWith("Kd ")) {
                parseRgb(line.removePrefix("Kd"))?.let { result[current] = it }
            }
        }
        return result
    }

    fun palette(obj: String, mtl: String?): Palette {
        val groups = groups(obj)
        val materials = mtl?.let { materials(it) } ?: linkedMapOf()
        val used = groups.flatMap { it.materials }.distinct()
        val names = (materials.keys + used).distinct()
        val entries = names.map { name ->
            PaletteEntry(
                key = name,
                name = name,
                color = materials[name] ?: "#cccccc",
                parts = groups.filter { name in it.materials }.map { it.name.ifEmpty { "-" } },
                matchName = name,
                matchColor = false,
            )
        }
        return Palette(entries, canSeparate = true)
    }

    /** `Kd` des Materials [name] setzen; fehlt die Zeile, kommt sie direkt hinter `newmtl`. Ergebnis null: Material fehlt. */
    fun setKd(mtl: String, name: String, color: String): String? {
        val lines = mtl.split("\n").toMutableList()
        val start = lines.indexOfFirst { it.trim() == "newmtl $name" }
        if (start < 0) return null
        val end = (start + 1 until lines.size).firstOrNull { lines[it].trim().startsWith("newmtl ") } ?: lines.size
        val kd = "Kd ${toRgb(color)}"
        val existing = (start + 1 until end).firstOrNull { lines[it].trim().startsWith("Kd ") }
        if (existing != null) {
            val indent = lines[existing].takeWhile { it == ' ' || it == '\t' }
            val cr = if (lines[existing].endsWith("\r")) "\r" else ""
            lines[existing] = "$indent$kd$cr"
        } else {
            lines.add(start + 1, kd)
        }
        return lines.joinToString("\n")
    }

    /** Neues Material am Ende anhängen (Werte wie die übrigen Materialien typischer Exporte). */
    fun addMaterial(mtl: String, name: String, color: String): String {
        val base = if (mtl.isEmpty() || mtl.endsWith("\n\n")) mtl else if (mtl.endsWith("\n")) "$mtl\n" else "$mtl\n\n"
        return base + "newmtl $name\nKa 0.2 0.2 0.2\nKd ${toRgb(color)}\nKs 0.05 0.05 0.05\nNs 10\nd 1\nillum 2\n"
    }

    /**
     * Eigenes Material für die Gruppe [group]: alle `usemtl` innerhalb der Gruppe auf [material] umstellen.
     * Gruppen gleichen Namens kommen alle mit.
     */
    fun setGroupMaterial(obj: String, group: String, material: String): String {
        var inGroup = group.isEmpty()
        return obj.split("\n").joinToString("\n") { raw ->
            val line = raw.trim()
            when {
                line.startsWith("g ") || line.startsWith("o ") -> { inGroup = line.substring(2).trim() == group; raw }
                inGroup && line.startsWith("usemtl ") -> raw.substring(0, raw.indexOf("usemtl")) + "usemtl $material" + if (raw.endsWith("\r")) "\r" else ""
                else -> raw
            }
        }
    }

    /**
     * OBJ ohne MTL: `mtllib` an den Anfang, für jede Gruppe ein Material gleichen Namens (bzw. eins für alles).
     * Liefert neues OBJ und neue MTL.
     */
    fun createMtl(obj: String, mtlName: String, color: String): Pair<String, String> {
        val lines = obj.split("\n")
        val groupNames = lines.map { it.trim() }.filter { it.startsWith("g ") || it.startsWith("o ") }
            .map { it.substring(2).trim() }.filter { it.isNotEmpty() }.distinct()
        val out = StringBuilder()
        // Kommentare am Anfang bleiben oben
        val head = lines.takeWhile { it.trim().startsWith("#") }
        head.forEach { out.append(it).append('\n') }
        out.append("mtllib ").append(mtlName).append('\n')
        val hasUse = lines.any { it.trim().startsWith("usemtl ") }
        if (groupNames.isEmpty() && !hasUse) out.append("usemtl default\n")
        for (line in lines.drop(head.size)) {
            out.append(line).append('\n')
            val t = line.trim()
            if (!hasUse && (t.startsWith("g ") || t.startsWith("o ")) && t.length > 2) out.append("usemtl ").append(t.substring(2).trim()).append('\n')
        }
        val names = if (hasUse) groups(obj).flatMap { it.materials }.distinct() else groupNames.ifEmpty { listOf("default") }
        val mtl = names.fold("") { text, name -> addMaterial(text, name, color) }
        // split lieferte nach dem letzten Zeilenumbruch eine leere Zeile - deren Umbruch fällt weg
        return out.toString().removeSuffix("\n") to mtl
    }

    private fun parseRgb(values: String): String? {
        val parts = values.trim().split(Regex("\\s+")).mapNotNull { it.toDoubleOrNull() }
        if (parts.size < 3) return null
        return "#%02x%02x%02x".format(*parts.take(3).map { (it.coerceIn(0.0, 1.0) * 255).toInt().coerceIn(0, 255) }.toTypedArray())
    }

    private fun toRgb(color: String): String {
        val hex = color.removePrefix("#")
        return (0 until 3).joinToString(" ") { i ->
            String.format(Locale.ROOT, "%.3f", hex.substring(i * 2, i * 2 + 2).toInt(16) / 255.0)
        }
    }
}

/**
 * 3MF: `basematerials` (`displaycolor` je `base`) und Farbgruppen (`m:colorgroup`, `color` je Eintrag).
 * Geschrieben wird nur das eine Attribut im XML; alle übrigen Dateien des Archivs bleiben byte-gleich.
 */
object ThreeMfColors {
    /** Projektdateien von Bambu Studio/OrcaSlicer: Farben kommen dort aus den Filamenten des Projekts. */
    fun isSlicerProject(bytes: ByteArray): Boolean = entries(bytes).keys.any {
        it.equals("Metadata/project_settings.config", ignoreCase = true) || it.equals("Metadata/model_settings.config", ignoreCase = true)
    }

    fun palette(bytes: ByteArray, slicerHint: String?): Palette {
        val result = mutableListOf<PaletteEntry>()
        for ((path, content) in entries(bytes)) {
            if (!path.lowercase().endsWith(".model")) continue
            val doc = runCatching { parse(content) }.getOrNull() ?: continue
            // Welche Objekte nutzen welche Eigenschaft (pid + Index)?
            val users = mutableMapOf<String, MutableSet<String>>()
            val objects = doc.getElementsByTagNameNS("*", "object")
            for (i in 0 until objects.length) {
                val obj = objects.item(i) as Element
                val name = obj.getAttribute("name").ifEmpty { "#" + obj.getAttribute("id") }
                val pid = obj.getAttribute("pid")
                val pindex = obj.getAttribute("pindex").ifEmpty { "0" }
                val triangles = obj.getElementsByTagNameNS("*", "triangle")
                val refs = mutableSetOf<String>()
                for (t in 0 until triangles.length) {
                    val tri = triangles.item(t) as Element
                    val tpid = tri.getAttribute("pid").ifEmpty { pid }
                    if (tpid.isEmpty()) continue
                    for (p in listOf("p1", "p2", "p3")) {
                        val index = tri.getAttribute(p).ifEmpty { if (p == "p1") pindex else "" }
                        if (index.isNotEmpty()) refs += "$tpid:$index"
                    }
                }
                if (triangles.length > 0 && refs.isEmpty() && pid.isNotEmpty()) refs += "$pid:$pindex"
                refs.forEach { users.getOrPut(it) { linkedSetOf() } += name }
            }
            val baseGroups = doc.getElementsByTagNameNS("*", "basematerials")
            for (g in 0 until baseGroups.length) {
                val group = baseGroups.item(g) as Element
                val id = group.getAttribute("id")
                val bases = childElements(group, "base")
                bases.forEachIndexed { index, base ->
                    val name = base.getAttribute("name")
                    result += PaletteEntry(
                        key = "b|$path|$id|$index",
                        name = name.ifEmpty { "${index + 1}" },
                        color = normalize(base.getAttribute("displaycolor")),
                        parts = users["$id:$index"]?.toList().orEmpty(),
                        matchName = name,
                        matchColor = true,
                    )
                }
            }
            val colorGroups = doc.getElementsByTagNameNS("*", "colorgroup")
            for (g in 0 until colorGroups.length) {
                val group = colorGroups.item(g) as Element
                val id = group.getAttribute("id")
                childElements(group, "color").forEachIndexed { index, color ->
                    result += PaletteEntry(
                        key = "c|$path|$id|$index",
                        name = normalize(color.getAttribute("color")),
                        color = normalize(color.getAttribute("color")),
                        parts = users["$id:$index"]?.toList().orEmpty(),
                        matchName = null,
                        matchColor = true,
                    )
                }
            }
        }
        return Palette(result, hint = slicerHint)
    }

    /** Farbe des Eintrags [key] auf [color] (`#rrggbb`) setzen; ein vorhandener Alphawert bleibt. Null: nicht gefunden. */
    fun setColor(bytes: ByteArray, key: String, color: String): ByteArray? {
        val (kind, path, id, indexText) = key.split("|").takeIf { it.size == 4 } ?: return null
        val index = indexText.toIntOrNull() ?: return null
        val all = entries(bytes)
        val xml = all[path]?.toString(Charsets.UTF_8) ?: return null
        val (groupTag, itemTag, attribute) = if (kind == "b") Triple("basematerials", "base", "displaycolor") else Triple("colorgroup", "color", "color")
        val group = Regex("<(?:[\\w.-]+:)?$groupTag\\b[^>]*\\bid\\s*=\\s*[\"']${Regex.escape(id)}[\"'][^>]*>(.*?)</(?:[\\w.-]+:)?$groupTag\\s*>", RegexOption.DOT_MATCHES_ALL)
            .find(xml) ?: return null
        val body = group.groups[1]!!
        val item = Regex("<(?:[\\w.-]+:)?$itemTag\\b[^>]*>").findAll(body.value).elementAtOrNull(index) ?: return null
        val attr = Regex("\\b$attribute\\s*=\\s*([\"'])([^\"']*)\\1").find(item.value) ?: return null
        val old = attr.groupValues[2]
        val alpha = old.removePrefix("#").let { if (it.length == 8) it.substring(6) else "" }
        val replaced = item.value.replaceRange(attr.groups[2]!!.range, color.uppercase(Locale.ROOT) + alpha.uppercase(Locale.ROOT))
        val start = body.range.first + item.range.first
        val newXml = xml.replaceRange(start, start + item.value.length, replaced)
        return rewrite(bytes, path, newXml.toByteArray(Charsets.UTF_8))
    }

    private fun normalize(color: String): String {
        val hex = color.trim().removePrefix("#")
        return when (hex.length) {
            3, 4 -> "#" + hex.take(3).map { "$it$it" }.joinToString("")
            6, 8 -> "#" + hex.take(6)
            else -> "#cccccc"
        }.lowercase(Locale.ROOT)
    }

    private fun childElements(parent: Element, localName: String): List<Element> {
        val list = mutableListOf<Element>()
        var node = parent.firstChild
        while (node != null) {
            if (node is Element && (node.localName ?: node.tagName.substringAfter(':')) == localName) list += node
            node = node.nextSibling
        }
        return list
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

    /** Archiv neu schreiben, Reihenfolge und Zeitstempel der Einträge bleiben, nur [path] bekommt neuen Inhalt. */
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
