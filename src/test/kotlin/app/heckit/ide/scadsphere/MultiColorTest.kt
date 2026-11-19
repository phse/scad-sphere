package app.heckit.ide.scadsphere

import app.heckit.ide.scadsphere.export.MultiColorExport
import app.heckit.ide.scadsphere.export.MultiColorRequest
import app.heckit.ide.scadsphere.export.MultiPart3mf
import app.heckit.ide.scadsphere.render.ColorParts
import app.heckit.ide.scadsphere.render.OpenScad
import com.intellij.openapi.progress.EmptyProgressIndicator
import com.intellij.openapi.util.io.FileUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.io.File
import java.util.zip.ZipFile

/** Zerlegen nach Farben (CSG-Baum), 3MF mit Teilen und Filamenten; mit installiertem OpenSCAD auch der ganze Weg. */
class MultiColorTest : BasePlatformTestCase() {
    private val red = "color([1, 0, 0, 1])"
    private val blue = "color([0, 0, 1, 1])"

    fun testSplitByColorKeepsOrderAndUncolored() {
        val csg = """
            group() {
                $red { cube(size = [10, 10, 10], center = false); }
                $blue { multmatrix([[1, 0, 0, 0], [0, 1, 0, 0], [0, 0, 1, 10], [0, 0, 0, 1]]) { cube(size = [5, 5, 1], center = false); } }
                cube(size = [1, 1, 1], center = false);
            }
        """.trimIndent()
        val parts = ColorParts.split(csg)!!
        assertEquals(listOf("ff0000", "0000ff", ColorParts.NO_COLOR), parts.map { it.color })
        assertTrue(parts[0].source.contains("size = [10, 10, 10]"))
        assertFalse(parts[0].source.contains("size = [5, 5, 1]"))
        assertTrue(parts[1].source.contains("multmatrix"))
        assertFalse(parts[2].source.contains("color("))
    }

    fun testDifferenceTakesColorOfFirstChildAndSubtractsAll() {
        val csg = "difference() {\n$red { cube(size = [10, 10, 10], center = false); }\n$blue { cylinder(h = 30, r1 = 2, r2 = 2, center = true); }\n}"
        val parts = ColorParts.split(csg)!!
        assertEquals(listOf("ff0000"), parts.map { it.color })
        assertTrue("cutter stays", parts[0].source.contains("cylinder"))
    }

    fun testOuterColorWinsAndAtomicNodesStayWhole() {
        assertEquals(listOf("ff0000"), ColorParts.split("$red { $blue { cube(size = [1, 1, 1], center = false); } }")!!.map { it.color })
        // hull über zwei Farben: als Ganzes in der ersten
        val hull = ColorParts.split("hull() {\n$red { cube(size = [1, 1, 1], center = false); }\n$blue { sphere(r = 1); }\n}")!!
        assertEquals(listOf("ff0000"), hull.map { it.color })
        assertTrue(hull[0].source.contains("sphere"))
    }

    fun testBackgroundAndDisabledPartsAreLeftOut() {
        val parts = ColorParts.split("group() {\n$red { cube(size = [1, 1, 1], center = false); }\n%$blue { sphere(r = 1); }\n}")!!
        assertEquals(listOf("ff0000"), parts.map { it.color })
        assertFalse(parts[0].source.contains("sphere"))
    }

    fun testColorOf() {
        assertEquals("4682b4", ColorParts.colorOf("color([0.27451, 0.509804, 0.705882, 1])"))
        assertNull(ColorParts.colorOf("multmatrix([[1, 0, 0, 0]])"))
    }

    fun testWriteOneObjectWithPartsAndFilaments() {
        val cube = MultiPart3mf.Mesh(
            doubleArrayOf(0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0),
            intArrayOf(0, 2, 1, 0, 1, 3, 0, 3, 2, 1, 2, 3),
        )
        val bytes = MultiPart3mf.write("box & co", listOf(MultiPart3mf.Part("body", 1, cube), MultiPart3mf.Part("label", 2, cube), MultiPart3mf.Part("empty", 3, MultiPart3mf.Mesh(DoubleArray(0), IntArray(0)))))
        val file = File.createTempFile("multi", ".3mf").apply { writeBytes(bytes); deleteOnExit() }
        ZipFile(file).use { zip ->
            val model = zip.getInputStream(zip.getEntry("3D/3dmodel.model")).readBytes().toString(Charsets.UTF_8)
            val config = zip.getInputStream(zip.getEntry("Metadata/model_settings.config")).readBytes().toString(Charsets.UTF_8)
            assertTrue(model.contains("""<component objectid="1"/>""") && model.contains("""<component objectid="2"/>"""))
            assertFalse("empty part left out", model.contains("empty"))
            assertTrue(model.contains("""<item objectid="3"/>"""))
            assertTrue(model.contains("name=\"box &amp; co\""))
            assertTrue(config.contains("""<object id="3">"""))
            assertTrue(config.contains("""<part id="2" subtype="normal_part">""") && config.contains("""<metadata key="extruder" value="2"/>"""))
        }
        // Zurückgelesen: beide Netze in einem
        val mesh = MultiPart3mf.readMesh(bytes)
        assertEquals(8, mesh.vertices.size / 3)
        assertEquals(8, mesh.triangles.size / 3)
    }

    fun testWithOpenScad() {
        val exe = OpenScad.detect() ?: return println("OpenSCAD not found, skipping")
        val dir = FileUtil.createTempDirectory("scadsphere-multicolor-test", null, true)
        try {
            val source = File(dir, "box.scad").apply {
                writeText(
                    """
                    part = "all"; // [all:All, body:Body, label:Label]
                    // Farben im Modul: nur über den CSG-Baum trennbar, nicht über die oberste Ebene
                    module box() {
                        if (part != "label") color("SteelBlue") difference() { cube([20, 20, 10]); translate([4, 4, 9.4]) cube([12, 12, 1]); }
                        if (part != "body") color("White") translate([4, 4, 9.4]) cube([12, 12, 0.6]);
                    }
                    box();
                    """.trimIndent(),
                )
            }
            val (byColor, errors) = MultiColorExport.byColor(exe, source, emptyList(), dir, EmptyProgressIndicator())
            assertTrue(errors.joinToString { it.text }, errors.isEmpty())
            assertEquals(listOf("steelblue", "white"), byColor.map { it.name })
            assertEquals(listOf(1, 2), byColor.map { it.filament })
            val request = MultiColorRequest.ByParameter("part", listOf(Triple("\"body\"", "Body", 1), Triple("\"label\"", "Label", 2)))
            val (byValue, valueErrors) = MultiColorExport.byParameter(exe, source, emptyList(), request, dir, EmptyProgressIndicator())
            assertTrue(valueErrors.joinToString { it.text }, valueErrors.isEmpty())
            assertEquals(listOf("Body", "Label"), byValue.map { it.name })
            // Jedes Teil ein geschlossener Körper: jede Kante gehört zu genau zwei Dreiecken
            for (part in byColor + byValue) assertTrue("${part.name} closed", closed(part.mesh))
            // Schleife im Modul und ein Teil ohne Farbe: drei Teile, das ohne Farbe zuletzt
            val sign = File(dir, "sign.scad").apply {
                writeText(
                    """
                    module sign() {
                        color("SteelBlue") difference() { cube([60, 20, 4]); translate([5, 5, 3.4]) cube([50, 10, 1]); }
                        color("White") for (x = [5:5:55]) translate([x - 0.4, 5, 3.4]) cube([0.8, x % 10 == 0 ? 10 : 6, 0.6]);
                    }
                    sign();
                    translate([0, 18, -6]) cube([60, 2, 6]);
                    """.trimIndent(),
                )
            }
            val (signParts, signErrors) = MultiColorExport.byColor(exe, sign, emptyList(), dir, EmptyProgressIndicator())
            assertTrue(signErrors.joinToString { it.text }, signErrors.isEmpty())
            assertEquals(listOf("steelblue", "white", MultiColorExport.colorName(ColorParts.NO_COLOR)), signParts.map { it.name })
            assertEquals(listOf(1, 2, 3), signParts.map { it.filament })
            // Zum Prüfen im Slicer (z. B. bambu-studio --export-3mf): SCADSPHERE_TEST_OUT=<ordner>
            System.getenv("SCADSPHERE_TEST_OUT")?.let { out ->
                File(out, "by-color.3mf").writeBytes(MultiPart3mf.write("box", byColor))
                File(out, "by-value.3mf").writeBytes(MultiPart3mf.write("box", byValue))
            }
        } finally {
            FileUtil.delete(dir)
        }
    }

    private fun closed(mesh: MultiPart3mf.Mesh): Boolean {
        val v = mesh.vertices
        val key = { i: Int -> "%.4f,%.4f,%.4f".format(v[i * 3], v[i * 3 + 1], v[i * 3 + 2]) }
        val edges = HashMap<Pair<String, String>, Int>()
        val t = mesh.triangles
        for (i in 0 until t.size / 3) for (k in 0 until 3) {
            val a = key(t[i * 3 + k]); val b = key(t[i * 3 + (k + 1) % 3])
            val edge = if (a < b) a to b else b to a
            edges[edge] = (edges[edge] ?: 0) + 1
        }
        return edges.isNotEmpty() && edges.values.all { it == 2 }
    }
}
