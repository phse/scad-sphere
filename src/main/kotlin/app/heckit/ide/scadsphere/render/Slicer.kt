package app.heckit.ide.scadsphere.render

import app.heckit.ide.scadsphere.ScadSphereSettings
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.openapi.util.SystemInfo
import com.intellij.util.EnvironmentUtil
import java.io.File

/** Installierte Slicer (Bambu Studio, OrcaSlicer, PrusaSlicer, UltiMaker Cura) finden und eine Datei darin öffnen. */
object Slicer {
    class Found(val name: String, val path: String)

    private class Known(val name: String, val macApps: List<String>, val windows: List<String>, val linux: List<String>)

    private val KNOWN = listOf(
        Known("Bambu Studio", listOf("BambuStudio.app"), listOf("Bambu Studio\\bambu-studio.exe"), listOf("bambu-studio", "BambuStudio")),
        Known("OrcaSlicer", listOf("OrcaSlicer.app"), listOf("OrcaSlicer\\orca-slicer.exe"), listOf("orca-slicer", "OrcaSlicer")),
        Known("PrusaSlicer", listOf("PrusaSlicer.app", "Original Prusa Drivers/PrusaSlicer.app"),
            listOf("Prusa3D\\PrusaSlicer\\prusa-slicer.exe", "PrusaSlicer\\prusa-slicer.exe"), listOf("prusa-slicer", "PrusaSlicer")),
        Known("UltiMaker Cura", listOf("UltiMaker Cura.app"), listOf("UltiMaker Cura\\UltiMaker-Cura.exe"), listOf("cura", "UltiMaker-Cura")),
    )

    /** Alle gefundenen Slicer, in der Reihenfolge oben. */
    fun detect(): List<Found> = KNOWN.mapNotNull { known -> candidates(known).firstOrNull { File(it).exists() }?.let { Found(known.name, it) } }

    /** Eingestellter Slicer oder der erste gefundene. */
    fun configured(): Found? {
        val path = ScadSphereSettings.slicerPath.trim()
        if (path.isEmpty()) return detect().firstOrNull()
        val file = File(path)
        if (!file.exists()) return null
        return Found(file.nameWithoutExtension.removeSuffix(".app"), path)
    }

    fun open(slicer: Found, file: File) {
        val command = if (SystemInfo.isMac && slicer.path.endsWith(".app")) {
            GeneralCommandLine("open", "-a", slicer.path, file.path)
        } else {
            GeneralCommandLine(slicer.path, file.path)
        }
        command.withParentEnvironmentType(GeneralCommandLine.ParentEnvironmentType.CONSOLE).createProcess()
    }

    private fun candidates(known: Known): List<String> {
        val home = System.getProperty("user.home")
        return when {
            SystemInfo.isMac -> listOf("/Applications", "$home/Applications").flatMap { dir -> known.macApps.map { "$dir/$it" } }
            SystemInfo.isWindows -> listOfNotNull(System.getenv("ProgramFiles"), System.getenv("ProgramFiles(x86)"), System.getenv("LOCALAPPDATA")?.let { "$it\\Programs" })
                .flatMap { base -> known.windows.map { "$base\\$it" } }
            else -> known.linux.mapNotNull(::inPath)
        }
    }

    private fun inPath(name: String): String? =
        EnvironmentUtil.getValue("PATH")?.split(File.pathSeparatorChar)?.map { File(it, name) }?.firstOrNull { it.isFile && it.canExecute() }?.path
}
