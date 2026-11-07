package app.heckit.ide.scadsphere

import app.heckit.ide.scadsphere.lang.ScadDocComments
import app.heckit.ide.scadsphere.lang.ScadFileType
import app.heckit.ide.scadsphere.psi.ScadModuleDeclaration
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/** Doku-Kommentare: BOSL2-Blöcke und `//!` aus NopSCADlib. */
class ScadDocCommentsTest : BasePlatformTestCase() {
    private val bosl = """
        // Module: cuboid()
        // Synopsis: Creates a cube with chamfering and roundovers.
        // Topics: Shapes (3D)
        // See Also: prismoid()
        // Usage: Standard Cubes
        //   cuboid(size, [anchor=]);
        // Description:
        //   Creates a cube or cuboid object,
        //   with optional chamfering.
        // Arguments:
        //   size = The size of the cube.
        //   ---
        //   chamfer = Size of chamfer.
        // Example: Simple cube.
        //   cuboid(40);

        module cuboid(size, chamfer) cube(size);
    """.trimIndent()

    fun testBoslBlock() {
        val doc = ScadDocComments.parseBlocks(bosl)["cuboid"]!!
        assertEquals("Creates a cube with chamfering and roundovers.", doc.synopsis)
        assertEquals(listOf("cuboid(size, [anchor=]);"), doc.usage)
        assertEquals("Creates a cube or cuboid object, with optional chamfering.", doc.description)
        assertEquals(listOf("size" to "The size of the cube.", "chamfer" to "Size of chamfer."), doc.arguments)
        assertEquals("prismoid()", doc.seeAlso)
    }

    fun testBoslBlockFoundForDeclaration() {
        myFixture.configureByText(ScadFileType, bosl)
        val module = PsiTreeUtil.findChildOfType(myFixture.file, ScadModuleDeclaration::class.java)!!
        assertEquals("Creates a cube with chamfering and roundovers.", ScadDocComments.of(module)?.summary)
    }

    fun testNopScadLibComment() {
        myFixture.configureByText(ScadFileType, "module rounded_square(size, r) //! Like `square()` but rounded\n{\n    square(size);\n}\n")
        val module = PsiTreeUtil.findChildOfType(myFixture.file, ScadModuleDeclaration::class.java)!!
        assertEquals("Like `square()` but rounded", ScadDocComments.of(module)?.text)
    }
}
