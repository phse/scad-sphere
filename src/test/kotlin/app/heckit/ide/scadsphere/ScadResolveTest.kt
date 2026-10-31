package app.heckit.ide.scadsphere

import app.heckit.ide.scadsphere.lang.ScadFileType
import app.heckit.ide.scadsphere.psi.ScadArgument
import app.heckit.ide.scadsphere.psi.ScadAssignment
import app.heckit.ide.scadsphere.psi.ScadModuleDeclaration
import app.heckit.ide.scadsphere.psi.ScadNamedElement
import app.heckit.ide.scadsphere.psi.ScadParameter
import com.intellij.psi.PsiPolyVariantReference
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/** Namensauflösung, include/use, Umbenennen, Formatieren. Das Ziel steht jeweils hinter `<caret>`. */
class ScadResolveTest : BasePlatformTestCase() {
    private fun resolveAtCaret(text: String): List<ScadNamedElement> {
        myFixture.configureByText(ScadFileType, text)
        val reference = myFixture.file.findReferenceAt(myFixture.caretOffset) ?: error("no reference at caret")
        return (reference as PsiPolyVariantReference).multiResolve(false).mapNotNull { it.element as? ScadNamedElement }
    }

    fun testModuleCall() {
        val targets = resolveAtCaret("module wall(h) cube(h);\nw<caret>all(3);")
        assertEquals(1, targets.size)
        assertTrue(targets.single() is ScadModuleDeclaration)
    }

    fun testModulesFunctionsAndVariablesAreSeparate() {
        val targets = resolveAtCaret("size = 3;\nfunction size() = 4;\nmodule size() cube(1);\nx = si<caret>ze;")
        assertTrue(targets.single() is ScadAssignment)
    }

    fun testParameterShadowsGlobal() {
        val targets = resolveAtCaret("h = 1;\nmodule m(h) cube(<caret>h);")
        assertTrue(targets.single() is ScadParameter)
    }

    fun testForVariable() {
        val targets = resolveAtCaret("for (i = [0 : 3]) translate([<caret>i, 0, 0]) cube(1);")
        assertTrue(targets.single() is ScadArgument)
    }

    fun testListComprehensionLet() {
        val targets = resolveAtCaret("p = [for (i = [0 : 3]) let (a = i * 2) <caret>a];")
        assertEquals("a", targets.single().name)
    }

    fun testNamedArgumentToParameter() {
        val targets = resolveAtCaret("module box(size, wall = 2) cube(size);\nbox(10, w<caret>all = 3);")
        assertTrue(targets.single() is ScadParameter)
    }

    fun testBlockVariableIsVisibleBeforeAssignment() {
        val targets = resolveAtCaret("module m() {\n  cube(<caret>d);\n  d = 4;\n}")
        assertTrue(targets.single() is ScadAssignment)
    }

    fun testInclude() {
        myFixture.addFileToProject("lib/parts.scad", "module part() cube(1);\nwall = 2;")
        val targets = resolveAtCaret("include <lib/parts.scad>\npa<caret>rt();")
        assertEquals("parts.scad", targets.single().containingFile.name)
    }

    fun testIncludeIsTransitive() {
        myFixture.addFileToProject("a.scad", "include <b.scad>")
        myFixture.addFileToProject("b.scad", "wall = 2;")
        assertEquals("b.scad", resolveAtCaret("include <a.scad>\nx = wa<caret>ll;").single().containingFile.name)
    }

    fun testUseSeesModulesButNotVariables() {
        myFixture.addFileToProject("lib.scad", "module part() cube(1);\nwall = 2;")
        assertEquals(1, resolveAtCaret("use <lib.scad>\npa<caret>rt();").size)
        assertEquals(0, resolveAtCaret("use <lib.scad>\nx = wa<caret>ll;").size)
    }

    fun testIncludePathResolvesToFile() {
        myFixture.addFileToProject("lib/parts.scad", "module part() cube(1);")
        myFixture.configureByText(ScadFileType, "include <lib/par<caret>ts.scad>")
        val reference = myFixture.file.findReferenceAt(myFixture.caretOffset)!!
        assertEquals("parts.scad", (reference.resolve() as com.intellij.psi.PsiFile).name)
    }

    fun testFindUsagesStaysInScadFiles() {
        myFixture.configureByText(ScadFileType, "module wa<caret>ll() cube(1);\nwall();\ntranslate([1, 0, 0]) wall();")
        val declaration = myFixture.elementAtCaret
        assertEquals(2, ReferencesSearch.search(declaration).findAll().size)
    }

    fun testRenameModuleAndNamedArgument() {
        myFixture.configureByText(ScadFileType, "module box(wa<caret>ll = 2) cube(wall);\nbox(wall = 3);")
        myFixture.renameElementAtCaret("thickness")
        myFixture.checkResult("module box(thickness = 2) cube(thickness);\nbox(thickness = 3);")
    }

    fun testCompletionOfModulesAndParameters() {
        myFixture.configureByText(ScadFileType, "module rounded_box(size, radius = 2) cube(size);\nround<caret>")
        val items = myFixture.completeBasic()?.map { it.lookupString }.orEmpty()
        // einziger Treffer wird direkt eingesetzt
        if (items.isEmpty()) myFixture.checkResult("module rounded_box(size, radius = 2) cube(size);\nrounded_box(<caret>)")
        else assertTrue(items.toString(), "rounded_box" in items)

        myFixture.configureByText(ScadFileType, "cylinder(h = 2, <caret>)")
        val parameters = myFixture.completeBasic()?.map { it.lookupString }.orEmpty()
        assertTrue(parameters.toString(), "r1 = " in parameters && "h = " !in parameters)
    }

    fun testReformat() {
        myFixture.configureByText(
            ScadFileType,
            """
            module m() {
            difference() {
            cube(3);
              translate([1, 0, 0])
            cube(1);
            }
            }
            points = [
            [0, 0],
                 [1, 0]
            ];
            """.trimIndent(),
        )
        com.intellij.openapi.command.WriteCommandAction.runWriteCommandAction(project) {
            com.intellij.psi.codeStyle.CodeStyleManager.getInstance(project).reformat(myFixture.file)
        }
        myFixture.checkResult(
            """
            module m() {
                difference() {
                    cube(3);
                    translate([1, 0, 0])
                        cube(1);
                }
            }
            points = [
                [0, 0],
                [1, 0]
            ];
            """.trimIndent(),
        )
    }
}
