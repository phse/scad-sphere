package app.heckit.ide.scadsphere.editor

import app.heckit.ide.scadsphere.ScadSphereBundle
import app.heckit.ide.scadsphere.ScadSphereConfigurable
import app.heckit.ide.scadsphere.ScadSphereSettings
import app.heckit.ide.scadsphere.ScadSphereSettings.RenderMode
import app.heckit.ide.scadsphere.customizer.CustomizerPanel
import app.heckit.ide.scadsphere.customizer.CustomizerParameter
import app.heckit.ide.scadsphere.customizer.CustomizerVisibilityListener
import app.heckit.ide.scadsphere.customizer.ParameterSets
import app.heckit.ide.scadsphere.customizer.ScadCustomizer
import app.heckit.ide.scadsphere.customizer.ToggleCustomizerAction
import app.heckit.ide.scadsphere.export.ExportFormat
import app.heckit.ide.scadsphere.lang.ScadFileType
import app.heckit.ide.scadsphere.psi.ScadFile
import app.heckit.ide.scadsphere.customizer.formatNumber
import app.heckit.ide.scadsphere.render.CsgOverlay
import app.heckit.ide.scadsphere.render.OpenScad
import app.heckit.ide.scadsphere.render.OpenScadRender
import app.heckit.ide.scadsphere.render.RenderMessages
import app.heckit.ide.scadsphere.render.RenderResult
import app.heckit.ide.scadsphere.viewer.ModelViewer
import com.intellij.ide.BrowserUtil
import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.Separator
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorPolicy
import com.intellij.openapi.fileEditor.FileEditorProvider
import com.intellij.openapi.fileEditor.FileEditorState
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.fileEditor.TextEditorWithPreview
import com.intellij.openapi.fileEditor.TextEditorWithPreviewProvider
import com.intellij.openapi.fileTypes.FileTypeRegistry
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.UserDataHolderBase
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileContentChangeEvent
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.psi.PsiManager
import com.intellij.ui.OnePixelSplitter
import com.intellij.util.Alarm
import com.intellij.util.concurrency.AppExecutorUtil
import java.awt.event.HierarchyEvent
import java.beans.PropertyChangeListener
import java.io.File
import javax.swing.JComponent

/**
 * Vorschau einer .scad-Datei: OpenSCAD rechnet im Hintergrund (Text ungespeichert über stdin), das Ergebnis
 * erscheint im 3D-Viewer. Gerechnet wird nur, solange die Vorschau sichtbar ist; der Blickwinkel bleibt erhalten.
 * Rechts daneben der Customizer, sofern die Datei Parameter hat.
 */
class ScadPreviewEditor(private val project: Project, private val file: VirtualFile) : UserDataHolderBase(), FileEditor {
    private val viewer = ModelViewer(this)
    private val alarm = Alarm(this)
    private val parseAlarm = Alarm(this)
    private val document = FileDocumentManager.getInstance().getDocument(file)
    private val customizer = CustomizerPanel(project, file, this) { onCustomizerChanged() }
    private val splitter = OnePixelSplitter(false, "ScadSphere.Customizer.Proportion", 0.55f).apply {
        firstComponent = viewer.component
        setHonorComponentsMinimumSize(true)
    }

    @Volatile
    private var current: OpenScadRender? = null
    private var generation = 0
    /** Neu rechnen, sobald die Vorschau sichtbar wird */
    private var dirty = true
    /** Es wird schon ein Modell gezeigt (dann Blickwinkel behalten) */
    private var hasModel = false
    /** Dateien, die der letzte Lauf gelesen hat (include/use/import) */
    private var dependencies: Set<String> = emptySet()

    @Volatile
    private var disposed = false

    /** Animation über `$t`: nur, wenn der Code `$t` verwendet. Bilder je Schritt gemerkt, bis sich etwas ändert. */
    private var animated = false
    private var steps = DEFAULT_STEPS
    private var frame = 0
    private var playing = false
    private val frames = HashMap<Int, RenderResult>()
    private val playAlarm = Alarm(this)

    val isRendering: Boolean get() = current != null

    /** Die Datei hat Parameter für den Customizer. */
    val hasParameters: Boolean get() = customizer.hasParameters

    init {
        viewer.onNavigate = ::navigate
        viewer.onAction = ::onAction
        viewer.onImage = { saveViewImage(project, file, it) }
        document?.addDocumentListener(object : DocumentListener {
            override fun documentChanged(event: DocumentEvent) {
                frames.clear()
                if (ScadSphereSettings.renderMode == RenderMode.TYPING) schedule(ScadSphereSettings.renderDelay)
                parseAlarm.cancelAllRequests()
                parseAlarm.addRequest(::parseParameters, PARSE_DELAY)
            }
        }, this)
        val connection = ApplicationManager.getApplication().messageBus.connect(this)
        connection.subscribe(VirtualFileManager.VFS_CHANGES, object : BulkFileListener {
            override fun after(events: List<VFileEvent>) = onFilesChanged(events)
        })
        connection.subscribe(CustomizerVisibilityListener.TOPIC, CustomizerVisibilityListener { updateCustomizerVisibility() })
        viewer.component.addHierarchyListener { event ->
            if (event.changeFlags and HierarchyEvent.SHOWING_CHANGED.toLong() != 0L && viewer.component.isShowing && dirty) schedule(0)
        }
        viewer.onAnimation = ::onAnimation
        parseParameters()
    }

    /** Parameter für den Customizer aus dem (gerade committeten) Code lesen. */
    private fun parseParameters() {
        ReadAction.nonBlocking<List<CustomizerParameter>> {
            val psi = PsiManager.getInstance(project).findFile(file) as? ScadFile
            psi?.let { ScadCustomizer.parameters(it).map { parsed -> parsed.parameter } }.orEmpty()
        }
            .withDocumentsCommitted(project)
            .expireWith(this)
            .finishOnUiThread(ModalityState.nonModal()) { parameters ->
                updateAnimated()
                customizer.update(parameters)
                updateCustomizerVisibility()
            }
            .submit(AppExecutorUtil.getAppExecutorService())
    }

    private fun updateCustomizerVisibility() {
        val show = ScadSphereSettings.showCustomizer && customizer.hasParameters
        if (show && splitter.secondComponent == null) splitter.secondComponent = customizer
        else if (!show && splitter.secondComponent != null) splitter.secondComponent = null
        updateTools()
    }

    /** Neu rechnen/Abbrechen und Customizer in der Werkzeugleiste des Viewers. */
    private fun updateTools() = viewer.setTools(isRendering, ScadSphereSettings.showCustomizer, customizer.hasParameters)

    /** Ein Wert im Customizer wurde geändert: neu rechnen (außer „nur auf Knopfdruck“). */
    private fun onCustomizerChanged() {
        if (ScadSphereSettings.renderMode != RenderMode.MANUAL) schedule(CUSTOMIZER_DELAY)
    }

    private fun onFilesChanged(events: List<VFileEvent>) {
        val setsPath = file.parent?.path?.let { "$it/${ParameterSets.fileName(file)}" }
        if (setsPath != null && events.any { it.path == setsPath }) customizer.reloadSets()
        val mode = ScadSphereSettings.renderMode
        if (mode == RenderMode.MANUAL) return
        val changed = events.filterIsInstance<VFileContentChangeEvent>()
        if (changed.isEmpty()) return
        val own = changed.any { it.file == file }
        // Beim Tippen rechnet schon der DocumentListener; gespeichert wird danach nur noch der gleiche Text
        if (own && mode == RenderMode.SAVE) return schedule(0)
        if (changed.any { it.file != file && runCatching { File(it.file.path).canonicalPath }.getOrNull() in dependencies }) schedule(300)
    }

    /** Neu rechnen nach [delay] ms (spätere Aufrufe verschieben das). */
    fun schedule(delay: Int) {
        dirty = true
        frames.clear()
        alarm.cancelAllRequests()
        alarm.addRequest({ if (viewer.component.isShowing) render() }, delay)
    }

    /** Sofort neu rechnen (Knopf in der Werkzeugleiste). */
    fun render() {
        frames.clear()
        renderFrame()
    }

    /** Aktuellen Stand rechnen; bei einer Animation den aktuellen Schritt (aus dem Speicher, wenn schon gerechnet). */
    private fun renderFrame() {
        if (animated) frames[frame]?.let { cached ->
            dirty = false
            generation++
            current?.cancel()
            current = null
            viewer.setStatus(null)
            updateTools()
            show(cached, null)
            continuePlaying(cached = true)
            return
        }
        val exe = OpenScad.executable()
        if (exe == null) {
            viewer.showEmpty(ScadSphereBundle.message("preview.notFound"), ScadSphereBundle.message("preview.notFound.settings"), "settings")
            return
        }
        dirty = false
        current?.cancel()
        val render = OpenScadRender(exe)
        current = render
        val run = ++generation
        val unsaved = document != null && FileDocumentManager.getInstance().isDocumentUnsaved(document)
        val text = if (unsaved) document.text else null
        val stamp = document?.modificationStamp
        val source = File(file.path)
        val step = frame.takeIf { animated }
        val defines = ScadCustomizer.defines(project, file) + listOfNotNull(step?.let { "${'$'}t=${formatNumber(it.toDouble() / steps)}" })
        viewer.setStatus(ScadSphereBundle.message("preview.rendering"))
        updateTools()
        ApplicationManager.getApplication().executeOnPooledThread {
            val result = runCatching { render.render(source, text, defines) }
            ApplicationManager.getApplication().invokeLater({
                if (run != generation) return@invokeLater
                current = null
                viewer.setStatus(null)
                updateTools()
                result.onSuccess {
                    if (step != null && !it.cancelled && it.bytes != null) frames[step] = it
                    show(it, stamp)
                    if (step != null) continuePlaying(cached = false)
                }.onFailure {
                    stopPlaying()
                    viewer.showEmpty(ScadSphereBundle.message("preview.error", it.message ?: it.javaClass.simpleName), keepModel = hasModel)
                }
            }, { disposed })
        }
    }

    fun expandMessages() = viewer.expandMessages()

    /** Aufruf an den Viewer (Screenshot-Helfer). */
    fun invokeViewer(function: String, argument: Any?) = viewer.invoke(function, argument)

    fun cancel() {
        stopPlaying()
        generation++
        current?.cancel()
        current = null
        viewer.setStatus(null)
        updateTools()
    }

    private fun show(result: RenderResult, stamp: Long?) {
        if (result.cancelled) return
        dependencies = result.dependencies
        viewer.setMessages(result.messages)
        RenderMessages.getInstance(project).update(this, file, result.messages, stamp)
        val bytes = result.bytes
        when {
            bytes != null -> {
                viewer.showModel(bytes, result.format, keepCamera = hasModel, overlay = result.overlay)
                hasModel = true
                file.putUserData(ExportFormat.LAST_RENDER_FORMAT, result.format)
            }
            result.empty -> {
                viewer.showEmpty(ScadSphereBundle.message("preview.empty"))
                hasModel = false
            }
            // Bei Fehlern bleibt das letzte Modell stehen, die Meldungen erscheinen darüber
            !hasModel -> viewer.showEmpty(ScadSphereBundle.message("preview.failed"))
        }
    }

    private fun navigate(path: String?, line: Int) {
        val target = path?.let { LocalFileSystem.getInstance().findFileByPath(it) } ?: file
        OpenFileDescriptor(project, target, (line - 1).coerceAtLeast(0), 0).navigate(true)
    }

    private fun onAction(action: String) {
        when (action) {
            "settings" -> ShowSettingsUtil.getInstance().showSettingsDialog(project, ScadSphereConfigurable::class.java)
            "download" -> BrowserUtil.browse("https://openscad.org/downloads.html")
            "render" -> if (isRendering) cancel() else render()
            "customizer" -> ToggleCustomizerAction.setVisible(!ScadSphereSettings.showCustomizer)
        }
    }

    /** Der Code verwendet `$t` (ohne Kommentare und Zeichenketten): Animationsleiste zeigen. */
    private fun updateAnimated() {
        val now = document?.let { CsgOverlay.usesTime(it.text) } ?: false
        if (now == animated) return
        animated = now
        frames.clear()
        if (!now) stopPlaying()
        frame = 0
        updateAnimation()
        // Ohne Animation rechnet OpenSCAD mit $t = 0 - der erste Schritt ist also schon zu sehen
    }

    private fun updateAnimation() =
        viewer.setAnimation(if (animated) mapOf("frame" to frame, "steps" to steps, "playing" to playing) else null)

    private fun onAnimation(action: String, value: Int?) {
        when (action) {
            "play" -> { playing = true; updateAnimation(); renderFrame() }
            "pause" -> stopPlaying()
            "frame" -> {
                stopPlaying()
                frame = (value ?: 0).coerceIn(0, steps - 1)
                updateAnimation()
                renderFrame()
            }
            "steps" -> {
                val new = (value ?: DEFAULT_STEPS).coerceIn(2, 360)
                if (new == steps) return
                // gleiche Stelle der Animation behalten
                frame = (frame * new / steps).coerceIn(0, new - 1)
                steps = new
                frames.clear()
                updateAnimation()
                renderFrame()
            }
        }
    }

    /** Beim Abspielen den nächsten Schritt holen: gemerkte Bilder im festen Takt, neue so schnell OpenSCAD rechnet. */
    private fun continuePlaying(cached: Boolean) {
        if (!playing) return
        playAlarm.cancelAllRequests()
        playAlarm.addRequest({
            if (!playing || !viewer.component.isShowing) return@addRequest stopPlaying()
            frame = (frame + 1) % steps
            updateAnimation()
            renderFrame()
        }, if (cached) FRAME_MS else 0)
    }

    private fun stopPlaying() {
        playAlarm.cancelAllRequests()
        if (!playing) return
        playing = false
        updateAnimation()
    }

    override fun getComponent(): JComponent = splitter
    override fun getPreferredFocusedComponent(): JComponent? = null
    override fun getName(): String = ScadSphereBundle.message("editor.preview")
    override fun getFile(): VirtualFile = file
    override fun setState(state: FileEditorState) = Unit
    override fun isModified(): Boolean = false
    override fun isValid(): Boolean = file.isValid
    override fun addPropertyChangeListener(listener: PropertyChangeListener) = Unit
    override fun removePropertyChangeListener(listener: PropertyChangeListener) = Unit

    override fun dispose() {
        disposed = true
        current?.cancel()
        RenderMessages.getInstance(project).clear(this, file)
    }

    private companion object {
        const val PARSE_DELAY = 300
        const val CUSTOMIZER_DELAY = 300
        const val DEFAULT_STEPS = 24
        const val FRAME_MS = 100
    }
}

/** Editor und Vorschau nebeneinander (umschaltbar oben rechts, wie bei Markdown). */
class ScadSplitEditor(editor: TextEditor, val preview: ScadPreviewEditor) : TextEditorWithPreview(
    editor, preview, "OpenSCAD",
    if (ScadSphereSettings.showPreview) Layout.SHOW_EDITOR_AND_PREVIEW else Layout.SHOW_EDITOR,
) {
    override fun createLeftToolbarActionGroup(): ActionGroup {
        val actions = ActionManager.getInstance()
        // Neu rechnen und Customizer stehen in der Leiste des Viewers rechts oben
        return DefaultActionGroup(actions.getAction("ScadSphere.Export"), actions.getAction("ScadSphere.OpenInSlicer"), actions.getAction("ScadSphere.OpenInOpenScad"))
    }

}

class ScadSplitEditorProvider : TextEditorWithPreviewProvider(ScadPreviewEditorProvider()) {
    override fun createSplitEditor(firstEditor: TextEditor, secondEditor: FileEditor): FileEditor =
        ScadSplitEditor(firstEditor, secondEditor as ScadPreviewEditor)
}

class ScadPreviewEditorProvider : FileEditorProvider, DumbAware {
    override fun accept(project: Project, file: VirtualFile): Boolean = FileTypeRegistry.getInstance().isFileOfType(file, ScadFileType)
    override fun acceptRequiresReadAction(): Boolean = false
    override fun createEditor(project: Project, file: VirtualFile): FileEditor = ScadPreviewEditor(project, file)
    override fun getEditorTypeId(): String = "scadsphere-preview"
    override fun getPolicy(): FileEditorPolicy = FileEditorPolicy.PLACE_AFTER_DEFAULT_EDITOR
}
