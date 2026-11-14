package app.heckit.ide.scadsphere

import app.heckit.ide.scadsphere.lang.ScadFileType
import app.heckit.ide.scadsphere.psi.ScadLibraryCatalog
import com.intellij.openapi.util.io.FileUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.io.File
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Bekannte Bibliotheken aus einem fehlenden include/use heraus installieren (ohne Netz: ZIP aus einer Datei). */
class LibraryInstallTest : BasePlatformTestCase() {
    private lateinit var dir: File

    override fun setUp() {
        super.setUp()
        dir = FileUtil.createTempDirectory("scadsphere-library", null, true)
    }

    override fun tearDown() {
        try {
            FileUtil.delete(dir)
        } finally {
            super.tearDown()
        }
    }

    private fun zip(vararg entries: Pair<String, String?>): File {
        val file = File(dir, "library.zip")
        ZipOutputStream(file.outputStream()).use { out ->
            for ((name, content) in entries) {
                out.putNextEntry(ZipEntry(name))
                content?.let { out.write(it.toByteArray()) }
                out.closeEntry()
            }
        }
        return file
    }

    fun testCatalog() {
        assertEquals("BelfrySCAD/BOSL2", ScadLibraryCatalog.forPath("BOSL2/std.scad")?.repository)
        assertEquals("https://github.com/mrWheel/YAPP_Box/archive/refs/heads/main.zip", ScadLibraryCatalog.forPath("YAPP_Box/YAPPgenerator_v3.scad")?.zipUrl)
        assertEquals("nophead/NopSCADlib", ScadLibraryCatalog.forPath("NopSCADlib/vitamins/screws.scad")?.repository)
        assertNull(ScadLibraryCatalog.forPath("BOSL2"))
        assertNull(ScadLibraryCatalog.forPath("bosl2/std.scad"))
        assertNull(ScadLibraryCatalog.forPath("lib/BOSL2/std.scad"))
        assertNull(ScadLibraryCatalog.forPath("../BOSL2/std.scad"))
        assertNull(ScadLibraryCatalog.forPath("parts/arm.scad"))
    }

    fun testInstall() {
        val archive = zip("BOSL2-master/" to null, "BOSL2-master/std.scad" to "module cuboid() {}", "BOSL2-master/sub/gears.scad" to "// gears")
        val root = File(dir, "libraries")
        val library = ScadLibraryCatalog.forPath("BOSL2/std.scad")!!
        val installed = ScadLibraryCatalog.install(library, root, null, archive.toURI().toString())
        assertEquals(File(root, "BOSL2"), installed)
        assertEquals("module cuboid() {}", File(installed, "std.scad").readText())
        assertEquals("// gears", File(installed, "sub/gears.scad").readText())
        assertEquals(listOf("BOSL2"), root.list()!!.toList())
        // Ein vorhandener Ordner wird nie überschrieben
        try {
            ScadLibraryCatalog.install(library, root, null, archive.toURI().toString())
            fail("expected an error")
        } catch (e: IOException) {
            assertTrue(e.message!!.contains("already exists"))
        }
    }

    fun testFailedDownloadLeavesNothing() {
        val root = File(dir, "libraries")
        val library = ScadLibraryCatalog.forPath("BOSL2/std.scad")!!
        try {
            ScadLibraryCatalog.install(library, root, null, File(dir, "missing.zip").toURI().toString())
            fail("expected an error")
        } catch (_: IOException) {
        }
        assertTrue(root.list().isNullOrEmpty())
    }

    fun testUnsafeArchive() {
        val archive = zip("x-master/ok.scad" to "a", "x-master/../../evil.scad" to "b")
        try {
            ScadLibraryCatalog.extract(archive, File(dir, "out"))
            fail("expected an error")
        } catch (e: IOException) {
            assertTrue(e.message!!.contains("Unsafe path"))
        }
        assertFalse(File(dir, "evil.scad").exists())
    }

    fun testQuickFixOffered() {
        myFixture.configureByText(ScadFileType, "include <Round-Anything/poly<caret>round.scad>\nuse <parts/missing.scad>\n")
        assertNotNull(myFixture.findSingleIntention("Download library 'Round-Anything' from GitHub"))
        myFixture.configureByText(ScadFileType, "use <parts/mis<caret>sing.scad>\n")
        assertTrue(myFixture.filterAvailableIntentions("Download library").isEmpty())
    }
}
