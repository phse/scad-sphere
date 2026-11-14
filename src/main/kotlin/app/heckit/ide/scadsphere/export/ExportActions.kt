package app.heckit.ide.scadsphere.export

import app.heckit.ide.scadsphere.ScadFileStates
import app.heckit.ide.scadsphere.ScadSphereConfigurable
import app.heckit.ide.scadsphere.ScadSphereBundle
import app.heckit.ide.scadsphere.customizer.ParameterSets
import app.heckit.ide.scadsphere.customizer.ScadCustomizer
import app.heckit.ide.scadsphere.editor.NOTIFICATIONS
import app.heckit.ide.scadsphere.editor.notFound
import app.heckit.ide.scadsphere.editor.notify
import app.heckit.ide.scadsphere.editor.scadFile
import app.heckit.ide.scadsphere.lang.ScadIcons
import app.heckit.ide.scadsphere.render.ExportOptions
import app.heckit.ide.scadsphere.render.OpenScad
import app.heckit.ide.scadsphere.render.OpenScadFeatures
import app.heckit.ide.scadsphere.render.OpenScadRender
import app.heckit.ide.scadsphere.render.RenderMessage
import app.heckit.ide.scadsphere.render.Slicer
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.icons.AllIcons
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.Separator
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import java.io.File

/**
 * *Export ▸* für .scad-Dateien: ein Eintrag je Format, 3D- bzw. 2D-Formate passend zum letzten Ergebnis der Vorschau
 * (ohne Vorschau alle), darüber *Erneut exportieren*. In der Werkzeugleiste der Vorschau als Knopf mit Menü.
 */
class ExportGroup : ActionGroup(ScadSphereBundle.messagePointer("action.export"), ScadSphereBundle.messagePointer("action.export.description"), AllIcons.ToolbarDecorator.Export), DumbAware {
    init {
        templatePresentation.isPopupGroup = true
    }

    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null && e.scadFile() != null
    }

    override fun getChildren(e: AnActionEvent?): Array<AnAction> {
        val file = e?.scadFile() ?: return EMPTY_ARRAY
        val last = file.getUserData(ExportFormat.LAST_RENDER_FORMAT)
        val formats = ExportFormat.entries.filter { last == null || it.is3d == (last != "svg") }
        val actions = mutableListOf<AnAction>()
        ActionManager.getInstance().getAction("ScadSphere.ExportAgain")?.let { actions += it; actions += Separator.getInstance() }
        formats.forEachIndexed { index, format ->
            if (index > 0 && formats[index - 1].is3d != format.is3d) actions += Separator.getInstance()
            actions += EXPORT_ACTIONS.getValue(format)
        }
        return actions.toTypedArray()
    }

    private companion object {
        val EXPORT_ACTIONS = ExportFormat.entries.associateWith { ExportFormatAction(it) }
    }
}

/** Export in ein Format: Dialog mit Ziel und Einstellungen, dann OpenSCAD im Hintergrund. */
class ExportFormatAction(private val format: ExportFormat) : DumbAwareAction() {
    init {
        templatePresentation.setText(ScadSphereBundle.messagePointer("export.format", format.label))
        templatePresentation.description = format.description
    }

    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null && e.scadFile() != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val file = e.scadFile() ?: return
        val exe = OpenScad.executable() ?: return notFound(project)
        val features = features(project, exe) ?: return
        val states = ScadFileStates.of(project)
        // Zuletzt gewähltes Ziel (gleiches Format) bzw. derselbe Ordner wie beim letzten Export
        val last = states.exportTarget(file)?.let(::File)
        val folder = last?.parentFile?.takeIf { it.isDirectory } ?: File(file.parent.path)
        val defaultTarget = last?.takeIf { it.extension.equals(format.extension, ignoreCase = true) }
            ?: File(folder, "${file.nameWithoutExtension}.${format.extension}")
        val defines = ScadCustomizer.defines(project, file)
        val note = if (defines.isEmpty()) null else states.parameterSet(file)
            ?.let { ScadSphereBundle.message("export.dialog.customizerSet", defines.size, it) }
            ?: ScadSphereBundle.message("export.dialog.customizer", defines.size)
        val sets = ParameterSets.read(file).keys.toList()
        val dialog = ExportDialog(project, format, ScadSphereBundle.message("export.dialog.title", format.label), ScadSphereBundle.message("export.dialog.ok"), defaultTarget, emptyList(), format.choices(features), note,
            sets, states.exportAllSets(file))
        if (!dialog.showAndGet()) return
        val target = dialog.target() ?: return
        states.setExportTarget(file, target.path, dialog.allSets())
        if (dialog.allSets()) exportSets(project, exe, file, target, dialog.options(), sets)
        else export(project, exe, file, target, dialog.options())
    }
}

/** Noch einmal ins letzte Ziel exportieren, mit den zuletzt gewählten Einstellungen und ohne Dialog. */
class ExportAgainAction : DumbAwareAction(ScadSphereBundle.messagePointer("action.exportAgain"), ScadSphereBundle.messagePointer("action.exportAgain.description")) {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val project = e.project
        val file = e.scadFile()
        val target = if (project != null && file != null) ScadFileStates.of(project).exportTarget(file) else null
        e.presentation.isVisible = file != null
        e.presentation.isEnabled = target != null && ExportFormat.byExtension(File(target).extension) != null
        e.presentation.text = target?.let { ScadSphereBundle.message("action.exportAgain.target", File(it).name) } ?: ScadSphereBundle.message("action.exportAgain")
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val file = e.scadFile() ?: return
        val target = ScadFileStates.of(project).exportTarget(file)?.let(::File) ?: return
        val format = ExportFormat.byExtension(target.extension) ?: return
        val exe = OpenScad.executable() ?: return notFound(project)
        val features = features(project, exe) ?: return
        val sets = if (ScadFileStates.of(project).exportAllSets(file)) ParameterSets.read(file).keys.toList() else emptyList()
        if (sets.isNotEmpty()) exportSets(project, exe, file, target, format.rememberedOptions(features), sets)
        else export(project, exe, file, target, format.rememberedOptions(features))
    }
}

/** *Umwandeln in ▸* für Modelldateien (auch mehrere): OpenSCAD liest sie per `import()` und schreibt das neue Format. */
class ConvertGroup : ActionGroup(ScadSphereBundle.messagePointer("action.convert"), ScadSphereBundle.messagePointer("action.convert.description"), AllIcons.ToolbarDecorator.Export), DumbAware {
    init {
        templatePresentation.isPopupGroup = true
    }

    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null && modelFiles(e).isNotEmpty()
    }

    override fun getChildren(e: AnActionEvent?): Array<AnAction> = CONVERT_ACTIONS

    private companion object {
        val CONVERT_ACTIONS: Array<AnAction> = ExportFormat.entries.filter { it.is3d }.map { ConvertFormatAction(it) }.toTypedArray()
    }
}

class ConvertFormatAction(private val format: ExportFormat) : DumbAwareAction() {
    init {
        templatePresentation.setText(ScadSphereBundle.messagePointer("export.format", format.label))
        templatePresentation.description = format.description
    }

    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null && modelFiles(e).isNotEmpty()
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val files = modelFiles(e).ifEmpty { return }
        val exe = OpenScad.executable() ?: return notFound(project)
        val features = features(project, exe) ?: return
        val targets = files.map { targetFor(it) }
        val title = if (files.size == 1) ScadSphereBundle.message("convert.dialog.title", files.single().name, format.label)
        else ScadSphereBundle.message("convert.dialog.titleMany", files.size, format.label)
        val dialog = ExportDialog(project, format, title, ScadSphereBundle.message("convert.dialog.ok"), targets.singleOrNull(), targets, format.choices(features), null)
        if (!dialog.showAndGet()) return
        val pairs = if (files.size == 1) listOf(File(files.single().path) to (dialog.target() ?: return)) else files.map { File(it.path) }.zip(targets)
        convert(project, exe, pairs, dialog.options())
    }

    /** Neben dem Original; bei gleichem Format mit Zusatz, damit das Original nicht überschrieben wird. */
    private fun targetFor(file: VirtualFile): File {
        val dir = File(file.parent.path)
        val same = file.extension.equals(format.extension, ignoreCase = true)
        return File(dir, "${file.nameWithoutExtension}${if (same) "-converted" else ""}.${format.extension}")
    }
}

/** Neue .scad-Datei mit `import("modell.stl");`, um ein fertiges Modell zu skalieren, zu schneiden oder zu ergänzen. */
class NewScadFromModelAction : DumbAwareAction(ScadSphereBundle.messagePointer("action.newScad"), ScadSphereBundle.messagePointer("action.newScad.description"), ScadIcons.SCAD) {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val file = e.getData(CommonDataKeys.VIRTUAL_FILE)
        e.presentation.isEnabledAndVisible = e.project != null && file != null && ExportFormat.isModel(file) && file.parent?.isWritable == true
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val model = e.getData(CommonDataKeys.VIRTUAL_FILE)?.takeIf { ExportFormat.isModel(it) } ?: return
        val dir = model.parent ?: return
        val name = generateSequence(1) { it + 1 }.map { if (it == 1) "${model.nameWithoutExtension}.scad" else "${model.nameWithoutExtension}-$it.scad" }
            .first { dir.findChild(it) == null }
        val path = model.name.replace("\\", "\\\\").replace("\"", "\\\"")
        val created = WriteCommandAction.writeCommandAction(project).withName(ScadSphereBundle.message("action.newScad")).compute<VirtualFile, Throwable> {
            dir.createChildData(this, name).also { VfsUtil.saveText(it, "import(\"$path\");\n") }
        }
        OpenFileDescriptor(project, created).navigate(true)
    }
}

private fun modelFiles(e: AnActionEvent): List<VirtualFile> {
    val files = e.getData(CommonDataKeys.VIRTUAL_FILE_ARRAY)?.toList() ?: listOfNotNull(e.getData(CommonDataKeys.VIRTUAL_FILE))
    return if (files.isNotEmpty() && files.all { ExportFormat.isModel(it) }) files else emptyList()
}

/** Fähigkeiten der installierten Version; beim ersten Mal kurz mit Fortschrittsanzeige. */
private fun features(project: Project, exe: String): OpenScadFeatures? =
    OpenScad.cachedFeatures(exe) ?: runCatching {
        ProgressManager.getInstance().runProcessWithProgressSynchronously<OpenScadFeatures, Exception>(
            { OpenScad.features(exe) }, ScadSphereBundle.message("export.checking"), false, project,
        )
    }.getOrNull()

private fun export(project: Project, exe: String, file: VirtualFile, target: File, options: ExportOptions) {
    FileDocumentManager.getInstance().getDocument(file)?.let { FileDocumentManager.getInstance().saveDocument(it) }
    val defines = ScadCustomizer.defines(project, file)
    object : Task.Backgroundable(project, ScadSphereBundle.message("export.progress", target.name), true) {
        private var result: OpenScadRender.ExportResult? = null

        override fun run(indicator: ProgressIndicator) {
            result = OpenScadRender(exe).export(File(file.path), target, options, defines, indicator)
        }

        override fun onSuccess() {
            val result = result ?: return
            if (!result.ok) return failed(project, target, result.messages)
            done(project, ScadSphereBundle.message("export.done", target.name), listOf(target))
        }
    }.queue()
}

/** Ziel für den Parametersatz [set] neben [target]: `<name>-<satz>.<endung>`, Zeichen für Pfade ersetzt. */
internal fun setTarget(target: File, set: String): File {
    val safe = set.trim().replace(Regex("""[\\/:*?"<>|\p{Cntrl}]"""), "_").ifEmpty { "_" }
    return File(target.parentFile, "${target.nameWithoutExtension}-$safe.${target.extension}")
}

/**
 * Alle Parametersätze der Datei exportieren, je Satz eine Datei ([setTarget]). OpenSCAD liest die Sätze selbst
 * (`-p`/`-P`), wie seine Oberfläche; die gerade im Customizer geänderten Werte zählen dabei nicht.
 */
private fun exportSets(project: Project, exe: String, file: VirtualFile, target: File, options: ExportOptions, sets: List<String>) {
    FileDocumentManager.getInstance().getDocument(file)?.let { FileDocumentManager.getInstance().saveDocument(it) }
    val json = ParameterSets.find(file)?.let { File(it.path) } ?: return
    FileDocumentManager.getInstance().getCachedDocument(ParameterSets.find(file)!!)?.let { FileDocumentManager.getInstance().saveDocument(it) }
    object : Task.Backgroundable(project, ScadSphereBundle.message("export.progressSets", sets.size), true) {
        private val exported = mutableListOf<File>()
        private val failures = mutableListOf<Pair<File, List<RenderMessage>>>()

        override fun run(indicator: ProgressIndicator) {
            indicator.isIndeterminate = false
            sets.forEachIndexed { index, set ->
                indicator.checkCanceled()
                val setFile = setTarget(target, set)
                indicator.text2 = setFile.name
                indicator.fraction = index.toDouble() / sets.size
                val result = OpenScadRender(exe).export(File(file.path), setFile, options, emptyList(), indicator, json to set)
                if (result.ok) exported += setFile else failures += setFile to result.messages
            }
        }

        override fun onSuccess() {
            failures.forEach { (setFile, messages) -> failed(project, setFile, messages) }
            if (exported.isEmpty()) return
            done(project, ScadSphereBundle.message("export.doneSets", exported.size, target.parentFile.name), exported)
        }
    }.queue()
}

private fun convert(project: Project, exe: String, pairs: List<Pair<File, File>>, options: ExportOptions) {
    val title = if (pairs.size == 1) ScadSphereBundle.message("convert.progress", pairs.single().first.name) else ScadSphereBundle.message("convert.progressMany", pairs.size)
    object : Task.Backgroundable(project, title, true) {
        private val converted = mutableListOf<File>()
        private val failures = mutableListOf<Pair<File, List<RenderMessage>>>()

        override fun run(indicator: ProgressIndicator) {
            indicator.isIndeterminate = pairs.size == 1
            pairs.forEachIndexed { index, (source, target) ->
                indicator.checkCanceled()
                indicator.text2 = source.name
                indicator.fraction = index.toDouble() / pairs.size
                val result = OpenScadRender(exe).convert(source, target, options, indicator)
                if (result.ok) converted += target else failures += source to result.messages
            }
        }

        override fun onSuccess() {
            failures.forEach { (source, messages) -> failed(project, source, messages, convert = true) }
            if (converted.isEmpty()) return
            val text = if (converted.size == 1) ScadSphereBundle.message("convert.done", converted.single().name)
            else ScadSphereBundle.message("convert.doneMany", converted.size)
            done(project, text, converted)
        }
    }.queue()
}

private fun failed(project: Project, file: File, messages: List<RenderMessage>, convert: Boolean = false) {
    if (messages.isEmpty() && !convert) return
    val errors = messages.filter { it.level == RenderMessage.Level.ERROR }.ifEmpty { messages }.take(5)
    val title = ScadSphereBundle.message(if (convert) "convert.failed" else "export.failed", file.name)
    notify(project, title + errors.joinToString("<br>", prefix = "<br>") { it.text }, NotificationType.ERROR)
}

private fun done(project: Project, text: String, files: List<File>) {
    val exported = files.mapNotNull { LocalFileSystem.getInstance().refreshAndFindFileByIoFile(it) }
    val notification = NotificationGroupManager.getInstance().getNotificationGroup(NOTIFICATIONS).createNotification(text, NotificationType.INFORMATION)
    exported.singleOrNull()?.let { file ->
        notification.addAction(NotificationAction.createSimpleExpiring(ScadSphereBundle.message("export.open")) { OpenFileDescriptor(project, file).navigate(true) })
    }
    notification.notify(project)
}

/**
 * Im Slicer öffnen: .scad wird als 3MF (mit Farben und den Werten des Customizers) in einen temporären Ordner exportiert,
 * Modelldateien gehen direkt an den Slicer.
 */
class OpenInSlicerAction : DumbAwareAction(ScadSphereBundle.messagePointer("action.openInSlicer"), ScadSphereBundle.messagePointer("action.openInSlicer.description"), AllIcons.Ide.External_link_arrow) {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val file = e.getData(CommonDataKeys.VIRTUAL_FILE)
        e.presentation.isEnabledAndVisible = e.project != null && file != null && (e.scadFile() != null || file.extension?.lowercase() in SLICER_FORMATS)
        Slicer.configured()?.let { e.presentation.text = ScadSphereBundle.message("action.openInSlicer.named", it.name) }
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val file = e.getData(CommonDataKeys.VIRTUAL_FILE) ?: return
        val slicer = Slicer.configured() ?: return slicerNotFound(project)
        if (e.scadFile() == null) return open(project, slicer, File(file.path))
        val exe = OpenScad.executable() ?: return notFound(project)
        val features = features(project, exe) ?: return
        FileDocumentManager.getInstance().getDocument(file)?.let { FileDocumentManager.getInstance().saveDocument(it) }
        val defines = ScadCustomizer.defines(project, file)
        val target = File(File(PathManager.getTempPath(), "scad-sphere"), "${file.nameWithoutExtension}.3mf").also { it.parentFile.mkdirs() }
        object : Task.Backgroundable(project, ScadSphereBundle.message("export.progress", target.name), true) {
            private var result: OpenScadRender.ExportResult? = null

            override fun run(indicator: ProgressIndicator) {
                result = OpenScadRender(exe).export(File(file.path), target, ExportFormat.THREE_MF.rememberedOptions(features), defines, indicator)
            }

            override fun onSuccess() {
                val result = result ?: return
                if (!result.ok) return failed(project, target, result.messages)
                open(project, slicer, target)
            }
        }.queue()
    }

    private fun open(project: Project, slicer: Slicer.Found, file: File) {
        runCatching { Slicer.open(slicer, file) }
            .onFailure { notify(project, ScadSphereBundle.message("openInSlicer.failed", slicer.name, it.message ?: ""), NotificationType.ERROR) }
    }

    private fun slicerNotFound(project: Project) {
        NotificationGroupManager.getInstance().getNotificationGroup(NOTIFICATIONS)
            .createNotification(ScadSphereBundle.message("openInSlicer.notFound"), NotificationType.WARNING)
            .addAction(NotificationAction.createSimpleExpiring(ScadSphereBundle.message("preview.notFound.settings")) {
                ShowSettingsUtil.getInstance().showSettingsDialog(project, ScadSphereConfigurable::class.java)
            })
            .notify(project)
    }

    private companion object {
        val SLICER_FORMATS = setOf("stl", "3mf", "obj", "amf")
    }
}
