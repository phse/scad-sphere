package app.heckit.ide.scadsphere

import app.heckit.ide.scadsphere.render.CsgOverlay
import app.heckit.ide.scadsphere.render.MeshStats
import app.heckit.ide.scadsphere.render.OpenScad
import app.heckit.ide.scadsphere.render.OpenScadRender
import app.heckit.ide.scadsphere.render.RenderMessage
import com.intellij.openapi.progress.EmptyProgressIndicator
import com.intellij.openapi.util.io.FileUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.io.File
import java.util.zip.ZipInputStream

/**
 * Mit dem installierten OpenSCAD: Prüf-Lauf der MCP-Werkzeuge (Maße, Meldungen, Bilder), `echo` mit Zeile, der
 * Aufruf an der Schreibmarke und der Export eines Parametersatzes. Ohne OpenSCAD werden die Tests übersprungen.
 */
class OpenScadCheckTest : BasePlatformTestCase() {
    private lateinit var dir: File

    override fun setUp() {
        super.setUp()
        dir = FileUtil.createTempDirectory("scadsphere-check", null, true)
    }

    override fun tearDown() {
        try {
            FileUtil.delete(dir)
        } finally {
            super.tearDown()
        }
    }

    private fun exe(): String? = OpenScad.detect().also { if (it == null) println("OpenSCAD not found, skipping") }

    private val code = """
        w = 40; // [10:80]
        echo("w", w);
        difference() {
            cube([w, 20, 4]);
            translate([10, 10, -2]) cylinder(h = 12, r = 3);
        }
        for (i = [0 : 1]) echo(i = i);
    """.trimIndent()

    fun testCheck() {
        val exe = exe() ?: return
        val file = File(dir, "plate.scad").apply { writeText(code) }
        val result = OpenScadRender(exe).check(file, null, listOf("w=50"), emptyList(), listOf(OpenScadRender.View.byName("iso")!!, OpenScadRender.View.byName("top")!!), 300, File(dir, "images"))
        assertFalse(result.failed)
        val stats = result.stats!!
        assertEquals(listOf(50.0, 20.0, 4.0), stats.size.map { Math.round(it * 100) / 100.0 })
        assertTrue("volume ${stats.volume}", stats.volume in 3850.0..3900.0)
        val echoes = result.messages.filter { it.level == RenderMessage.Level.ECHO }
        assertEquals(listOf(2 to "\"w\", 50", 7 to "i = 0", 7 to "i = 1"), echoes.map { it.line to it.text })
        if (result.imageErrors.isNotEmpty()) return println("no images: ${result.imageErrors}")
        assertEquals(setOf("iso", "top"), result.images.keys)
        result.images.values.forEach { assertTrue(it.path, it.length() > 1000) }
    }

    fun testCheckSyntaxErrorAndUnsavedText() {
        val exe = exe() ?: return
        val file = File(dir, "broken.scad").apply { writeText("cube(1);") }
        val result = OpenScadRender(exe).check(file, "cube(1);\ncube([1, 2;\n", emptyList(), emptyList(), emptyList(), 300, File(dir, "images"))
        assertTrue(result.failed)
        val error = result.messages.first { it.level == RenderMessage.Level.ERROR }
        assertEquals(2, error.line)
        assertEquals(file.path, error.file)
    }

    fun testCaretOverlay() {
        val exe = exe() ?: return
        val file = File(dir, "plate.scad").apply { writeText(code) }
        // Der abgezogene Zylinder: 6 mm breit, 12 mm hoch, von z = -2 bis 10
        val overlay = OpenScadRender(exe).caretOverlay(file, CsgOverlay.markCall(code, code.indexOf("translate")))
        assertNotNull(overlay)
        val model = ZipInputStream(overlay!!.inputStream()).use { zip ->
            generateSequence { zip.nextEntry }.first { it.name.endsWith(".model") }
            zip.readBytes().decodeToString()
        }
        val zs = Regex("""z="([-\d.eE]+)"""").findAll(model).map { it.groupValues[1].toDouble() }.toList()
        assertEquals(-2.0, zs.min(), 1e-6)
        assertEquals(10.0, zs.max(), 1e-6)
        // Ein Aufruf, der nichts erzeugt (Variable davor): keine Marke im Baum
        assertNull(OpenScadRender(exe).caretOverlay(file, code))
    }

    fun testExportParameterSet() {
        val exe = exe() ?: return
        val file = File(dir, "plate.scad").apply { writeText(code) }
        val sets = File(dir, "plate.json").apply { writeText("""{"parameterSets": {"wide one": {"w": "70"}}, "fileFormatVersion": "1"}""") }
        val target = File(dir, "plate-wide.stl")
        val result = OpenScadRender(exe).export(file, target, app.heckit.ide.scadsphere.render.ExportOptions(), emptyList(), EmptyProgressIndicator(), sets to "wide one")
        assertTrue(result.messages.joinToString { it.text }, result.ok)
        assertEquals(70.0, MeshStats.ofStl(target.readBytes())!!.size[0], 0.01)
    }
}
