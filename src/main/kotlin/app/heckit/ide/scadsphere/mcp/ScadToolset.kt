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
import app.heckit.ide.scadsphere.ScadSphereSettings
import app.heckit.ide.scadsphere.editor.ModelFileEditor
import app.heckit.ide.scadsphere.editor.ScadPreviewEditor
import app.heckit.ide.scadsphere.editor.ScadSplitEditor
import app.heckit.ide.scadsphere.render.PrintAnalysis
import com.intellij.openapi.application.EDT
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.components.service
import kotlinx.coroutines.future.await
import kotlinx.coroutines.withTimeout
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
        |triangle count, volume and surface area, overhangs for printing along +Z (area, height range, lowest point,
        |flat undersides that need support), plus PNG images of the model from the requested views.
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

    @McpTool
    @McpDescription(
        """
        |Print check of an OpenSCAD file (.scad) or a mesh file (.stl, .3mf, .obj, .off, .amf) for printing along +Z, like
        |the print check in the IDE's 3D view: overhang area, spots where a layer starts in mid-air (islands: they need
        |support, e.g. a hanging pin), walls thinner than minWall, the area lying on the bed, open or non-manifold edges,
        |volume, weight and filament length, and whether the model fits the print bed configured in the IDE.
        |Use it before exporting a model for printing; fix islands and thin walls in the code.
        """
    )
    suspend fun scad_print_check(
        @McpDescription("Path of the .scad or mesh file, absolute or relative to the project root.")
        filePath: String,
        @McpDescription("Variables to override: one 'name=value' per line, values as OpenSCAD expressions.")
        parameters: String = "",
        @McpDescription("Name of a parameter set from the <file>.json next to the .scad file. Empty = none.")
        parameterSet: String = "",
        @McpDescription("Overhang angle in degrees from vertical (10-80); steeper downward faces count as overhangs.")
        overhangAngle: Double = 45.0,
        @McpDescription("Minimum wall thickness in mm (0 = no thin wall check), e.g. 0.8 for two lines of a 0.4 mm nozzle.")
        minWall: Double = 0.8,
        @McpDescription("Filament for the weight: PLA, PETG, ABS, ASA, TPU, PA or PC.")
        material: String = "PLA",
    ): String {
        val (file, result) = renderMesh(filePath, parameters, parameterSet).let { it.first to (it.second ?: return it.third) }
        val mesh = result.mesh ?: return "No 3D result.\n" + result.messages.joinToString("\n") { "${it.level}: ${it.text}" }
        return withContext(Dispatchers.Default) {
            val report = PrintAnalysis.check(PrintAnalysis.trianglesOfStl(mesh), overhangAngle.coerceIn(10.0, 80.0), minWall.coerceIn(0.0, 10.0))
            buildString {
                append("File: ").append(file.path).append('\n')
                appendMessages(result.messages.filter { it.level == RenderMessage.Level.ERROR || it.level == RenderMessage.Level.WARNING }, file)
                val size = DoubleArray(3) { report.max[it] - report.min[it] }
                append("Size: ").append(size.joinToString(" x ") { number(it) }).append(" mm\n")
                append("Overhangs over ").append(number(overhangAngle)).append(" degrees: ")
                if (report.overhangArea == 0.0) append("none\n") else {
                    append(number(report.overhangArea)).append(" mm2")
                    report.overhangLowest?.let { append(", lowest at ").append(vector(it)) }
                    append('\n')
                }
                append("Starts in mid-air (islands, need support): ")
                if (report.islands.isEmpty()) append("none\n") else {
                    append(report.islands.size).append('\n')
                    for (island in report.islands.take(20)) append("  at ").append(vector(doubleArrayOf(island.x, island.y, island.z)))
                        .append(", underside radius ").append(number(island.r)).append(" mm, ").append(number(island.z - report.min[2])).append(" mm above the bed\n")
                }
                if (minWall > 0) {
                    append("Walls under ").append(number(minWall)).append(" mm: ")
                    if (report.thinArea == 0.0) append("none\n") else {
                        append(number(report.thinArea)).append(" mm2, e.g. at ").append(report.thinSpots.take(5).joinToString(", ") { vector(it) }).append('\n')
                    }
                }
                append("On the bed: ").append(number(report.contactArea)).append(" mm2\n")
                append("Edges: ").append(if (report.openEdges == 0 && report.nonManifoldEdges == 0) "closed surface" else "${report.openEdges} open, ${report.nonManifoldEdges} with more than two faces").append('\n')
                val density = mapOf("PLA" to 1.24, "PETG" to 1.27, "ABS" to 1.04, "ASA" to 1.07, "TPU" to 1.21, "PA" to 1.14, "PC" to 1.2)
                val name = material.uppercase().takeIf { it in density } ?: "PLA"
                val grams = report.volume / 1000 * density.getValue(name)
                append("Volume: ").append(number(report.volume / 1000)).append(" cm3, ").append(number(grams)).append(" g ").append(name)
                    .append(" (solid), ").append(number(report.volume / (Math.PI * 0.875 * 0.875) / 1000)).append(" m of 1.75 mm filament\n")
                if (ScadSphereSettings.bedEnabled) {
                    val bed = doubleArrayOf(ScadSphereSettings.bedWidth.toDouble(), ScadSphereSettings.bedDepth.toDouble(), ScadSphereSettings.bedHeight.toDouble())
                    val fits = (0..2).all { size[it] <= bed[it] + 1e-6 }
                    append("Print bed ").append(bed.joinToString(" x ") { number(it) }).append(" mm: ").append(if (fits) "fits" else "too large - split it (section view in the IDE) or turn it").append('\n')
                }
            }
        }
    }

    @McpTool
    @McpDescription(
        """
        |Cuts an OpenSCAD file (.scad) or a mesh file at a plane across the X, Y or Z axis and returns the cross-section:
        |its area, perimeter, number of separate pieces, extent, and a PNG image (hatched like a section drawing, with a
        |scale bar). Use it to check wall thickness, holes, clearances between parts or the inside of a model.
        """
    )
    suspend fun scad_section(
        @McpDescription("Path of the .scad or mesh file, absolute or relative to the project root.")
        filePath: String,
        @McpDescription("Axis across which to cut: x, y or z.")
        axis: String = "z",
        @McpDescription("Position of the cut on that axis in mm. Empty = the middle of the model.")
        position: String = "",
        @McpDescription("Variables to override: one 'name=value' per line, values as OpenSCAD expressions.")
        parameters: String = "",
        @McpDescription("Name of a parameter set from the <file>.json next to the .scad file. Empty = none.")
        parameterSet: String = "",
        @McpDescription("Longer side of the image in pixels (200-2000).")
        imageSize: Int = 800,
        @McpDescription("Folder for the image, absolute or relative to the project root. Empty = a temporary folder.")
        outputDir: String = "",
    ): String {
        val axisIndex = "xyz".indexOf(axis.trim().lowercase()).takeIf { it >= 0 && axis.trim().length == 1 } ?: return "Unknown axis '$axis'. Use x, y or z."
        val (file, result) = renderMesh(filePath, parameters, parameterSet).let { it.first to (it.second ?: return it.third) }
        val mesh = result.mesh ?: return "No 3D result.\n" + result.messages.joinToString("\n") { "${it.level}: ${it.text}" }
        val project = currentCoroutineContext().project
        val imageDir = if (outputDir.isBlank()) File(File(PathManager.getTempPath(), "scad-sphere"), "mcp") else resolve(project, outputDir)
        return withContext(Dispatchers.Default) {
            val tris = PrintAnalysis.trianglesOfStl(mesh)
            var lo = Double.POSITIVE_INFINITY; var hi = Double.NEGATIVE_INFINITY
            for (i in axisIndex until tris.size step 3) { lo = minOf(lo, tris[i]); hi = maxOf(hi, tris[i]) }
            val value = position.trim().replace(',', '.').takeIf { it.isNotEmpty() }?.toDoubleOrNull() ?: ((lo + hi) / 2)
            val section = PrintAnalysis.section(tris, axisIndex, value)
            val name = "xyz"[axisIndex]
            buildString {
                append("File: ").append(file.path).append('\n')
                append("Cut at ").append(name).append(" = ").append(number(value)).append(" mm (model from ").append(number(lo)).append(" to ").append(number(hi)).append(")\n")
                if (section.isEmpty) {
                    append("The plane misses the model.\n")
                    return@buildString
                }
                val (ua, va) = (0..2).filter { it != axisIndex }.map { "XYZ"[it] }
                val e = section.extent()
                append("Area: ").append(number(section.area)).append(" mm2, perimeter ").append(number(section.perimeter)).append(" mm, pieces: ").append(section.pieces()).append('\n')
                append("Extent: ").append(ua).append(' ').append(number(e[0])).append(" to ").append(number(e[2])).append(", ")
                    .append(va).append(' ').append(number(e[1])).append(" to ").append(number(e[3])).append(" mm\n")
                val target = File(imageDir, "${file.nameWithoutExtension}-section-$name.png")
                runCatching { PrintAnalysis.drawSection(section, target, imageSize.coerceIn(200, 2000), "${file.name}: $name = ${number(value)} mm, ${number(section.area)} mm²") }
                    .onSuccess { append("Image (").append(ua).append(" to the right, ").append(va).append(" up): ").append(target.path).append('\n') }
                    .onFailure { append("No image: ").append(it.message).append('\n') }
            }
        }
    }

    @McpTool
    @McpDescription(
        """
        |Shows what the user currently sees in the IDE's 3D view of an open file: a PNG of the view exactly as displayed
        |(camera, section, kept measurements, dimensions, print check colors, highlighted part at the caret) and its
        |state as text: view direction, section plane, kept measurements, print check results, selected or hidden parts.
        |Use it when the user refers to something in the view ("look at this", "the measurement I kept").
        """
    )
    suspend fun scad_view(
        @McpDescription("Path of an open .scad or mesh file, absolute or relative to the project root. Empty = the file in the active editor.")
        filePath: String = "",
        @McpDescription("Longer side of the image in pixels (200-2000), 0 = no image.")
        imageSize: Int = 1000,
        @McpDescription("Folder for the image, absolute or relative to the project root. Empty = a temporary folder.")
        outputDir: String = "",
    ): String {
        val project = currentCoroutineContext().project
        val target = withContext(Dispatchers.EDT) {
            val manager = project.service<FileEditorManager>()
            val file = if (filePath.isBlank()) manager.selectedFiles.firstOrNull() else LocalFileSystem.getInstance().refreshAndFindFileByIoFile(resolve(project, filePath))
            val editor = file?.let { manager.getAllEditors(it).firstNotNullOfOrNull { editor ->
                when (editor) {
                    is ScadSplitEditor -> editor.preview
                    is ScadPreviewEditor, is ModelFileEditor -> editor
                    else -> null
                }
            } }
            file to editor
        }
        val file = target.first ?: return "No file is open in the editor."
        val future = when (val editor = target.second) {
            is ScadPreviewEditor -> editor.describeView(if (imageSize <= 0) 0 else imageSize.coerceIn(200, 2000))
            is ModelFileEditor -> editor.describeView(if (imageSize <= 0) 0 else imageSize.coerceIn(200, 2000))
            else -> return "${file.name} is not open with a 3D view in the IDE."
        }
        val message = runCatching { withTimeout(20_000) { future.await() } }.getOrElse { return "The 3D view did not answer (${it.message}). Is the preview visible?" }
        val imageDir = if (outputDir.isBlank()) File(File(PathManager.getTempPath(), "scad-sphere"), "mcp") else resolve(project, outputDir)
        return withContext(Dispatchers.IO) { describeView(file.path, message, imageDir) }
    }

    private fun describeView(path: String, message: com.google.gson.JsonObject, imageDir: File): String = buildString {
        fun text(name: String) = message.get(name)?.takeIf { !it.isJsonNull }
        append("File: ").append(path).append('\n')
        if (text("hasModel")?.asBoolean != true) { append("No model is shown.\n"); return@buildString }
        text("size")?.asJsonArray?.let { size -> append("Size: ").append(size.joinToString(" x ") { number(it.asDouble) }).append(" mm\n") }
        text("view")?.asJsonObject?.let { view ->
            append("View: ").append(view.get("name").asString).append(" (camera direction from the target ").append(view.get("direction")).append(")")
            if (text("ortho")?.asBoolean == true) append(", orthographic")
            append(", up axis ").append(text("upAxis")?.asString ?: "z").append('\n')
        }
        text("section")?.asJsonObject?.let { append("Section: ").append(it.get("axis").asString).append(" = ").append(number(it.get("value").asDouble)).append(" mm").append(if (it.get("flip").asBoolean) " (showing the upper side)" else "").append('\n') }
        text("kept")?.asJsonArray?.takeIf { it.size() > 0 }?.let { kept -> append("Kept measurements: ").append(kept.joinToString("; ") { it.asString }).append('\n') }
        text("measure")?.asString?.let { append("Current measurement: ").append(it).append('\n') }
        if (text("dimensions")?.asBoolean == true) append("Dimensions shown.\n")
        text("bed")?.asString?.let { append("Print bed: ").append(it).append('\n') }
        text("selectedPart")?.asString?.let { append("Selected part: ").append(it).append('\n') }
        text("hiddenParts")?.asJsonArray?.takeIf { it.size() > 0 }?.let { append("Hidden parts: ").append(it.joinToString(", ") { p -> p.asString }).append('\n') }
        text("animation")?.asJsonObject?.let { append("Animation step ").append(it.get("frame").asInt).append(" of ").append(it.get("steps").asInt).append('\n') }
        if (text("caret")?.asBoolean == true) append("The call at the caret is highlighted in blue.\n")
        text("splitCuts")?.asJsonArray?.takeIf { it.size() > 0 }?.let { cuts -> append("Kept cuts for splitting: ").append(cuts.joinToString(", ") { val c = it.asJsonObject; "${c.get("axis").asString} = ${number(c.get("value").asDouble)} (${c.get("pins").asInt} pins)" }).append('\n') }
        text("print")?.asJsonObject?.let { p ->
            append("Print check: overhangs over ").append(number(p.get("angle").asDouble)).append("° ").append(number(p.get("overhangArea").asDouble)).append(" mm2, on the bed ")
                .append(number(p.get("contactArea").asDouble)).append(" mm2, islands ").append(p.getAsJsonArray("islands").size())
            if (p.get("minWall").asDouble > 0) append(", walls under ").append(number(p.get("minWall").asDouble)).append(" mm ").append(number(p.get("thinArea").asDouble)).append(" mm2")
            append(", open edges ").append(p.get("openEdges").asInt).append(", volume ").append(number(p.get("volume").asDouble / 1000)).append(" cm3\n")
        }
        text("error")?.asString?.let { append("Error in the view: ").append(it).append('\n') }
        text("image")?.asString?.substringAfter("base64,", "")?.takeIf { it.isNotEmpty() }?.let { data ->
            val target = File(imageDir, "${File(path).nameWithoutExtension}-view.png")
            runCatching {
                imageDir.mkdirs()
                target.writeBytes(java.util.Base64.getDecoder().decode(data))
            }.onSuccess { append("Image of the view: ").append(target.path).append('\n') }
                .onFailure { append("No image: ").append(it.message).append('\n') }
        }
    }

    /** Datei rechnen (STL) wie scad_render, ohne Bilder: Datei, Ergebnis oder Fehlertext. */
    private suspend fun renderMesh(filePath: String, parameters: String, parameterSet: String): Triple<File, OpenScadRender.CheckResult?, String> {
        val project = currentCoroutineContext().project
        val file = resolve(project, filePath)
        if (!file.isFile) return Triple(file, null, "File not found: ${file.path}")
        val exe = OpenScad.executable() ?: return Triple(file, null, NOT_FOUND)
        val isScad = file.extension.equals("scad", ignoreCase = true)
        if (!isScad && ExportFormat.byExtension(file.extension)?.is3d != true) return Triple(file, null, "Not an OpenSCAD or mesh file: ${file.name}")
        if (parameterSet.isNotBlank() && (!isScad || parameterSet !in sets(file))) return Triple(file, null, "Parameter set '$parameterSet' not found. ${setNames(file)}")
        val set = OpenScadRender.parameterSetArguments(parameterSet(file, parameterSet))
        val text = if (isScad) unsavedText(file) else "import(\"${file.absolutePath.replace('\\', '/').replace("\"", "\\\"")}\");\n"
        val result = withContext(Dispatchers.IO) {
            OpenScadRender(exe).check(file, text, defines(parameters), set, emptyList(), 200, File(PathManager.getTempPath(), "scad-sphere"))
        }
        if (result.failed) return Triple(file, null, "Rendering failed.\n" + result.messages.joinToString("\n") { "${it.level}: ${it.text}" + (it.line?.let { line -> " (line $line)" } ?: "") })
        if (result.flat) return Triple(file, null, "2D design - nothing to check for printing.")
        return Triple(file, result, "")
    }

    private fun vector(values: DoubleArray) = values.joinToString(", ", "[", "]") { number(it) }

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
        val overhang = stats.overhang
        append("Overhangs (faces more than ").append(number(MeshStats.OVERHANG_ANGLE)).append(" degrees from vertical facing down, printed along +Z, not on the bed): ")
        if (overhang.area == 0.0) append("none\n")
        else {
            append(number(overhang.area)).append(" mm2 (").append(number(100 * overhang.area / stats.area)).append("% of the surface), z ")
                .append(number(overhang.minZ)).append(" to ").append(number(overhang.maxZ)).append(" mm")
            overhang.lowest?.let { append(", lowest at ").append(vector(it)) }
            append('\n')
            if (overhang.flatArea > 0) append("  of which flat undersides (need support or bridging): ").append(number(overhang.flatArea)).append(" mm2\n")
        }
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
