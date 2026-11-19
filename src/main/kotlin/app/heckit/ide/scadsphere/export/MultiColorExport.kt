package app.heckit.ide.scadsphere.export

import app.heckit.ide.scadsphere.ScadSphereBundle
import app.heckit.ide.scadsphere.customizer.CustomizerParameter
import app.heckit.ide.scadsphere.customizer.ScadCustomizer
import app.heckit.ide.scadsphere.editor.NOTIFICATIONS
import app.heckit.ide.scadsphere.editor.notFound
import app.heckit.ide.scadsphere.editor.notify
import app.heckit.ide.scadsphere.editor.scadFile
import app.heckit.ide.scadsphere.lang.ScadColorProvider
import app.heckit.ide.scadsphere.psi.ScadFile
import app.heckit.ide.scadsphere.render.ColorParts
import app.heckit.ide.scadsphere.render.ExportOptions
import app.heckit.ide.scadsphere.render.OpenScad
import app.heckit.ide.scadsphere.render.OpenScadRender
import app.heckit.ide.scadsphere.render.RenderMessage
import app.heckit.ide.scadsphere.render.Slicer
import com.intellij.ide.util.PropertiesComponent
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileChooser.FileChooserFactory
import com.intellij.openapi.fileChooser.FileSaverDescriptor
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.openapi.util.Computable
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiManager
import com.intellij.ui.JBIntSpinner
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBRadioButton
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.ui.JBUI
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.io.File
import javax.swing.ButtonGroup
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * *Export ▸ 3MF for Multi-Color Print…*: ein Objekt aus mehreren Teilen, jedes mit seinem Filament - nach den Farben aus
 * `color()` oder nach den Werten eines Customizer-Parameters (z. B. `part = "body"` / `"label"`, je Wert ein Lauf).
 * Aufbau der Datei in [MultiPart3mf].
 */
class MultiColorExportAction : DumbAwareAction(ScadSphereBundle.messagePointer("action.exportMultiColor"), ScadSphereBundle.messagePointer("action.exportMultiColor.description")) {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null && e.scadFile() != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val file = e.scadFile() ?: return
        val exe = OpenScad.executable() ?: return notFound(project)
        val parameters = ApplicationManager.getApplication().runReadAction(Computable {
            (PsiManager.getInstance(project).findFile(file) as? ScadFile)?.let { psi ->
                ScadCustomizer.parameters(psi).map { it.parameter }.filter { !it.options.isNullOrEmpty() }
            }.orEmpty()
        })
        val dialog = MultiColorDialog(project, file, parameters)
        if (!dialog.showAndGet()) return
        val target = dialog.target() ?: return
        val request = dialog.request()
        dialog.remember()
        FileDocumentManager.getInstance().getDocument(file)?.let { FileDocumentManager.getInstance().saveDocument(it) }
        MultiColorExport.run(project, exe, file, target, request)
    }
}

/** Was exportiert wird: nach Farben oder je Wert eines Parameters (Wert als OpenSCAD-Ausdruck, Name, Filament). */
sealed interface MultiColorRequest {
    data object ByColor : MultiColorRequest
    data class ByParameter(val name: String, val values: List<Triple<String, String, Int>>) : MultiColorRequest
}

object MultiColorExport {
    fun run(project: Project, exe: String, file: VirtualFile, target: File, request: MultiColorRequest) {
        val defines = ScadCustomizer.defines(project, file)
        object : Task.Backgroundable(project, ScadSphereBundle.message("multiColor.progress", target.name), true) {
            private var parts: List<MultiPart3mf.Part> = emptyList()
            private var messages: List<RenderMessage> = emptyList()
            private var problem: String? = null

            override fun run(indicator: ProgressIndicator) {
                val dir = FileUtil.createTempDirectory("scadsphere-multicolor", null, true)
                try {
                    val (result, error) = when (request) {
                        is MultiColorRequest.ByColor -> byColor(exe, File(file.path), defines, dir, indicator)
                        is MultiColorRequest.ByParameter -> byParameter(exe, File(file.path), defines, request, dir, indicator)
                    }
                    parts = result
                    messages = error
                    if (parts.isEmpty() && messages.isEmpty()) problem = ScadSphereBundle.message("multiColor.empty")
                    else if (request is MultiColorRequest.ByColor && parts.size == 1) problem = ScadSphereBundle.message("multiColor.oneColor")
                    if (parts.isNotEmpty() && problem == null) target.writeBytes(MultiPart3mf.write(target.nameWithoutExtension, parts))
                } finally {
                    FileUtil.delete(dir)
                }
            }

            override fun onSuccess() {
                problem?.let { return notify(project, it, NotificationType.WARNING) }
                if (parts.isEmpty()) {
                    val errors = messages.filter { it.level == RenderMessage.Level.ERROR }.ifEmpty { messages }.take(5)
                    return notify(project, ScadSphereBundle.message("export.failed", target.name) + errors.joinToString("<br>", prefix = "<br>") { it.text }, NotificationType.ERROR)
                }
                val exported = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(target)
                val summary = parts.joinToString(", ") { ScadSphereBundle.message("multiColor.partSummary", it.name, it.filament) }
                val notification = NotificationGroupManager.getInstance().getNotificationGroup(NOTIFICATIONS)
                    .createNotification(ScadSphereBundle.message("multiColor.done", target.name, summary), NotificationType.INFORMATION)
                if (exported != null) {
                    notification.addAction(NotificationAction.createSimpleExpiring(ScadSphereBundle.message("export.open")) { OpenFileDescriptor(project, exported).navigate(true) })
                }
                Slicer.configured()?.let { slicer ->
                    notification.addAction(NotificationAction.createSimpleExpiring(ScadSphereBundle.message("action.openInSlicer.named", slicer.name)) {
                        runCatching { Slicer.open(slicer, target) }
                            .onFailure { notify(project, ScadSphereBundle.message("openInSlicer.failed", slicer.name, it.message ?: ""), NotificationType.ERROR) }
                    })
                }
                notification.notify(project)
            }
        }.queue()
    }

    /** Ein CSG-Lauf, dann je Farbe ein Lauf mit nur ihren Teilen ([ColorParts]); Filamente in der Reihenfolge der Farben. */
    fun byColor(exe: String, file: File, defines: List<String>, dir: File, indicator: ProgressIndicator): Pair<List<MultiPart3mf.Part>, List<RenderMessage>> {
        val render = OpenScadRender(exe)
        val tree = File(dir, "tree.csg")
        indicator.text2 = ScadSphereBundle.message("multiColor.progressTree")
        val csg = render.export(file, tree, ExportOptions(), defines, indicator)
        if (!csg.ok) return emptyList<MultiPart3mf.Part>() to csg.messages
        val split = ColorParts.split(tree.readText()) ?: return emptyList<MultiPart3mf.Part>() to emptyList()
        indicator.isIndeterminate = false
        val parts = mutableListOf<MultiPart3mf.Part>()
        split.forEachIndexed { index, part ->
            indicator.checkCanceled()
            val name = colorName(part.color)
            indicator.text2 = name
            indicator.fraction = index.toDouble() / split.size
            val output = File(dir, "part-$index.3mf")
            val result = render.exportSource(file, part.source, output, indicator)
            if (!result.ok) return emptyList<MultiPart3mf.Part>() to result.messages
            parts += MultiPart3mf.Part(name, index + 1, MultiPart3mf.readMesh(output.readBytes()))
        }
        return parts.filter { !it.mesh.isEmpty } to emptyList()
    }

    /** Je gewähltem Wert ein Lauf mit `-D name=wert` (nach den Werten aus dem Customizer, gewinnt also). */
    fun byParameter(exe: String, file: File, defines: List<String>, request: MultiColorRequest.ByParameter, dir: File, indicator: ProgressIndicator): Pair<List<MultiPart3mf.Part>, List<RenderMessage>> {
        val render = OpenScadRender(exe)
        indicator.isIndeterminate = false
        val parts = mutableListOf<MultiPart3mf.Part>()
        request.values.forEachIndexed { index, (expression, label, filament) ->
            indicator.checkCanceled()
            indicator.text2 = label
            indicator.fraction = index.toDouble() / request.values.size
            val output = File(dir, "part-$index.3mf")
            val result = render.export(file, output, ExportOptions(), defines + "${request.name}=$expression", indicator)
            if (!result.ok) {
                // Ein Wert ohne Geometrie (leeres Teil) ist kein Fehler, nur fehlt das Teil dann
                if (result.messages.any { it.level == RenderMessage.Level.ERROR && !EMPTY.containsMatchIn(it.text) }) return emptyList<MultiPart3mf.Part>() to result.messages
                return@forEachIndexed
            }
            parts += MultiPart3mf.Part(label, filament, MultiPart3mf.readMesh(output.readBytes()))
        }
        return parts.filter { !it.mesh.isEmpty } to emptyList()
    }

    /** Lesbarer Name einer Farbe: CSS-Name, sonst `#rrggbb`; ohne Farbe „Default“. */
    fun colorName(color: String): String = when (color) {
        ColorParts.NO_COLOR -> ScadSphereBundle.message("multiColor.noColor")
        else -> ScadColorProvider.nameOf(color) ?: "#$color"
    }

    private val EMPTY = Regex("""top level object is empty|Current top level object is empty""", RegexOption.IGNORE_CASE)
}

/**
 * Teile nach Farben oder nach den Werten eines Parameters mit Auswahlliste; bei Werten je Zeile ein Haken und das
 * Filament. Die Wahl wird je Datei gemerkt.
 */
class MultiColorDialog(private val project: Project, private val file: VirtualFile, private val parameters: List<CustomizerParameter>) : DialogWrapper(project) {
    private val byColor = JBRadioButton(ScadSphereBundle.message("multiColor.byColor"))
    private val byParameter = JBRadioButton(ScadSphereBundle.message("multiColor.byParameter"))
    private val parameterBox = ComboBox(parameters.map { it.name }.toTypedArray())
    private val valuesPanel = JPanel(GridBagLayout())
    private val rows = mutableListOf<Triple<JBCheckBox, JBIntSpinner, String>>()
    private val pathField = TextFieldWithBrowseButton()
    private val stored = Stored.read(project, file)

    init {
        title = ScadSphereBundle.message("multiColor.title")
        setOKButtonText(ScadSphereBundle.message("export.dialog.ok"))
        ButtonGroup().apply { add(byColor); add(byParameter) }
        byParameter.isEnabled = parameters.isNotEmpty()
        val useParameter = stored?.parameter?.let { name -> parameters.any { it.name == name } } ?: false
        (if (useParameter) byParameter else byColor).isSelected = true
        stored?.parameter?.takeIf { useParameter }?.let { parameterBox.selectedItem = it }
        parameterBox.addActionListener { fillValues() }
        byColor.addActionListener { updateEnabled() }
        byParameter.addActionListener { updateEnabled() }
        pathField.text = (stored?.target?.let(::File)?.takeIf { it.parentFile?.isDirectory == true }
            ?: File(file.parent.path, "${file.nameWithoutExtension}-multicolor.3mf")).path
        pathField.addActionListener { browse() }
        fillValues()
        updateEnabled()
        init()
        initValidation()
    }

    override fun createCenterPanel(): JComponent = panel {
        row { cell(byColor) }
        row { comment(ScadSphereBundle.message("multiColor.byColor.comment"), 70) }
        row {
            cell(byParameter)
            cell(parameterBox)
        }
        if (parameters.isEmpty()) row { comment(ScadSphereBundle.message("multiColor.noParameters"), 70) }
        row { cell(valuesPanel).align(AlignX.FILL) }
        row(ScadSphereBundle.message("export.dialog.file")) { cell(pathField).align(AlignX.FILL).resizableColumn() }
        row { comment(ScadSphereBundle.message("multiColor.comment"), 70) }
    }.apply { preferredSize = preferredSize.apply { width = maxOf(width, 560) } }

    private fun selectedParameter(): CustomizerParameter? = parameters.firstOrNull { it.name == parameterBox.selectedItem }

    /** Zeilen für die Werte des gewählten Parameters: Haken (gemerkt) und Filament (gemerkt, sonst 1, 2, 3, …). */
    private fun fillValues() {
        valuesPanel.removeAll()
        rows.clear()
        val parameter = selectedParameter() ?: return
        val remembered = stored?.takeIf { it.parameter == parameter.name }?.values.orEmpty()
        val header = GridBagConstraints().apply { gridy = 0; anchor = GridBagConstraints.WEST; insets = JBUI.insets(0, 24, 4, 12) }
        valuesPanel.add(JBLabel(ScadSphereBundle.message("multiColor.value")), header.apply { gridx = 0 })
        valuesPanel.add(JBLabel(ScadSphereBundle.message("multiColor.filament")), GridBagConstraints().apply { gridx = 1; gridy = 0; anchor = GridBagConstraints.WEST; insets = JBUI.insetsBottom(4) })
        parameter.options!!.forEachIndexed { index, option ->
            val expression = option.value.toScad()
            val memory = remembered[expression]
            val box = JBCheckBox(option.label, memory?.first ?: true)
            val spinner = JBIntSpinner(memory?.second ?: (index + 1).coerceAtMost(MAX_FILAMENT), 1, MAX_FILAMENT)
            box.addActionListener { spinner.isEnabled = box.isSelected && byParameter.isSelected }
            rows += Triple(box, spinner, expression)
            valuesPanel.add(box, GridBagConstraints().apply { gridx = 0; gridy = index + 1; anchor = GridBagConstraints.WEST; insets = JBUI.insets(0, 20, 2, 12) })
            valuesPanel.add(spinner, GridBagConstraints().apply { gridx = 1; gridy = index + 1; anchor = GridBagConstraints.WEST; insets = JBUI.insetsBottom(2) })
        }
        valuesPanel.add(JPanel(), GridBagConstraints().apply { gridx = 2; gridy = 0; weightx = 1.0 })
        updateEnabled()
        valuesPanel.revalidate()
        valuesPanel.repaint()
    }

    private fun updateEnabled() {
        val on = byParameter.isSelected
        parameterBox.isEnabled = on
        for ((box, spinner) in rows) {
            box.isEnabled = on
            spinner.isEnabled = on && box.isSelected
        }
    }

    private fun browse() {
        val descriptor = FileSaverDescriptor(ScadSphereBundle.message("export.dialog.browse"), "", "3mf")
        val current = target()
        val dir = current?.parentFile?.let { LocalFileSystem.getInstance().findFileByIoFile(it) } ?: file.parent
        FileChooserFactory.getInstance().createSaveFileDialog(descriptor, project).save(dir, current?.name ?: "")?.file?.let { pathField.text = it.path }
    }

    fun target(): File? {
        val text = pathField.text.trim()
        if (text.isEmpty()) return null
        val target = File(text)
        return if (target.extension.equals("3mf", ignoreCase = true)) target else File(target.path + ".3mf")
    }

    fun request(): MultiColorRequest {
        val parameter = selectedParameter()
        if (!byParameter.isSelected || parameter == null) return MultiColorRequest.ByColor
        val labels = parameter.options!!.associate { it.value.toScad() to it.label }
        return MultiColorRequest.ByParameter(
            parameter.name,
            rows.filter { it.first.isSelected }.map { (_, spinner, expression) -> Triple(expression, labels[expression] ?: expression, spinner.number) },
        )
    }

    fun remember() {
        val parameter = selectedParameter()?.takeIf { byParameter.isSelected }
        Stored(parameter?.name, target()?.path, rows.associate { (box, spinner, expression) -> expression to (box.isSelected to spinner.number) }.takeIf { parameter != null }.orEmpty())
            .write(project, file)
    }

    override fun doValidateAll(): List<ValidationInfo> {
        val target = target() ?: return listOf(ValidationInfo(ScadSphereBundle.message("export.dialog.noFile"), pathField))
        if (target.parentFile?.isDirectory != true) return listOf(ValidationInfo(ScadSphereBundle.message("export.dialog.noFolder"), pathField))
        if (byParameter.isSelected && rows.count { it.first.isSelected } < 1) return listOf(ValidationInfo(ScadSphereBundle.message("multiColor.noValues"), valuesPanel))
        if (target.exists()) return listOf(ValidationInfo(ScadSphereBundle.message("export.dialog.exists"), pathField).asWarning().withOKEnabled())
        return emptyList()
    }

    /** Gemerkte Wahl je Datei (Projekt-Einstellung): Parameter (null = nach Farben), Ziel, je Wert Haken und Filament. */
    private class Stored(val parameter: String?, val target: String?, val values: Map<String, Pair<Boolean, Int>>) {
        fun write(project: Project, file: VirtualFile) {
            val text = listOf(parameter.orEmpty(), target.orEmpty()) + values.map { (expression, value) -> "${if (value.first) 1 else 0}:${value.second}:$expression" }
            PropertiesComponent.getInstance(project).setList(key(file), text)
        }

        companion object {
            fun read(project: Project, file: VirtualFile): Stored? {
                val list = PropertiesComponent.getInstance(project).getList(key(file)) ?: return null
                if (list.size < 2) return null
                val values = list.drop(2).mapNotNull { entry ->
                    val parts = entry.split(':', limit = 3)
                    if (parts.size < 3) return@mapNotNull null
                    parts[2] to ((parts[0] == "1") to (parts[1].toIntOrNull() ?: 1))
                }.toMap()
                return Stored(list[0].ifEmpty { null }, list[1].ifEmpty { null }, values)
            }

            private fun key(file: VirtualFile) = "scadsphere.multiColor.${file.path}"
        }
    }

    private companion object {
        const val MAX_FILAMENT = 16
    }
}
