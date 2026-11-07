package app.heckit.ide.scadsphere

import app.heckit.ide.scadsphere.lang.ScadFileType
import app.heckit.ide.scadsphere.render.RenderMessage
import app.heckit.ide.scadsphere.render.RenderMessages
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/** Unbekannte Module/Funktionen, fehlende Dateien und Meldungen von OpenSCAD im Editor. */
class ScadAnnotatorTest : BasePlatformTestCase() {
    private fun check(text: String) {
        myFixture.configureByText(ScadFileType, text)
        myFixture.checkHighlighting(true, false, true)
    }

    fun testUnknownModuleAndFunction() = check(
        """
        module wall() cube(1);
        wall();
        <weak_warning descr="Unknown module 'walll'">walll</weak_warning>();
        x = <weak_warning descr="Unknown function 'f'">f</weak_warning>(2) + sin(3);
        """.trimIndent(),
    )

    fun testFunctionLiteralInVariable() = check("f = function (x) x * 2;\ny = f(2);")

    fun testBuiltinsAndChildren() = check("module m() { children(); echo(len([1])); }\nm() translate([1, 0, 0]) cube(1);")

    fun testImportedNames() {
        myFixture.addFileToProject("lib/parts.scad", "module part() cube(1);\nfunction g() = 1;")
        check("use <lib/parts.scad>\npart();\nx = g();")
    }

    fun testMissingLibrarySilencesUnknownNames() = check(
        "use <<warning descr=\"File not found: BOSL3/std.scad\">BOSL3/std.scad</warning>>\ncuboid(1);",
    )

    fun testRenderMessagesFollowEdits() {
        myFixture.configureByText(ScadFileType, "cube(1);\nsphere(2);\n")
        val path = myFixture.file.virtualFile.path
        val stamp = myFixture.editor.document.modificationStamp
        val messages = listOf(
            RenderMessage(RenderMessage.Level.WARNING, "Ignoring unknown variable 'r'", path, 2),
            RenderMessage(RenderMessage.Level.ECHO, "ignored", path, 1),
        )
        RenderMessages.getInstance(project).update(this, myFixture.file.virtualFile, messages, stamp)
        myFixture.editor.caretModel.moveToOffset(0)
        myFixture.type("\n")
        val warnings = myFixture.doHighlighting().filter { it.description?.startsWith("OpenSCAD:") == true }
        assertEquals(1, warnings.size)
        assertEquals("sphere(2);", warnings.single().text)
    }

    fun testStaleRenderIsDropped() {
        myFixture.configureByText(ScadFileType, "cube(1);\n")
        val path = myFixture.file.virtualFile.path
        val message = RenderMessage(RenderMessage.Level.ERROR, "Parser error", path, 1)
        RenderMessages.getInstance(project).update(this, myFixture.file.virtualFile, listOf(message), myFixture.editor.document.modificationStamp - 1)
        assertTrue(myFixture.doHighlighting().none { it.description?.startsWith("OpenSCAD:") == true })
    }

    fun testRenderWarningNamesCallWithoutArgument() {
        myFixture.configureByText(ScadFileType, "module wand(w, h) {\n    cube([w, 2, h]);\n}\nwand(10);\nwand(10, 5);\nwand(undefined, h = 3);\n")
        val file = myFixture.file.virtualFile
        val message = RenderMessage(RenderMessage.Level.WARNING, "Unable to convert cube(size=[10, 2, undef])", file.path, 2)
        val second = RenderMessage(RenderMessage.Level.WARNING, "Unable to convert cube(size=[12, 2, undef])", file.path, 2)
        RenderMessages.getInstance(project).update(this, file, listOf(message, second), myFixture.editor.document.modificationStamp)
        val warning = myFixture.doHighlighting().single { it.description?.startsWith("OpenSCAD:") == true }
        assertTrue(warning.description, warning.description.endsWith("(wand() without 'h': line 4) (wand() with undefined 'w': line 6)"))
    }

    fun testSecondPreviewKeepsMessages() {
        myFixture.configureByText(ScadFileType, "cube(1);\n")
        val file = myFixture.file.virtualFile
        val message = RenderMessage(RenderMessage.Level.WARNING, "Something", file.path, 1)
        val messages = RenderMessages.getInstance(project)
        val first = Any()
        val second = Any()
        messages.update(first, file, listOf(message), myFixture.editor.document.modificationStamp)
        messages.update(second, file, listOf(message), myFixture.editor.document.modificationStamp)
        messages.clear(first, file)
        assertEquals(1, messages.forFile(file.path).size)
        messages.clear(second, file)
        assertTrue(messages.forFile(file.path).isEmpty())
    }
}
