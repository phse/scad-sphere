package app.heckit.ide.scadsphere

import app.heckit.ide.scadsphere.export.setTarget
import app.heckit.ide.scadsphere.lang.ScadEchoLinePainter
import app.heckit.ide.scadsphere.render.CsgOverlay
import app.heckit.ide.scadsphere.render.EchoLines
import app.heckit.ide.scadsphere.render.MeshStats
import app.heckit.ide.scadsphere.render.OpenScadRender
import app.heckit.ide.scadsphere.render.RenderMessage
import junit.framework.TestCase
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** echo-Zeilen, Aufruf an der Schreibmarke, Kennzahlen eines Netzes, Ziele der Parametersätze. */
class RenderHelpersTest : TestCase() {
    fun testEchoMarks() {
        val code = """
            w = 5; // echo("no")
            echo("a", w);
            for (i = [0:1]) { echo(i = i); }
            x = echo ( "expr" ) 5;
            echo();
            s = "echo(1)";
        """.trimIndent()
        assertEquals(
            """
            w = 5; // echo("no")
            echo("@@scadsphere:2", "a", w);
            for (i = [0:1]) { echo("@@scadsphere:3", i = i); }
            x = echo ( "@@scadsphere:4", "expr" ) 5;
            echo("@@scadsphere:5");
            s = "echo(1)";
            """.trimIndent(),
            EchoLines.instrument(code),
        )
        assertEquals("cube(1);", EchoLines.instrument("cube(1);"))
    }

    fun testEchoOutput() {
        val file = File("/project/a.scad")
        val messages = OpenScadRender.parseMessages(
            listOf("ECHO: \"@@scadsphere:3\", i = 0", "ECHO: \"@@scadsphere:5\"", "ECHO: \"from include\"", "WARNING: x, in file a.scad, line 2"), file,
        )
        assertEquals(RenderMessage(RenderMessage.Level.ECHO, "i = 0", file.path, 3), messages[0])
        assertEquals(RenderMessage(RenderMessage.Level.ECHO, "", file.path, 5), messages[1])
        assertEquals(RenderMessage(RenderMessage.Level.ECHO, "\"from include\"", null, null), messages[2])
        assertEquals(2, messages[3].line)
    }

    fun testEchoText() {
        assertEquals("a  ·  b", ScadEchoLinePainter.text(listOf("a", "b")))
        assertEquals("1  ·  2  ·  3  … (+2)", ScadEchoLinePainter.text(listOf("1", "2", "3", "4", "5")))
    }

    fun testCaretOverlay() {
        assertEquals("a();\ncolor([0.011, 0.022, 0.033]) b();", CsgOverlay.markCall("a();\nb();", 5))
        val tree = """
            difference() {
            	cube(size = [10, 10, 10], center = false);
            	multmatrix([[1, 0, 0, 2], [0, 1, 0, 2], [0, 0, 1, 2], [0, 0, 0, 1]]) {
            		color([0.011, 0.022, 0.033, 1]) {
            			cylinder(h = 20, r1 = 2, r2 = 2, center = false);
            		}
            	}
            }
            color([1, 0, 0, 1]) {
            	sphere(r = 3);
            }
        """.trimIndent()
        assertEquals(
            "multmatrix([[1, 0, 0, 2], [0, 1, 0, 2], [0, 0, 1, 2], [0, 0, 0, 1]]) {\ncylinder(h = 20, r1 = 2, r2 = 2, center = false);\n}",
            CsgOverlay.buildCaret(tree),
        )
        assertNull(CsgOverlay.buildCaret("cube(size = [1, 1, 1], center = false);"))
    }

    /** Würfel 2 × 3 × 4 ab (1, 0, 0) als binäres und als ASCII-STL. */
    fun testMeshStats() {
        val corners = listOf(
            doubleArrayOf(1.0, 0.0, 0.0), doubleArrayOf(3.0, 0.0, 0.0), doubleArrayOf(3.0, 3.0, 0.0), doubleArrayOf(1.0, 3.0, 0.0),
            doubleArrayOf(1.0, 0.0, 4.0), doubleArrayOf(3.0, 0.0, 4.0), doubleArrayOf(3.0, 3.0, 4.0), doubleArrayOf(1.0, 3.0, 4.0),
        )
        // Außen gegen den Uhrzeigersinn
        val faces = listOf(
            intArrayOf(0, 2, 1), intArrayOf(0, 3, 2), intArrayOf(4, 5, 6), intArrayOf(4, 6, 7), intArrayOf(0, 1, 5), intArrayOf(0, 5, 4),
            intArrayOf(1, 2, 6), intArrayOf(1, 6, 5), intArrayOf(2, 3, 7), intArrayOf(2, 7, 6), intArrayOf(3, 0, 4), intArrayOf(3, 4, 7),
        )
        val binary = ByteBuffer.allocate(84 + faces.size * 50).order(ByteOrder.LITTLE_ENDIAN)
        binary.position(80)
        binary.putInt(faces.size)
        val ascii = StringBuilder("solid cube\n")
        for (face in faces) {
            repeat(3) { binary.putFloat(0f) }
            ascii.append(" facet normal 0 0 0\n  outer loop\n")
            for (index in face) {
                corners[index].forEach { binary.putFloat(it.toFloat()) }
                ascii.append("   vertex ").append(corners[index].joinToString(" ")).append('\n')
            }
            binary.putShort(0)
            ascii.append("  endloop\n endfacet\n")
        }
        ascii.append("endsolid cube\n")
        for (stats in listOf(MeshStats.ofStl(binary.array())!!, MeshStats.ofStl(ascii.toString().toByteArray())!!)) {
            assertEquals(12, stats.triangles)
            assertEquals(listOf(1.0, 0.0, 0.0), stats.min.toList())
            assertEquals(listOf(2.0, 3.0, 4.0), stats.size.toList())
            assertEquals(24.0, stats.volume, 1e-6)
            assertEquals(52.0, stats.area, 1e-6)
        }
        assertNull(MeshStats.ofStl("solid empty\nendsolid empty\n".toByteArray()))
    }

    fun testSetTarget() {
        assertEquals(File("/out/box-Big one.stl"), setTarget(File("/out/box.stl"), "Big one"))
        assertEquals(File("/out/box-a_b_c.3mf"), setTarget(File("/out/box.3mf"), "a/b:c"))
    }
}
