package app.heckit.ide.scadsphere.mcp

import app.heckit.ide.scadsphere.customizer.CustomizerParameter
import app.heckit.ide.scadsphere.customizer.ParameterSets
import app.heckit.ide.scadsphere.customizer.ScadCustomizer
import app.heckit.ide.scadsphere.customizer.formatNumber
import app.heckit.ide.scadsphere.export.ExportFormat
import app.heckit.ide.scadsphere.psi.ScadFile
import app.heckit.ide.scadsphere.render.ExportOptions
import app.heckit.ide.scadsphere.render.MeshStats
import app.heckit.ide.scadsphere.render.OpenScad
import app.heckit.ide.scadsphere.render.OpenScadRender
import app.heckit.ide.scadsphere.render.RenderMessage
import com.intellij.mcpserver.McpToolset
import com.intellij.mcpserver.annotations.McpDescription
import com.intellij.mcpserver.annotations.McpTool
import com.intellij.mcpserver.project
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.progress.EmptyProgressIndicator
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Werkzeuge für den MCP-Server der IDE: Ein KI-Assistent kann ein OpenSCAD-Modell rechnen lassen und bekommt
 * Meldungen, Maße und Bilder zurück - mit dem OpenSCAD, den Bibliotheksordnern und dem Backend aus den Einstellungen.
 */
class ScadToolset : McpToolset {

    @McpTool
    @McpDescription(
        """
        |Renders an OpenSCAD file (.scad) or a mesh file (.stl, .3mf, .obj, .off, .amf) with the OpenSCAD configured in the IDE
        |and reports what came out: errors, warnings and echo output with line numbers, the bounding box and size in mm,
        |triangle count, volume and surface area, plus PNG images of the model from the requested views.
        |Use it after editing a .scad file to check that the model still renders and looks as intended: read the returned
        |image files to see the model. Unsaved editor content is used if the file is open and modified.
        """
    )
    suspend fun scad_render(
        @McpDescription("Path of the .scad or mesh file, absolute or relative to the project root.")
        filePath: String,
        @McpDescription("Views to render as PNG, comma separated: iso, top, bottom, front, back, right, left. Empty = no images.")
        views: String = "iso",
        @McpDescription("Variables to override, like the Customizer or -D on the command line: one 'name=value' per line, values as OpenSCAD expressions (80, \"text\", true, [1, 2]).")
        parameters: String = "",
        @McpDescription("Name of a parameter set from the <file>.json next to the .scad file (see scad_parameters). Empty = none.")
        parameterSet: String = "",
        @McpDescription("Width and height of the images in pixels (200-2000).")
        imageSize: Int = 800,
        @McpDescription("Folder for the images, absolute or relative to the project root. Empty = a temporary folder.")
        outputDir: String = "",
    ): String {
        val project = currentCoroutineContext().project
        val file = resolve(project, filePath)
        if (!file.isFile) return "File not found: ${file.path}"
        val exe = OpenScad.executable() ?: return NOT_FOUND
        val isScad = file.extension.equals("scad", ignoreCase = true)
        if (!isScad && ExportFormat.byExtension(file.extension)?.is3d != true) return "Not an OpenSCAD or mesh file: ${file.name}"
        val wanted = views.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        val unknown = wanted.filter { OpenScadRender.View.byName(it) == null }
        if (unknown.isNotEmpty()) return "Unknown view(s): ${unknown.joinToString(", ")}. Available: ${OpenScadRender.View.ALL.joinToString(", ") { it.name }}"
        if (parameterSet.isNotBlank() && (!isScad || parameterSet !in sets(file))) return "Parameter set '$parameterSet' not found. ${setNames(file)}"
        val set = OpenScadRender.parameterSetArguments(parameterSet(file, parameterSet))
        val text = if (isScad) unsavedText(file) else "import(\"${file.absolutePath.replace('\\', '/').replace("\"", "\\\"")}\");\n"
        val imageDir = if (outputDir.isBlank()) File(File(PathManager.getTempPath(), "scad-sphere"), "mcp") else resolve(project, outputDir)
        return withContext(Dispatchers.IO) {
            val result = OpenScadRender(exe).check(
                file, text, defines(parameters), set, wanted.mapNotNull { OpenScadRender.View.byName(it) }, imageSize.coerceIn(200, 2000), imageDir,
            )
            report(file, exe, result)
        }
    }

    @McpTool
    @McpDescription(
        """
        |Lists the Customizer parameters of an OpenSCAD file (top-level variables with their type, default value, allowed
        |range or options, group and description) and the names of its saved parameter sets. Use it to learn which values
        |can be passed to scad_render or scad_export as 'parameters' or 'parameterSet'.
        """
    )
    suspend fun scad_parameters(
        @McpDescription("Path of the .scad file, absolute or relative to the project root.")
        filePath: String,
    ): String {
        val project = currentCoroutineContext().project
        val file = resolve(project, filePath)
        val virtualFile = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(file) ?: return "File not found: ${file.path}"
        val parameters = ReadAction.nonBlocking<List<CustomizerParameter>?> {
            (PsiManager.getInstance(project).findFile(virtualFile) as? ScadFile)?.let { psi -> ScadCustomizer.parameters(psi).map { it.parameter } }
        }.executeSynchronously() ?: return "Not an OpenSCAD file: ${file.name}"
        return buildString {
            if (parameters.isEmpty()) append("No Customizer parameters.\n")
            for ((group, list) in parameters.groupBy { it.group }) {
                append("[").append(group).append("]\n")
                for (parameter in list) append("  ").append(describe(parameter)).append('\n')
            }
            append(setNames(file))
        }
    }

    @McpTool
    @McpDescription(
        """
        |Exports an OpenSCAD file with the OpenSCAD configured in the IDE. The format follows the extension of the target:
        |3mf, stl, obj, off, amf (3D) or svg, dxf, pdf (2D designs). Returns the errors and warnings of the run.
        """
    )
    suspend fun scad_export(
        @McpDescription("Path of the .scad file, absolute or relative to the project root. The saved file is exported.")
        filePath: String,
        @McpDescription("Target file, absolute or relative to the project root; an existing file is overwritten.")
        targetPath: String,
        @McpDescription("Variables to override: one 'name=value' per line, values as OpenSCAD expressions.")
        parameters: String = "",
        @McpDescription("Name of a parameter set from the <file>.json next to the .scad file. Empty = none.")
        parameterSet: String = "",
    ): String {
        val project = currentCoroutineContext().project
        val file = resolve(project, filePath)
        if (!file.isFile || !file.extension.equals("scad", ignoreCase = true)) return "Not an OpenSCAD file: ${file.path}"
        val target = resolve(project, targetPath)
        val format = ExportFormat.byExtension(target.extension)
            ?: return "Unknown format '.${target.extension}'. Available: ${ExportFormat.entries.joinToString(", ") { it.extension }}"
        if (target.parentFile?.isDirectory != true) return "Folder not found: ${target.parent}"
        val exe = OpenScad.executable() ?: return NOT_FOUND
        if (parameterSet.isNotBlank() && parameterSet !in sets(file)) return "Parameter set '$parameterSet' not found. ${setNames(file)}"
        return withContext(Dispatchers.IO) {
            val options = if (format == ExportFormat.STL && OpenScad.features(exe).binaryStl) ExportOptions(exportFormat = "binstl") else ExportOptions()
            val result = OpenScadRender(exe).export(file, target, options, defines(parameters), EmptyProgressIndicator(), parameterSet(file, parameterSet))
            LocalFileSystem.getInstance().refreshAndFindFileByIoFile(target)
            buildString {
                append(if (result.ok) "Exported ${target.path} (${target.length()} bytes)" else "Export failed").append('\n')
                appendMessages(result.messages.filter { it.level != RenderMessage.Level.ECHO && it.level != RenderMessage.Level.TRACE }, file)
            }
        }
    }

    private fun resolve(project: Project, path: String): File {
        val file = File(path.trim())
        return if (file.isAbsolute) file else File(project.basePath ?: System.getProperty("user.home"), path.trim()).normalize()
    }

    /** Ungespeicherter Stand im Editor, sonst null (dann liest OpenSCAD die Datei). */
    private fun unsavedText(file: File): String? {
        val virtualFile: VirtualFile = LocalFileSystem.getInstance().findFileByIoFile(file) ?: return null
        val documents = FileDocumentManager.getInstance()
        val document = documents.getCachedDocument(virtualFile)?.takeIf { documents.isDocumentUnsaved(it) } ?: return null
        return ReadAction.nonBlocking<String> { document.text }.executeSynchronously()
    }

    private fun defines(parameters: String): List<String> =
        parameters.lines().map { it.trim().removeSuffix(";") }.filter { it.contains('=') }.map { line ->
            line.substringBefore('=').trim() + "=" + line.substringAfter('=').trim()
        }

    private fun setsFile(file: File) = File(file.parentFile, "${file.nameWithoutExtension}.json")

    private fun sets(file: File): List<String> =
        LocalFileSystem.getInstance().refreshAndFindFileByIoFile(file)?.let { ParameterSets.read(it).keys.toList() }.orEmpty()

    private fun setNames(file: File): String =
        sets(file).let { if (it.isEmpty()) "No parameter sets." else "Parameter sets: ${it.joinToString(", ") { name -> "'$name'" }}" }

    /** Datei mit den Sätzen und Name des Satzes für `-p … -P …`; null ohne Satz. */
    private fun parameterSet(file: File, name: String): Pair<File, String>? = if (name.isBlank()) null else setsFile(file) to name

    private fun describe(parameter: CustomizerParameter): String = buildString {
        append(parameter.name).append(" = ").append(parameter.initial.toScad())
        append("  (").append(parameter.type.name.lowercase())
        if (parameter.min != null && parameter.max != null) {
            append(", ").append(formatNumber(parameter.min)).append(" to ").append(formatNumber(parameter.max))
            parameter.step?.let { append(" step ").append(formatNumber(it)) }
        }
        parameter.options?.let { options ->
            append(", one of: ").append(options.joinToString(", ") { if (it.label != it.value.toScad()) "${it.value.toScad()} (${it.label})" else it.value.toScad() })
        }
        append(")")
        parameter.description?.let { append("  // ").append(it) }
    }

    private fun report(file: File, exe: String, result: OpenScadRender.CheckResult): String = buildString {
        append("File: ").append(file.path).append('\n')
        OpenScad.version(exe)?.let { append("OpenSCAD ").append(it).append(", ").append("%.1f".format(java.util.Locale.ROOT, result.seconds)).append(" s\n") }
        append("Result: ").append(
            when {
                result.failed -> "failed - no geometry, see the errors below"
                result.empty -> "empty - the file creates no object at the top level"
                result.flat -> "2D design (no 3D measures)"
                else -> "ok"
            },
        ).append('\n')
        result.stats?.let { appendStats(it) }
        appendMessages(result.messages.filter { it.level != RenderMessage.Level.TRACE }, file)
        if (result.images.isNotEmpty()) {
            append("Images (PNG, axes through the origin, x/y/z indicator at the bottom left):\n")
            result.images.forEach { (view, image) -> append("  ").append(view).append(": ").append(image.path).append('\n') }
        }
        result.imageErrors.forEach { (view, error) -> append("  ").append(view).append(": no image (").append(error).append(")\n") }
    }

    private fun StringBuilder.appendStats(stats: MeshStats) {
        fun vector(values: DoubleArray) = values.joinToString(", ", "[", "]") { number(it) }
        val size = stats.size
        append("Size: ").append(number(size[0])).append(" x ").append(number(size[1])).append(" x ").append(number(size[2])).append(" mm (X x Y x Z)\n")
        append("Bounding box: min ").append(vector(stats.min)).append(", max ").append(vector(stats.max)).append('\n')
        append("Triangles: ").append(stats.triangles).append(", volume: ").append(number(stats.volume / 1000)).append(" cm3, surface: ")
            .append(number(stats.area / 100)).append(" cm2\n")
    }

    private fun StringBuilder.appendMessages(messages: List<RenderMessage>, file: File) {
        if (messages.isEmpty()) return
        append("Messages:\n")
        for (message in messages.take(MAX_MESSAGES)) {
            append("  ").append(message.level.name)
            message.line?.let { line ->
                append(" line ").append(line)
                message.file?.takeIf { it != file.path }?.let { append(" in ").append(File(it).name) }
            }
            append(": ").append(message.text.replace("\n", "\n    ")).append('\n')
        }
        if (messages.size > MAX_MESSAGES) append("  ... and ").append(messages.size - MAX_MESSAGES).append(" more\n")
    }

    /** Zwei Nachkommastellen, ohne überflüssige Nullen. */
    private fun number(value: Double): String = formatNumber(Math.round(value * 100) / 100.0)

    private companion object {
        const val NOT_FOUND = "OpenSCAD was not found. Set its path under Settings | Tools | SCAD Sphere."
        const val MAX_MESSAGES = 60
    }
}
