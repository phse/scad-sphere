package app.heckit.ide.scadsphere

import app.heckit.ide.scadsphere.lang.ScadDuplicateAssignmentInspection
import app.heckit.ide.scadsphere.lang.ScadFileType
import app.heckit.ide.scadsphere.lang.ScadMissingArgumentInspection
import app.heckit.ide.scadsphere.lang.ScadUnusedInspection
import app.heckit.ide.scadsphere.lang.ScadUnusedUseInspection
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/** Inspections, Quick-Fixes und Intentions. */
class ScadInspectionsTest : BasePlatformTestCase() {
    override fun setUp() {
        super.setUp()
        myFixture.enableInspections(ScadUnusedInspection(), ScadDuplicateAssignmentInspection(), ScadUnusedUseInspection(), ScadMissingArgumentInspection())
    }

    private fun check(text: String) {
        myFixture.configureByText(ScadFileType, text)
        myFixture.checkHighlighting(true, false, true)
    }

    fun testUnused() = check(
        """
        width = 10;
        module m(a, <warning descr="Parameter 'b' is never used">b</warning>, ${'$'}fn = 8, _c) {
            <warning descr="Variable 'x' is never used">x</warning> = 1;
            y = a;
            for (i = [0 : 2]) cube(y);
        }
        f = function (<warning descr="Parameter 'q' is never used">q</warning>) 1;
        z = let (<warning descr="Variable 'k' is never used">k</warning> = 2) 3;
        """.trimIndent(),
    )

    fun testDuplicate() = check(
        """
        <warning descr="'h' is assigned again in line 3: OpenSCAD uses the last value in the whole scope, also before that line">h</warning> = 1;
        cube(h);
        h = 2;
        """.trimIndent(),
    )

    fun testUnusedUse() {
        myFixture.addFileToProject("a.scad", "module pa() cube(1);")
        myFixture.addFileToProject("b.scad", "include <c.scad>")
        myFixture.addFileToProject("c.scad", "function fc() = 1;")
        myFixture.addFileToProject("d.scad", "module pd() cube(1);")
        check("use <a.scad>\nuse <b.scad>\n<warning descr=\"No module or function from 'd.scad' is used\">use <d.scad></warning>\npa();\nx = fc();")
    }

    fun testMissingArgument() = check(
        """
        module wall(w, h, d = 2) cube([w, d, h]);
        function area(w, h) = w * h;
        wall(10, 20);
        wall(h = 5, w = 1);
        <warning descr="Missing argument 'h': the parameter has no default value and becomes undef">wall</warning>(10);
        x = <warning descr="Missing arguments 'w', 'h': the parameters have no default value and become undef">area</warning>();
        """.trimIndent(),
    )

    private fun applyIntention(before: String, name: String, after: String) {
        myFixture.configureByText(ScadFileType, before)
        myFixture.launchAction(myFixture.findSingleIntention(name))
        myFixture.checkResult(after)
    }

    fun testRemoveUnusedVariable() =
        applyIntention("module m() {\n    <caret>x = 1;\n    cube(1);\n}", "Remove variable", "module m() {\n    cube(1);\n}")

    fun testRemoveUse() {
        myFixture.addFileToProject("d.scad", "module pd() cube(1);")
        applyIntention("use <d.<caret>scad>\ncube(1);", "Remove use statement", "cube(1);")
    }

    fun testCreateModule() {
        myFixture.configureByText(ScadFileType, "<caret>bracket(10, h = 2);\n")
        myFixture.launchAction(myFixture.findSingleIntention("Create module 'bracket'"))
        myFixture.checkResult("bracket(10, h = 2);\n\nmodule bracket(a, h) {\n    <caret>\n}\n")
    }

    fun testCreateFunction() {
        myFixture.configureByText(ScadFileType, "x = <caret>gap(1);")
        myFixture.launchAction(myFixture.findSingleIntention("Create function 'gap'"))
        myFixture.checkResult("x = gap(1);\n\nfunction gap(a) = <selection><caret>undef</selection>;\n")
    }

    fun testToggleHighlight() {
        applyIntention("difference() {\n    cube(10);\n    <caret>cylinder(h = 30, r = 2);\n}", "Highlight with #", "difference() {\n    cube(10);\n    #cylinder(h = 30, r = 2);\n}")
        applyIntention("#<caret>cylinder(h = 30);", "Remove # highlight", "cylinder(h = 30);")
    }

    fun testInlineVariable() = applyIntention(
        "wall = 1 + 1;\ncube([20, <caret>wall, 10]);\ny = wall * 2;\n",
        "Inline variable 'wall'",
        "cube([20, 1 + 1, 10]);\ny = (1 + 1) * 2;\n",
    )

    fun testExtractModule() = applyIntention(
        "module shelf(w) {\n    <selection>cube([w, 20, 2]);\n    translate([0, 18, 0]) cube([w, 2, 10]);</selection>\n}\n",
        "Extract module",
        "module shelf(w) {\n    part(w);\n}\n\nmodule part(w) {\n    cube([w, 20, 2]);\n    translate([0, 18, 0]) cube([w, 2, 10]);\n}\n",
    )

    /** Refactor | Extract | Method… (⌥⌘M) macht dasselbe wie die Absicht. */
    fun testExtractModuleViaExtractMethod() {
        myFixture.configureByText(ScadFileType, "module shelf(w) {\n    <selection>cube([w, 20, 2]);</selection>\n}\n")
        app.heckit.ide.scadsphere.lang.ScadExtractModuleHandler.invoke(project, myFixture.editor, myFixture.file, null)
        myFixture.checkResult("module shelf(w) {\n    part(w);\n}\n\nmodule part(w) {\n    cube([w, 20, 2]);\n}\n")
    }

    private fun applyParameterIntention(before: String, after: String) {
        myFixture.configureByText(ScadFileType, before)
        myFixture.launchAction(myFixture.findSingleIntention("Make Customizer parameter"))
        assertEquals(after, myFixture.editor.document.text)
    }

    fun testMakeParameterAfterLastParameter() = applyParameterIntention(
        "width = 40; // [10:100]\n\nmodule m() cylinder(h = <caret>12, r = 3);\nm();\n",
        "width = 40; // [10:100]\ncylinder_h = 12; // [0:50]\n\nmodule m() cylinder(h = cylinder_h, r = 3);\nm();\n",
    )

    fun testMakeParameterAtTopWithVectorAndSign() {
        applyParameterIntention(
            "// Box\ninclude <x.scad>\ncube([20, <caret>1.5, 5]);\n",
                "// Box\ninclude <x.scad>\nsize_y = 1.5; // [0:0.1:5]\n\ncube([20, size_y, 5]);\n",
        )
        applyParameterIntention(
            "translate([0, 0, -<caret>10]) cube(1);\n",
                "translate_z = -10; // [-20:20]\n\ntranslate([0, 0, translate_z]) cube(1);\n",
        )
    }

    fun testMakeParameterCaretAfterNumber() = applyParameterIntention(
        "cube([20, 5<caret>, 2]);\n",
        "size_y = 5; // [0:10]\n\ncube([20, size_y, 2]);\n",
    )

    fun testMakeParameterNotOnParameterOrNamedValue() {
        myFixture.configureByText(ScadFileType, "w = <caret>40;\ncube(w);\n")
        assertEmpty(myFixture.filterAvailableIntentions("Make Customizer parameter"))
    }

    fun testMakeParameterAvoidsShadowedName() = applyParameterIntention(
        "module m(cylinder_h) cylinder(h = <caret>12);\nm(1);\n",
        "cylinder_h2 = 12; // [0:50]\n\nmodule m(cylinder_h) cylinder(h = cylinder_h2);\nm(1);\n",
    )

    fun testSliderRange() {
        assertEquals("[0:20]", app.heckit.ide.scadsphere.lang.MakeCustomizerParameterIntention.sliderRange("10"))
        assertEquals("[0:10]", app.heckit.ide.scadsphere.lang.MakeCustomizerParameterIntention.sliderRange("0"))
        assertEquals("[0:0.01:0.5]", app.heckit.ide.scadsphere.lang.MakeCustomizerParameterIntention.sliderRange("0.25"))
        assertEquals("[-200:200]", app.heckit.ide.scadsphere.lang.MakeCustomizerParameterIntention.sliderRange("-75"))
    }
}
