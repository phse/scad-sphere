package app.heckit.ide.scadsphere

import app.heckit.ide.scadsphere.export.ExportFormat
import app.heckit.ide.scadsphere.render.ExportOptions
import app.heckit.ide.scadsphere.render.OpenScad
import app.heckit.ide.scadsphere.render.OpenScadRender
import app.heckit.ide.scadsphere.render.RenderMessage
import com.intellij.openapi.progress.EmptyProgressIndicator
import com.intellij.openapi.util.io.FileUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.io.File
import java.util.zip.ZipFile

/**
 * Export und Umwandeln mit dem installierten OpenSCAD (Optionen, `-D`, `import()` über stdin). Ohne OpenSCAD werden
 * die Tests übersprungen.
 */
class OpenScadExportTest : BasePlatformTestCase() {
    private lateinit var dir: File

    override fun setUp() {
        super.setUp()
        dir = FileUtil.createTempDirectory("scadsphere-export", null, true)
        File("marketplace/demo-project").copyRecursively(dir)
    }

    override fun tearDown() {
        try {
            FileUtil.delete(dir)
        } finally {
            super.tearDown()
        }
    }

    private fun exe(): String? = OpenScad.detect().also { if (it == null) println("OpenSCAD not found, skipping") }

    fun testExportWithDefinesAndBinaryStl() {
        val exe = exe() ?: return
        val features = OpenScad.features(exe)
        val source = File(dir, "phone-stand.scad")

        // -D: breiter als im Code
        val off = File(dir, "wide.off")
        val result = OpenScadRender(exe).export(source, off, ExportOptions(), listOf("width=100"), EmptyProgressIndicator())
        assertTrue(result.messages.joinToString { it.text }, result.ok)
        val maxX = off.readLines().drop(1).mapNotNull { line -> line.trim().split(Regex("\\s+")).takeIf { it.size == 3 }?.get(0)?.toDoubleOrNull() }.max()
        assertEquals(100.0, maxX, 0.01)

        if (!features.binaryStl) return
        val stl = File(dir, "stand.stl")
        val options = ExportFormat.STL.choices(features).single().options("binstl")
        assertTrue(OpenScadRender(exe).export(source, stl, options, emptyList(), EmptyProgressIndicator()).ok)
        assertFalse("binary STL expected", String(stl.readBytes().copyOf(5)) == "solid")
    }

    fun testThreeMfWithoutColors() {
        val exe = exe() ?: return
        val features = OpenScad.features(exe)
        val choice = ExportFormat.THREE_MF.choices(features).find { it.id == "3mf.colors" } ?: return println("no color option, skipping")
        val target = File(dir, "plain.3mf")
        assertTrue(OpenScadRender(exe).export(File(dir, "phone-stand.scad"), target, choice.options("none"), emptyList(), EmptyProgressIndicator()).ok)
        val model = ZipFile(target).use { zip -> zip.getInputStream(zip.getEntry("3D/3dmodel.model")).readBytes().toString(Charsets.UTF_8) }
        assertFalse("no colors expected", "displaycolor=\"#4682B4" in model)
    }

    fun testConvertStlTo3mfAndBack() {
        val exe = exe() ?: return
        val threeMf = File(dir, "coaster.3mf")
        val result = OpenScadRender(exe).convert(File(dir, "coaster.stl"), threeMf, ExportOptions(), EmptyProgressIndicator())
        assertTrue(result.messages.joinToString { it.text }, result.ok)
        assertEquals("PK", String(threeMf.readBytes().copyOf(2)))

        val obj = File(dir, "coaster.obj")
        assertTrue(OpenScadRender(exe).convert(threeMf, obj, ExportOptions(), EmptyProgressIndicator()).ok)
        assertTrue(obj.readLines().any { it.startsWith("f ") })

        // Fehlende Datei: kein Erfolg, alte Ausgabe wird nicht als Ergebnis gewertet
        val missing = OpenScadRender(exe).convert(File(dir, "missing.stl"), obj, ExportOptions(), EmptyProgressIndicator())
        assertFalse(missing.ok)
        assertFalse(obj.exists())
    }

    fun testRenderMessagesOnceAndAssertIsNotEmpty() {
        val exe = exe() ?: return
        val source = File(dir, "assert.scad").apply { writeText("echo(\"hi\");\nassert(false, \"boom\");\ncube(1);\n") }
        val result = OpenScadRender(exe).render(source, null)
        assertEquals(1, result.messages.count { it.level == RenderMessage.Level.ERROR })
        assertEquals(1, result.messages.count { it.level == RenderMessage.Level.ECHO })
        assertTrue(result.failed)
        assertFalse(result.empty)
    }

    fun testOverlayForHighlightedParts() {
        val exe = exe() ?: return
        val source = File(dir, "marked.scad").apply {
            writeText("echo(\"x\");\nmodule h() #cylinder(d = 2, h = 20, center = true);\ndifference() { cube(10, center = true); h(); }\n")
        }
        val result = OpenScadRender(exe).render(source, null)
        assertNotNull(result.overlay)
        assertEquals(1, result.messages.count { it.level == RenderMessage.Level.ECHO })
        assertNull(OpenScadRender(exe).render(File(dir, "plain.scad").apply { writeText("cube(1);") }, null).overlay)
    }
}
