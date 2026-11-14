package app.heckit.ide.scadsphere.psi

import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.util.io.FileUtil
import com.intellij.util.io.HttpRequests
import java.io.File
import java.io.IOException
import java.util.zip.ZipInputStream

/**
 * Verbreitete OpenSCAD-Bibliotheken, die sich aus einem fehlenden `include <BOSL2/std.scad>` heraus installieren
 * lassen: Der erste Teil des Pfads ist der Ordner im Bibliotheksordner, der Inhalt kommt als ZIP des Hauptzweigs
 * von GitHub (kein git nötig).
 */
object ScadLibraryCatalog {
    class Library(val folder: String, val repository: String, val branch: String, val license: String) {
        val zipUrl: String get() = "https://github.com/$repository/archive/refs/heads/$branch.zip"
        val page: String get() = "github.com/$repository"
    }

    val libraries: List<Library> = listOf(
        Library("BOSL2", "BelfrySCAD/BOSL2", "master", "BSD-2-Clause"),
        Library("BOSL", "revarbat/BOSL", "master", "BSD-2-Clause"),
        Library("NopSCADlib", "nophead/NopSCADlib", "master", "GPL-3.0"),
        Library("MCAD", "openscad/MCAD", "master", "LGPL-2.1"),
        Library("Round-Anything", "Irev-Dev/Round-Anything", "master", "MIT"),
        Library("threads-scad", "rcolyer/threads-scad", "master", "CC0-1.0"),
        Library("smooth-prim", "rcolyer/smooth-prim", "master", "CC0-1.0"),
        Library("scad-utils", "openscad/scad-utils", "master", "MIT"),
        Library("YAPP_Box", "mrWheel/YAPP_Box", "main", "MIT"),
    )

    /** Bibliothek zum Pfad eines include/use (`BOSL2/std.scad`); null bei Pfaden ohne Ordner oder unbekanntem Ordner. */
    fun forPath(path: String): Library? {
        val normalized = path.trim().replace('\\', '/')
        if (!normalized.contains('/') || normalized.startsWith("/") || normalized.startsWith(".")) return null
        val folder = normalized.substringBefore('/')
        return libraries.find { it.folder == folder }
    }

    /**
     * [library] nach `<root>/<folder>` laden. Erst vollständig in einen Nachbarordner entpacken, dann umbenennen - ein
     * abgebrochener Download hinterlässt keinen halben Bibliotheksordner. Wirft bei Netz- oder Dateifehlern.
     */
    fun install(library: Library, root: File, indicator: ProgressIndicator?, url: String = library.zipUrl): File {
        val target = File(root, library.folder)
        if (target.exists()) throw IOException("${target.path} already exists")
        if (!root.isDirectory && !root.mkdirs()) throw IOException("Cannot create ${root.path}")
        val zip = FileUtil.createTempFile("scadsphere-${library.folder}", ".zip", true)
        val staging = File(root, ".${library.folder}.download")
        try {
            HttpRequests.request(url).saveToFile(zip, indicator)
            indicator?.checkCanceled()
            FileUtil.delete(staging)
            extract(zip, staging)
            if (staging.list().isNullOrEmpty()) throw IOException("The archive is empty")
            if (!staging.renameTo(target)) throw IOException("Cannot create ${target.path}")
            return target
        } finally {
            FileUtil.delete(zip)
            FileUtil.delete(staging)
        }
    }

    /**
     * ZIP von GitHub nach [target] entpacken, ohne den obersten Ordner (`BOSL2-master/`). Einträge, die aus [target]
     * herausführen würden (`../`), werden abgelehnt.
     */
    fun extract(zip: File, target: File) {
        val base = target.canonicalFile
        ZipInputStream(zip.inputStream().buffered()).use { input ->
            while (true) {
                val entry = input.nextEntry ?: break
                val relative = entry.name.replace('\\', '/').substringAfter('/', "")
                if (relative.isEmpty()) continue
                val file = File(base, relative).canonicalFile
                if (!file.toPath().startsWith(base.toPath())) throw IOException("Unsafe path in archive: ${entry.name}")
                if (entry.isDirectory) {
                    file.mkdirs()
                } else {
                    file.parentFile.mkdirs()
                    file.outputStream().use { input.copyTo(it) }
                }
            }
        }
    }
}
