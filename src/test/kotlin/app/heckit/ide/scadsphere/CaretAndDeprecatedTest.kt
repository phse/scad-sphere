package app.heckit.ide.scadsphere

import app.heckit.ide.scadsphere.editor.caretCallStart
import app.heckit.ide.scadsphere.lang.ScadDeprecatedInspection
import app.heckit.ide.scadsphere.lang.ScadFileType
import app.heckit.ide.scadsphere.psi.ScadFile
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/** Aufruf an der Schreibmarke (für die Hervorhebung in der Vorschau) und veraltete Module/Argumente. */
class CaretAndDeprecatedTest : BasePlatformTestCase() {
    /** `|` markiert die Schreibmarke; erwartet wird der Text ab dem gefundenen Aufruf bis zum Zeilenende. */
    private fun call(code: String): String? {
        val offset = code.indexOf('|')
        val text = code.removeRange(offset, offset + 1)
        val file = myFixture.configureByText(ScadFileType, text) as ScadFile
        return caretCallStart(file, offset)?.let { text.substring(it).lineSequence().first() }
    }

    fun testCaretCall() {
        assertEquals("cube(5);", call("translate([1, 0, 0]) cu|be(5);"))
        assertEquals("translate([1, 0, 0]) cube(5);", call("trans|late([1, 0, 0]) cube(5);"))
        assertEquals("translate([1, 0, 0]) cube(5);", call("translate([1, |0, 0]) cube(5);"))
        assertEquals("cube(5);", call("translate([1, 0, 0]) cube(5)|;"))
        assertEquals("#cube(5);", call("#cu|be(5);"))
        assertEquals("for (i = [0:2]) cube(i);", call("f|or (i = [0:2]) cube(i);"))
        // Im Block, aber nicht im Kopf eines Aufrufs
        assertNull(call("difference() {\n  |\n  cube(5);\n}"))
        assertEquals("cylinder(h = 9, r = 1);", call("difference() {\n  cube(5);\n  cylinder(h = 9, |r = 1);\n}"))
        assertNull(call("ec|ho(1);"))
        assertNull(call("*cu|be(5);"))
        assertNull(call("!cu|be(5);"))
        assertNull(call("w = |5;"))
        assertNull(call("module m|(a) { cube(a); }"))
    }

    fun testDeprecated() {
        myFixture.enableInspections(ScadDeprecatedInspection())
        myFixture.configureByText(
            ScadFileType,
            """
            module child() { cube(1); }
            child();
            module wrap() { <warning descr="'assign()' is deprecated or no longer supported: use 'let()'">assign</warning>(a = 1) children(); }
            <warning descr="'import_stl()' is deprecated or no longer supported: use 'import()'">import_stl</warning>("a.stl");
            import(<warning descr="'filename' is deprecated or no longer supported: use 'file'">filename</warning> = "a.stl");
            polyhedron(points = [], <warning descr="'triangles' is deprecated or no longer supported: use 'faces'">triangles</warning> = []);
            <warning descr="'dxf_linear_extrude()' is deprecated or no longer supported: use 'linear_extrude()' with 'import()' as its child">dxf_linear_extrude</warning>(file = "a.dxf", height = 2);
            linear_extrude(<warning descr="'file' of 'linear_extrude()' is deprecated or no longer supported: use 'import()' as its child">file</warning> = "a.dxf", height = 2);
            """.trimIndent(),
        )
        myFixture.checkHighlighting(true, false, false)
    }

    fun testDeprecatedFix() {
        myFixture.enableInspections(ScadDeprecatedInspection())
        myFixture.configureByText(ScadFileType, "module m() { chi<caret>ld(); }\nm() cube(1);")
        myFixture.launchAction(myFixture.findSingleIntention("Replace with 'children'"))
        myFixture.checkResult("module m() { children(0); }\nm() cube(1);")
        myFixture.configureByText(ScadFileType, "polyhedron(points = [], tri<caret>angles = []);")
        myFixture.launchAction(myFixture.findSingleIntention("Replace with 'faces'"))
        myFixture.checkResult("polyhedron(points = [], faces = []);")
    }
}
