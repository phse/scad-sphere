package app.heckit.ide.scadsphere

import app.heckit.ide.scadsphere.render.CsgOverlay
import junit.framework.TestCase

/** #/%-Teile aus dem CSG-Baum von OpenSCAD herauslösen. */
class CsgOverlayTest : TestCase() {
    private val tree = """
        multmatrix([[1, 0, 0, 5], [0, 1, 0, 0], [0, 0, 1, 0], [0, 0, 0, 1]]) {
        	difference() {
        		cube(size = [10, 10, 10], center = true);
        		multmatrix([[0, 0, 1, 0], [0, 1, 0, 0], [-1, 0, 0, 0], [0, 0, 0, 1]]) {
        			group() {
        #				cylinder(${'$'}fn = 0, ${'$'}fa = 12, ${'$'}fs = 2, h = 20, r1 = 2, r2 = 2, center = true);
        			}
        		}
        	}
        }
        %multmatrix([[1, 0, 0, 0], [0, 1, 0, 20], [0, 0, 1, 0], [0, 0, 0, 1]]) {
        	sphere(${'$'}fn = 0, ${'$'}fa = 12, ${'$'}fs = 2, r = 3);
        }
        color([1, 0, 0, 1]) {
        	text(text = "a{b)#", size = 3);
        }
    """.trimIndent()

    fun testParse() {
        val nodes = CsgOverlay.parse(tree)
        assertEquals(3, nodes.size)
        assertEquals("%", nodes[1].modifiers)
        assertEquals("text(text = \"a{b)#\", size = 3)", nodes[2].children!!.single().head)
    }

    fun testOverlayKeepsTransformsOnly() {
        val overlay = CsgOverlay.build(tree)!!
        val highlight = overlay.substringBefore("color([0.5")
        assertTrue(highlight.contains("[0, 0, 0, 5]") || highlight.contains("[1, 0, 0, 5]"))
        assertTrue(highlight.contains("cylinder("))
        assertFalse(highlight.contains("difference"))
        assertFalse(highlight.contains("cube"))
        assertFalse(overlay.contains("#"))
        assertTrue(overlay.substringAfter("color([0.5").contains("sphere("))
    }

    fun testHighlightInsideExtrusionKeepsExtrusion() {
        val overlay = CsgOverlay.build(
            "linear_extrude(height = 5) {\n\tsquare(size = [10, 10]);\n#\tmultmatrix([[1, 0, 0, 2], [0, 1, 0, 2], [0, 0, 1, 0], [0, 0, 0, 1]]) {\n\t\tcircle(r = 2);\n\t}\n}",
        )!!
        assertTrue(overlay.contains("linear_extrude(height = 5)"))
        assertTrue(overlay.contains("circle(r = 2)"))
        assertFalse(overlay.contains("square"))
    }

    fun testUsesTime() {
        assertTrue(CsgOverlay.usesTime("rotate([0, 0, 360 * ${'$'}t]) cube(1);"))
        assertFalse(CsgOverlay.usesTime("// ${'$'}t\necho(\"${'$'}t\"); ${'$'}tx = 1;"))
    }

    fun testNothingMarked() {
        assertNull(CsgOverlay.build("cube(size = [1, 1, 1], center = false);\ncolor(\"#ff0000\") { cube(size = 1); }"))
    }

    fun testSourceCheck() {
        assertTrue(CsgOverlay.mayHaveModifiers("difference() { cube(1); #cylinder(1); }"))
        assertTrue(CsgOverlay.mayHaveModifiers("% translate([1, 0, 0]) cube(1);"))
        assertFalse(CsgOverlay.mayHaveModifiers("color(\"#ff0000\") cube(1); // #comment\nx = 5 % 3;"))
    }
}
