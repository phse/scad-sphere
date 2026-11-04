package app.heckit.ide.scadsphere.psi

import app.heckit.ide.scadsphere.ScadSphereSettings
import app.heckit.ide.scadsphere.lang.ScadFileType
import app.heckit.ide.scadsphere.render.OpenScad
import com.intellij.openapi.util.SystemInfo
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.psi.AbstractElementManipulator
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiFileFactory
import com.intellij.psi.PsiFileSystemItem
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiReference
import com.intellij.psi.impl.source.resolve.reference.impl.providers.FileReferenceSet
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.PsiModificationTracker
import com.intellij.util.EnvironmentUtil
import java.io.File

/** include/use: Dateien finden (neben der Datei, dann in den Bibliotheksordnern) und ihre Deklarationen. */
object ScadImports {
    /** Datei zu `include <path>` aus Sicht von [context], wie OpenSCAD sie sucht. */
    fun resolve(context: PsiFile, path: String): VirtualFile? {
        if (path.isBlank()) return null
        val file = File(path)
        if (file.isAbsolute) return findFile(path)
        val dir = context.originalFile.virtualFile?.parent
        dir?.findFileByRelativePath(path)?.takeIf { !it.isDirectory }?.let { return it }
        for (library in ScadLibraries.directories()) {
            library.findFileByRelativePath(path)?.takeIf { !it.isDirectory }?.let { return it }
        }
        return null
    }

    fun importedFile(statement: ScadImportStatement): ScadFile? {
        val path = statement.path ?: return null
        val file = resolve(statement.containingFile, path) ?: return null
        return PsiManager.getInstance(statement.project).findFile(file) as? ScadFile
    }

    /**
     * Was eine Datei per include (Text wird eingefügt, also alles, auch rekursiv) und per use (nur Module und
     * Funktionen der Datei samt deren includes) sieht. Ändert sich irgendeine Datei, wird neu berechnet.
     */
    fun importedDeclarations(file: ScadFile): List<ScadNamedElement> =
        CachedValuesManager.getCachedValue(file) {
            CachedValueProvider.Result.create(
                computeImported(file),
                PsiModificationTracker.getInstance(file.project),
                VirtualFileManager.VFS_STRUCTURE_MODIFICATIONS,
            )
        }

    /**
     * Alle include/use der Datei (auch in eingebundenen Dateien) sind auffindbar. Sonst kann ein Name aus einer
     * fehlenden Bibliothek stammen - unbekannte Namen werden dann nicht angemerkt.
     */
    fun allImportsFound(file: ScadFile): Boolean =
        CachedValuesManager.getCachedValue(file) {
            CachedValueProvider.Result.create(
                allFound(file, mutableSetOf()),
                PsiModificationTracker.getInstance(file.project),
                VirtualFileManager.VFS_STRUCTURE_MODIFICATIONS,
            )
        }

    private fun allFound(file: ScadFile, visited: MutableSet<VirtualFile>): Boolean {
        file.originalFile.virtualFile?.let { if (!visited.add(it)) return true }
        return file.imports.all { statement ->
            val target = importedFile(statement) ?: return@all false
            allFound(target, visited)
        }
    }

    private fun computeImported(file: ScadFile): List<ScadNamedElement> {
        val visited = mutableSetOf<VirtualFile>()
        file.originalFile.virtualFile?.let(visited::add)
        val result = mutableListOf<ScadNamedElement>()
        val used = mutableListOf<ScadFile>()
        collectIncludes(file, visited, result, used)
        val usedVisited = mutableSetOf<VirtualFile>()
        for (target in used) {
            if (!usedVisited.add(target.virtualFile ?: continue)) continue
            val declarations = mutableListOf<ScadNamedElement>()
            declarations += ScadResolver.topLevel(target)
            collectIncludes(target, mutableSetOf(target.virtualFile), declarations, mutableListOf())
            result += declarations.filter { it.kind == ScadKind.MODULE || it.kind == ScadKind.FUNCTION }
        }
        return result
    }

    private fun collectIncludes(file: ScadFile, visited: MutableSet<VirtualFile>, out: MutableList<ScadNamedElement>, used: MutableList<ScadFile>) {
        for (statement in file.imports) {
            val target = importedFile(statement) ?: continue
            if (!statement.isInclude) {
                used += target
                continue
            }
            if (!visited.add(target.virtualFile ?: continue)) continue
            out += ScadResolver.topLevel(target)
            collectIncludes(target, visited, out, used)
        }
    }

    /** Verweise auf die Teile des Pfads (Ordner, Datei) - Strg+Klick, Vervollständigung, Umbenennen. */
    fun fileReferences(statement: ScadImportStatement): Array<PsiReference> {
        val path = statement.path ?: return PsiReference.EMPTY_ARRAY
        val range = statement.pathRange ?: return PsiReference.EMPTY_ARRAY
        val set = object : FileReferenceSet(path, statement, range.startOffset, null, true, true) {
            override fun computeDefaultContexts(): Collection<PsiFileSystemItem> {
                val manager = PsiManager.getInstance(statement.project)
                val dirs = listOfNotNull(statement.containingFile.originalFile.virtualFile?.parent) + ScadLibraries.directories()
                return dirs.distinct().mapNotNull { manager.findDirectory(it) }
            }

            // Fehlt eine Bibliothek, meldet OpenSCAD das selbst; rot unterstreichen wir den Pfad nicht.
            override fun isSoft(): Boolean = true
        }
        return set.allReferences.map { it as PsiReference }.toTypedArray()
    }

    private fun findFile(path: String): VirtualFile? =
        LocalFileSystem.getInstance().findFileByPath(path)?.takeIf { !it.isDirectory }
}

/** Ändert den Pfad in `include <…>` (z. B. wenn die Datei umbenannt oder verschoben wird). */
class ScadImportManipulator : AbstractElementManipulator<ScadImportStatement>() {
    override fun handleContentChange(element: ScadImportStatement, range: TextRange, newContent: String): ScadImportStatement {
        val text = range.replace(element.text, newContent)
        val file = PsiFileFactory.getInstance(element.project).createFileFromText("dummy.scad", ScadFileType, text) as ScadFile
        val replacement = file.imports.firstOrNull() ?: return element
        return element.replace(replacement) as ScadImportStatement
    }

    override fun getRangeInElement(element: ScadImportStatement): TextRange = element.pathRange ?: TextRange.EMPTY_RANGE
}

/** Bibliotheksordner, in denen OpenSCAD include/use sucht. */
object ScadLibraries {
    fun directories(): List<VirtualFile> {
        val now = System.currentTimeMillis()
        cached?.let { (time, dirs) -> if (now - time < CACHE_MS && dirs.all(VirtualFile::isValid)) return dirs }
        val fs = LocalFileSystem.getInstance()
        return paths().distinct().mapNotNull { fs.findFileByPath(it)?.takeIf(VirtualFile::isDirectory) }
            .also { cached = now to it }
    }

    /** Kurz gemerkt: wird bei jedem include/use gefragt. */
    @Volatile
    private var cached: Pair<Long, List<VirtualFile>>? = null
    private const val CACHE_MS = 5_000

    fun paths(): List<String> = buildList {
        addAll(ScadSphereSettings.libraryPathList())
        EnvironmentUtil.getValue("OPENSCADPATH")?.split(File.pathSeparatorChar)?.filter { it.isNotBlank() }?.let(::addAll)
        add(userLibraryDir())
        OpenScad.installLibraryDir()?.let(::add)
    }

    /** Bibliotheksordner im Benutzerverzeichnis, wie ihn OpenSCAD unter Hilfe → Bibliotheksinfo zeigt. */
    fun userLibraryDir(): String {
        val home = System.getProperty("user.home")
        return when {
            SystemInfo.isMac -> "$home/Documents/OpenSCAD/libraries"
            SystemInfo.isWindows -> "$home\\Documents\\OpenSCAD\\libraries"
            else -> "${EnvironmentUtil.getValue("XDG_DATA_HOME") ?: "$home/.local/share"}/OpenSCAD/libraries"
        }
    }
}
