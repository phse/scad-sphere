package app.heckit.ide.scadsphere.editor

import app.heckit.ide.scadsphere.ScadSphereBundle
import app.heckit.ide.scadsphere.ScadSphereConfigurable
import app.heckit.ide.scadsphere.customizer.ScadCustomizer
import app.heckit.ide.scadsphere.lang.ScadFileType
import app.heckit.ide.scadsphere.render.OpenScad
import com.intellij.icons.AllIcons
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.PlatformCoreDataKeys
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileTypes.FileTypeRegistry
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.fileChooser.FileChooserFactory
import com.intellij.openapi.fileChooser.FileSaverDescriptor

internal fun AnActionEvent.splitEditor(): ScadSplitEditor? =
    getData(PlatformCoreDataKeys.FILE_EDITOR) as? ScadSplitEditor
        ?: project?.let { project -> getData(CommonDataKeys.VIRTUAL_FILE)?.let { FileEditorManager.getInstance(project).getSelectedEditor(it) } } as? ScadSplitEditor

internal fun AnActionEvent.scadFile(): VirtualFile? =
    getData(CommonDataKeys.VIRTUAL_FILE)?.takeIf { FileTypeRegistry.getInstance().isFileOfType(it, ScadFileType) }

/** Vorschau sofort neu rechnen. */
class RenderAction : DumbAwareAction(ScadSphereBundle.messagePointer("action.render"), ScadSphereBundle.messagePointer("action.render.description"), AllIcons.Actions.Refresh) {
    override fun getActionUpdateThread() = ActionUpdateThread.EDT
    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = e.splitEditor() != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        e.splitEditor()?.preview?.render()
    }
}

class StopRenderAction : DumbAwareAction(ScadSphereBundle.messagePointer("action.stop"), ScadSphereBundle.messagePointer("action.stop.description"), AllIcons.Actions.Suspend) {
    override fun getActionUpdateThread() = ActionUpdateThread.EDT
    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = e.splitEditor()?.preview?.isRendering == true
    }

    override fun actionPerformed(e: AnActionEvent) {
        e.splitEditor()?.preview?.cancel()
    }
}

/** Datei in der OpenSCAD-Anwendung öffnen (gespeichert). */
class OpenInOpenScadAction : DumbAwareAction(ScadSphereBundle.messagePointer("action.openInOpenScad"), ScadSphereBundle.messagePointer("action.openInOpenScad.description"), AllIcons.Ide.External_link_arrow) {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT
    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null && e.scadFile() != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val file = e.scadFile() ?: return
        val exe = OpenScad.executable() ?: return notFound(project)
        FileDocumentManager.getInstance().getDocument(file)?.let { FileDocumentManager.getInstance().saveDocument(it) }
        // Geänderte Customizer-Werte mitgeben, sonst zeigt die Oberfläche ein anderes Modell als die Vorschau
        val defines = ScadCustomizer.defines(project, file)
        runCatching { OpenScad.openInGui(exe, file.path, defines) }
            .onFailure { notify(project, ScadSphereBundle.message("openInOpenScad.failed", it.message ?: ""), NotificationType.ERROR) }
    }
}

const val NOTIFICATIONS = "SCAD Sphere"

/** Aktuelle Ansicht des Viewers als PNG speichern; vorgeschlagen wird `<datei>.png` daneben. */
internal fun saveViewImage(project: Project?, file: VirtualFile, png: ByteArray) {
    val descriptor = FileSaverDescriptor(ScadSphereBundle.message("image.title"), ScadSphereBundle.message("image.description"), "png")
    val target = FileChooserFactory.getInstance().createSaveFileDialog(descriptor, project)
        .save(file.parent, file.nameWithoutExtension + ".png") ?: return
    runCatching {
        target.file.writeBytes(png)
        LocalFileSystem.getInstance().refreshAndFindFileByIoFile(target.file)
    }.onFailure { error ->
        project?.let { notify(it, ScadSphereBundle.message("image.failed", error.message ?: ""), NotificationType.ERROR) }
    }
}

internal fun notify(project: Project, text: String, type: NotificationType) {
    NotificationGroupManager.getInstance().getNotificationGroup(NOTIFICATIONS).createNotification(text, type).notify(project)
}

internal fun notFound(project: Project) {
    NotificationGroupManager.getInstance().getNotificationGroup(NOTIFICATIONS)
        .createNotification(ScadSphereBundle.message("preview.notFound"), NotificationType.WARNING)
        .addAction(NotificationAction.createSimpleExpiring(ScadSphereBundle.message("preview.notFound.settings")) {
            ShowSettingsUtil.getInstance().showSettingsDialog(project, ScadSphereConfigurable::class.java)
        })
        .notify(project)
}
