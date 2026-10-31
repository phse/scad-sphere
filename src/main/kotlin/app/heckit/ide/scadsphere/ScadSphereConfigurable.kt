package app.heckit.ide.scadsphere

import app.heckit.ide.scadsphere.psi.ScadLibraries
import app.heckit.ide.scadsphere.render.OpenScad
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.fileChooser.FileChooserDescriptor
import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogPanel
import com.intellij.openapi.util.text.StringUtil
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.Cell
import com.intellij.ui.dsl.builder.bindIntValue
import com.intellij.ui.dsl.builder.bindItem
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.dsl.builder.rows
import com.intellij.ui.dsl.builder.toNullableProperty
import com.intellij.ui.dsl.listCellRenderer.textListCellRenderer
import com.intellij.ui.layout.selectedValueIs
import javax.swing.JEditorPane

/** Settings | Tools | SCAD Sphere: OpenSCAD, Bibliotheksordner, wann die Vorschau rechnet. */
class ScadSphereConfigurable : BoundConfigurable(ScadSphereBundle.message("settings.title")) {
    override fun createPanel(): DialogPanel = panel {
        group(ScadSphereBundle.message("settings.openscad")) {
            lateinit var found: JEditorPane
            row(ScadSphereBundle.message("settings.path")) {
                val field = textFieldWithBrowseButton(FileChooserDescriptor(true, true, false, false, false, false), null)
                    .bindText(ScadSphereSettings::openScadPath)
                    .align(AlignX.FILL)
                (field.component.textField as? JBTextField)?.emptyText?.text =
                    OpenScad.detect() ?: ScadSphereBundle.message("settings.path.notFound")
            }
            row("") {
                found = comment("").component
            }
            // Version im Hintergrund abfragen (startet OpenSCAD kurz)
            val exe = OpenScad.executable()
            if (exe == null) {
                found.text = ScadSphereBundle.message("settings.path.download")
            } else {
                ApplicationManager.getApplication().executeOnPooledThread {
                    val version = OpenScad.version(exe)
                    ApplicationManager.getApplication().invokeLater({
                        found.text = ScadSphereBundle.message("settings.path.found", StringUtil.escapeXmlEntities(exe), version ?: "?")
                    }, ModalityState.any())
                }
            }
            row(ScadSphereBundle.message("settings.libraries")) {
                textArea().bindText(ScadSphereSettings::libraryPaths).rows(3).align(AlignX.FILL)
                    .comment(ScadSphereBundle.message("settings.libraries.comment", StringUtil.escapeXmlEntities(ScadLibraries.userLibraryDir())))
            }
        }
        group(ScadSphereBundle.message("settings.preview")) {
            row {
                checkBox(ScadSphereBundle.message("settings.showPreview")).bindSelected(ScadSphereSettings::showPreview)
                    .comment(ScadSphereBundle.message("settings.showPreview.comment"))
            }
            lateinit var mode: Cell<ComboBox<ScadSphereSettings.RenderMode>>
            row(ScadSphereBundle.message("settings.renderMode")) {
                mode = comboBox(ScadSphereSettings.RenderMode.entries, textListCellRenderer { ScadSphereBundle.message("settings.renderMode." + it!!.name.lowercase()) })
                    .bindItem(ScadSphereSettings::renderMode.toNullableProperty())
            }
            row(ScadSphereBundle.message("settings.renderDelay")) {
                spinner(200..10000, 100).bindIntValue(ScadSphereSettings::renderDelay)
                label(ScadSphereBundle.message("settings.renderDelay.unit"))
            }.enabledIf(mode.component.selectedValueIs(ScadSphereSettings.RenderMode.TYPING))
        }
    }
}
