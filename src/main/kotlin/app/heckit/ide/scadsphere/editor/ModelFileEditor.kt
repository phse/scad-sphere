package app.heckit.ide.scadsphere.editor

import app.heckit.ide.scadsphere.ScadSphereBundle
import app.heckit.ide.scadsphere.export.ExportFormat
import app.heckit.ide.scadsphere.viewer.ModelViewer
import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DataSink
import com.intellij.openapi.actionSystem.UiDataProvider
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorPolicy
import com.intellij.openapi.fileEditor.FileEditorProvider
import com.intellij.openapi.fileEditor.FileEditorState
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.UserDataHolderBase
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileContentChangeEvent
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.beans.PropertyChangeListener
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * .stl, .3mf, .obj, .off und .amf: die 3D-Ansicht, darüber Umwandeln und „Neue .scad-Datei“. Ändert sich die Datei
 * (z. B. neu exportiert), wird sie neu geladen.
 */
class ModelFileEditor(private val file: VirtualFile) : UserDataHolderBase(), FileEditor {
    private val viewer = ModelViewer(this)
    private var loaded = false
    private val root = object : JPanel(BorderLayout()), UiDataProvider {
        override fun uiDataSnapshot(sink: DataSink) {
            sink[CommonDataKeys.VIRTUAL_FILE] = file
            sink[CommonDataKeys.VIRTUAL_FILE_ARRAY] = arrayOf(file)
        }
    }

    @Volatile
    private var disposed = false

    init {
        val toolbar = ActionManager.getInstance().createActionToolbar(
            "ScadSphereModel", ActionManager.getInstance().getAction("ScadSphere.ModelToolbar") as ActionGroup, true,
        )
        toolbar.targetComponent = root
        toolbar.component.border = JBUI.Borders.customLineBottom(JBColor.border())
        root.add(toolbar.component, BorderLayout.NORTH)
        root.add(viewer.component, BorderLayout.CENTER)
        load(keepCamera = false)
        ApplicationManager.getApplication().messageBus.connect(this).subscribe(VirtualFileManager.VFS_CHANGES, object : BulkFileListener {
            override fun after(events: List<VFileEvent>) {
                if (events.any { it is VFileContentChangeEvent && it.file == file }) load(keepCamera = true)
            }
        })
    }

    private fun load(keepCamera: Boolean) {
        ApplicationManager.getApplication().executeOnPooledThread {
            val bytes = runCatching { file.contentsToByteArray(false) }
                .onFailure { thisLogger().warn("${file.path} nicht lesbar", it) }
                .getOrNull()
            if (disposed) return@executeOnPooledThread
            if (bytes == null || bytes.isEmpty()) {
                viewer.showEmpty(ScadSphereBundle.message("viewer.emptyFile"))
            } else {
                viewer.showModel(bytes, file.extension?.lowercase() ?: "stl", keepCamera && loaded)
                loaded = true
            }
        }
    }

    override fun getComponent(): JComponent = root
    override fun getPreferredFocusedComponent(): JComponent = viewer.component
    override fun getName(): String = ScadSphereBundle.message("editor.model")
    override fun getFile(): VirtualFile = file
    override fun setState(state: FileEditorState) = Unit
    override fun isModified(): Boolean = false
    override fun isValid(): Boolean = file.isValid
    override fun addPropertyChangeListener(listener: PropertyChangeListener) = Unit
    override fun removePropertyChangeListener(listener: PropertyChangeListener) = Unit
    override fun dispose() {
        disposed = true
    }
}

class ModelFileEditorProvider : FileEditorProvider, DumbAware {
    override fun accept(project: Project, file: VirtualFile): Boolean = file.extension?.lowercase() in ExportFormat.MODEL_EXTENSIONS
    override fun acceptRequiresReadAction(): Boolean = false
    override fun createEditor(project: Project, file: VirtualFile): FileEditor = ModelFileEditor(file)
    override fun getEditorTypeId(): String = "scadsphere-model"
    override fun getPolicy(): FileEditorPolicy = FileEditorPolicy.HIDE_DEFAULT_EDITOR
}
