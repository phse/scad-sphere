package app.heckit.ide.scadsphere.customizer

import app.heckit.ide.scadsphere.ScadFileStates
import app.heckit.ide.scadsphere.ScadSphereBundle
import app.heckit.ide.scadsphere.textRenderer
import com.google.gson.JsonElement
import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.MAX_LINE_LENGTH_WORD_WRAP
import com.intellij.ui.dsl.builder.Panel
import com.intellij.ui.dsl.builder.RowLayout
import com.intellij.ui.dsl.builder.TopGap
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.ui.EmptyIcon
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Cursor
import java.awt.Dimension
import java.awt.Font
import java.awt.GridLayout
import java.awt.Rectangle
import java.awt.event.FocusAdapter
import java.awt.event.FocusEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.math.BigDecimal
import java.math.RoundingMode
import java.text.DecimalFormatSymbols
import java.util.Locale
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JSlider
import javax.swing.JSpinner
import javax.swing.JViewport
import javax.swing.Scrollable
import javax.swing.ScrollPaneConstants
import javax.swing.SpinnerNumberModel
import javax.swing.event.DocumentEvent
import javax.swing.text.AbstractDocument
import javax.swing.text.AttributeSet
import javax.swing.text.DefaultFormatter
import javax.swing.text.DocumentFilter
import kotlin.math.roundToInt

/**
 * Customizer neben der Vorschau: die Parameter der Datei als Regler, Auswahllisten, Häkchen und Textfelder.
 * Geänderte Werte landen nicht im Code, sondern in [ScadFileStates] und gehen beim Rechnen per `-D` an OpenSCAD.
 * Oben: Parametersätze (OpenSCADs `<datei>.json`), zurücksetzen und die Werte in den Code übernehmen.
 */
class CustomizerPanel(
    private val project: Project,
    private val file: VirtualFile,
    parent: Disposable,
    private val onChanged: () -> Unit,
) : JPanel(BorderLayout()), Disposable {
    private val states = ScadFileStates.of(project)
    private var parameters: List<CustomizerParameter> = emptyList()
    private var sets: Map<String, Map<String, JsonElement>> = emptyMap()
    private val setCombo = ComboBox<SetItem>()
    private var updatingCombo = false
    private val form = ScrollableForm()
    private val rows = mutableMapOf<String, RowWidgets>()
    private val collapsed = mutableSetOf<String>()

    /** Eintrag der Satz-Auswahl; null = Werte aus dem Code. */
    private data class SetItem(val name: String?)

    private class RowWidgets(val name: JComponent, val reset: JBLabel)

    val hasParameters: Boolean get() = parameters.isNotEmpty()

    init {
        Disposer.register(parent, this)
        setCombo.renderer = textRenderer { item ->
            val name = item?.name
            when {
                name == null -> ScadSphereBundle.message("customizer.codeValues")
                name == states.parameterSet(file) && isModified() -> ScadSphereBundle.message("customizer.setModified", name)
                else -> name
            }
        }
        setCombo.addActionListener {
            if (updatingCombo) return@addActionListener
            val item = setCombo.selectedItem as? SetItem ?: return@addActionListener
            if (item.name == null) setValues(emptyMap(), null) else applySet(item.name)
        }
        val toolbar = ActionManager.getInstance().createActionToolbar(
            "ScadSphereCustomizer",
            DefaultActionGroup(SaveSetAction(), DeleteSetAction(), ResetAction(), WriteToCodeAction()),
            true,
        )
        toolbar.targetComponent = this
        // Satz-Auswahl über die volle Breite, die Knöpfe darunter (das Panel ist oft schmal)
        setCombo.setMinimumAndPreferredWidth(JBUI.scale(80))
        toolbar.component.border = JBUI.Borders.empty()
        // Ausblenden direkt am Panel (wie bei Tool-Fenstern); einblenden über den Knopf in der Editor-Leiste
        val hideToolbar = ActionManager.getInstance().createActionToolbar("ScadSphereCustomizerHide", DefaultActionGroup(HideAction()), true)
        hideToolbar.targetComponent = this
        hideToolbar.component.border = JBUI.Borders.empty()
        val header = JPanel(BorderLayout(0, JBUI.scale(2))).apply {
            border = JBUI.Borders.compound(JBUI.Borders.customLineBottom(JBColor.border()), JBUI.Borders.empty(6, 8, 2, 8))
            add(setCombo, BorderLayout.NORTH)
            add(toolbar.component, BorderLayout.CENTER)
            add(hideToolbar.component, BorderLayout.EAST)
        }
        add(header, BorderLayout.NORTH)
        add(JBScrollPane(form, ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED, ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED).apply {
            border = JBUI.Borders.empty()
        }, BorderLayout.CENTER)
        minimumSize = JBUI.size(220, 100)
        // Zweite Vorschau derselben Datei (anderes Fenster) bleibt auf demselben Stand
        states.addListener(this) { path, source ->
            if (path == file.path && source !== this) {
                rebuildForm()
                updateCombo()
            }
        }
        reloadSets()
    }

    /** Neue Parameter nach einer Änderung im Code; das Formular wird nur neu gebaut, wenn sich etwas geändert hat. */
    fun update(newParameters: List<CustomizerParameter>) {
        if (newParameters == parameters) return
        parameters = newParameters
        rebuildForm()
        updateCombo()
    }

    /** `<datei>.json` neu lesen (geändert, angelegt oder gelöscht). */
    fun reloadSets() {
        sets = ParameterSets.read(file)
        updateCombo()
    }

    private fun overrides(): Map<String, String> = states.overrides(file)

    private fun value(parameter: CustomizerParameter, overrides: Map<String, String> = overrides()): ParameterValue =
        overrides[parameter.name]?.let(parameter::parse) ?: parameter.initial

    private fun setValues(overrides: Map<String, String>, set: String?) {
        states.setCustomizer(file, overrides, set, this)
        rebuildForm()
        updateCombo()
        onChanged()
    }

    /** Ein Wert wurde im Formular geändert. */
    private fun changed(parameter: CustomizerParameter, value: ParameterValue) {
        val overrides = overrides().toMutableMap()
        if (value == parameter.initial) overrides.remove(parameter.name) else overrides[parameter.name] = value.toScad()
        if (overrides == overrides()) return
        states.setCustomizer(file, overrides, states.parameterSet(file), this)
        updateRow(parameter)
        setCombo.repaint()
        onChanged()
    }

    private fun valueInSet(parameter: CustomizerParameter, values: Map<String, JsonElement>): ParameterValue =
        values[parameter.name]?.let { ScadCustomizer.parseSetValue(it, parameter.type) } ?: parameter.initial

    private fun applySet(name: String) {
        val values = sets[name] ?: return
        val overrides = LinkedHashMap<String, String>()
        for (parameter in parameters) {
            val value = valueInSet(parameter, values)
            if (value != parameter.initial) overrides[parameter.name] = value.toScad()
        }
        setValues(overrides, name)
    }

    /** Weichen die aktuellen Werte vom gewählten Satz ab? */
    private fun isModified(): Boolean {
        val name = states.parameterSet(file) ?: return false
        val values = sets[name] ?: return false
        val overrides = overrides()
        return parameters.any { value(it, overrides) != valueInSet(it, values) }
    }

    private fun updateCombo() {
        updatingCombo = true
        try {
            val selected = states.parameterSet(file)?.takeIf { it in sets }
            setCombo.removeAllItems()
            setCombo.addItem(SetItem(null))
            sets.keys.forEach { setCombo.addItem(SetItem(it)) }
            setCombo.selectedItem = SetItem(selected)
            setCombo.toolTipText = ScadSphereBundle.message("customizer.sets.tooltip", ParameterSets.fileName(file))
        } finally {
            updatingCombo = false
        }
    }

    // --- Formular ---

    private fun rebuildForm() {
        rows.clear()
        val overrides = overrides()
        val content = panel {
            for ((group, members) in parameters.groupBy { it.group }) {
                val section = collapsibleGroup(group, indent = false) {
                    members.forEachIndexed { index, parameter -> parameterRows(parameter, value(parameter, overrides), index > 0) }
                }
                section.expanded = group !in collapsed
                section.addExpandedListener { expanded -> if (expanded) collapsed -= group else collapsed += group }
            }
        }
        content.border = JBUI.Borders.empty(0, 8, 8, 6)
        form.removeAll()
        form.add(content, BorderLayout.NORTH)
        form.revalidate()
        form.repaint()
        parameters.forEach(::updateRow)
    }

    /**
     * Zeilen eines Parameters: Häkchen und Zahlenfelder neben dem Namen, Schieberegler (Zahl neben dem Namen),
     * Auswahllisten, Text und Vektoren in der Zeile darunter; die Beschreibung umbricht.
     */
    private fun Panel.parameterRows(parameter: CustomizerParameter, value: ParameterValue, gap: Boolean) {
        val reset = resetButton(parameter)
        val topGap = if (gap) TopGap.SMALL else TopGap.NONE
        // Lange Namen dürfen gekürzt werden, damit Feld und Zurücksetzen-Knopf sichtbar bleiben
        val label = JBLabel(parameter.name).apply { minimumSize = Dimension(JBUI.scale(24), minimumSize.height) }
        label.toolTipText = parameter.name
        var name: JComponent = label
        when {
            value is ParameterValue.Bool && parameter.options == null -> {
                val box = JBCheckBox(parameter.name, value.value)
                box.addActionListener { changed(parameter, ParameterValue.Bool(box.isSelected)) }
                name = box
                row {
                    cell(box).resizableColumn()
                    cell(reset)
                }.layout(RowLayout.INDEPENDENT).topGap(topGap)
            }
            value is ParameterValue.Number && parameter.options == null && parameter.isSlider -> {
                val slider = SliderField(parameter.min!!, parameter.max!!, parameter.step ?: 1.0, value.value) { changed(parameter, ParameterValue.Number(it)) }
                row {
                    cell(label).resizableColumn()
                    cell(slider.field)
                    cell(reset)
                }.layout(RowLayout.INDEPENDENT).topGap(topGap)
                row { cell(slider.slider).align(AlignX.FILL) }.layout(RowLayout.INDEPENDENT)
            }
            value is ParameterValue.Number && parameter.options == null -> {
                val spinner = spinner(value.value, parameter.min, parameter.max, parameter.step ?: defaultStep(value.value)) {
                    changed(parameter, ParameterValue.Number(it))
                }
                row {
                    cell(label).resizableColumn()
                    cell(spinner)
                    cell(reset)
                }.layout(RowLayout.INDEPENDENT).topGap(topGap)
            }
            else -> {
                row {
                    cell(label).resizableColumn()
                    cell(reset)
                }.layout(RowLayout.INDEPENDENT).topGap(topGap)
                row { cell(control(parameter, value)).align(AlignX.FILL) }.layout(RowLayout.INDEPENDENT)
            }
        }
        parameter.description?.let { text ->
            row { comment(StringUtil.escapeXmlEntities(text), MAX_LINE_LENGTH_WORD_WRAP) }.layout(RowLayout.INDEPENDENT)
        }
        rows[parameter.name] = RowWidgets(name, reset)
    }

    /** Geänderte Werte: Name fett, daneben ein Knopf zum Zurücksetzen (sonst Platzhalter, damit nichts springt). */
    private fun updateRow(parameter: CustomizerParameter) {
        val row = rows[parameter.name] ?: return
        val changed = parameter.name in overrides()
        row.name.font = row.name.font.deriveFont(if (changed) Font.BOLD else Font.PLAIN)
        row.reset.icon = if (changed) AllIcons.General.Reset else EmptyIcon.ICON_16
        row.reset.toolTipText = if (changed) ScadSphereBundle.message("customizer.resetValue", parameter.initial.toScad()) else null
        row.reset.cursor = if (changed) Cursor.getPredefinedCursor(Cursor.HAND_CURSOR) else Cursor.getDefaultCursor()
    }

    private fun resetButton(parameter: CustomizerParameter) = JBLabel(EmptyIcon.ICON_16).apply {
        addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(event: MouseEvent) {
                val overrides = overrides()
                if (parameter.name !in overrides) return
                setValues(overrides - parameter.name, states.parameterSet(file))
            }
        })
    }

    /** Eingabe in eigener Zeile unter dem Namen. */
    private fun control(parameter: CustomizerParameter, value: ParameterValue): JComponent {
        val options = parameter.options
        return when {
            options != null -> optionBox(parameter, options, value)
            value is ParameterValue.Text -> textField(parameter, value.value)
            value is ParameterValue.Vector -> vectorFields(parameter, value)
            else -> JBLabel(value.toScad())
        }
    }

    private fun optionBox(parameter: CustomizerParameter, options: List<ParameterOption>, value: ParameterValue): JComponent {
        // Steht im Code (oder im Satz) ein Wert, den die Liste nicht kennt, kommt er dazu
        val items = if (options.any { it.value == value }) options else listOf(ParameterOption(value, value.toSetValue())) + options
        val box = ComboBox(items.toTypedArray())
        box.setMinimumAndPreferredWidth(JBUI.scale(60))
        box.renderer = textRenderer { it?.label ?: "" }
        box.selectedItem = items.first { it.value == value }
        box.addActionListener { (box.selectedItem as? ParameterOption)?.let { changed(parameter, it.value) } }
        return box
    }

    private fun textField(parameter: CustomizerParameter, value: String): JComponent {
        val field = JBTextField(value, 6)
        parameter.maxLength?.let { max ->
            (field.document as? AbstractDocument)?.documentFilter = object : DocumentFilter() {
                override fun replace(fb: FilterBypass, offset: Int, length: Int, text: String?, attrs: AttributeSet?) {
                    val room = max - (fb.document.length - length)
                    super.replace(fb, offset, length, text?.take(room.coerceAtLeast(0)), attrs)
                }

                override fun insertString(fb: FilterBypass, offset: Int, text: String?, attr: AttributeSet?) = replace(fb, offset, 0, text, attr)
            }
        }
        field.document.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) = changed(parameter, ParameterValue.Text(field.text))
        })
        return field
    }

    private fun vectorFields(parameter: CustomizerParameter, value: ParameterValue.Vector): JComponent {
        val values = value.values.toMutableList()
        val panel = JPanel(GridLayout(1, values.size, JBUI.scale(4), 0))
        values.forEachIndexed { index, item ->
            panel.add(spinner(item, parameter.min, parameter.max, parameter.step ?: defaultStep(item), columns = 2) {
                values[index] = it
                changed(parameter, ParameterValue.Vector(values.toList()))
            })
        }
        // GridLayout teilt die verfügbare Breite; nicht breiter verlangen, als das Panel meist ist
        panel.preferredSize = Dimension(JBUI.scale(50) * values.size, panel.preferredSize.height)
        panel.minimumSize = Dimension(JBUI.scale(36) * values.size, panel.preferredSize.height)
        return panel
    }

    private fun spinner(value: Double, min: Double?, max: Double?, step: Double, columns: Int = 4, onChange: (Double) -> Unit): JSpinner {
        val start = value.coerceIn(min ?: Double.NEGATIVE_INFINITY, max ?: Double.POSITIVE_INFINITY)
        val spinner = JSpinner(SpinnerNumberModel(start, min, max, step))
        // Punkt als Dezimaltrenner wie im Code, Werte schon beim Tippen übernehmen
        val editor = JSpinner.NumberEditor(spinner, "0.##########")
        editor.format.decimalFormatSymbols = DecimalFormatSymbols(Locale.ROOT)
        spinner.editor = editor
        (editor.textField.formatter as? DefaultFormatter)?.commitsOnValidEdit = true
        editor.textField.value = spinner.value
        editor.textField.columns = columns
        spinner.addChangeListener { onChange(roundTo((spinner.value as Number).toDouble(), step)) }
        return spinner
    }

    // --- Aktionen oben rechts ---

    private abstract inner class PanelAction(text: String, description: String, icon: javax.swing.Icon) : DumbAwareAction(text, description, icon) {
        override fun getActionUpdateThread() = ActionUpdateThread.EDT
    }

    private inner class SaveSetAction : PanelAction(
        ScadSphereBundle.message("customizer.saveSet"), ScadSphereBundle.message("customizer.saveSet.description"), AllIcons.Actions.MenuSaveall,
    ) {
        override fun update(e: AnActionEvent) {
            e.presentation.isEnabled = hasParameters
        }

        override fun actionPerformed(e: AnActionEvent) {
            val current = states.parameterSet(file)
            val suggestion = current ?: ScadSphereBundle.message("customizer.saveSet.default", sets.size + 1)
            val name = Messages.showInputDialog(
                project, ScadSphereBundle.message("customizer.saveSet.prompt", ParameterSets.fileName(file)),
                ScadSphereBundle.message("customizer.saveSet.title"), null, suggestion, null,
            )?.trim()?.takeIf { it.isNotEmpty() } ?: return
            if (name != current && name in sets && Messages.showYesNoDialog(
                    project, ScadSphereBundle.message("customizer.saveSet.overwrite", name), ScadSphereBundle.message("customizer.saveSet.title"), null,
                ) != Messages.YES
            ) return
            val overrides = overrides()
            val values = parameters.associate { it.name to value(it, overrides).toSetValue() }
            ParameterSets.write(file, name, values)
            sets = ParameterSets.read(file)
            states.setCustomizer(file, overrides, name, this@CustomizerPanel)
            updateCombo()
        }
    }

    private inner class DeleteSetAction : PanelAction(
        ScadSphereBundle.message("customizer.deleteSet"), ScadSphereBundle.message("customizer.deleteSet.description"), AllIcons.General.Remove,
    ) {
        override fun update(e: AnActionEvent) {
            e.presentation.isEnabled = states.parameterSet(file)?.let { it in sets } == true
        }

        override fun actionPerformed(e: AnActionEvent) {
            val name = states.parameterSet(file) ?: return
            if (Messages.showYesNoDialog(project, ScadSphereBundle.message("customizer.deleteSet.confirm", name), ScadSphereBundle.message("customizer.deleteSet"), null) != Messages.YES) return
            ParameterSets.write(file, name, null)
            sets = ParameterSets.read(file)
            states.setCustomizer(file, overrides(), null, this@CustomizerPanel)
            updateCombo()
        }
    }

    private inner class ResetAction : PanelAction(
        ScadSphereBundle.message("customizer.reset"), ScadSphereBundle.message("customizer.reset.description"), AllIcons.General.Reset,
    ) {
        override fun update(e: AnActionEvent) {
            val set = states.parameterSet(file)?.takeIf { it in sets }
            e.presentation.isEnabled = if (set != null) isModified() else overrides().isNotEmpty()
        }

        override fun actionPerformed(e: AnActionEvent) {
            val set = states.parameterSet(file)?.takeIf { it in sets }
            if (set != null) applySet(set) else setValues(emptyMap(), null)
        }
    }

    private inner class HideAction : PanelAction(
        ScadSphereBundle.message("customizer.hide"), ScadSphereBundle.message("customizer.hide.description"), AllIcons.General.HideToolWindow,
    ) {
        override fun actionPerformed(e: AnActionEvent) = ToggleCustomizerAction.setVisible(false)
    }

    private inner class WriteToCodeAction : PanelAction(
        ScadSphereBundle.message("customizer.writeToCode"), ScadSphereBundle.message("customizer.writeToCode.description"), AllIcons.Actions.Edit,
    ) {
        override fun update(e: AnActionEvent) {
            e.presentation.isEnabled = overrides().isNotEmpty() && file.isWritable
        }

        override fun actionPerformed(e: AnActionEvent) {
            if (!ScadCustomizer.writeToCode(project, file, overrides())) return
            // Die Werte stehen jetzt im Code; die neuen Standardwerte liefert die nächste Auswertung des Codes
            states.setCustomizer(file, emptyMap(), null, this@CustomizerPanel)
            updateCombo()
        }
    }

    override fun dispose() = Unit

    companion object {
        /** Schrittweite ohne Angabe: 1 für ganze Zahlen, sonst die letzte Nachkommastelle (1.25 → 0.01). */
        fun defaultStep(value: Double): Double {
            val scale = BigDecimal.valueOf(value).stripTrailingZeros().scale().coerceIn(0, 6)
            return BigDecimal.ONE.movePointLeft(scale).toDouble()
        }

        /** Auf die Nachkommastellen der Schrittweite runden (0.1 + 0.2 = 0.3, nicht 0.30000000000000004). */
        fun roundTo(value: Double, step: Double): Double {
            val scale = BigDecimal.valueOf(step).stripTrailingZeros().scale().coerceAtLeast(0) + 2
            return BigDecimal.valueOf(value).setScale(scale, RoundingMode.HALF_UP).stripTrailingZeros().toDouble()
        }
    }
}

/** Schieberegler mit Zahlenfeld (neben dem Namen); das Feld nimmt auch Werte zwischen den Stufen. */
private class SliderField(
    private val min: Double,
    private val max: Double,
    private val step: Double,
    value: Double,
    private val onChange: (Double) -> Unit,
) {
    private val steps = (((max - min) / step).roundToInt()).coerceIn(1, 100_000)
    val slider = JSlider(0, steps, index(value))
    val field = JBTextField(formatNumber(value), 4)
    private var syncing = false

    init {
        slider.isOpaque = false
        slider.preferredSize = Dimension(JBUI.scale(60), slider.preferredSize.height)
        slider.addChangeListener {
            if (syncing) return@addChangeListener
            val current = CustomizerPanel.roundTo(min + slider.value * step, step)
            field.text = formatNumber(current)
            onChange(current)
        }
        field.horizontalAlignment = JBTextField.RIGHT
        field.addActionListener { commitField() }
        field.addFocusListener(object : FocusAdapter() {
            override fun focusLost(e: FocusEvent) = commitField()
        })
    }

    private fun index(value: Double) = ((value - min) / step).roundToInt().coerceIn(0, steps)

    private fun commitField() {
        val typed = field.text.trim().replace(',', '.').toDoubleOrNull()
        if (typed == null) {
            field.text = formatNumber(CustomizerPanel.roundTo(min + slider.value * step, step))
            return
        }
        val value = typed.coerceIn(min, max)
        syncing = true
        slider.value = index(value)
        syncing = false
        field.text = formatNumber(value)
        onChange(value)
    }
}

/**
 * Formular, das sich der Breite des sichtbaren Bereichs anpasst (Beschreibungen umbrechen); erst wenn es schmaler
 * würde, als die Felder brauchen, lässt es sich waagerecht scrollen statt abgeschnitten zu werden.
 */
private class ScrollableForm : JPanel(BorderLayout()), Scrollable {
    override fun getPreferredScrollableViewportSize(): Dimension = preferredSize
    override fun getScrollableUnitIncrement(visibleRect: Rectangle, orientation: Int, direction: Int) = JBUI.scale(16)
    override fun getScrollableBlockIncrement(visibleRect: Rectangle, orientation: Int, direction: Int) = (visibleRect.height - JBUI.scale(16)).coerceAtLeast(JBUI.scale(16))
    override fun getScrollableTracksViewportWidth() = (parent as? JViewport)?.let { it.width >= minimumSize.width } ?: true
    override fun getScrollableTracksViewportHeight() = false
}
