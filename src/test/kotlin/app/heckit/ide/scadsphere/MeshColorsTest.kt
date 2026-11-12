package app.heckit.ide.scadsphere

import app.heckit.ide.scadsphere.mesh.ObjColors
import app.heckit.ide.scadsphere.mesh.ThreeMfColors
import junit.framework.TestCase

/** Farben von OBJ/MTL und 3MF lesen und zurückschreiben. */
class MeshColorsTest : TestCase() {
    private val obj = """
        # Test
        mtllib box.mtl
        g a
        usemtl holz
        v 0 0 0
        g b
        usemtl holz
        f 1 1 1
        g c
        usemtl rot
        f 1 1 1
    """.trimIndent()

    private val mtl = """
        newmtl holz
        Ka 0.2 0.2 0.2
        Kd 1.000 0.500 0.000
        d 1

        newmtl rot
        Ka 0.2 0.2 0.2
    """.trimIndent()

    fun testObjPalette() {
        assertEquals(listOf("box.mtl"), ObjColors.mtlLibraries(obj))
        val palette = ObjColors.palette(obj, mtl)
        assertEquals(listOf("holz", "rot"), palette.entries.map { it.key })
        assertEquals("#ff7f00", palette.entries[0].color)
        assertEquals(listOf("a", "b"), palette.entries[0].parts)
        assertEquals("#cccccc", palette.entries[1].color)
    }

    fun testSetKd() {
        val changed = ObjColors.setKd(mtl, "holz", "#00ff00")!!
        assertTrue(changed.contains("Kd 0.000 1.000 0.000"))
        assertFalse(changed.contains("Kd 1.000 0.500"))
        // Fehlt Kd, kommt es hinter newmtl
        val added = ObjColors.setKd(mtl, "rot", "#ff0000")!!
        assertTrue(added.contains("newmtl rot\nKd 1.000 0.000 0.000\nKa"))
        assertNull(ObjColors.setKd(mtl, "fehlt", "#ff0000"))
    }

    fun testSeparate() {
        val changed = ObjColors.setGroupMaterial(obj, "b", "b_eigen")
        assertEquals(listOf("holz"), ObjColors.groups(changed).first { it.name == "a" }.materials)
        assertEquals(listOf("b_eigen"), ObjColors.groups(changed).first { it.name == "b" }.materials)
    }

    fun testCreateMtl() {
        val plain = "# Kopf\ng links\nv 0 0 0\ng rechts\nv 1 1 1\n"
        val (newObj, newMtl) = ObjColors.createMtl(plain, "plain.mtl", "#f9d72c")
        assertEquals("# Kopf\nmtllib plain.mtl\ng links\nusemtl links\nv 0 0 0\ng rechts\nusemtl rechts\nv 1 1 1\n", newObj)
        assertEquals(listOf("links", "rechts"), ObjColors.materials(newMtl).keys.toList())
    }

    fun testThreeMf() {
        val bytes = javaClass.getResource("/mesh/colors.3mf")!!.readBytes()
        assertFalse(ThreeMfColors.isSlicerProject(bytes))
        val palette = ThreeMfColors.palette(bytes, null)
        assertEquals(listOf("Rot", "Blau", "#20a040"), palette.entries.map { it.name })
        assertEquals(listOf("#c03030", "#3050c0", "#20a040"), palette.entries.map { it.color })
        assertEquals(listOf(listOf("links"), listOf("mitte"), listOf("rechts")), palette.entries.map { it.parts })

        // Alpha bleibt, nur das eine Attribut ändert sich
        val changed = ThreeMfColors.setColor(bytes, palette.entries[0].key, "#112233")!!
        assertEquals("#112233", ThreeMfColors.palette(changed, null).entries[0].color)
        val again = ThreeMfColors.setColor(changed, palette.entries[2].key, "#445566")!!
        assertEquals(listOf("#112233", "#3050c0", "#445566"), ThreeMfColors.palette(again, null).entries.map { it.color })
        val xml = java.util.zip.ZipInputStream(again.inputStream()).use { zip ->
            generateSequence { zip.nextEntry }.first { it.name == "3D/3dmodel.model" }.let { zip.readBytes().toString(Charsets.UTF_8) }
        }
        assertTrue(xml.contains("displaycolor=\"#112233FF\""))
    }
}
