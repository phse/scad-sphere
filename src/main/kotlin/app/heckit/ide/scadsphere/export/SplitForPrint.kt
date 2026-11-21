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
    /** Ein Schnitt quer zur Achse [axis] bei [value], mit Stiftstellen (u, v = die beiden anderen Achsen aufsteigend). */
    class Cut(val axis: Int, val value: Double, val pins: List<DoubleArray>, val pinD: Double, val pinLength: Double)

    /** Wie die Teile zusammenhalten: lose Stifte, Filament-Stücke (1,75 mm), angedruckte Zapfen oder Schwalbenschwänze. */
    enum class Connector(val id: String) { PINS("pins"), FILAMENT("filament"), PEGS("pegs"), DOVETAIL("dovetail") }

    class Request(
        val cuts: List<Cut>,
        val min: DoubleArray,
        val max: DoubleArray,
        val connector: Connector = Connector.PINS,
    ) {
        /** Ein Schnitt (Format bis 1.8 und Tests). */
        constructor(axis: Int, value: Double, min: DoubleArray, max: DoubleArray, pins: List<DoubleArray>, pinD: Double, pinLength: Double, connector: Connector = Connector.PINS) :
            this(listOf(Cut(axis, value, pins, pinD, pinLength)), min, max, connector)

        val axis: Int get() = cuts.first().axis
        val value: Double get() = cuts.first().value
        val pins: List<DoubleArray> get() = cuts.flatMap { it.pins }

        /** Anzahl der Teile: je Achse ein Stück mehr als Schnitte, multipliziert. */
        val pieceCount: Int get() = (0..2).fold(1) { product, axis -> product * (cuts.count { it.axis == axis } + 1) }

        companion object {
            fun of(message: JsonObject): Request? = runCatching {
                fun vector(name: String) = message.getAsJsonArray(name).map { it.asDouble }.toDoubleArray().takeIf { it.size == 3 }!!
                fun cut(json: JsonObject) = Cut(
                    axis = json.get("axis").asInt.takeIf { it in 0..2 }!!,
                    value = json.get("value").asDouble,
                    pins = json.getAsJsonArray("pins").map { pin -> pin.asJsonArray.map { it.asDouble }.toDoubleArray() }.filter { it.size == 2 },
                    pinD = json.get("pinD").asDouble,
                    pinLength = json.get("pinLength").asDouble,
                )
                val cuts = message.getAsJsonArray("cuts")?.map { cut(it.asJsonObject) } ?: listOf(cut(message))
                val connector = message.get("connector")?.asString?.let { id -> Connector.entries.firstOrNull { it.id == id } } ?: Connector.PINS
                Request(cuts.distinctBy { it.axis to it.value }.takeIf { it.isNotEmpty() }!!, vector("min"), vector("max"), connector)
            }.getOrNull()
        }
    }

    /** Namen der Teile für `part`: bei einem Schnitt lower/upper, sonst 1, 2, 3, ... */
    fun pieceNames(request: Request): List<String> =
        if (request.pieceCount == 2) listOf("lower", "upper") else (1..request.pieceCount).map { it.toString() }

    /**
     * Quelltext der geteilten Datei. [model]: Anweisung(en) im Modul `model()`, z. B. `include <box.scad>` oder
     * `import("box.stl");`; [sourceName] nur für den Kopfkommentar.
     */
    fun source(sourceName: String, model: String, request: Request): String {
        val margin = 1.0
        val lo = DoubleArray(3) { request.min[it] - margin }
        val hi = DoubleArray(3) { request.max[it] + margin }
        val first = request.cuts.first()
        val pinD = first.pinD
        val pinLength = request.cuts.minOf { it.pinLength }
        val dovetailWidth = maxOf(6.0, 2 * pinD)
        val names = pieceNames(request)
        val all = if (names.size == 2) "both" else "all"
        val cuts = request.cuts.joinToString(",\n    ", "[\n    ", "\n]") { cut ->
            val pins = cut.pins.joinToString(", ", "[", "]") { "[${number(it[0])}, ${number(it[1])}]" }
            // Schwalbenschwänze an den u-Werten der Stifte, zu nahe beieinander nur einmal
            val tails = mutableListOf<Double>()
            for (u in cut.pins.map { it[0] }.sorted()) if (tails.none { kotlin.math.abs(it - u) < dovetailWidth * 1.5 }) tails += u
            "[${cut.axis}, ${number(cut.value)}, $pins, ${tails.joinToString(", ", "[", "]") { number(it) }}]"
        }
        val where = request.cuts.joinToString(", ") { "${"xyz"[it.axis]} = ${number(it.value)}" }
        val partOptions = (listOf(all) + names + if (request.connector == Connector.PINS) listOf("pins") else emptyList()).joinToString(", ")
        val connectors = Connector.entries.joinToString(", ") { it.id }
        val pieces = if (names.size == 2) "two parts" else "${names.size} parts"
        return """
            |// $sourceName split at $where mm for printing in $pieces.
            |// Created by SCAD Sphere. Pick a part in the Customizer and export it, or export all parts
            |// into one 3MF: 3MF for Multi-Color Print..., by the values of "part".
            |// The pin positions come from the section at the time of the split: after moving a
            |// cut, check them with the section view (they must lie inside the material).
            |
            |/* [Split] */
            |// Part to show (lower = the side with smaller coordinates)
            |part = "$all"; // [$partOptions]
            |// How the parts are joined (filament = 1.75 mm dowels, pegs on the lower part, dovetails slide in along ${if (first.axis == 2) "Y" else "Z"})
            |connector = "${request.connector.id}"; // [$connectors]
            |// Pin and peg diameter
            |pin_d = ${number(pinD)};
            |// Pin length (half of it goes into each part)
            |pin_length = ${number(pinLength)};
            |// Length of the filament pieces
            |filament_length = 12;
            |// Width of a dovetail at the cut
            |dovetail_width = ${number(dovetailWidth)};
            |// Depth of a dovetail
            |dovetail_depth = ${number(minOf(8.0, maxOf(3.0, pinLength / 2)))};
            |// Extra room in the holes
            |clearance = 0.15;
            |// Space between the parts when showing all
            |gap = 10;
            |
            |/* [Hidden] */
            |// Cuts: [axis (0 = X, 1 = Y, 2 = Z), position, pin centers in the cut plane, dovetail positions]
            |cuts = $cuts;
            |lo = [${lo.joinToString(", ") { number(it) }}];
            |hi = [${hi.joinToString(", ") { number(it) }}];
            |piece_names = [${names.joinToString(", ") { "\"$it\"" }}];
            |${'$'}fn = 48;
            |
            |module model() {
            |${model.trimEnd().prependIndent("    ")}
            |}
            |
            |function sorted(v) = len(v) <= 1 ? v : let(p = v[0], rest = [for (i = [1:len(v) - 1]) v[i]])
            |    concat(sorted([for (x = rest) if (x < p) x]), [p], sorted([for (x = rest) if (x >= p) x]));
            |edges = [for (a = [0:2]) concat([lo[a]], sorted([for (c = cuts) if (c[0] == a) c[1]]), [hi[a]])];
            |// Cells between the cuts: [[i, j, k], lower corner, upper corner]
            |cells = [for (i = [0:len(edges[0]) - 2], j = [0:len(edges[1]) - 2], k = [0:len(edges[2]) - 2])
            |    [[i, j, k], [edges[0][i], edges[1][j], edges[2][k]], [edges[0][i + 1], edges[1][j + 1], edges[2][k + 1]]]];
            |
            |function pin_point(c, p) = c[0] == 0 ? [c[1], p[0], p[1]] : c[0] == 1 ? [p[0], c[1], p[1]] : [p[0], p[1], c[1]];
            |function pin_turn(a) = [[0, 90, 0], [-90, 0, 0], [0, 0, 0]][a];
            |// The cut lies on the upper (or lower) face of the cell, the point inside that face
            |function on_face(cell, c, upper) = abs((upper ? cell[2] : cell[1])[c[0]] - c[1]) < 1e-6;
            |function in_face(cell, c, q) = len([for (a = [0:2]) if (a != c[0] && (q[a] < cell[1][a] || q[a] > cell[2][a])) a]) == 0;
            |
            |module holes(cell) {
            |    for (c = cuts) if (on_face(cell, c, true) || on_face(cell, c, false)) for (p = c[2]) if (in_face(cell, c, pin_point(c, p)))
            |        translate(pin_point(c, p)) rotate(pin_turn(c[0])) {
            |            if (connector == "pins")
            |                cylinder(d = pin_d + 2 * clearance, h = pin_length + 1, center = true);
            |            if (connector == "filament")
            |                cylinder(d = 1.75 + 2 * clearance, h = filament_length + 1, center = true, ${'$'}fn = 24);
            |            // Pegs: the hole only in the upper part
            |            if (connector == "pegs" && on_face(cell, c, false))
            |                cylinder(d = pin_d + 2 * clearance, h = pin_length / 2 + 0.5);
            |        }
            |}
            |
            |module pegs(cell) {
            |    for (c = cuts) if (on_face(cell, c, true)) for (p = c[2]) if (in_face(cell, c, pin_point(c, p)))
            |        translate(pin_point(c, p)) rotate(pin_turn(c[0])) {
            |            cylinder(d = pin_d, h = pin_length / 2 - 0.4);
            |            translate([0, 0, pin_length / 2 - 0.4]) cylinder(d1 = pin_d, d2 = pin_d - 0.8, h = 0.4);
            |        }
            |}
            |
            |// Map local X to the cut axis, Y across the dovetail and Z along the slide direction
            |module along(a, w, s, origin) multmatrix([for (i = [0:2]) [i == a ? 1 : 0, i == w ? 1 : 0, i == s ? 1 : 0, origin[i]]]) children();
            |
            |// Dovetails at the upper face of the cell (tongues) or at its lower face (slots, with clearance)
            |module dovetails(cell, slots) {
            |    flare = dovetail_depth * tan(15);
            |    for (c = cuts) if (on_face(cell, c, !slots)) {
            |        a = c[0];
            |        s = a == 2 ? 1 : 2;
            |        w = 3 - a - s;
            |        for (u = c[3]) {
            |            origin = [for (i = [0:2]) i == a ? c[1] : i == w ? u : cell[1][i]];
            |            along(a, w, s, origin) linear_extrude(height = cell[2][s] - cell[1][s])
            |                offset(delta = slots ? clearance : 0)
            |                    polygon([[slots ? -0.01 : 0, -dovetail_width / 2], [dovetail_depth, -dovetail_width / 2 - flare],
            |                             [dovetail_depth, dovetail_width / 2 + flare], [slots ? -0.01 : 0, dovetail_width / 2]]);
            |        }
            |    }
            |}
            |
            |module piece(n) {
            |    cell = cells[n];
            |    union() {
            |        difference() {
            |            intersection() {
            |                model();
            |                union() {
            |                    translate(cell[1]) cube(cell[2] - cell[1]);
            |                    if (connector == "dovetail") dovetails(cell, false);
            |                }
            |            }
            |            holes(cell);
            |            if (connector == "dovetail") dovetails(cell, true);
            |        }
            |        if (connector == "pegs") pegs(cell);
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
            |all_pins = [for (c = cuts, p = c[2]) p];
            |module pins() {
            |    if (len(all_pins) > 0) for (i = [0:len(all_pins) - 1])
            |        translate([lo.x + i * (pin_d + 4) + pin_d / 2, lo.y - gap - pin_d / 2, lo.z]) pin();
            |}
            |
            |for (n = [0:len(cells) - 1]) {
            |    if (part == piece_names[n]) piece(n);
            |    if (part == "$all") translate(cells[n][0] * gap) piece(n);
            |}
            |if ((part == "pins" || part == "$all") && connector == "pins") pins();
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
