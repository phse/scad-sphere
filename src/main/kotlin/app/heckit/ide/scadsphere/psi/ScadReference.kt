package app.heckit.ide.scadsphere.psi

import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiElementResolveResult
import com.intellij.psi.PsiPolyVariantReferenceBase
import com.intellij.psi.ResolveResult
import com.intellij.psi.impl.source.resolve.ResolveCache

/**
 * Verweis auf ein Modul, eine Funktion oder eine Variable. Mehrere Ziele, wenn ein Name im selben Bereich
 * mehrfach zugewiesen wird (in OpenSCAD gilt dann die letzte Zuweisung).
 */
class ScadReference(element: ScadPsiElement, range: TextRange, val target: Target) :
    PsiPolyVariantReferenceBase<ScadPsiElement>(element, range) {

    /** Funktionsaufrufe suchen erst Funktionen, dann Variablen (Funktionsliterale). */
    enum class Target { MODULE, FUNCTION, VARIABLE }

    override fun multiResolve(incompleteCode: Boolean): Array<ResolveResult> =
        ResolveCache.getInstance(element.project).resolveWithCaching(this, RESOLVER, false, incompleteCode)

    private fun doResolve(): Array<ResolveResult> {
        val name = value
        val found = when (target) {
            Target.MODULE -> ScadResolver.resolve(element, name, Namespace.MODULE)
            Target.VARIABLE -> ScadResolver.resolve(element, name, Namespace.VARIABLE)
            Target.FUNCTION -> ScadResolver.resolve(element, name, Namespace.FUNCTION)
                .ifEmpty { ScadResolver.resolve(element, name, Namespace.VARIABLE) }
        }
        return found.map { PsiElementResolveResult(it) }.toTypedArray()
    }

    /** Schnell ablehnen, ohne aufzulösen: Bei der Suche nach PHP-, HTML-, …-Elementen kommen wir hier nie weiter. */
    override fun isReferenceTo(element: PsiElement): Boolean {
        if (element !is ScadNamedElement || element.name != value) return false
        return super.isReferenceTo(element)
    }

    override fun handleElementRename(newElementName: String): PsiElement {
        element.findElementAt(rangeInElement.startOffset)?.replace(ScadElementFactory.identifier(element.project, newElementName))
        return element
    }

    override fun getVariants(): Array<Any> = emptyArray()

    companion object {
        private val RESOLVER = ResolveCache.PolyVariantResolver<ScadReference> { ref, _ -> ref.doResolve() }
    }
}

/** Benanntes Argument (`cube(size = 3)` → Parameter `size` des aufgerufenen Moduls). */
class ScadParameterReference(element: ScadArgument, range: TextRange) :
    PsiPolyVariantReferenceBase<ScadArgument>(element, range) {

    override fun multiResolve(incompleteCode: Boolean): Array<ResolveResult> =
        ResolveCache.getInstance(element.project).resolveWithCaching(this, RESOLVER, false, incompleteCode)

    private fun doResolve(): Array<ResolveResult> =
        ScadResolver.calleeParameters(element).filter { it.name == value }.map { PsiElementResolveResult(it) }.toTypedArray()

    override fun isReferenceTo(element: PsiElement): Boolean {
        if (element !is ScadParameter || element.name != value) return false
        return super.isReferenceTo(element)
    }

    override fun handleElementRename(newElementName: String): PsiElement {
        element.argumentName?.replace(ScadElementFactory.identifier(element.project, newElementName))
        return element
    }

    override fun getVariants(): Array<Any> = emptyArray()

    companion object {
        private val RESOLVER = ResolveCache.PolyVariantResolver<ScadParameterReference> { ref, _ -> ref.doResolve() }
    }
}
