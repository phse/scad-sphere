package app.heckit.ide.scadsphere

import app.heckit.ide.scadsphere.lang.ScadColorProvider
import app.heckit.ide.scadsphere.lang.ScadFileType
import app.heckit.ide.scadsphere.lang.ScadInlayParameterHintsProvider
import app.heckit.ide.scadsphere.psi.ScadSymbolContributor
import com.intellij.navigation.NavigationItem
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.PsiElement
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.CommonProcessors
import com.intellij.util.indexing.FindSymbolParameters
import java.awt.Color

/** Farbe im Gutter, Parameternamen als Inlay, Live Templates, Go to Symbol. */
class ScadEditorFeaturesTest : BasePlatformTestCase() {
    private fun colorAt(text: String): Color? {
        myFixture.configureByText(ScadFileType, text)
        val leaf = myFixture.file.findElementAt(myFixture.file.text.indexOf("color"))!!
        return ScadColorProvider().getColorFrom(leaf)
    }

    fun testColorFormats() {
        assertEquals(Color(255, 0, 0), colorAt("color(\"red\") cube(1);"))
        assertEquals(Color(70, 130, 180), colorAt("color(\"SteelBlue\") cube(1);"))
        assertEquals(Color(255, 0, 0), colorAt("color(\"#f00\") cube(1);"))
        assertEquals(Color(0, 255, 0, 128), colorAt("color(\"#00ff0080\") cube(1);"))
        assertEquals(Color(255, 0, 0), colorAt("color([1, 0, 0]) cube(1);"))
        assertEquals(Color(0, 0, 255), colorAt("color(c = \"blue\") cube(1);"))
        assertNull(colorAt("color(c) cube(1);"))
        assertNull(colorAt("color([2, 0, 0]) cube(1);"))
    }

    fun testColorWriteBack() {
        assertEquals("\"#336699\"", ScadColorProvider.format("\"red\"", Color(0x33, 0x66, 0x99)))
        assertEquals("[0.2, 0.4, 0.6]", ScadColorProvider.format("[1, 0, 0]", Color(0x33, 0x66, 0x99)))
        assertEquals("[1, 0, 0, 0.5]", ScadColorProvider.format("[1, 0, 0, 1]", Color(255, 0, 0, 128)))
        myFixture.configureByText(ScadFileType, "color([1, 0, 0]) cube(1);")
        val leaf = myFixture.file.findElementAt(0)!!
        ScadColorProvider().setColorTo(leaf, Color(0, 0, 255))
        assertEquals("color([0, 0, 1]) cube(1);", myFixture.editor.document.text)
    }

    private fun hints(text: String): List<String> {
        myFixture.configureByText(ScadFileType, text)
        val provider = ScadInlayParameterHintsProvider()
        return PsiTreeUtil.collectElements(myFixture.file) { true }
            .flatMap { provider.hints(it) }
            .sortedBy { it.offset }
            .map { "${it.name}@${myFixture.file.text.substring(it.offset).take(4)}" }
    }

    fun testInlayHints() {
        assertEquals(listOf("size@[1, ", "center@true"), hints("cube([1, 2, 3], true);"))
        assertEquals(emptyList<String>(), hints("translate([1, 0, 0]) cube(1);"))
        assertEquals(listOf("w@10, ", "h@-2);"), hints("module wall(w, h) cube([w, 1, h]);\nwall(10, -2);"))
        assertEquals(listOf("h@20, "), hints("cylinder(20, r = 3);"))
        assertEquals(listOf("lo@0, y"), hints("function clamp(x, lo, hi) = x;\ny = 3;\nv = clamp(y, 0, y);"))
    }

    fun testLiveTemplate() {
        myFixture.configureByText(ScadFileType, "diff<caret>")
        myFixture.type("\t")
        assertTrue(myFixture.editor.document.text, myFixture.editor.document.text.startsWith("difference() {"))
    }

    fun testGotoSymbol() {
        myFixture.addFileToProject("lib/parts.scad", "module bracket(w) cube(w);\nfunction gap() = 1;\nmodule outer() { module inner() cube(1); }")
        myFixture.configureByText(ScadFileType, "bracket(2);")
        val contributor = ScadSymbolContributor()
        val names = CommonProcessors.CollectProcessor<String>()
        contributor.processNames(names, GlobalSearchScope.projectScope(project), null)
        assertTrue(names.results.containsAll(listOf("bracket", "gap", "outer")))
        // processAllKeys darf veraltete Schlüssel liefern - entscheidend ist, dass "inner" kein Ziel hat
        val inner = CommonProcessors.CollectProcessor<NavigationItem>()
        contributor.processElementsWithName("inner", inner, FindSymbolParameters.wrap("inner", project, false))
        assertEmpty(inner.results)
        val items = CommonProcessors.CollectProcessor<NavigationItem>()
        contributor.processElementsWithName("bracket", items, FindSymbolParameters.wrap("bracket", project, false))
        assertEquals("bracket(w)", (items.results.single() as PsiElement).let { (it as NavigationItem).presentation?.presentableText })
    }
}
