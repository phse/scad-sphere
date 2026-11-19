package app.heckit.ide.scadsphere.export

import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.xml.parsers.DocumentBuilderFactory

/**
 * 3MF für den Mehrfarbdruck: ein Objekt aus mehreren Teilen, jedes Teil mit seinem Filament.
 *
 * Aufbau (in Bambu Studio 2.09 über `--export-3mf` geprüft): je Teil ein eigenes Netz-Objekt, darüber ein Objekt, das sie
 * per `<components>` zusammenfasst - so laden Bambu Studio, OrcaSlicer und PrusaSlicer ein Objekt mit mehreren Teilen.
 * Namen und Filament je Teil stehen in `Metadata/model_settings.config`, die Bambu Studio und OrcaSlicer auch bei
 * fremden 3MF lesen. Farben (`basematerials`) bleiben bewusst weg: Bei Farben macht Bambu Studio aus einer fremden 3MF
 * eine eingefärbte Fläche und verwirft die Filamente der Teile (Plater.cpp, `standard_3mf_to_textured_meshes`).
 */
object MultiPart3mf {
    /** Dreiecksnetz in mm: Ecken als x, y, z hintereinander, Dreiecke als drei Eckennummern. */
    class Mesh(val vertices: DoubleArray, val triangles: IntArray) {
        val isEmpty: Boolean get() = triangles.isEmpty()
    }

    class Part(val name: String, val filament: Int, val mesh: Mesh)

    /** Alle Netze einer 3MF (wie OpenSCAD sie schreibt) zu einem zusammengefasst, mit Lage aus dem Build und in mm. */
    fun readMesh(bytes: ByteArray): Mesh {
        val model = entries(bytes).entries.firstOrNull { it.key.lowercase().endsWith(".model") }?.value ?: return Mesh(DoubleArray(0), IntArray(0))
        val doc = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            isExpandEntityReferences = false
        }.newDocumentBuilder().parse(ByteArrayInputStream(model))
        val scale = when (doc.documentElement.getAttribute("unit").lowercase()) {
            "micron" -> 0.001
            "centimeter" -> 10.0
            "inch" -> 25.4
            "foot" -> 304.8
            "meter" -> 1000.0
            else -> 1.0
        }
        val objects = elements(doc.documentElement, "object").associateBy { it.getAttribute("id") }
        val vertices = ArrayList<Double>()
        val triangles = ArrayList<Int>()
        // Objekt samt Bauteilen, mit der Lage der Build-Einträge (3MF: Zeilenvektor mal Matrix)
        fun add(objectId: String, transform: DoubleArray, depth: Int) {
            val element = objects[objectId] ?: return
            if (depth > 16) return
            elements(element, "mesh").firstOrNull()?.let { mesh ->
                val base = vertices.size / 3
                for (vertex in elements(mesh, "vertex")) {
                    val x = vertex.getAttribute("x").toDouble()
                    val y = vertex.getAttribute("y").toDouble()
                    val z = vertex.getAttribute("z").toDouble()
                    vertices += (x * transform[0] + y * transform[3] + z * transform[6] + transform[9]) * scale
                    vertices += (x * transform[1] + y * transform[4] + z * transform[7] + transform[10]) * scale
                    vertices += (x * transform[2] + y * transform[5] + z * transform[8] + transform[11]) * scale
                }
                for (triangle in elements(mesh, "triangle")) {
                    triangles += base + triangle.getAttribute("v1").toInt()
                    triangles += base + triangle.getAttribute("v2").toInt()
                    triangles += base + triangle.getAttribute("v3").toInt()
                }
            }
            for (component in elements(element, "component")) {
                add(component.getAttribute("objectid"), multiply(matrix(component.getAttribute("transform")), transform), depth + 1)
            }
        }
        for (item in elements(doc.documentElement, "item")) add(item.getAttribute("objectid"), matrix(item.getAttribute("transform")), 0)
        return Mesh(vertices.toDoubleArray(), triangles.toIntArray())
    }

    /** 3MF mit einem Objekt [name] aus den Teilen [parts] (leere Teile fallen weg). */
    fun write(name: String, parts: List<Part>): ByteArray {
        val used = parts.filter { !it.mesh.isEmpty }
        val model = StringBuilder()
        model.append("""<?xml version="1.0" encoding="UTF-8"?>""").append('\n')
        model.append("""<model unit="millimeter" xml:lang="en-US" xmlns="http://schemas.microsoft.com/3dmanufacturing/core/2015/02">""").append('\n')
        model.append("""  <metadata name="Application">SCAD Sphere</metadata>""").append('\n')
        model.append("  <resources>\n")
        used.forEachIndexed { index, part ->
            model.append("""    <object id="${index + 1}" type="model" name="${xml(part.name)}">""").append('\n')
            model.append("      <mesh>\n        <vertices>\n")
            val v = part.mesh.vertices
            for (i in 0 until v.size / 3) {
                model.append("""          <vertex x="${number(v[i * 3])}" y="${number(v[i * 3 + 1])}" z="${number(v[i * 3 + 2])}"/>""").append('\n')
            }
            model.append("        </vertices>\n        <triangles>\n")
            val t = part.mesh.triangles
            for (i in 0 until t.size / 3) {
                model.append("""          <triangle v1="${t[i * 3]}" v2="${t[i * 3 + 1]}" v3="${t[i * 3 + 2]}"/>""").append('\n')
            }
            model.append("        </triangles>\n      </mesh>\n    </object>\n")
        }
        val parentId = used.size + 1
        model.append("""    <object id="$parentId" type="model" name="${xml(name)}">""").append('\n').append("      <components>\n")
        used.indices.forEach { model.append("""        <component objectid="${it + 1}"/>""").append('\n') }
        model.append("      </components>\n    </object>\n  </resources>\n")
        model.append("""  <build>""").append('\n').append("""    <item objectid="$parentId"/>""").append('\n').append("  </build>\n</model>\n")

        val config = StringBuilder()
        config.append("""<?xml version="1.0" encoding="UTF-8"?>""").append('\n').append("<config>\n")
        config.append("""  <object id="$parentId">""").append('\n')
        config.append("""    <metadata key="name" value="${xml(name)}"/>""").append('\n')
        config.append("""    <metadata key="extruder" value="${used.firstOrNull()?.filament ?: 1}"/>""").append('\n')
        used.forEachIndexed { index, part ->
            config.append("""    <part id="${index + 1}" subtype="normal_part">""").append('\n')
            config.append("""      <metadata key="name" value="${xml(part.name)}"/>""").append('\n')
            config.append("""      <metadata key="extruder" value="${part.filament}"/>""").append('\n')
            config.append("    </part>\n")
        }
        config.append("  </object>\n</config>\n")

        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            fun put(path: String, text: String) {
                zip.putNextEntry(ZipEntry(path))
                zip.write(text.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
            put("[Content_Types].xml", """<?xml version="1.0" encoding="UTF-8"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
  <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
  <Default Extension="model" ContentType="application/vnd.ms-package.3dmanufacturing-3dmodel+xml"/>
  <Default Extension="config" ContentType="text/xml"/>
</Types>
""")
            put("_rels/.rels", """<?xml version="1.0" encoding="UTF-8"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
  <Relationship Target="/3D/3dmodel.model" Id="rel0" Type="http://schemas.microsoft.com/3dmanufacturing/2013/01/3dmodel"/>
</Relationships>
""")
            put("3D/3dmodel.model", model.toString())
            put("Metadata/model_settings.config", config.toString())
        }
        return out.toByteArray()
    }

    /** 3MF-Matrix (12 Zahlen, Zeilen der oberen 4×3) oder die Einheitsmatrix. */
    private fun matrix(text: String): DoubleArray {
        val values = text.trim().split(Regex("\\s+")).mapNotNull { it.toDoubleOrNull() }
        return if (values.size == 12) values.toDoubleArray() else doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0)
    }

    /** Erst [a], dann [b] (Zeilenvektor-Konvention wie in 3MF). */
    private fun multiply(a: DoubleArray, b: DoubleArray): DoubleArray {
        fun at(m: DoubleArray, r: Int, c: Int) = if (r < 3) m[r * 3 + c] else m[9 + c]
        val out = DoubleArray(12)
        for (r in 0 until 4) for (c in 0 until 3) {
            var sum = if (r == 3) at(b, 3, c) else 0.0
            for (k in 0 until 3) sum += at(a, r, k) * at(b, k, c)
            if (r < 3) out[r * 3 + c] = sum else out[9 + c] = sum
        }
        return out
    }

    private fun number(value: Double): String {
        val text = String.format(Locale.ROOT, "%.6f", value).trimEnd('0').trimEnd('.')
        return if (text == "-0") "0" else text
    }

    private fun xml(text: String) = text.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;").replace(">", "&gt;")

    private fun elements(parent: Element, localName: String): List<Element> {
        val nodes = parent.getElementsByTagNameNS("*", localName)
        return (0 until nodes.length).map { nodes.item(it) as Element }
    }

    private fun entries(bytes: ByteArray): Map<String, ByteArray> {
        val result = linkedMapOf<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (!entry.isDirectory) result[entry.name] = zip.readBytes()
            }
        }
        return result
    }
}
