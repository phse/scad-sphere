package app.heckit.ide.scadsphere.mesh

import app.heckit.ide.scadsphere.ScadSphereBundle
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.command.undo.BasicUndoableAction
import com.intellij.openapi.command.undo.UndoManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile

/** Modelldateien als Undo-Schritt schreiben - ⌘Z im Viewer findet die Schritte über die Modelldatei [owner]. */
object MeshFiles {
    fun writeUndoable(project: Project, owner: VirtualFile, target: VirtualFile, content: ByteArray) {
        val before = target.contentsToByteArray(false)
        target.setBinaryContent(content)
        UndoManager.getInstance(project).undoableActionPerformed(object : BasicUndoableAction(owner) {
            override fun undo() = ApplicationManager.getApplication().runWriteAction { target.setBinaryContent(before) }
            override fun redo() = ApplicationManager.getApplication().runWriteAction { target.setBinaryContent(content) }
        })
    }

    fun fail(project: Project, error: Throwable) {
        Logger.getInstance(MeshFiles::class.java).warn("Mesh edit failed", error)
        NotificationGroupManager.getInstance().getNotificationGroup("SCAD Sphere")
            .createNotification(ScadSphereBundle.message("parts.writeFailed", error.message ?: error.toString()), NotificationType.ERROR)
            .notify(project)
    }

    fun editable(file: VirtualFile?) = file != null && file.isWritable && file.extension?.lowercase() in setOf("obj", "3mf")
}

abstract class MeshFileAction(text: String, description: String, icon: javax.swing.Icon) :
    DumbAwareAction(ScadSphereBundle.messagePointer(text), ScadSphereBundle.messagePointer(description), icon) {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT
    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null && MeshFiles.editable(e.getData(CommonDataKeys.VIRTUAL_FILE))
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val file = e.getData(CommonDataKeys.VIRTUAL_FILE) ?: return
        runCatching { perform(project, file) }.onFailure { MeshFiles.fail(project, it) }
    }

    abstract fun perform(project: Project, file: VirtualFile)

    protected fun write(project: Project, file: VirtualFile, name: String, content: ByteArray) =
        WriteCommandAction.runWriteCommandAction(project, name, null, { MeshFiles.writeUndoable(project, file, file, content) })
}

/** Zweite Datei gleichen Formats anhängen. */
class MergeModelAction : MeshFileAction("action.merge", "action.merge.description", com.intellij.icons.AllIcons.General.Add) {
    override fun perform(project: Project, file: VirtualFile) {
        val extension = file.extension!!.lowercase()
        val descriptor = FileChooserDescriptorFactory.createSingleFileDescriptor(extension)
            .withTitle(ScadSphereBundle.message("merge.title"))
            .withDescription(ScadSphereBundle.message("merge.description", extension.uppercase()))
        val other = FileChooser.chooseFile(descriptor, project, file.parent) ?: return
        if (other == file) return
        val bytes = file.contentsToByteArray(false)
        val otherBytes = other.contentsToByteArray(false)
        if (extension == "obj") {
            val otherText = otherBytes.toString(Charsets.UTF_8)
            val merged = ObjEdits.merge(bytes.toString(Charsets.UTF_8), otherText, other.nameWithoutExtension)
            WriteCommandAction.runWriteCommandAction(project, ScadSphereBundle.message("merge.title"), null, {
                // Materialdateien der anderen OBJ mitnehmen, wenn sie in einem anderen Ordner liegt
                if (other.parent != file.parent) {
                    for (name in ObjColors.mtlLibraries(otherText)) {
                        val source = other.parent?.findFileByRelativePath(name) ?: continue
                        if (file.parent.findFileByRelativePath(name) == null && !name.contains('/')) {
                            MeshFiles.writeUndoable(project, file, file.parent.createChildData(this, name), source.contentsToByteArray(false))
                        }
                    }
                }
                MeshFiles.writeUndoable(project, file, file, merged.toByteArray(Charsets.UTF_8))
            })
        } else {
            val merged = ThreeMfEdits.merge(bytes, otherBytes) ?: error(ScadSphereBundle.message("merge.unsupported"))
            write(project, file, ScadSphereBundle.message("merge.title"), merged)
        }
    }
}
