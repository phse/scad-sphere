package app.heckit.ide.scadsphere.editor

import app.heckit.ide.scadsphere.ScadSphereBundle
import app.heckit.ide.scadsphere.export.ExportFormat
import app.heckit.ide.scadsphere.viewer.ModelViewer
import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.ActionPromoter
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.DataContext
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
import app.heckit.ide.scadsphere.mesh.MeshColorEditing
import app.heckit.ide.scadsphere.mesh.ObjColors
import app.heckit.ide.scadsphere.mesh.Palette
import app.heckit.ide.scadsphere.mesh.ThreeMfColors
import com.intellij.openapi.util.Computable
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.vfs.newvfs.events.VFileCreateEvent
import com.intellij.util.Alarm
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.IdeActions
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.command.undo.UndoManager
import com.intellij.openapi.command.undo.DocumentReference
import com.intellij.openapi.command.undo.DocumentReferenceManager
import com.intellij.openapi.command.undo.DocumentReferenceProvider
import com.intellij.openapi.vfs.LocalFileSystem
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
class ModelFileEditor(private val project: Project, private val file: VirtualFile) : UserDataHolderBase(), FileEditor, DocumentReferenceProvider {
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
        viewer.onImage = { saveViewImage(project, file, it) }
        viewer.fileKey = file.path
        viewer.onUndo = { redo -> undoRedo(redo) }
        // ⌘Z/⇧⌘Z: Kürzel der IDE-Aktionen Undo/Redo direkt am Browser anmelden, der Promoter gibt ihnen Vorrang vor
        // „Rückgängig“ der IDE bzw. des Browsers (wie die Kürzel im LatherIt-Plugin)
        for ((redo, id) in listOf(false to IdeActions.ACTION_UNDO, true to IdeActions.ACTION_REDO)) {
            ViewerUndoAction { undoRedo(redo) }
                .registerCustomShortcutSet(ActionManager.getInstance().getAction(id).shortcutSet, viewer.component, this)
        }
        viewer.onPalette = { action, key, part -> MeshColorEditing(project, file, viewer, root, currentPalette).handle(action, key, part) }
        load(keepCamera = false)
        ApplicationManager.getApplication().messageBus.connect(this).subscribe(VirtualFileManager.VFS_CHANGES, object : BulkFileListener {
            override fun after(events: List<VFileEvent>) {
                // Die Datei selbst oder eine ihrer Materialdateien (auch neu angelegt)
                val related = events.any { event ->
                    event.file == file || (event is VFileContentChangeEvent || event is VFileCreateEvent) &&
                        (event.file?.let { isMaterialFile(it) } == true || event is VFileCreateEvent && event.childName.endsWith(".mtl", true))
                }
                if (related) reload()
            }
        })
        // Tippen in der .mtl: Ansicht folgt, ohne zu speichern
        EditorFactory.getInstance().eventMulticaster.addDocumentListener(object : DocumentListener {
            override fun documentChanged(event: DocumentEvent) {
                val changed = FileDocumentManager.getInstance().getFile(event.document) ?: return
                if (isMaterialFile(changed)) reload()
            }
        }, this)
    }

    /** Materialdateien der OBJ (beim letzten Laden ermittelt). */
    @Volatile
    private var materialFiles: Set<String> = emptySet()

    @Volatile
    private var currentPalette: Palette? = null

    private val reloadAlarm = Alarm(Alarm.ThreadToUse.POOLED_THREAD, this)

    private fun isMaterialFile(candidate: VirtualFile) = candidate.path in materialFiles

    private fun reload() {
        reloadAlarm.cancelAllRequests()
        reloadAlarm.addRequest({ load(keepCamera = true) }, 150)
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
                val format = file.extension?.lowercase() ?: "stl"
                when (format) {
                    "obj" -> loadObj(bytes, keepCamera)
                    "3mf" -> {
                        viewer.showModel(bytes, format, keepCamera && loaded)
                        val hint = runCatching { ThreeMfColors.isSlicerProject(bytes) }.getOrDefault(false)
                        val palette = runCatching {
                            ThreeMfColors.palette(bytes, if (hint) ScadSphereBundle.message("parts.slicerProject") else null)
                        }.onFailure { thisLogger().info("Farben aus ${file.path} nicht lesbar", it) }.getOrNull()
                        currentPalette = palette
                        viewer.setPalette(palette)
                    }
                    else -> viewer.showModel(bytes, format, keepCamera && loaded)
                }
                loaded = true
            }
        }
    }

    /** OBJ samt MTL: ungespeicherte Stände aus dem Editor gewinnen, Texturen kommen von der Platte. */
    private fun loadObj(bytes: ByteArray, keepCamera: Boolean) {
        val text = bytes.toString(Charsets.UTF_8)
        val dir = file.parent
        val mtl = linkedMapOf<String, ByteArray>()
        val paths = mutableSetOf<String>()
        for (name in ObjColors.mtlLibraries(text)) {
            val mtlFile = dir?.findFileByRelativePath(name.replace('\\', '/'))
            if (mtlFile == null) {
                paths += (dir?.path ?: "") + "/" + name
                continue
            }
            paths += mtlFile.path
            val content = ApplicationManager.getApplication().runReadAction(Computable {
                FileDocumentManager.getInstance().getCachedDocument(mtlFile)?.text?.toByteArray(Charsets.UTF_8)
            }) ?: runCatching { mtlFile.contentsToByteArray(false) }.getOrNull() ?: continue
            mtl[name] = content
        }
        materialFiles = paths
        viewer.showModel(bytes, "obj", keepCamera && loaded, mtl = mtl, siblings = { name ->
            dir?.findFileByRelativePath(name)?.takeIf { !it.isDirectory }?.let { runCatching { it.contentsToByteArray(false) }.getOrNull() }
        })
        val palette = ObjColors.palette(text, mtl.values.joinToString("\n") { it.toString(Charsets.UTF_8) }.takeIf { mtl.isNotEmpty() })
        currentPalette = palette
        viewer.setPalette(palette)
    }

    /**
     * Wofür ⌘Z im Viewer gilt: die Modelldatei selbst (Umfärben einer 3MF, eigenes Material in der OBJ) und die
     * Materialdateien (Farbe in der MTL) - so lässt sich jede Änderung aus der Teile-Liste hier zurücknehmen.
     */
    override fun getDocumentReferences(): Collection<DocumentReference> {
        val manager = DocumentReferenceManager.getInstance()
        val refs = mutableListOf(manager.create(file))
        for (path in materialFiles) {
            val mtl = file.fileSystem.findFileByPath(path) ?: continue
            refs += FileDocumentManager.getInstance().getCachedDocument(mtl)?.let { manager.create(it) } ?: manager.create(mtl)
        }
        return refs
    }

    private fun undoRedo(redo: Boolean) {
        val undo = UndoManager.getInstance(project)
        val available = if (redo) undo.isRedoAvailable(this) else undo.isUndoAvailable(this)
        if (available) if (redo) undo.redo(this) else undo.undo(this)
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

/** Undo/Redo aus dem 3D-Viewer (Teile-Liste). */
class ViewerUndoAction(private val perform: () -> Unit) : DumbAwareAction() {
    override fun actionPerformed(e: AnActionEvent) = perform()
}

/** Im Viewer gewinnt [ViewerUndoAction] gegen Undo/Redo der IDE und des eingebetteten Browsers mit demselben Kürzel. */
class ViewerUndoPromoter : ActionPromoter {
    override fun promote(actions: List<AnAction>, context: DataContext): List<AnAction> = actions.filterIsInstance<ViewerUndoAction>()
}

class ModelFileEditorProvider : FileEditorProvider, DumbAware {
    override fun accept(project: Project, file: VirtualFile): Boolean = file.extension?.lowercase() in ExportFormat.MODEL_EXTENSIONS
    override fun acceptRequiresReadAction(): Boolean = false
    override fun createEditor(project: Project, file: VirtualFile): FileEditor = ModelFileEditor(project, file)
    override fun getEditorTypeId(): String = "scadsphere-model"
    override fun getPolicy(): FileEditorPolicy = FileEditorPolicy.HIDE_DEFAULT_EDITOR
}
