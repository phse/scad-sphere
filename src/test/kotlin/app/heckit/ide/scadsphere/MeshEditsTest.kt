package app.heckit.ide.scadsphere

import app.heckit.ide.scadsphere.mesh.AmfEdits
import app.heckit.ide.scadsphere.mesh.ObjEdits
import app.heckit.ide.scadsphere.mesh.OffEdits
import app.heckit.ide.scadsphere.mesh.StlEdits
import app.heckit.ide.scadsphere.mesh.ThreeMfEdits
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream

class MeshEditsTest {
    private val obj = "mtllib a.mtl\nv 0 0 0\nv 1 0 0\nv 0 1 0\ng box\nusemtl red\nf 1 2 3\ng lid\nusemtl red\nf 3 2 1\n"

    @Test
    fun renameAndDeleteObjGroup() {
        assertTrue("g top\n" in ObjEdits.rename(obj, "lid", "top"))
        val deleted = ObjEdits.delete(obj, "lid")
        assertTrue("g lid" !in deleted)
        assertEquals(1, deleted.lines().count { it.startsWith("f ") })
        assertTrue("v 0 1 0" in deleted)
    }

    @Test
    fun transformObj() {
        val all = ObjEdits.transformPart(obj, null, doubleArrayOf(2.0, 0.0, 0.0, 10.0, 0.0, 2.0, 0.0, 0.0, 0.0, 0.0, 2.0, -1.0))
        assertTrue("v 12 0 -1" in all)
        assertTrue("v 10 2 -1" in all)
        // lid teilt alle Eckpunkte mit box: Kopien am Ende, box bleibt
        val lid = ObjEdits.transformPart(obj, "lid", doubleArrayOf(1.0, 0.0, 0.0, 5.0, 0.0, 1.0, 0.0, 0.0, 0.0, 0.0, 1.0, 0.0))
        assertTrue("v 1 0 0\n" in lid)
        assertTrue("f 1 2 3" in lid)
        assertTrue("f 6 5 4" in lid)
        assertTrue("v 6 0 0" in lid && "v 5 1 0" in lid)
        // eigenes Teil ohne geteilte Ecken: in place
        val own = "v 0 0 0\nv 1 0 0\nv 0 1 0\ng a\nf -3 -2 -1\n"
        val movedOwn = ObjEdits.transformPart(own, "a", doubleArrayOf(1.0, 0.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 0.0, 1.0, 3.0))
        assertTrue("v 0 0 3\nv 1 0 3" in movedOwn)
        assertTrue("f 1 2 3" in movedOwn)
    }

    @Test
    fun mergeObjOffsetsIndices() {
        val other = "mtllib b.mtl\nv 5 5 5\nv 6 5 5\nv 5 6 5\nvn 0 0 1\nf 1//1 2//1 -1//1\n"
        val merged = ObjEdits.merge(obj, other, "extra")
        assertTrue("mtllib a.mtl\nmtllib b.mtl" in merged)
        assertTrue("g extra\n" in merged)
        assertTrue("f 4//1 5//1 -1//1" in merged)
    }

    private val model = """<?xml version="1.0" encoding="UTF-8"?>
<model unit="millimeter" xmlns="http://schemas.microsoft.com/3dmanufacturing/core/2015/02">
 <resources>
  <object id="1" name="a" type="model"><mesh><vertices><vertex x="0" y="0" z="0"/><vertex x="1" y="0" z="0"/><vertex x="0" y="1" z="0"/></vertices><triangles><triangle v1="0" v2="1" v3="2"/></triangles></mesh></object>
  <object id="2" name="b" type="model"><mesh><vertices><vertex x="0" y="0" z="0"/><vertex x="1" y="0" z="0"/><vertex x="0" y="1" z="0"/></vertices><triangles><triangle v1="0" v2="1" v3="2"/></triangles></mesh></object>
 </resources>
 <build><item objectid="1"/><item objectid="2" transform="1 0 0 0 1 0 0 0 1 5 0 0"/></build>
</model>"""

    private fun zip(xml: String): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        java.util.zip.ZipOutputStream(out).use {
            it.putNextEntry(java.util.zip.ZipEntry("3D/3dmodel.model")); it.write(xml.toByteArray()); it.closeEntry()
        }
        return out.toByteArray()
    }

    private fun xml(bytes: ByteArray): String {
        ZipInputStream(ByteArrayInputStream(bytes)).use { z ->
            while (true) { val e = z.nextEntry ?: break; if (e.name.endsWith(".model")) return z.readBytes().toString(Charsets.UTF_8) }
        }
        error("no model")
    }

    @Test
    fun threeMfEdits() {
        val bytes = zip(model)
        assertTrue("name=\"c\"" in xml(ThreeMfEdits.rename(bytes, "b", "c")!!))
        val deleted = xml(ThreeMfEdits.delete(bytes, "b")!!)
        assertTrue("objectid=\"2\"" !in deleted)
        assertNull(ThreeMfEdits.delete(bytes, "missing"))
        val scaled = doubleArrayOf(2.0, 0.0, 0.0, 1.0, 0.0, 2.0, 0.0, 2.0, 0.0, 0.0, 2.0, 3.0)
        val moved = xml(ThreeMfEdits.transformPart(bytes, null, scaled)!!)
        assertTrue("transform=\"2 0 0 0 2 0 0 0 2 1 2 3\"" in moved)
        assertTrue("transform=\"2 0 0 0 2 0 0 0 2 11 2 3\"" in moved)
        val onlyB = xml(ThreeMfEdits.transformPart(bytes, "b", scaled)!!)
        assertTrue("<item objectid=\"1\"/>" in onlyB)
    }

    @Test
    fun threeMfAssignMaterial() {
        val xml = model.replace("<object id=\"1\" name=\"a\" type=\"model\">", "<object id=\"1\" name=\"a\" type=\"model\" pid=\"9\" pindex=\"0\">")
            .replace("<triangle v1=\"0\" v2=\"1\" v3=\"2\"/>", "<triangle v1=\"0\" v2=\"1\" v3=\"2\" pid=\"9\" p1=\"1\"/>")
        val result = xml(ThreeMfEdits.assignMaterial(zip(xml), "b", "b|3D/3dmodel.model|7|2")!!)
        assertTrue(Regex("name=\"b\"[^>]*pid=\"7\"|pid=\"7\"[^>]*name=\"b\"").containsMatchIn(result))
        assertTrue("pindex=\"2\"" in result)
        assertNull(ThreeMfEdits.assignMaterial(zip(model), "b", "b|other.model|7|2"))
    }

    @Test
    fun threeMfMergeShiftsIds() {
        val merged = xml(ThreeMfEdits.merge(zip(model), zip(model))!!)
        assertNotNull(merged)
        assertTrue("id=\"3\"" in merged && "id=\"4\"" in merged)
        assertTrue("objectid=\"4\"" in merged)
    }

    private val move = doubleArrayOf(1.0, 0.0, 0.0, 5.0, 0.0, 1.0, 0.0, 0.0, 0.0, 0.0, 1.0, 0.0)

    @Test
    fun stlBinaryAndAscii() {
        val buffer = java.nio.ByteBuffer.allocate(134).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        buffer.position(80); buffer.putInt(1)
        listOf(0f, 0f, 1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f, 0f).forEach { buffer.putFloat(it) }
        val binary = buffer.array()
        assertTrue(StlEdits.isBinary(binary))
        val moved = java.nio.ByteBuffer.wrap(StlEdits.transform(binary, move)).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        assertEquals(5f, moved.getFloat(84 + 12))
        assertEquals(1f, moved.getFloat(84 + 8))
        val ascii = "solid x\n facet normal 0 0 1\n  outer loop\n   vertex 1 2 3\n  endloop\n endfacet\nendsolid x\n"
        assertTrue("vertex 6.000000e+00 2.000000e+00 3.000000e+00" in StlEdits.transform(ascii.toByteArray(), move).toString(Charsets.UTF_8))
    }

    @Test
    fun offVertices() {
        val off = "OFF\n# comment\n2 1 0\n0 0 0\n1 1 1 255 0 0\n3 0 1 0\n"
        val moved = OffEdits.transform(off, move)
        assertTrue("5 0 0\n6 1 1 255 0 0\n3 0 1 0" in moved)
    }

    @Test
    fun amfEdits() {
        val amf = """<?xml version="1.0"?><amf><material id="7"><metadata type="name">red</metadata><color><r>1</r><g>0</g><b>0</b></color></material>
<object id="1"><metadata type="name">box</metadata><mesh><vertices><vertex><coordinates><x>1</x><y>2</y><z>3</z></coordinates></vertex></vertices><volume materialid="7"/></mesh></object>
<object id="2"><mesh><vertices/><volume/></mesh></object></amf>""".toByteArray()
        val palette = AmfEdits.palette(amf)
        assertEquals(listOf("box"), palette.entries.first { it.key == "m|7" }.parts)
        assertTrue("<x>6</x>" in String(AmfEdits.transform(amf, "box", move)!!))
        assertTrue(">lid<" in String(AmfEdits.rename(amf, "box", "lid")!!))
        assertTrue("materialid=\"7\"" in String(AmfEdits.assignMaterial(amf, "#2", "m|7")!!).substringAfter("id=\"2\""))
        assertTrue("<g>0.5020</g>" in String(AmfEdits.setColor(amf, "m|7", "#ff8000")!!))
        assertTrue("id=\"1\"" !in String(AmfEdits.delete(amf, "box")!!).substringAfter("</material>"))
    }
}
