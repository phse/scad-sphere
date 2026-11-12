package app.heckit.ide.scadsphere

import app.heckit.ide.scadsphere.editor.ModelFileEditor
import app.heckit.ide.scadsphere.mesh.MeshColorEditing
import app.heckit.ide.scadsphere.mesh.ObjColors
import app.heckit.ide.scadsphere.viewer.ModelViewer
import com.intellij.openapi.command.undo.UndoManager
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import javax.swing.JPanel

/** Änderungen aus der Teile-Liste lassen sich im Viewer rückgängig machen. */
class MeshUndoTest : BasePlatformTestCase() {
    private val obj = "mtllib box.mtl\ng a\nusemtl holz\nv 0 0 0\ng b\nusemtl holz\nv 1 1 1\n"
    private val mtl = "newmtl holz\nKd 1.000 0.500 0.000\n"

    fun testSeparateUndo() {
        val mtlFile = myFixture.addFileToProject("box.mtl", mtl).virtualFile
        val objFile = myFixture.addFileToProject("box.obj", obj).virtualFile
        val editor = ModelFileEditor(project, objFile)
        Disposer.register(testRootDisposable, editor)
        // Laden läuft im Hintergrund - warten, bis die MTL bekannt ist
        val deadline = System.currentTimeMillis() + 20000
        while (editor.documentReferences.size < 2 && System.currentTimeMillis() < deadline) {
            PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
            Thread.sleep(20)
        }
        assertEquals(2, editor.documentReferences.size)

        val viewer = ModelViewer(testRootDisposable)
        val palette = ObjColors.palette(obj, mtl)
        MeshColorEditing(project, objFile, viewer, JPanel(), palette).handle("separate", "holz", "b")
        assertTrue(String(objFile.contentsToByteArray()).contains("usemtl b"))
        assertTrue(FileDocumentManager.getInstance().getDocument(mtlFile)!!.text.contains("newmtl b"))

        val undo = UndoManager.getInstance(project)
        assertTrue("undo available", undo.isUndoAvailable(editor))
        undo.undo(editor)
        assertEquals(obj, String(objFile.contentsToByteArray()))
        assertEquals(mtl, FileDocumentManager.getInstance().getDocument(mtlFile)!!.text)
        assertTrue("redo available", undo.isRedoAvailable(editor))
    }
}
