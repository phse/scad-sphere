package app.heckit.ide.scadsphere.psi

import app.heckit.ide.scadsphere.lang.ScadFileType
import com.intellij.navigation.ChooseByNameContributorEx
import com.intellij.navigation.NavigationItem
import com.intellij.psi.PsiManager
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.util.Processor
import com.intellij.util.indexing.DataIndexer
import com.intellij.util.indexing.DefaultFileTypeSpecificInputFilter
import com.intellij.util.indexing.FileBasedIndex
import com.intellij.util.indexing.FileContent
import com.intellij.util.indexing.FindSymbolParameters
import com.intellij.util.indexing.ID
import com.intellij.util.indexing.IdFilter
import com.intellij.util.indexing.ScalarIndexExtension
import com.intellij.util.io.EnumeratorStringDescriptor
import com.intellij.util.io.KeyDescriptor

/** Namen der Module und Funktionen auf oberster Ebene je Datei - für Go to Symbol, ohne Stub-Index. */
class ScadSymbolIndex : ScalarIndexExtension<String>() {
    override fun getName(): ID<String, Void> = NAME
    override fun getIndexer(): DataIndexer<String, Void, FileContent> = DataIndexer { content ->
        val file = content.psiFile as? ScadFile ?: return@DataIndexer emptyMap()
        file.children.filterIsInstance<ScadCallableDeclaration>().mapNotNull { it.name }.associateWith { null }
    }
    override fun getKeyDescriptor(): KeyDescriptor<String> = EnumeratorStringDescriptor.INSTANCE
    override fun getVersion(): Int = 1
    override fun getInputFilter(): FileBasedIndex.InputFilter = DefaultFileTypeSpecificInputFilter(ScadFileType)
    override fun dependsOnFileContent(): Boolean = true

    companion object {
        val NAME: ID<String, Void> = ID.create("app.heckit.ide.scadsphere.symbols")
    }
}

/** Go to Symbol (⌘⌥O / Ctrl+Alt+Shift+N): Module und Funktionen aus allen .scad-Dateien des Projekts
 * und - mit „Include non-project items“ - der Bibliotheksordner ([ScadLibraryRootsProvider]). */
class ScadSymbolContributor : ChooseByNameContributorEx {
    override fun processNames(processor: Processor<in String>, scope: GlobalSearchScope, filter: IdFilter?) {
        FileBasedIndex.getInstance().processAllKeys(ScadSymbolIndex.NAME, processor, scope, filter)
    }

    override fun processElementsWithName(name: String, processor: Processor<in NavigationItem>, parameters: FindSymbolParameters) {
        val psiManager = PsiManager.getInstance(parameters.project)
        for (file in FileBasedIndex.getInstance().getContainingFiles(ScadSymbolIndex.NAME, name, parameters.searchScope)) {
            val scad = psiManager.findFile(file) as? ScadFile ?: continue
            for (declaration in scad.children.filterIsInstance<ScadCallableDeclaration>()) {
                if (declaration.name == name && !processor.process(declaration)) return
            }
        }
    }
}
