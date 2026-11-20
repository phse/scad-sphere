package app.heckit.ide.scadsphere

import app.heckit.ide.scadsphere.export.SplitForPrint
import app.heckit.ide.scadsphere.render.ExportOptions
import app.heckit.ide.scadsphere.render.MeshStats
import app.heckit.ide.scadsphere.render.OpenScad
import app.heckit.ide.scadsphere.render.OpenScadRender
import com.intellij.openapi.progress.EmptyProgressIndicator
import com.intellij.openapi.util.io.FileUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.io.File

/**
 * Druckgerecht teilen: die erzeugte .scad mit dem installierten OpenSCAD rechnen (ohne OpenSCAD übersprungen) - untere und
 * obere Hälfte mit Löchern, die Stifte, Customizer-Werte im Modul und der Weg über den CSG-Baum, wenn die Datei `use` enthält.
 */
class SplitForPrintTest : BasePlatformTestCase() {
    private lateinit var dir: File

    override fun setUp() {
        super.setUp()
        dir = FileUtil.createTempDirectory("scadsphere-split", null, true)
    }

    override fun tearDown() {
        try {
            FileUtil.delete(dir)
        } finally {
            super.tearDown()
        }
    }

    private fun exe(): String? = OpenScad.detect().also { if (it == null) println("OpenSCAD not found, skipping") }

    // Quader 30 x 20 x h, geteilt bei z = 20, zwei Stifte 5 mm, 10 mm lang
    private val request = SplitForPrint.Request(
        axis = 2, value = 20.0, min = doubleArrayOf(0.0, 0.0, 0.0), max = doubleArrayOf(30.0, 20.0, 40.0),
        pins = listOf(doubleArrayOf(8.0, 10.0), doubleArrayOf(22.0, 10.0)), pinD = 5.0, pinLength = 10.0,
    )

    private fun part(exe: String, file: File, part: String): MeshStats {
        val out = File(dir, "${file.nameWithoutExtension}-$part.stl")
        val result = OpenScadRender(exe).export(file, out, ExportOptions(), listOf("part=\"$part\""), EmptyProgressIndicator())
        assertTrue(result.messages.joinToString { it.text }, result.ok)
        return MeshStats.ofStl(out.readBytes())!!
    }

    /** Nachricht, wie sie der Viewer schickt (computeSplit). */
    fun testRequestFromViewer() {
        val json = """{"type":"split","axis":0,"value":30,"min":[0,0,0],"max":[60,40,50],"pins":[[7.52,4.78],[32.12,4.78]],"pinD":5,"pinLength":20}"""
        val request = SplitForPrint.Request.of(com.google.gson.JsonParser.parseString(json).asJsonObject)!!
        assertEquals(0, request.axis)
        assertEquals(30.0, request.value)
        assertEquals(listOf(32.12, 4.78), request.pins[1].toList())
        assertEquals(20.0, request.pinLength)
        assertNull(SplitForPrint.Request.of(com.google.gson.JsonParser.parseString("""{"type":"split","axis":5}""").asJsonObject))
        assertTrue(SplitForPrint.source("a.stl", "import(\"a.stl\");", request).contains("pin_positions = [[7.52, 4.78], [32.12, 4.78]];"))
    }

    fun testSplitParts() {
        val exe = exe() ?: return
        File(dir, "box.scad").writeText("h = 30;\ncube([30, 20, h]);\n")
        val split = File(dir, "box-split.scad")
        // Customizer-Wert h = 40 gilt im Modul model()
        split.writeText(SplitForPrint.source("box.scad", "include <box.scad>\nh = 40;", request))
        val hole = Math.PI * (2.5 + 0.15) * (2.5 + 0.15) * 5.5
        val lower = part(exe, split, "lower")
        assertEquals(listOf(30.0, 20.0, 20.0), lower.size.map { Math.round(it * 100) / 100.0 })
        assertEquals(30.0 * 20 * 20 - 2 * hole, lower.volume, 15.0)
        val upper = part(exe, split, "upper")
        assertEquals(20.0, upper.min[2], 1e-6)
        assertEquals(40.0, upper.max[2], 1e-6)
        assertEquals(lower.volume, upper.volume, 1.0)
        val pins = part(exe, split, "pins")
        assertEquals(10.0, pins.size[2], 1e-6)
        assertTrue("pin volume ${pins.volume}", pins.volume in 2 * 180.0..2 * 200.0)
    }

    /** Mit `use` in der eingebundenen Datei scheitert schon das Parsen - dann nimmt das Plugin den CSG-Baum. */
    fun testUseNeedsSnapshot() {
        val exe = exe() ?: return
        File(dir, "lib.scad").writeText("module block(h) cube([30, 20, h]);\n")
        val source = File(dir, "shelf.scad").apply { writeText("use <lib.scad>\nblock(40);\n") }
        val live = File(dir, "live.scad").apply { writeText(SplitForPrint.source("shelf.scad", "include <shelf.scad>", request)) }
        assertFalse(OpenScadRender(exe).export(live, File(dir, "live.ast"), ExportOptions(), emptyList(), EmptyProgressIndicator()).ok)
        assertTrue(OpenScadRender(exe).export(source, File(dir, "shelf-split.csg"), ExportOptions(), emptyList(), EmptyProgressIndicator()).ok)
        val snapshot = File(dir, "shelf-split.scad").apply { writeText(SplitForPrint.source("shelf.scad", "include <shelf-split.csg>", request)) }
        assertEquals(20.0, part(exe, snapshot, "upper").min[2], 1e-6)
    }
}
