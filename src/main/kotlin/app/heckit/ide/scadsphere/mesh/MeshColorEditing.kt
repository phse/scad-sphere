package app.heckit.ide.scadsphere.mesh

import app.heckit.ide.scadsphere.ScadSphereBundle
import app.heckit.ide.scadsphere.viewer.ModelViewer
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.command.undo.BasicUndoableAction
import com.intellij.openapi.command.undo.UndoManager
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.ColorChooserService
import com.intellij.ui.ColorPickerListener
import java.awt.Color
import javax.swing.JComponent

/**
 * Klicks aus der Seitenleiste „Teile“ umsetzen: Farbe wählen und in die Datei schreiben (OBJ: `Kd` in der MTL, als
 * Undo-Schritt im Dokument; 3MF: Attribut im Archiv), einem Teil ein eigenes Material geben, fehlende MTL anlegen.
 * Die Ansicht lädt danach von selbst neu (Dokument- bzw. Dateiänderung).
 */
class MeshColorEditing(
    private val project: Project,
    private val file: VirtualFile,
    private val viewer: ModelViewer,
    private val parent: JComponent,
    private val palette: Palette?,
) {
    fun handle(action: String, key: String?, part: String?) {
        runCatching {
            when (action) {
                "color" -> key?.let { pickColor(it) }
                "separate" -> if (key != null && part != null) separate(key, part)
                "create" -> createMtl()
            }
        }.onFailure {
            com.intellij.openapi.diagnostic.Logger.getInstance(MeshColorEditing::class.java).warn("Teile-Liste: $action fehlgeschlagen", it)
            NotificationGroupManager.getInstance().getNotificationGroup("SCAD Sphere")
                .createNotification(ScadSphereBundle.message("parts.writeFailed", it.message ?: it.toString()), NotificationType.ERROR)
                .notify(project)
        }
    }

    private fun pickColor(key: String) {
        val entry = palette?.entries?.firstOrNull { it.key == key } ?: return
        val initial = Color.decode(entry.color)
        val listener = object : ColorPickerListener {
            override fun colorChanged(color: Color) = viewer.previewColor(key, hex(color))
            override fun closed(color: Color?) = Unit
        }
        val chosen = ColorChooserService.getInstance().showDialog(
            project, parent, ScadSphereBundle.message("parts.pickTitle", entry.name), initial, false, listOf(listener), false,
        )
        if (chosen == null || hex(chosen) == entry.color) {
            viewer.previewColor(key, null)
            return
        }
        writeColor(entry, hex(chosen))
    }

    private fun writeColor(entry: PaletteEntry, color: String) {
        when (file.extension?.lowercase()) {
            "obj" -> {
                val mtl = mtlFiles().firstOrNull { (_, text) -> Regex("(?m)^\\s*newmtl\\s+${Regex.escape(entry.key)}\\s*$").containsMatchIn(text) }
                if (mtl == null) {
                    // Material wird benutzt, steht aber in keiner MTL: in die erste anhängen bzw. MTL anlegen
                    val first = mtlFiles().firstOrNull()
                    if (first == null) createMtl() else editDocument(first.first) { ObjColors.addMaterial(it, entry.key, color) }
                    return
                }
                editDocument(mtl.first) { ObjColors.setKd(it, entry.key, color) ?: it }
            }
            "3mf" -> {
                val bytes = file.contentsToByteArray(false)
                val updated = ThreeMfColors.setColor(bytes, entry.key, color) ?: return
                WriteCommandAction.runWriteCommandAction(project, ScadSphereBundle.message("color.change"), null, {
                    writeUndoable(file, updated)
                })
            }
        }
    }

    /** Eigenes Material für [part]: Kopie des bisherigen (gleiche Farbe), danach lässt es sich einzeln umfärben. */
    private fun separate(key: String, part: String) {
        val entry = palette?.entries?.firstOrNull { it.key == key } ?: return
        val mtl = mtlFiles().firstOrNull() ?: return
        val existing = palette.entries.map { it.key }.toSet()
        val base = part.replace(Regex("\\s+"), "_").ifEmpty { "part" }
        val name = generateSequence(1) { it + 1 }.map { if (it == 1) base else "${base}_$it" }.first { it !in existing }
        val obj = file.contentsToByteArray(false).toString(Charsets.UTF_8)
        WriteCommandAction.runWriteCommandAction(project, ScadSphereBundle.message("parts.separate"), null, {
            val document = FileDocumentManager.getInstance().getDocument(mtl.first) ?: return@runWriteCommandAction
            document.setText(ObjColors.addMaterial(document.text, name, entry.color))
            FileDocumentManager.getInstance().saveDocument(document)
            writeUndoable(file, ObjColors.setGroupMaterial(obj, part, name).toByteArray(Charsets.UTF_8))
        })
    }

    /** OBJ ohne (vorhandene) MTL: `<name>.mtl` daneben anlegen und eintragen. */
    private fun createMtl() {
        val dir = file.parent ?: return
        val obj = file.contentsToByteArray(false).toString(Charsets.UTF_8)
        val referenced = ObjColors.mtlLibraries(obj).firstOrNull()
        WriteCommandAction.runWriteCommandAction(project, ScadSphereBundle.message("parts.create"), null, {
            if (referenced != null) {
                // mtllib steht schon drin, die Datei fehlt: mit den benutzten Materialien anlegen
                val names = ObjColors.groups(obj).flatMap { it.materials }.distinct().ifEmpty { listOf("default") }
                createUndoable(dir, referenced.substringAfterLast('/'), names.fold("") { text, n -> ObjColors.addMaterial(text, n, DEFAULT_COLOR) }.toByteArray(Charsets.UTF_8))
            } else {
                val name = file.nameWithoutExtension + ".mtl"
                val (newObj, mtl) = ObjColors.createMtl(obj, name, DEFAULT_COLOR)
                createUndoable(dir, name, mtl.toByteArray(Charsets.UTF_8))
                writeUndoable(file, newObj.toByteArray(Charsets.UTF_8))
            }
        })
    }

    /**
     * Binäre Modelldatei schreiben und als Undo-Schritt anmelden. Die Schritte hängen an der Modelldatei, ⌘Z im Viewer
     * findet sie über [app.heckit.ide.scadsphere.editor.ModelFileEditor.getDocumentReferences].
     */
    private fun writeUndoable(target: VirtualFile, content: ByteArray) {
        val before = target.contentsToByteArray(false)
        target.setBinaryContent(content)
        UndoManager.getInstance(project).undoableActionPerformed(object : BasicUndoableAction(file) {
            override fun undo() = write { target.setBinaryContent(before) }
            override fun redo() = write { target.setBinaryContent(content) }
        })
    }

    /** Neue Datei anlegen (bzw. eine vorhandene überschreiben); Undo löscht sie wieder bzw. stellt den alten Inhalt her. */
    private fun createUndoable(dir: VirtualFile, name: String, content: ByteArray) {
        val existing = dir.findChild(name)
        if (existing != null) {
            writeUndoable(existing, content)
            return
        }
        dir.createChildData(this, name).setBinaryContent(content)
        UndoManager.getInstance(project).undoableActionPerformed(object : BasicUndoableAction(file) {
            override fun undo() = write { dir.findChild(name)?.delete(this@MeshColorEditing) }
            override fun redo() = write { (dir.findChild(name) ?: dir.createChildData(this@MeshColorEditing, name)).setBinaryContent(content) }
        })
    }

    private fun write(action: () -> Unit) = ApplicationManager.getApplication().runWriteAction(action)

    private fun editDocument(target: VirtualFile, change: (String) -> String) {
        val document = FileDocumentManager.getInstance().getDocument(target) ?: return
        WriteCommandAction.runWriteCommandAction(project, ScadSphereBundle.message("color.change"), null, {
            val text = change(document.text)
            if (text != document.text) document.setText(text)
            FileDocumentManager.getInstance().saveDocument(document)
        })
    }

    /** Die MTL-Dateien der OBJ mit aktuellem Inhalt (ungespeicherter Stand aus dem Editor). */
    private fun mtlFiles(): List<Pair<VirtualFile, String>> {
        val obj = file.contentsToByteArray(false).toString(Charsets.UTF_8)
        return ObjColors.mtlLibraries(obj).mapNotNull { name ->
            val mtl = file.parent?.findFileByRelativePath(name.replace('\\', '/')) ?: return@mapNotNull null
            val text = FileDocumentManager.getInstance().getDocument(mtl)?.text ?: return@mapNotNull null
            mtl to text
        }
    }

    private fun hex(color: Color) = "#%02x%02x%02x".format(color.red, color.green, color.blue)

    private companion object {
        /** Modellfarbe des Viewers - die neue MTL sieht zunächst aus wie vorher. */
        const val DEFAULT_COLOR = "#f9d72c"
    }
}
