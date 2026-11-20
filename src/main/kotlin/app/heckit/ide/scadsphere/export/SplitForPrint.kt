package app.heckit.ide.scadsphere.export

import app.heckit.ide.scadsphere.ScadSphereBundle
import app.heckit.ide.scadsphere.render.ExportOptions
import app.heckit.ide.scadsphere.render.OpenScadRender
import app.heckit.ide.scadsphere.render.RenderMessage
import com.google.gson.JsonObject
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import java.io.File
import java.util.Locale

/**
 * Druckgerecht teilen: Der Viewer schickt Achse und Lage des Schnitts (Koordinaten der Datei), die Ausdehnung des Modells
 * und Stellen für Passstifte in der Schnittfläche. Daraus entsteht neben der Datei eine `.scad`, die das Modell per
 * `include` (bzw. `import` bei Netzdateien) einbindet und mit dem Parameter `part` die untere Hälfte, die obere Hälfte
 * oder die Stifte zeigt - mit Löchern für die Stifte in beiden Hälften. Über „3MF for Multi-Color Print…“ (nach den
 * Werten von `part`) lassen sich alle Teile in eine Datei exportieren.
 */
object SplitForPrint {
    class Request(
        val axis: Int,
        val value: Double,
        val min: DoubleArray,
        val max: DoubleArray,
        val pins: List<DoubleArray>,
        val pinD: Double,
        val pinLength: Double,
    ) {
        companion object {
            fun of(message: JsonObject): Request? = runCatching {
                fun vector(name: String) = message.getAsJsonArray(name).map { it.asDouble }.toDoubleArray().takeIf { it.size == 3 }!!
                Request(
                    axis = message.get("axis").asInt.takeIf { it in 0..2 }!!,
                    value = message.get("value").asDouble,
                    min = vector("min"),
                    max = vector("max"),
                    pins = message.getAsJsonArray("pins").map { pin -> pin.asJsonArray.map { it.asDouble }.toDoubleArray() }.filter { it.size == 2 },
                    pinD = message.get("pinD").asDouble,
                    pinLength = message.get("pinLength").asDouble,
                )
            }.getOrNull()
        }
    }

    /**
     * Quelltext der geteilten Datei. [model]: Anweisung(en) im Modul `model()`, z. B. `include <box.scad>` oder
     * `import("box.stl");`; [sourceName] nur für den Kopfkommentar.
     */
    fun source(sourceName: String, model: String, request: Request): String {
        val axisName = "XYZ"[request.axis]
        val margin = 1.0
        val lo = DoubleArray(3) { request.min[it] - margin }
        val hi = DoubleArray(3) { request.max[it] + margin }
        val pins = request.pins.joinToString(", ", "[", "]") { "[${number(it[0])}, ${number(it[1])}]" }
        val (u, v) = listOf(0, 1, 2).filter { it != request.axis }.map { "XYZ"[it] }
        return """
            |// $sourceName split at ${axisName.lowercaseChar()} = ${number(request.value)} mm for printing in two parts, joined with pins.
            |// Created by SCAD Sphere. Pick a part in the Customizer and export it, or export all parts
            |// into one 3MF: 3MF for Multi-Color Print..., by the values of "part".
            |// The pin positions come from the section at the time of the split: after moving the
            |// cut, check them with the section view (they must lie inside the material).
            |
            |/* [Split] */
            |// Part to show (lower = the side with smaller $axisName)
            |part = "both"; // [both, lower, upper, pins]
            |// Position of the cut on the $axisName axis
            |cut = ${number(request.value)};
            |// Pin diameter
            |pin_d = ${number(request.pinD)};
            |// Pin length (half of it goes into each part)
            |pin_length = ${number(request.pinLength)};
            |// Extra room in the holes
            |clearance = 0.15;
            |// Space between the parts when showing both
            |gap = 10;
            |
            |/* [Hidden] */
            |axis = ${request.axis}; // 0 = X, 1 = Y, 2 = Z
            |// Pin centers in the cut plane ($u, $v)
            |pin_positions = $pins;
            |lo = [${lo.joinToString(", ") { number(it) }}];
            |hi = [${hi.joinToString(", ") { number(it) }}];
            |${'$'}fn = 48;
            |
            |module model() {
            |${model.trimEnd().prependIndent("    ")}
            |}
            |
            |function at_axis(p, value) = [for (i = [0:2]) i == axis ? value : p[i]];
            |function pin_point(p) = axis == 0 ? [cut, p[0], p[1]] : axis == 1 ? [p[0], cut, p[1]] : [p[0], p[1], cut];
            |pin_turn = [[0, 90, 0], [-90, 0, 0], [0, 0, 0]][axis];
            |
            |module half(upper) {
            |    a = upper ? at_axis(lo, cut) : lo;
            |    b = upper ? hi : at_axis(hi, cut);
            |    translate(a) cube(b - a);
            |}
            |
            |module pin_holes() {
            |    for (p = pin_positions)
            |        translate(pin_point(p)) rotate(pin_turn)
            |            cylinder(d = pin_d + 2 * clearance, h = pin_length + 1, center = true);
            |}
            |
            |module piece(upper) {
            |    difference() {
            |        intersection() {
            |            model();
            |            half(upper);
            |        }
            |        pin_holes();
            |    }
            |}
            |
            |// Pin with chamfered ends, printed standing
            |module pin() {
            |    c = min(0.4, pin_d / 8);
            |    cylinder(d1 = pin_d - 2 * c, d2 = pin_d, h = c);
            |    translate([0, 0, c]) cylinder(d = pin_d, h = pin_length - 2 * c);
            |    translate([0, 0, pin_length - c]) cylinder(d1 = pin_d, d2 = pin_d - 2 * c, h = c);
            |}
            |
            |module pins() {
            |    for (i = [0:len(pin_positions) - 1])
            |        translate([lo.x + i * (pin_d + 4) + pin_d / 2, lo.y - gap - pin_d / 2, axis == 2 ? lo.z : 0]) pin();
            |}
            |
            |if (part == "lower" || part == "both") piece(false);
            |if (part == "upper") piece(true);
            |if (part == "both") translate(at_axis([0, 0, 0], gap)) piece(true);
            |if ((part == "pins" || part == "both") && len(pin_positions) > 0) pins();
            |""".trimMargin()
    }

    /**
     * Für eine .scad: `include` im Modul `model()` geht nur, wenn die Datei (samt ihren `include`s) kein `use` enthält -
     * `use` ist nur auf oberster Ebene erlaubt. Das prüft ein kurzer Lauf (`-o x.ast`, nur Parsen und Auswerten). Geht es
     * nicht, wird der ausgewertete CSG-Baum (ohne `use`/`include`, Module schon eingesetzt) als Stand daneben gelegt.
     */
    fun createForScad(project: Project, exe: String, file: VirtualFile, defines: List<String>, request: Request) {
        ScadSplitTask(project, exe, file, defines, request).queue()
    }

    /** Ergebnis der Vorbereitung: einzubindender Text, sonst CSG-Stand, sonst Fehlermeldung. */
    internal class Prepared(val model: String?, val snapshot: String?, val error: String?)

    internal fun prepare(exe: String, file: VirtualFile, defines: List<String>, request: Request, indicator: ProgressIndicator): Prepared {
        val dir = FileUtil.createTempDirectory("scadsphere-split", null, true)
        try {
            val render = OpenScadRender(exe)
            val check = File(dir, "check.scad")
            val checkModel = (listOf("include <${file.path}>") + defines.map { "$it;" }).joinToString("\n")
            check.writeText(source("check", checkModel, request))
            if (render.export(check, File(dir, "check.ast"), ExportOptions(), emptyList(), indicator).ok) {
                return Prepared(includeLines(file, defines), null, null)
            }
            val csg = File(dir, "model.csg")
            val result = render.export(File(file.path), csg, ExportOptions(), defines, indicator)
            if (!result.ok || !csg.isFile) {
                return Prepared(null, null, result.messages.firstOrNull { it.level == RenderMessage.Level.ERROR }?.text ?: "")
            }
            return Prepared(null, csg.readText(), null)
        } finally {
            FileUtil.delete(dir)
        }
    }

    /**
     * Datei `<name>-split.scad` (bzw. `-split-2` usw.) neben [source] anlegen und öffnen. Mit [snapshot] (CSG-Baum) liegt
     * dieser als `<name>-split.csg` daneben und wird statt [model] eingebunden.
     */
    fun create(project: Project, source: VirtualFile, model: String, request: Request, snapshot: String? = null) {
        val dir = source.parent ?: return
        val base = source.nameWithoutExtension + "-split"
        val number = generateSequence(1) { it + 1 }.first { dir.findChild(suffixed(base, it, "scad")) == null && (snapshot == null || dir.findChild(suffixed(base, it, "csg")) == null) }
        val name = suffixed(base, number, "scad")
        val csgName = suffixed(base, number, "csg")
        val text = if (snapshot == null) source(source.name, model, request)
        else source(source.name, "// Snapshot of ${source.name} (it uses \"use\", which cannot be included in a module).\n// Split again after changing ${source.name}.\ninclude <$csgName>", request)
        val created = runCatching {
            WriteCommandAction.writeCommandAction(project).withName(ScadSphereBundle.message("split.command")).compute<VirtualFile, Exception> {
                if (snapshot != null) dir.createChildData(this, csgName).also { VfsUtil.saveText(it, snapshot) }
                dir.createChildData(this, name).also { VfsUtil.saveText(it, text) }
            }
        }.getOrElse { return notifyError(project, it.message ?: "") }
        OpenFileDescriptor(project, created).navigate(true)
        val message = if (request.pins.isEmpty()) ScadSphereBundle.message("split.doneNoPins", name) else ScadSphereBundle.message("split.done", name, request.pins.size)
        NotificationGroupManager.getInstance().getNotificationGroup("SCAD Sphere")
            .createNotification(message, NotificationType.INFORMATION).notify(project)
    }

    private fun suffixed(base: String, number: Int, extension: String) = if (number == 1) "$base.$extension" else "$base-$number.$extension"

    internal fun notifyError(project: Project, text: String) {
        NotificationGroupManager.getInstance().getNotificationGroup("SCAD Sphere")
            .createNotification(ScadSphereBundle.message("split.failed", text), NotificationType.ERROR).notify(project)
    }

    /** Anweisung, die eine Netzdatei einbindet; Pfad relativ zur geteilten Datei (liegt daneben). */
    fun importLine(file: VirtualFile): String = "import(\"${file.name.replace("\\", "\\\\").replace("\"", "\\\"")}\");"

    /** `include` der .scad, danach die Werte aus dem Customizer (die letzte Zuweisung gilt). */
    fun includeLines(file: VirtualFile, defines: List<String>): String =
        (listOf("include <${file.name}>") + defines.map { "$it;" }).joinToString("\n")

    private fun number(value: Double): String {
        val text = String.format(Locale.ROOT, "%.3f", value).trimEnd('0').trimEnd('.')
        return if (text == "-0") "0" else text
    }
}


private class ScadSplitTask(
    project: Project,
    private val exe: String,
    private val file: VirtualFile,
    private val defines: List<String>,
    private val request: SplitForPrint.Request,
) : Task.Backgroundable(project, ScadSphereBundle.message("split.progress", file.name), true) {
    private var prepared: SplitForPrint.Prepared? = null

    override fun run(indicator: ProgressIndicator) {
        prepared = SplitForPrint.prepare(exe, file, defines, request, indicator)
    }

    override fun onSuccess() {
        val result = prepared ?: return
        val project = project ?: return
        when {
            result.error != null -> SplitForPrint.notifyError(project, result.error)
            result.model != null -> SplitForPrint.create(project, file, result.model, request)
            result.snapshot != null -> SplitForPrint.create(project, file, "", request, result.snapshot)
        }
    }
}
