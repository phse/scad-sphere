package app.heckit.ide.scadsphere.export

import app.heckit.ide.scadsphere.ScadSphereBundle
import app.heckit.ide.scadsphere.textRenderer
import app.heckit.ide.scadsphere.render.ExportOptions
import com.intellij.openapi.fileChooser.FileChooserFactory
import com.intellij.openapi.fileChooser.FileSaverDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import java.io.File
import javax.swing.JComponent

/**
 * Ziel und Einstellungen für einen Export bzw. eine Umwandlung. Bei einer Datei mit Dateifeld, bei mehreren
 * Dateien landen die Ergebnisse neben den Originalen. Einstellungen gibt es nur, wenn die installierte Version
 * sie kennt; die letzte Wahl wird gemerkt.
 */
class ExportDialog(
    private val project: Project,
    private val format: ExportFormat,
    title: String,
    okText: String,
    private val defaultTarget: File?,
    private val batchTargets: List<File>,
    private val choices: List<ExportChoice>,
    private val note: String?,
    /** Namen der Parametersätze der Datei: Dann lässt sich „alle Sätze“ wählen (je Satz eine Datei). */
    private val sets: List<String> = emptyList(),
    allSetsDefault: Boolean = false,
) : DialogWrapper(project) {
    private val pathField = TextFieldWithBrowseButton()
    private val allSetsBox = JBCheckBox(ScadSphereBundle.message("export.dialog.allSets", sets.size), allSetsDefault && sets.isNotEmpty())
    private val boxes = choices.associateWith { choice ->
        ComboBox(choice.values.toTypedArray()).apply {
            renderer = textRenderer { it?.second ?: "" }
            selectedItem = choice.values.first { it.first == choice.remembered() }
        }
    }

    init {
        this.title = title
        setOKButtonText(okText)
        defaultTarget?.let { pathField.text = it.path }
        pathField.addActionListener { browse() }
        init()
        initValidation()
    }

    override fun createCenterPanel(): JComponent = panel {
        if (defaultTarget != null) {
            row(ScadSphereBundle.message("export.dialog.file")) {
                cell(pathField).align(AlignX.FILL).resizableColumn().focused()
            }
        } else {
            row {
                label(ScadSphereBundle.message("convert.dialog.files", batchTargets.size, format.label))
            }
        }
        for ((choice, box) in boxes) {
            row(choice.label) { cell(box) }
        }
        if (sets.isNotEmpty()) {
            row {
                cell(allSetsBox).comment(ScadSphereBundle.message("export.dialog.allSets.comment", StringUtil.escapeXmlEntities(sets.first())), 70)
            }
        }
        note?.let { text -> row { comment(StringUtil.escapeXmlEntities(text)) } }
    }.apply { preferredSize = preferredSize.apply { width = maxOf(width, 520) } }

    override fun getPreferredFocusedComponent(): JComponent? = if (defaultTarget != null) pathField.textField else null

    private fun browse() {
        val current = target()
        val descriptor = FileSaverDescriptor(ScadSphereBundle.message("export.dialog.browse"), "", format.extension)
        val dir = (current?.parentFile ?: defaultTarget?.parentFile)?.let { LocalFileSystem.getInstance().findFileByIoFile(it) }
        val name = current?.name ?: defaultTarget?.name ?: ""
        FileChooserFactory.getInstance().createSaveFileDialog(descriptor, project).save(dir, name)?.file?.let { pathField.text = it.path }
    }

    /** Eingegebenes Ziel, ohne passende Endung mit der des Formats. */
    fun target(): File? {
        val text = pathField.text.trim()
        if (text.isEmpty()) return null
        val file = File(text)
        return if (file.extension.equals(format.extension, ignoreCase = true)) file else File(file.path + "." + format.extension)
    }

    /** Alle Parametersätze exportieren statt der aktuellen Werte. */
    fun allSets(): Boolean = sets.isNotEmpty() && allSetsBox.isSelected

    /** Gewählte Einstellungen (und als zuletzt verwendet gemerkt). */
    fun options(): ExportOptions = boxes.entries.fold(ExportOptions()) { options, (choice, box) ->
        val value = (box.selectedItem as Pair<*, *>).first as String
        choice.remember(value)
        options + choice.options(value)
    }

    override fun doValidateAll(): List<ValidationInfo> {
        if (defaultTarget == null) {
            val existing = batchTargets.count { it.exists() }
            return if (existing > 0) listOf(ValidationInfo(ScadSphereBundle.message("convert.dialog.exists", existing)).asWarning().withOKEnabled()) else emptyList()
        }
        val target = target() ?: return listOf(ValidationInfo(ScadSphereBundle.message("export.dialog.noFile"), pathField))
        if (target.parentFile?.isDirectory != true) return listOf(ValidationInfo(ScadSphereBundle.message("export.dialog.noFolder"), pathField))
        if (allSets()) return emptyList()
        if (target.exists()) return listOf(ValidationInfo(ScadSphereBundle.message("export.dialog.exists"), pathField).asWarning().withOKEnabled())
        return emptyList()
    }
}
