package app.heckit.ide.scadsphere.render

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.CapturingProcessHandler
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.util.io.FileUtil
import java.io.File
import java.nio.charset.StandardCharsets

/** Meldung von OpenSCAD (ERROR, WARNING, ECHO, TRACE) mit Datei und Zeile, wenn angegeben. */
data class RenderMessage(val level: Level, val text: String, val file: String?, val line: Int?) {
    enum class Level { ERROR, WARNING, ECHO, TRACE }
}

class RenderResult(
    /** Modell (3MF) oder 2D-Zeichnung (SVG); null bei Fehlern oder ohne Geometrie */
    val bytes: ByteArray?,
    val format: String,
    val messages: List<RenderMessage>,
    /** OpenSCAD hat ohne Ergebnis aufgehört (Syntaxfehler, assert, …) */
    val failed: Boolean,
    /** Kein Objekt auf oberster Ebene (nur Module, nur echo, …) */
    val empty: Boolean,
    val cancelled: Boolean,
    /** Alle gelesenen Dateien (include/use/import), absolut */
    val dependencies: Set<String>,
    val seconds: Double,
)

/** Einstellungen für einen Export: `--export-format` (z. B. `binstl`) und `-O abschnitt/schlüssel=wert`. */
data class ExportOptions(val exportFormat: String? = null, val settings: Map<String, String> = emptyMap()) {
    fun arguments(): List<String> = buildList {
        exportFormat?.let { add("--export-format"); add(it) }
        for ((key, value) in settings) {
            add("-O")
            add("$key=$value")
        }
    }
}

/**
 * Ein Lauf von OpenSCAD auf der Kommandozeile. Ungespeicherter Text geht über stdin hinein; das Arbeitsverzeichnis
 * ist der Ordner der Datei, so finden include/use/import ihre relativen Pfade wie beim Speichern.
 */
class OpenScadRender(private val exe: String) {
    @Volatile
    private var handler: CapturingProcessHandler? = null

    @Volatile
    private var cancelled = false

    fun cancel() {
        cancelled = true
        handler?.destroyProcess()
    }

    /**
     * Rechnet [file] (bzw. [text], falls ungespeichert) als 3MF; ist das Ergebnis 2D, als SVG.
     * [defines]: Werte aus dem Customizer als `name=ausdruck` (`-D`). Blockiert bis OpenSCAD fertig ist - nur im
     * Hintergrund aufrufen.
     */
    fun render(file: File, text: String?, defines: List<String> = emptyList()): RenderResult {
        val started = System.nanoTime()
        val dir = FileUtil.createTempDirectory("scadsphere", null, true)
        try {
            val extra = defineArguments(defines)
            var run = run(file, text, File(dir, "model.3mf"), dir, extra)
            if (!cancelled && run.output.any { it.contains("top level object is not a 3D object") }) {
                run = run(file, text, File(dir, "model.svg"), dir, extra)
            }
            val output = run.target
            val bytes = output.takeIf { !cancelled && it.isFile && it.length() > 0 }?.readBytes()
            val messages = parseMessages(run.output, file).toMutableList()
            val empty = run.output.any { EMPTY.containsMatchIn(it) }
            val failed = bytes == null && run.exitCode != 0 && !empty
            // Ohne erkennbare Meldung (z. B. Absturz): die letzten Zeilen zeigen
            if (failed && !cancelled && messages.none { it.level == RenderMessage.Level.ERROR } && run.output.isNotEmpty()) {
                messages += RenderMessage(RenderMessage.Level.ERROR, run.output.takeLast(5).joinToString("\n"), null, null)
            }
            return RenderResult(
                bytes = bytes,
                format = output.extension,
                messages = messages,
                failed = failed,
                empty = empty,
                cancelled = cancelled,
                dependencies = parseDependencies(File(dir, "deps.d"), file),
                seconds = (System.nanoTime() - started) / 1e9,
            )
        } finally {
            FileUtil.delete(dir)
        }
    }

    private class Run(val target: File, val output: List<String>, val exitCode: Int)

    private fun run(file: File, text: String?, target: File, dir: File, extra: List<String>): Run {
        if (cancelled) return Run(target, emptyList(), -1)
        val process = CapturingProcessHandler(command(file, if (text != null) null else file, target, File(dir, "deps.d"), extra))
        handler = process
        if (text != null) {
            // Ohne Zeitlimit: OpenSCAD liest stdin komplett, bevor es etwas ausgibt
            ApplicationManager.getApplication().executeOnPooledThread {
                runCatching { process.processInput.use { it.write(text.toByteArray(StandardCharsets.UTF_8)) } }
            }
        }
        val output = process.runProcess()
        handler = null
        return Run(target, output.stderrLines + output.stdoutLines, output.exitCode)
    }

    /** `openscad -o target [-d deps] [extra] file|-`, im Ordner der Datei und mit den Bibliotheksordnern. */
    private fun command(file: File, input: File?, target: File, deps: File?, extra: List<String> = emptyList()): GeneralCommandLine {
        val command = GeneralCommandLine(exe, "-o", target.path)
            .withWorkingDirectory(file.parentFile.toPath())
            .withCharset(StandardCharsets.UTF_8)
            .withParentEnvironmentType(GeneralCommandLine.ParentEnvironmentType.CONSOLE)
        if (deps != null) command.addParameters("-d", deps.path)
        command.addParameters(extra)
        OpenScad.libraryPathEnvironment()?.let { command.withEnvironment("OPENSCADPATH", it) }
        command.addParameter(input?.path ?: "-")
        return command
    }

    class ExportResult(val ok: Boolean, val messages: List<RenderMessage>)

    /**
     * Gespeicherte Datei nach [target] exportieren; das Format ergibt sich aus der Endung, [options] und [defines]
     * (Customizer) kommen dazu.
     */
    fun export(file: File, target: File, options: ExportOptions, defines: List<String>, indicator: ProgressIndicator): ExportResult =
        exportRun(command(file, file, target, null, options.arguments() + defineArguments(defines)), null, file, target, indicator)

    /**
     * Modell (STL, 3MF, OBJ, OFF, AMF) in ein anderes Format umwandeln: OpenSCAD liest es per `import()` über stdin
     * und exportiert es nach [target].
     */
    fun convert(source: File, target: File, options: ExportOptions, indicator: ProgressIndicator): ExportResult {
        val path = source.absolutePath.replace('\\', '/').replace("\"", "\\\"")
        return exportRun(command(source, null, target, null, options.arguments()), "import(\"$path\");\n", source, target, indicator)
    }

    private fun exportRun(command: GeneralCommandLine, input: String?, file: File, target: File, indicator: ProgressIndicator): ExportResult {
        // Ein altes Ziel darf nicht als Erfolg durchgehen, wenn OpenSCAD abbricht
        if (target.isFile) FileUtil.delete(target)
        val process = CapturingProcessHandler(command)
        if (input != null) {
            ApplicationManager.getApplication().executeOnPooledThread {
                runCatching { process.processInput.use { it.write(input.toByteArray(StandardCharsets.UTF_8)) } }
            }
        }
        val output = process.runProcessWithProgressIndicator(indicator)
        val lines = output.stderrLines + output.stdoutLines
        val messages = parseMessages(lines, file).toMutableList()
        val ok = !output.isCancelled && output.exitCode == 0 && target.isFile && target.length() > 0
        if (!ok && messages.none { it.level == RenderMessage.Level.ERROR } && lines.isNotEmpty()) {
            messages += RenderMessage(RenderMessage.Level.ERROR, lines.takeLast(5).joinToString("\n"), null, null)
        }
        return ExportResult(ok, messages)
    }

    companion object {
        private fun defineArguments(defines: List<String>): List<String> = defines.flatMap { listOf("-D", it) }

        private val EMPTY = Regex("""top level object is empty""", RegexOption.IGNORE_CASE)
        private val PREFIX = Regex("""^(ERROR|WARNING|DEPRECATED|FONT-WARNING|EXPORT-WARNING|EXPORT-ERROR|UI-WARNING|ECHO|TRACE):\s?(.*)$""")
        private val LOCATION = Regex("""^(.*?),?\s+in file (.+?), line (\d+)\.?\s*$""")

        /** Zeilen mit bekanntem Präfix; Statistik („Geometries in cache …“) fällt weg. */
        fun parseMessages(lines: List<String>, file: File): List<RenderMessage> = lines.mapNotNull { line ->
            val match = PREFIX.find(line.trimEnd()) ?: return@mapNotNull null
            val level = when (match.groupValues[1]) {
                "ERROR", "EXPORT-ERROR" -> RenderMessage.Level.ERROR
                "ECHO" -> RenderMessage.Level.ECHO
                "TRACE" -> RenderMessage.Level.TRACE
                else -> RenderMessage.Level.WARNING
            }
            var text = match.groupValues[2]
            var path: String? = null
            var lineNumber: Int? = null
            LOCATION.find(text)?.let { location ->
                text = location.groupValues[1]
                lineNumber = location.groupValues[3].toIntOrNull()
                path = location.groupValues[2].trim('"', '\'').let { name ->
                    when {
                        name == "<stdin>" -> file.path
                        File(name).isAbsolute -> name
                        else -> File(file.parentFile, name).normalize().path
                    }
                }
            }
            RenderMessage(level, text, path, lineNumber)
        }

        /** Make-Abhängigkeiten (`-d`): `ziel: a.scad b\ c.scad \`, Leerzeichen in Pfaden mit `\` maskiert. */
        fun parseDependencies(deps: File, file: File): Set<String> {
            if (!deps.isFile) return emptySet()
            val tokens = mutableListOf<String>()
            val current = StringBuilder()
            val text = deps.readText()
            var i = 0
            while (i < text.length) {
                val c = text[i]
                when {
                    c == '\\' && i + 1 < text.length && text[i + 1] == ' ' -> { current.append(' '); i++ }
                    c == '\\' && i + 1 < text.length && (text[i + 1] == '\n' || text[i + 1] == '\r') -> {}
                    c.isWhitespace() -> if (current.isNotEmpty()) { tokens += current.toString(); current.clear() }
                    else -> current.append(c)
                }
                i++
            }
            if (current.isNotEmpty()) tokens += current.toString()
            // Erstes Token ist das Ziel mit Doppelpunkt
            return tokens.drop(1).asSequence()
                .map { it.removeSuffix(":") }
                .filter { it != "<stdin>" && it != "-" }
                .map { if (File(it).isAbsolute) File(it) else File(file.parentFile, it) }
                .filter { it.isFile }
                .map { it.canonicalPath }
                .toSet()
        }
    }
}
