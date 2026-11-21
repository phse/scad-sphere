package app.heckit.ide.scadsphere

import app.heckit.ide.scadsphere.editor.PrintFixes
import app.heckit.ide.scadsphere.editor.PrintHardware
import app.heckit.ide.scadsphere.export.SplitForPrint
import app.heckit.ide.scadsphere.render.EchoLines
import app.heckit.ide.scadsphere.render.ExportOptions
import app.heckit.ide.scadsphere.render.MeshStats
import app.heckit.ide.scadsphere.render.OpenScad
import app.heckit.ide.scadsphere.render.OpenScadRender
import app.heckit.ide.scadsphere.render.PrintAnalysis
import app.heckit.ide.scadsphere.render.RenderMessage
import com.intellij.openapi.progress.EmptyProgressIndicator
import com.intellij.openapi.util.io.FileUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.io.File
import kotlin.math.PI

/**
 * Funktionen von 1.9.0: Werte der Zuweisungen im Code, Druck-Hardware, Stützsäulen und Drucklage, Druckprüfung und Schnitt
 * ohne Viewer, Teilen mit mehreren Schnitten und anderen Verbindungen. Läufe mit OpenSCAD werden ohne OpenSCAD übersprungen.
 */
class PrintFeaturesTest : BasePlatformTestCase() {
    private lateinit var dir: File

    override fun setUp() {
        super.setUp()
        dir = FileUtil.createTempDirectory("scadsphere-print", null, true)
    }

    override fun tearDown() {
        try {
            FileUtil.delete(dir)
        } finally {
            super.tearDown()
        }
    }

    private fun exe(): String? = OpenScad.detect().also { if (it == null) println("OpenSCAD not found, skipping") }

    private fun stl(exe: String, code: String, name: String = "model", defines: List<String> = emptyList()): ByteArray {
        val source = File(dir, "$name.scad").apply { writeText(code) }
        val out = File(dir, "$name.stl")
        val result = OpenScadRender(exe).export(source, out, ExportOptions(), defines, EmptyProgressIndicator())
        assertTrue(result.messages.joinToString { it.text }, result.ok)
        return out.readBytes()
    }

    fun testValueMarks() {
        val code = """
            length = 240; // [160:10:320]
            hooks = 6;
            spacing = (length - 70) / (hooks - 1);
            v = [1, -2, 3];
            for (i = [0:1]) { inner = i * 2; }
            module m(a = 1) {
                half = a / 2;
                translate([0, 0, 1]) { deep = half + 1; }
            }
            f = function(x) x + 1;
            if (hooks > 2) w = 3;
        """.trimIndent()
        val marked = EchoLines.instrument(code, values = true)
        val lines = marked.lines()
        assertEquals("length = 240; // [160:10:320]", lines[0])
        assertEquals("spacing = (length - 70) / (hooks - 1); echo(\"@@scadsphere=3\", spacing);", lines[2])
        assertEquals("v = [1, -2, 3];", lines[3])
        assertFalse("loop bodies stay as they are", lines[4].contains("@@"))
        assertTrue(lines[6].endsWith("half = a / 2; echo(\"@@scadsphere=7\", half);"))
        assertFalse("blocks in a module stay as they are", lines[7].contains("@@"))
        assertFalse("function literals", lines[9].contains("@@"))
        assertFalse(lines[10].contains("@@"))
        assertEquals(code.lines().size, lines.size)
        // Ausgabe: eigene Stufe, nicht in der Meldungsliste
        val parsed = OpenScadRender.parseMessages(listOf("ECHO: \"@@scadsphere=3\", 34", "ECHO: \"@@scadsphere=7\", \"text\""), File("/p/a.scad"))
        assertEquals(RenderMessage(RenderMessage.Level.VALUE, "34", "/p/a.scad", 3), parsed[0])
        assertEquals("\"text\"", parsed[1].text)
    }

    fun testValuesAndEchoOnOneLine() {
        val psi = myFixture.configureByText("v.scad", "x = 1;\nsix = 2 * 3; echo(six = six);\ncube(1);\n")
        val file = psi.virtualFile
        val messages = OpenScadRender.parseMessages(listOf("ECHO: \"@@scadsphere=2\", 6", "ECHO: \"@@scadsphere:2\", six = 6"), File(file.path))
        val store = app.heckit.ide.scadsphere.render.RenderMessages.getInstance(project)
        store.update(this, file, messages, null)
        assertEquals(listOf("6"), store.values(file.path).single().values)
        assertEquals(listOf("six = 6"), store.echoes(file.path).single().values)
        store.clear(this, file)
    }

    fun testValuesRenderWithOpenScad() {
        val exe = exe() ?: return
        val file = File(dir, "rack.scad").apply { writeText("length = 240;\nhooks = 6;\nspacing = (length - 70) / (hooks - 1);\ncube(spacing);\n") }
        val result = OpenScadRender(exe).render(file, null, listOf("hooks=3"), values = true)
        val values = result.messages.filter { it.level == RenderMessage.Level.VALUE }
        assertEquals(listOf("85"), values.map { it.text })
        val both = File(dir, "both.scad").apply { writeText("x = 1;\nsix = 2 * 3; echo(six = six);\ncube(1);\n") }
        val run = OpenScadRender(exe).render(both, null, emptyList(), values = true)
        assertEquals(listOf("VALUE 2 6", "ECHO 2 six = 6"), run.messages.filter { it.level != RenderMessage.Level.WARNING }.map { "${it.level} ${it.line} ${it.text}" })
        assertEquals(3, values[0].line)
        assertNotNull(result.bytes)
    }

    fun testHardwareModulesRender() {
        val exe = exe() ?: return
        for (kind in PrintHardware.Kind.entries) {
            val choice = PrintHardware.Choice(kind, size = 3.0, length = 8.0, slot = if (kind == PrintHardware.Kind.NUT) 6.0 else 0.0)
            val call = PrintHardware.call(choice, 0.2)
            val body = if (kind == PrintHardware.Kind.SNAP) call else "difference() { translate([-10, -10, -12]) cube([20, 20, 12]); $call }"
            val mesh = MeshStats.ofStl(stl(exe, body + "\n\n" + PrintHardware.module(kind), "hw-${kind.name.lowercase()}"))!!
            assertTrue("${kind.name}: ${mesh.volume}", mesh.volume > 1)
        }
        // M3-Schraubenloch mit 0,2 Spiel: 3,4 mm
        val hole = MeshStats.ofStl(stl(exe, "difference() { translate([-5, -5, -10]) cube([10, 10, 10]); screw_hole(m = 3, length = 20, clearance = 0.2); }\n" + PrintHardware.module(PrintHardware.Kind.SCREW), "hole"))!!
        assertEquals(1000 - PI * 1.7 * 1.7 * 10, hole.volume, 3.0)
    }

    fun testTransformPrefix() {
        // 90° um X, dann 10 mm angehoben
        assertEquals("translate([0, 0, 10]) rotate([90, 0, 0]) ", PrintFixes.transformPrefix(doubleArrayOf(1.0, 0.0, 0.0, 0.0, 0.0, 0.0, -1.0, 0.0, 0.0, 1.0, 0.0, 10.0)))
        // 180° um Y
        assertEquals("rotate([0, 180, 0]) ", PrintFixes.transformPrefix(doubleArrayOf(-1.0, 0.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 0.0, -1.0, 0.0)))
        assertNull(PrintFixes.transformPrefix(doubleArrayOf(1.0, 0.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 0.0, 1.0, 0.0)))
        val s = Math.sqrt(0.5)
        // 90° um die Diagonale in der XY-Ebene: Achse und Winkel
        val prefix = PrintFixes.transformPrefix(doubleArrayOf(0.5, 0.5, s, 0.0, 0.5, 0.5, -s, 0.0, -s, s, 0.0, 0.0))!!
        assertTrue(prefix, prefix.startsWith("rotate(a = 90, v = [0.7071, 0.7071, 0])"))
        assertEquals("support_pillar(30, 5, 5, 18);\n", PrintFixes.pillarCalls(listOf(doubleArrayOf(30.0, 5.0, 5.0, 18.0))))
        assertEquals("support_pillar(30, 5, 5, 18, d = 4);\n", PrintFixes.pillarCalls(listOf(doubleArrayOf(30.0, 5.0, 5.0, 18.0, 4.0))))
    }

    fun testOrientationIsCombinedWithEarlierOne() {
        // Früherer Vorschlag vor dem Aufruf: wird erkannt und mit dem neuen zusammengefasst
        val (first, length) = PrintFixes.parsePrefix("translate([0, 0, 10]) rotate([90, 0, 0]) cube(10);")!!
        assertEquals("translate([0, 0, 10]) rotate([90, 0, 0]) ".length, length)
        assertEquals("translate([0, 0, 10]) rotate([90, 0, 0]) ", PrintFixes.transformPrefix(first))
        // Zurückdrehen ergibt keine Drehung mehr
        val back = PrintFixes.multiply(doubleArrayOf(1.0, 0.0, 0.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, -1.0, 0.0, 0.0), doubleArrayOf(1.0, 0.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 0.0, 1.0, -10.0))
        assertNull(PrintFixes.transformPrefix(PrintFixes.multiply(back, first)))
        // Zweimal 90° um X = 180° um X, eine Drehung statt zwei
        val twice = PrintFixes.multiply(doubleArrayOf(1.0, 0.0, 0.0, 0.0, 0.0, 0.0, -1.0, 0.0, 0.0, 1.0, 0.0, 0.0), first)
        assertEquals("translate([0, -10, 0]) rotate([180, 0, 0]) ", PrintFixes.transformPrefix(twice))
        // Achse und Winkel werden ebenso gelesen
        val (axisAngle, _) = PrintFixes.parsePrefix("translate([1, 2, 3]) rotate(a = 90, v = [0.7071, 0.7071, 0]) cube();")!!
        assertTrue(PrintFixes.transformPrefix(axisAngle)!!.startsWith("translate([1, 2, 3]) rotate(a = 90, v = [0.7071, 0.7071, 0])"))
        // Eigene Verschiebung ohne Drehung bleibt unangetastet
        assertNull(PrintFixes.parsePrefix("translate([0, 0, 5]) cube(10);"))
    }

    fun testIslandsAndSection() {
        val exe = exe() ?: return
        val hanging = """
            cube([40, 10, 5]);
            translate([0, 0, 5]) cube([5, 10, 30]);
            translate([0, 0, 30]) cube([40, 10, 5]);
            translate([30, 5, 18]) cylinder(d = 4, h = 12, ${'$'}fn = 24);
            // Loch quer durch den Fuß: dessen Boden ist ein Tiefpunkt, aber keine Insel
            difference() { translate([10, 0, 0]) cube([10, 10, 5]); translate([15, -1, 2.5]) rotate([-90, 0, 0]) cylinder(d = 2, h = 12, ${'$'}fn = 16); }
        """.trimIndent()
        val tris = PrintAnalysis.trianglesOfStl(stl(exe, hanging, "hanging"))
        val report = PrintAnalysis.check(tris, minWall = 0.0)
        assertEquals(1, report.islands.size)
        assertEquals(30.0, report.islands[0].x, 1e-6)
        assertEquals(18.0, report.islands[0].z, 1e-6)
        assertEquals(2.0, report.islands[0].r, 0.05)
        assertEquals(0, report.openEdges)
        // Mit der eingefügten Säule (0,2 mm Abstand) hängt der Zapfen nicht mehr frei
        val supported = hanging + "\n" + PrintFixes.PILLAR_MODULE + PrintFixes.pillarCalls(listOf(doubleArrayOf(30.0, 5.0, 5.0, 18.0, 4.0)))
        val after = PrintAnalysis.check(PrintAnalysis.trianglesOfStl(stl(exe, supported, "supported")), minWall = 0.0)
        assertEquals(0, after.islands.size)
        // Rohr: Fläche des Rings, ein Stück
        val tube = PrintAnalysis.trianglesOfStl(stl(exe, "difference() { cylinder(r = 10, h = 20, ${'$'}fn = 64); translate([0, 0, -1]) cylinder(r = 6, h = 22, ${'$'}fn = 64); }", "tube"))
        for (axis in 0..2) {
            val section = PrintAnalysis.section(tube, axis, if (axis == 2) 10.0 else 0.3)
            if (axis == 2) {
                assertEquals(PI * (100 - 36) * 0.995, section.area, 3.0)
                assertEquals(1, section.pieces())
            } else {
                assertEquals("axis $axis: two walls", 2, section.pieces())
            }
        }
        PrintAnalysis.drawSection(PrintAnalysis.section(tube, 2, 10.0), File(dir, "tube.png"), 400, "tube")
        assertTrue(File(dir, "tube.png").length() > 0)
        // Dünne Wand: 0,5 mm Platte
        val thin = PrintAnalysis.check(PrintAnalysis.trianglesOfStl(stl(exe, "cube([20, 20, 0.5]);", "plate")), minWall = 0.8)
        assertTrue(thin.thinArea > 700)
    }

    fun testSplitSeveralCutsAndConnectors() {
        val exe = exe() ?: return
        File(dir, "bar.scad").writeText("cube([90, 20, 20]);\n")
        val cuts = listOf(30.0, 60.0).map { SplitForPrint.Cut(0, it, listOf(doubleArrayOf(6.0, 10.0), doubleArrayOf(14.0, 10.0)), 4.0, 10.0) }
        val request = SplitForPrint.Request(cuts, doubleArrayOf(0.0, 0.0, 0.0), doubleArrayOf(90.0, 20.0, 20.0))
        assertEquals(listOf("1", "2", "3"), SplitForPrint.pieceNames(request))
        val split = File(dir, "bar-split.scad").apply { writeText(SplitForPrint.source("bar.scad", "include <bar.scad>", request)) }
        fun part(part: String, connector: String): MeshStats {
            val out = File(dir, "bar-$part-$connector.stl")
            val result = OpenScadRender(exe).export(split, out, ExportOptions(), listOf("part=\"$part\"", "connector=\"$connector\""), EmptyProgressIndicator())
            assertTrue(result.messages.joinToString { it.text }, result.ok)
            return MeshStats.ofStl(out.readBytes())!!
        }
        // Mittelteil: Löcher an beiden Enden
        val middle = part("2", "pins")
        assertEquals(30.0, middle.min[0], 1e-6)
        assertEquals(60.0, middle.max[0], 1e-6)
        val hole = PI * (2.0 + 0.15) * (2.0 + 0.15) * 5.5
        assertEquals(30.0 * 20 * 20 - 4 * hole, middle.volume, 15.0)
        // Zapfen: das erste Teil ragt über den Schnitt, das zweite hat nur Löcher
        val pegged = part("1", "pegs")
        assertTrue("peg sticks out: ${pegged.max[0]}", pegged.max[0] > 34.0)
        // Schwalbenschwanz: das erste Teil ragt hinein, das zweite hat die Nut
        val tail = part("1", "dovetail")
        assertTrue(tail.max[0] > 32.0)
        val filament = part("3", "filament")
        assertEquals(60.0, filament.min[0], 1e-6)
        // Nachricht des Viewers mit mehreren Schnitten
        val json = """{"type":"split","axis":0,"value":30,"min":[0,0,0],"max":[90,20,20],"connector":"dovetail","cuts":[{"axis":0,"value":30,"pins":[[6,10]],"pinD":4,"pinLength":10},{"axis":0,"value":60,"pins":[],"pinD":4,"pinLength":10}]}"""
        val parsed = SplitForPrint.Request.of(com.google.gson.JsonParser.parseString(json).asJsonObject)!!
        assertEquals(2, parsed.cuts.size)
        assertEquals(SplitForPrint.Connector.DOVETAIL, parsed.connector)
    }
}
