package app.heckit.ide.scadsphere.render

import app.heckit.ide.scadsphere.ScadSphereSettings
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.CapturingProcessHandler
import com.intellij.openapi.util.SystemInfo
import com.intellij.util.EnvironmentUtil
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/** Die installierte OpenSCAD-Anwendung: finden, Version, Bibliotheksordner, GUI öffnen. */
object OpenScad {
    /** Ausführbare Datei für die Kommandozeile oder null, wenn OpenSCAD nicht gefunden wurde. */
    fun executable(): String? {
        val configured = ScadSphereSettings.openScadPath
        return if (configured.isNotBlank()) normalize(configured) else detect()
    }

    /**
     * Wie [executable], aber nur gesucht (für den Platzhalter in den Einstellungen). Für einige Sekunden gemerkt:
     * Die Namensauflösung fragt bei jedem include nach den Bibliotheksordnern der Installation.
     */
    fun detect(): String? {
        val now = System.currentTimeMillis()
        detected?.let { (time, path) -> if (now - time < CACHE_MS) return path }
        return candidates().firstNotNullOfOrNull { normalize(it) }.also { detected = now to it }
    }

    @Volatile
    private var detected: Pair<Long, String?>? = null
    private const val CACHE_MS = 10_000

    /** Akzeptiert auch das .app-Bündel (macOS) bzw. den Programmordner (Windows). */
    fun normalize(path: String): String? {
        var file = File(path.trim())
        if (file.isDirectory) {
            file = when {
                SystemInfo.isMac && file.name.endsWith(".app") -> File(file, "Contents/MacOS/OpenSCAD")
                SystemInfo.isWindows -> File(file, "openscad.com").takeIf { it.isFile } ?: File(file, "openscad.exe")
                else -> File(file, "openscad")
            }
        }
        // Unter Windows schreibt nur openscad.com zuverlässig auf stdout/stderr
        if (SystemInfo.isWindows && file.name.equals("openscad.exe", ignoreCase = true)) {
            File(file.parentFile, "openscad.com").takeIf { it.isFile }?.let { file = it }
        }
        return file.takeIf { it.isFile && (SystemInfo.isWindows || it.canExecute()) }?.path
    }

    private fun candidates(): List<String> {
        val home = System.getProperty("user.home")
        return when {
            SystemInfo.isMac -> buildList {
                for (dir in listOf("/Applications", "$home/Applications")) {
                    add("$dir/OpenSCAD.app")
                    // auch Nightly-Builds und Versionen im Namen (OpenSCAD-2021.01.app)
                    File(dir).listFiles { f -> f.name.startsWith("OpenSCAD") && f.name.endsWith(".app") }
                        ?.sortedByDescending { it.name }?.forEach { add(it.path) }
                }
                add("/opt/homebrew/bin/openscad")
                add("/usr/local/bin/openscad")
            }
            SystemInfo.isWindows -> buildList {
                for (base in listOfNotNull(System.getenv("ProgramFiles"), System.getenv("ProgramFiles(x86)"), System.getenv("LOCALAPPDATA")?.let { "$it\\Programs" })) {
                    add("$base\\OpenSCAD")
                    add("$base\\OpenSCAD (Nightly)")
                }
                inPath("openscad.com")?.let(::add)
                inPath("openscad.exe")?.let(::add)
            }
            else -> buildList {
                inPath("openscad")?.let(::add)
                inPath("openscad-nightly")?.let(::add)
                add("/usr/bin/openscad")
                add("/usr/local/bin/openscad")
                add("/snap/bin/openscad")
                add("/var/lib/flatpak/exports/bin/org.openscad.OpenSCAD")
                add("$home/.local/share/flatpak/exports/bin/org.openscad.OpenSCAD")
            }
        }
    }

    /** Datei im PATH (der Shell, nicht nur der IDE - unter macOS startet die IDE ohne Shell-Umgebung). */
    private fun inPath(name: String): String? =
        EnvironmentUtil.getValue("PATH")?.split(File.pathSeparatorChar)?.asSequence()
            ?.map { File(it, name) }?.firstOrNull { it.isFile }?.path

    /** Mitgelieferte Bibliotheken der Installation (z. B. MCAD). */
    fun installLibraryDir(): String? {
        val exe = executable()?.let(::File) ?: return null
        val dir = when {
            SystemInfo.isMac && exe.path.contains(".app/Contents/MacOS/") -> File(exe.parentFile.parentFile, "Resources/libraries")
            SystemInfo.isWindows -> File(exe.parentFile, "libraries")
            else -> File(exe.parentFile.parentFile, "share/openscad/libraries")
        }
        return dir.takeIf { it.isDirectory }?.path
    }

    private val versions = ConcurrentHashMap<String, String>()

    /** Version, z. B. `2026.04.26` (aus `--version`, je Pfad gemerkt). Nicht auf dem EDT aufrufen. */
    fun version(exe: String): String? = versions[exe] ?: runCatching {
        val output = CapturingProcessHandler(GeneralCommandLine(exe, "--version")).runProcess(10_000)
        Regex("""version\s+(\S+)""").find(output.stderr + output.stdout)?.groupValues?.get(1)
    }.getOrNull()?.also { versions[exe] = it }

    private val features = ConcurrentHashMap<String, OpenScadFeatures>()

    /**
     * Was die installierte Version beim Export kann, aus `--help` und `--help-export` (je Pfad und Änderungsdatum
     * gemerkt). Ältere Versionen kennen `-O` nicht und bieten dann einfach weniger an. Nicht auf dem EDT aufrufen.
     */
    fun features(exe: String): OpenScadFeatures {
        val key = "$exe@${File(exe).lastModified()}"
        features[key]?.let { return it }
        fun output(vararg args: String): String = runCatching {
            val result = CapturingProcessHandler(GeneralCommandLine(exe, *args)).runProcess(10_000)
            result.stdout + "\n" + result.stderr
        }.getOrDefault("")
        return OpenScadFeatures.parse(output("--help"), output("--help-export")).also { features[key] = it }
    }

    /** Schon bekannte Fähigkeiten, ohne OpenSCAD zu starten (null, wenn noch nicht abgefragt). */
    fun cachedFeatures(exe: String): OpenScadFeatures? = features["$exe@${File(exe).lastModified()}"]

    /** OPENSCADPATH für einen Lauf: eigene Bibliotheksordner vor den vorhandenen. */
    fun libraryPathEnvironment(): String? {
        val paths = ScadSphereSettings.libraryPathList() + listOfNotNull(EnvironmentUtil.getValue("OPENSCADPATH"))
        return paths.joinToString(File.pathSeparator).ifEmpty { null }
    }

    /** OpenSCAD-Oberfläche mit [file] öffnen (läuft unabhängig von der IDE weiter). */
    fun openInGui(exe: String, file: String) {
        val app = Regex("""^(.*\.app)/Contents/MacOS/""").find(exe)?.groupValues?.get(1)
        val command = if (SystemInfo.isMac && app != null) {
            GeneralCommandLine("open", "-a", app, file)
        } else {
            // Unter Windows die GUI-Variante starten, nicht die Konsole
            val gui = if (SystemInfo.isWindows) File(File(exe).parentFile, "openscad.exe").takeIf { it.isFile }?.path ?: exe else exe
            GeneralCommandLine(gui, file)
        }
        command.withParentEnvironmentType(GeneralCommandLine.ParentEnvironmentType.CONSOLE).createProcess()
    }
}

/**
 * Fähigkeiten von OpenSCAD beim Export: binäres STL (`--export-format binstl`) und die Einstellungen für `-O`
 * je Abschnitt (`export-3mf`, `export-pdf`, …) mit ihren möglichen Werten.
 */
class OpenScadFeatures(val binaryStl: Boolean, private val settings: Map<String, Map<String, Setting>>) {
    /** Eine Einstellung; [values] bei Auswahl (enum) bzw. true/false, [default] wie OpenSCAD sie markiert. */
    class Setting(val type: String, val values: List<String>, val default: String?)

    fun setting(section: String, key: String): Setting? = settings[section]?.get(key)

    companion object {
        private val SECTION = Regex("""^Section '([^']+)':""")
        private val SETTING = Regex("""^\s*-\s*([\w-]+)\s*\((\w+)\):\s*(.*)$""")

        /**
         * `--help-export` sieht so aus:
         * ```
         * Section 'export-3mf':
         *   - color-mode (enum): [<model>,none,selected-only]
         *   - add-meta-data (bool): <true>/false
         * ```
         */
        fun parse(help: String, helpExport: String): OpenScadFeatures {
            val settings = LinkedHashMap<String, MutableMap<String, Setting>>()
            var section: MutableMap<String, Setting>? = null
            for (line in helpExport.lines()) {
                val sectionMatch = SECTION.find(line)
                if (sectionMatch != null) {
                    section = settings.getOrPut(sectionMatch.groupValues[1]) { LinkedHashMap() }
                    continue
                }
                val match = SETTING.find(line) ?: continue
                val current = section ?: continue
                val (key, type, rest) = match.destructured
                val items = when (type) {
                    "enum" -> rest.trim().removePrefix("[").substringBefore(']').split(',').map { it.trim() }
                    "bool" -> rest.trim().split('/').map { it.trim() }
                    else -> emptyList()
                }.filter { it.isNotEmpty() }
                val default = items.firstOrNull { it.startsWith("<") }?.trim('<', '>')
                current[key] = Setting(type, items.map { it.trim('<', '>') }, default)
            }
            return OpenScadFeatures("binstl" in help, settings)
        }
    }
}
