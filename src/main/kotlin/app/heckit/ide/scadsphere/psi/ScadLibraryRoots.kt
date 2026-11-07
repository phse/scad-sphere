package app.heckit.ide.scadsphere.psi

import app.heckit.ide.scadsphere.ScadSphereBundle
import com.intellij.navigation.ItemPresentation
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.AdditionalLibraryRootsProvider
import com.intellij.openapi.roots.SyntheticLibrary
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.icons.AllIcons
import javax.swing.Icon

/**
 * Bibliotheksordner außerhalb des Projekts (Einstellungen, OPENSCADPATH, Benutzer- und Installationsordner) als
 * Bibliothek: Die IDE indiziert sie, so finden Go to Symbol und die Suche auch BOSL2 & Co.; im Projektbaum erscheinen
 * sie unter „External Libraries“.
 */
class ScadLibraryRootsProvider : AdditionalLibraryRootsProvider() {
    override fun getAdditionalProjectLibraries(project: Project): Collection<SyntheticLibrary> {
        val base = project.basePath
        // Ordner im Projekt sind schon indiziert
        val roots = ScadLibraries.directories().filter { base == null || !it.path.startsWith("$base/") && it.path != base }
        if (roots.isEmpty()) return emptyList()
        return listOf(Library(roots))
    }

    override fun getRootsToWatch(project: Project): Collection<VirtualFile> =
        getAdditionalProjectLibraries(project).flatMap { it.sourceRoots }

    private class Library(private val roots: List<VirtualFile>) : SyntheticLibrary("ScadSphere.Libraries", null), ItemPresentation {
        override fun getSourceRoots(): Collection<VirtualFile> = roots
        override fun equals(other: Any?): Boolean = other is Library && other.roots == roots
        override fun hashCode(): Int = roots.hashCode()
        override fun getPresentableText(): String = ScadSphereBundle.message("libraries.external")
        override fun getIcon(unused: Boolean): Icon = AllIcons.Nodes.PpLibFolder
    }
}
