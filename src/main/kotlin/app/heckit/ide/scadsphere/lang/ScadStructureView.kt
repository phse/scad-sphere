package app.heckit.ide.scadsphere.lang

import app.heckit.ide.scadsphere.psi.ScadAssignment
import app.heckit.ide.scadsphere.psi.ScadCallableDeclaration
import app.heckit.ide.scadsphere.psi.ScadFile
import app.heckit.ide.scadsphere.psi.ScadNamedElement
import com.intellij.ide.structureView.StructureViewBuilder
import com.intellij.ide.structureView.StructureViewModel
import com.intellij.ide.structureView.StructureViewModelBase
import com.intellij.ide.structureView.StructureViewTreeElement
import com.intellij.ide.structureView.TreeBasedStructureViewBuilder
import com.intellij.ide.util.treeView.smartTree.Sorter
import com.intellij.lang.PsiStructureViewFactory
import com.intellij.navigation.ItemPresentation
import com.intellij.openapi.editor.Editor
import com.intellij.pom.Navigatable
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile

/** Struktur: Module, Funktionen und Variablen der obersten Ebene, in Modulen die lokalen. */
class ScadStructureViewFactory : PsiStructureViewFactory {
    override fun getStructureViewBuilder(psiFile: PsiFile): StructureViewBuilder? {
        if (psiFile !is ScadFile) return null
        return object : TreeBasedStructureViewBuilder() {
            override fun createStructureViewModel(editor: Editor?): StructureViewModel = ScadStructureViewModel(psiFile, editor)
            override fun isRootNodeShown(): Boolean = false
        }
    }
}

private class ScadStructureViewModel(file: ScadFile, editor: Editor?) :
    StructureViewModelBase(file, editor, ScadStructureElement(file)), StructureViewModel.ElementInfoProvider {
    init {
        withSuitableClasses(ScadNamedElement::class.java)
    }

    override fun getSorters(): Array<Sorter> = arrayOf(Sorter.ALPHA_SORTER)
    override fun isAlwaysShowsPlus(element: StructureViewTreeElement): Boolean = false
    override fun isAlwaysLeaf(element: StructureViewTreeElement): Boolean = element.value is ScadAssignment
}

private class ScadStructureElement(private val element: PsiElement) : StructureViewTreeElement {
    override fun getValue(): Any = element

    override fun getPresentation(): ItemPresentation =
        (element as? ScadNamedElement)?.presentation ?: object : ItemPresentation {
            override fun getPresentableText(): String = (element as? PsiFile)?.name ?: ""
            override fun getIcon(unused: Boolean) = ScadIcons.SCAD
        }

    override fun getChildren(): Array<StructureViewTreeElement> {
        val scope = when (element) {
            is ScadFile -> element
            // Lokale Module/Funktionen/Variablen im Rumpf eines Moduls
            is ScadCallableDeclaration -> element.node.findChildByType(ScadTypes.BLOCK)?.psi ?: return emptyArray()
            else -> return emptyArray()
        }
        val children = scope.children.filter { it is ScadCallableDeclaration || it is ScadAssignment && element is ScadFile }
        return children.map { ScadStructureElement(it) }.toTypedArray()
    }

    override fun navigate(requestFocus: Boolean) {
        (element as? Navigatable)?.navigate(requestFocus)
    }

    override fun canNavigate(): Boolean = (element as? Navigatable)?.canNavigate() == true
    override fun canNavigateToSource(): Boolean = (element as? Navigatable)?.canNavigateToSource() == true
}
