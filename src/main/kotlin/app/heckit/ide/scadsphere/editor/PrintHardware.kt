package app.heckit.ide.scadsphere.editor

import app.heckit.ide.scadsphere.ScadSphereBundle
import app.heckit.ide.scadsphere.ScadSphereSettings
import app.heckit.ide.scadsphere.customizer.formatNumber
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.psi.PsiDocumentManager
import com.intellij.ui.dsl.listCellRenderer.textListCellRenderer
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.layout.ComponentPredicate
import java.awt.event.ItemEvent
import javax.swing.JComponent
import javax.swing.JSpinner
import javax.swing.SpinnerNumberModel

/**
 * Bausteine für Druck-Hardware: Schraubenlöcher (auch mit Senkkopf), Mutterfallen, Löcher für Gewindeeinsätze,
 * Magnettaschen und Schnapphaken. Eingefügt wird ein Aufruf an der Schreibmarke und - einmal je Datei - ein eigenes
 * Modul am Dateiende, ohne Bibliothek. Taschen und Löcher zeigen von z = 0 nach unten (an die Oberfläche setzen und per
 * `difference()` abziehen); das Spiel kommt aus den Einstellungen ([ScadSphereSettings.fitClearance]).
 */
object PrintHardware {
    enum class Kind(val module: String) {
        SCREW("screw_hole"), COUNTERSUNK("screw_hole"), NUT("nut_trap"), INSERT("heat_insert_hole"), MAGNET("magnet_pocket"), SNAP("snap_hook");

        val label: String get() = ScadSphereBundle.message("hardware.kind.${name.lowercase()}")
    }

    /** Gewindegrößen mit Schlüsselweite und Höhe der Mutter (ISO 4032) */
    val SIZES = listOf(2.0, 2.5, 3.0, 4.0, 5.0, 6.0, 8.0)

    /** Werte des Dialogs; ungenutzte Felder bleiben unbeachtet. */
    data class Choice(
        val kind: Kind,
        val size: Double = 3.0,
        val length: Double = 10.0,
        val slot: Double = 0.0,
        val diameter: Double = 6.0,
        val height: Double = 3.0,
        val width: Double = 5.0,
        val thickness: Double = 1.5,
        val overhang: Double = 1.0,
    )

    /** Aufruf an der Schreibmarke, z. B. `screw_hole(m = 3, length = 10);`. */
    fun call(choice: Choice, clearance: Double): String {
        val c = n(clearance)
        val m = n(choice.size)
        return when (choice.kind) {
            Kind.SCREW -> "screw_hole(m = $m, length = ${n(choice.length)}, clearance = $c);"
            Kind.COUNTERSUNK -> "screw_hole(m = $m, length = ${n(choice.length)}, countersunk = true, clearance = $c);"
            Kind.NUT -> if (choice.slot > 0) "nut_trap(m = $m, slot = ${n(choice.slot)}, clearance = $c);" else "nut_trap(m = $m, clearance = $c);"
            Kind.INSERT -> "heat_insert_hole(m = $m);"
            Kind.MAGNET -> "magnet_pocket(d = ${n(choice.diameter)}, h = ${n(choice.height)}, clearance = $c);"
            Kind.SNAP -> "snap_hook(length = ${n(choice.length)}, width = ${n(choice.width)}, thickness = ${n(choice.thickness)}, overhang = ${n(choice.overhang)});"
        }
    }

    /** Modul zum Aufruf; steht `module <name>(` schon in der Datei, wird es nicht noch einmal angehängt. */
    fun module(kind: Kind): String = when (kind) {
        Kind.SCREW, Kind.COUNTERSUNK -> """
            |// Hole for a metric screw (SCAD Sphere): from z = 0 downwards, subtract it with difference().
            |// m = thread size, countersunk = 90° head (DIN 7991) at z = 0, clearance = extra room.
            |module screw_hole(m = 3, length = 10, countersunk = false, clearance = 0.2) {
            |    d = m + 2 * clearance;
            |    translate([0, 0, -length]) cylinder(d = d, h = length + 1, ${'$'}fn = 32);
            |    if (countersunk) {
            |        head = 2 * m + 2 * clearance;
            |        translate([0, 0, -(head - d) / 2]) cylinder(d1 = d, d2 = head, h = (head - d) / 2 + 0.01, ${'$'}fn = 32);
            |        cylinder(d = head, h = 1, ${'$'}fn = 32);
            |    }
            |}
            """
        Kind.NUT -> """
            |// Pocket for a hex nut (ISO 4032, SCAD Sphere): from z = 0 downwards, subtract it with difference().
            |// slot > 0 opens it towards +X, so the nut slides in from the side.
            |module nut_trap(m = 3, slot = 0, clearance = 0.2) {
            |    sizes = [[2, 4, 1.6], [2.5, 5, 2], [3, 5.5, 2.4], [4, 7, 3.2], [5, 8, 4], [6, 10, 5], [8, 13, 6.5]];
            |    row = sizes[search(m, sizes)[0]];
            |    flats = row[1] + 2 * clearance;
            |    depth = row[2] + clearance;
            |    translate([0, 0, -depth]) {
            |        cylinder(d = flats / cos(30), h = depth + 1, ${'$'}fn = 6);
            |        if (slot > 0) translate([0, -flats / 2, 0]) cube([slot, flats, depth]);
            |    }
            |}
            """
        Kind.INSERT -> """
            |// Hole for a heat-set threaded insert (common sizes, SCAD Sphere): from z = 0 downwards, subtract it.
            |// 1 mm deeper than the insert, for the plastic it pushes down. Check the size of your inserts.
            |module heat_insert_hole(m = 3) {
            |    sizes = [[2, 3.2, 4], [2.5, 3.6, 5.7], [3, 4, 5.7], [4, 5.6, 8.1], [5, 6.4, 9.5], [6, 8, 12.7], [8, 9.6, 12.7]];
            |    row = sizes[search(m, sizes)[0]];
            |    translate([0, 0, -row[2] - 1]) cylinder(d = row[1], h = row[2] + 2, ${'$'}fn = 32);
            |}
            """
        Kind.MAGNET -> """
            |// Pocket for a round magnet (SCAD Sphere): from z = 0 downwards, subtract it with difference().
            |module magnet_pocket(d = 6, h = 3, clearance = 0.2) {
            |    translate([0, 0, -h - clearance]) cylinder(d = d + 2 * clearance, h = h + clearance + 1, ${'$'}fn = 48);
            |}
            """
        Kind.SNAP -> """
            |// Snap-fit hook (SCAD Sphere): a beam along +Z with the hook at the top pointing towards +X.
            |// Add it to the part (union); the counterpart needs an edge of overhang + clearance to catch on.
            |module snap_hook(length = 10, width = 5, thickness = 1.5, overhang = 1) {
            |    translate([0, width, 0]) rotate([90, 0, 0]) linear_extrude(height = width)
            |        polygon([[0, 0], [thickness, 0], [thickness, length], [thickness + overhang, length],
            |                 [thickness, length + 2 * overhang], [0, length + 2 * overhang]]);
            |}
            """
    }.trimMargin().trim() + "\n"

    /** Text an der Schreibmarke und am Dateiende einfügen (eine Undo-Einheit). */
    fun insert(project: Project, editor: Editor, choice: Choice, clearance: Double) {
        val document = editor.document
        WriteCommandAction.writeCommandAction(project).withName(ScadSphereBundle.message("hardware.command")).run<RuntimeException> {
            val text = document.charsSequence
            val kind = choice.kind
            if (!Regex("""\bmodule\s+${kind.module}\s*\(""").containsMatchIn(text)) {
                val tail = if (text.isEmpty() || text.endsWith("\n\n")) "" else if (text.endsWith("\n")) "\n" else "\n\n"
                document.insertString(document.textLength, tail + module(kind))
            }
            val offset = editor.caretModel.offset
            val line = document.getLineNumber(offset)
            val lineStart = document.getLineStartOffset(line)
            val before = document.charsSequence.subSequence(lineStart, offset).toString()
            val indent = before.takeWhile { it == ' ' || it == '\t' }
            val call = call(choice, clearance)
            // Mitten in einer Zeile mit Code: eigene Zeile darunter
            val insertion = if (before.isBlank()) call else "\n$indent$call"
            val at = if (before.isBlank()) offset else document.getLineEndOffset(line)
            document.insertString(at, insertion)
            editor.caretModel.moveToOffset(at + insertion.length)
            PsiDocumentManager.getInstance(project).commitDocument(document)
        }
    }

    private fun n(value: Double) = formatNumber(Math.round(value * 1000) / 1000.0)
}

/** Generate (⌘N) im OpenSCAD-Editor: Druck-Hardware einfügen. */
class InsertPrintHardwareAction : DumbAwareAction(ScadSphereBundle.messagePointer("action.insertHardware"), ScadSphereBundle.messagePointer("action.insertHardware.description"), null as javax.swing.Icon?) {
    override fun getActionUpdateThread() = ActionUpdateThread.EDT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.getData(CommonDataKeys.EDITOR) != null && e.scadFile() != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        val dialog = PrintHardwareDialog(project)
        if (!dialog.showAndGet()) return
        ScadSphereSettings.fitClearance = dialog.clearance
        PrintHardware.insert(project, editor, dialog.choice(), dialog.clearance)
    }
}

private class PrintHardwareDialog(project: Project) : DialogWrapper(project) {
    private val kind = ComboBox(PrintHardware.Kind.entries.toTypedArray()).apply {
        renderer = textListCellRenderer { it?.label ?: "" }
        selectedItem = lastKind
    }
    private val size = ComboBox(PrintHardware.SIZES.toTypedArray()).apply {
        renderer = textListCellRenderer { it?.let { size -> "M" + formatNumber(size) } ?: "" }
        selectedItem = 3.0
    }
    private val length = spinner(10.0, 1.0, 200.0, 1.0)
    private val slot = spinner(0.0, 0.0, 100.0, 1.0)
    private val diameter = spinner(6.0, 1.0, 50.0, 0.5)
    private val height = spinner(3.0, 0.5, 30.0, 0.5)
    private val width = spinner(5.0, 1.0, 50.0, 0.5)
    private val thickness = spinner(1.5, 0.4, 10.0, 0.1)
    private val overhang = spinner(1.0, 0.2, 10.0, 0.1)
    private val clearanceSpinner = spinner(ScadSphereSettings.fitClearance, 0.0, 2.0, 0.05)

    val clearance: Double get() = (clearanceSpinner.value as Number).toDouble()

    init {
        title = ScadSphereBundle.message("hardware.title")
        init()
    }

    private fun spinner(value: Double, min: Double, max: Double, step: Double) =
        JSpinner(SpinnerNumberModel(value.coerceIn(min, max), min, max, step)).apply { editor = JSpinner.NumberEditor(this, "0.###") }

    private fun shownFor(vararg kinds: PrintHardware.Kind) = object : ComponentPredicate() {
        override fun invoke() = kind.selectedItem in kinds
        override fun addListener(listener: (Boolean) -> Unit) {
            kind.addItemListener { if (it.stateChange == ItemEvent.SELECTED) listener(invoke()) }
        }
    }

    override fun createCenterPanel(): JComponent = panel {
        val k = PrintHardware.Kind.entries
        row(ScadSphereBundle.message("hardware.kind")) { cell(kind).align(AlignX.FILL) }
        row(ScadSphereBundle.message("hardware.size")) { cell(size) }.visibleIf(shownFor(k[0], k[1], k[2], k[3]))
        row(ScadSphereBundle.message("hardware.length")) { cell(length); label("mm") }.visibleIf(shownFor(k[0], k[1], k[5]))
        row(ScadSphereBundle.message("hardware.slot")) { cell(slot); label("mm") }.visibleIf(shownFor(k[2]))
            .rowComment(ScadSphereBundle.message("hardware.slot.comment"))
        row(ScadSphereBundle.message("hardware.diameter")) { cell(diameter); label("mm") }.visibleIf(shownFor(k[4]))
        row(ScadSphereBundle.message("hardware.height")) { cell(height); label("mm") }.visibleIf(shownFor(k[4]))
        row(ScadSphereBundle.message("hardware.width")) { cell(width); label("mm") }.visibleIf(shownFor(k[5]))
        row(ScadSphereBundle.message("hardware.thickness")) { cell(thickness); label("mm") }.visibleIf(shownFor(k[5]))
        row(ScadSphereBundle.message("hardware.overhang")) { cell(overhang); label("mm") }.visibleIf(shownFor(k[5]))
        row(ScadSphereBundle.message("hardware.clearance")) { cell(clearanceSpinner); label("mm") }.visibleIf(shownFor(k[0], k[1], k[2], k[4]))
            .rowComment(ScadSphereBundle.message("hardware.clearance.comment"))
        row { comment(ScadSphereBundle.message("hardware.comment")) }
    }

    fun choice(): PrintHardware.Choice {
        val selected = kind.selectedItem as PrintHardware.Kind
        lastKind = selected
        fun value(spinner: JSpinner) = (spinner.value as Number).toDouble()
        return PrintHardware.Choice(
            kind = selected,
            size = size.selectedItem as Double,
            length = value(length),
            slot = value(slot),
            diameter = value(diameter),
            height = value(height),
            width = value(width),
            thickness = value(thickness),
            overhang = value(overhang),
        )
    }

    companion object {
        private var lastKind = PrintHardware.Kind.SCREW
    }
}
