package app.heckit.ide.scadsphere

import app.heckit.ide.scadsphere.psi.ScadLibraries
import app.heckit.ide.scadsphere.render.OpenScad
import app.heckit.ide.scadsphere.render.Slicer
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.fileChooser.FileChooserDescriptor
import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogPanel
import com.intellij.openapi.util.text.StringUtil
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.bind
import com.intellij.ui.dsl.builder.selected
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
import javax.swing.JSpinner

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
            row(ScadSphereBundle.message("settings.backend")) {
                comboBox(ScadSphereSettings.Backend.entries, textListCellRenderer { ScadSphereBundle.message("settings.backend." + (it ?: ScadSphereSettings.Backend.DEFAULT).name.lowercase()) })
                    .bindItem(ScadSphereSettings::backend.toNullableProperty())
                    .comment(ScadSphereBundle.message("settings.backend.comment"))
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
        group(ScadSphereBundle.message("settings.slicer")) {
            val slicers = Slicer.detect()
            row(ScadSphereBundle.message("settings.slicer.path")) {
                val field = textFieldWithBrowseButton(FileChooserDescriptor(true, true, false, false, false, false), null)
                    .bindText(ScadSphereSettings::slicerPath)
                    .align(AlignX.FILL)
                    .comment(
                        if (slicers.isEmpty()) ScadSphereBundle.message("settings.slicer.none")
                        else ScadSphereBundle.message("settings.slicer.found", slicers.joinToString(", ") { StringUtil.escapeXmlEntities(it.name) }),
                    )
                (field.component.textField as? JBTextField)?.emptyText?.text = slicers.firstOrNull()?.path ?: ScadSphereBundle.message("settings.path.notFound")
            }
        }
        group(ScadSphereBundle.message("settings.bed")) {
            lateinit var enabled: Cell<JBCheckBox>
            row {
                enabled = checkBox(ScadSphereBundle.message("settings.bed.show")).bindSelected(ScadSphereSettings::bedEnabled)
                    .comment(ScadSphereBundle.message("settings.bed.show.comment"))
            }
            lateinit var width: Cell<JSpinner>
            lateinit var depth: Cell<JSpinner>
            lateinit var height: Cell<JSpinner>
            row(ScadSphereBundle.message("settings.bed.preset")) {
                // Ohne passenden Drucker steht „Eigene Größe“ da (null) - der Renderer bekommt dann null
                comboBox(BedPreset.entries, textListCellRenderer { it?.label ?: ScadSphereBundle.message("settings.bed.preset.custom") })
                    .applyToComponent {
                        selectedItem = BedPreset.entries.firstOrNull {
                            it.width == ScadSphereSettings.bedWidth && it.depth == ScadSphereSettings.bedDepth && it.height == ScadSphereSettings.bedHeight
                        }
                        addActionListener {
                            val preset = selectedItem as? BedPreset ?: return@addActionListener
                            width.component.value = preset.width
                            depth.component.value = preset.depth
                            height.component.value = preset.height
                        }
                    }
                    .comment(ScadSphereBundle.message("settings.bed.preset.comment"))
            }.enabledIf(enabled.selected)
            row(ScadSphereBundle.message("settings.bed.size")) {
                width = spinner(10..5000, 1).bindIntValue(ScadSphereSettings::bedWidth)
                label("×")
                depth = spinner(10..5000, 1).bindIntValue(ScadSphereSettings::bedDepth)
                label("×")
                height = spinner(10..5000, 1).bindIntValue(ScadSphereSettings::bedHeight)
                label(ScadSphereBundle.message("settings.bed.size.unit"))
            }.enabledIf(enabled.selected)
            buttonsGroup(ScadSphereBundle.message("settings.bed.origin")) {
                row { radioButton(ScadSphereBundle.message("settings.bed.origin.center"), true) }
                row { radioButton(ScadSphereBundle.message("settings.bed.origin.corner"), false) }
            }.bind(ScadSphereSettings::bedCentered).enabledIf(enabled.selected)
        }
    }

    override fun apply() {
        super.apply()
        // Neue Bibliotheksordner sofort für include/use; External Libraries/Go to Symbol nach der nächsten
        // Änderung der Projektstruktur bzw. einem Neustart (der Weg zum sofortigen Neuindizieren ist experimentelle API)
        ScadLibraries.invalidate()
        ApplicationManager.getApplication().messageBus.syncPublisher(ViewerSettingsListener.TOPIC).changed()
    }

    /** Bekannte Drucker; Maße für eine Düse laut Hersteller. */
    private enum class BedPreset(val label: String, val width: Int, val depth: Int, val height: Int) {
        BAMBU_X2D("Bambu Lab X2D", 256, 256, 260),
        BAMBU_X1("Bambu Lab X1 / P1 / A1", 256, 256, 256),
        BAMBU_A1_MINI("Bambu Lab A1 mini", 180, 180, 180),
        BAMBU_H2D("Bambu Lab H2D", 350, 320, 325),
        PRUSA_MK4("Prusa MK4", 250, 210, 220),
        PRUSA_CORE_ONE("Prusa Core One", 250, 220, 270),
        PRUSA_MINI("Prusa MINI", 180, 180, 180),
        ENDER_3("Creality Ender-3 V3", 220, 220, 250),
    }
}
