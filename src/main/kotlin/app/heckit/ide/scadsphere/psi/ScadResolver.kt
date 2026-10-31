package app.heckit.ide.scadsphere.psi

import app.heckit.ide.scadsphere.lang.ScadTypes
import com.intellij.psi.PsiElement

/**
 * Namensauflösung nach den Regeln von OpenSCAD: Blöcke, Modul- und Funktionsparameter, Variablen von
 * for/let (auch in Listen-Comprehensions), dann die Datei selbst und zuletzt, was per include/use dazukommt.
 * Innerhalb eines Blocks sind alle Zuweisungen überall sichtbar, unabhängig von der Reihenfolge.
 */
object ScadResolver {
    fun resolve(place: PsiElement, name: String, namespace: Namespace): List<ScadNamedElement> {
        var found: List<ScadNamedElement> = emptyList()
        processScopes(place) { candidates ->
            found = candidates.filter { it.name == name && it.kind.namespace == namespace }
            found.isEmpty()
        }
        return found
    }

    /** Alle an [place] sichtbaren Deklarationen, die inneren zuerst (für die Vervollständigung). */
    fun visible(place: PsiElement): List<ScadNamedElement> {
        val all = mutableListOf<ScadNamedElement>()
        processScopes(place) { all += it; true }
        return all
    }

    /** Ruft [consumer] je Bereich von innen nach außen auf; `false` beendet die Suche. */
    private fun processScopes(place: PsiElement, consumer: (List<ScadNamedElement>) -> Boolean) {
        var child: PsiElement = place
        var scope: PsiElement? = place.parent
        while (scope != null) {
            val declarations = declarationsIn(scope, child, place)
            if (declarations.isNotEmpty() && !consumer(declarations)) return
            if (scope is ScadFile) {
                consumer(ScadImports.importedDeclarations(scope))
                return
            }
            child = scope
            scope = scope.parent
        }
    }

    private fun declarationsIn(scope: PsiElement, child: PsiElement, place: PsiElement): List<ScadNamedElement> {
        if (scope is ScadFile || scope.node?.elementType == ScadTypes.BLOCK) return topLevel(scope)
        if (scope is ScadCallableDeclaration) return scope.parameters
        return when (scope.node?.elementType) {
            ScadTypes.FUNCTION_LITERAL -> literalParameters(scope)
            ScadTypes.MODULE_CALL ->
                if ((scope as ScadModuleCall).moduleName in ScadArgument.SCOPE_MODULES) loopVariables(scope, child, place) else emptyList()
            ScadTypes.LET_EXPRESSION, ScadTypes.LC_LET, ScadTypes.LC_FOR -> loopVariables(scope, child, place)
            else -> emptyList()
        }
    }

    /** Zuweisungen, Module und Funktionen direkt in einer Datei oder einem Block. */
    fun topLevel(scope: PsiElement): List<ScadNamedElement> =
        scope.children.filter { it is ScadAssignment || it is ScadCallableDeclaration }.map { it as ScadNamedElement }

    private fun literalParameters(literal: PsiElement): List<ScadNamedElement> =
        literal.node.findChildByType(ScadTypes.PARAMETER_LIST)?.psi?.children?.filterIsInstance<ScadParameter>().orEmpty()

    /** Variablen von for/let; innerhalb der Argumentliste nur die vorherigen (let wertet der Reihe nach aus). */
    private fun loopVariables(owner: PsiElement, child: PsiElement, place: PsiElement): List<ScadNamedElement> {
        val list = owner.node.findChildByType(ScadTypes.ARGUMENT_LIST)?.psi ?: return emptyList()
        val variables = list.children.filterIsInstance<ScadArgument>().filter { it.isDeclaration }
        if (child != list) return variables
        val offset = place.textRange.startOffset
        return variables.filter { it.textRange.endOffset <= offset }
    }

    /**
     * Bereich, außerhalb dessen ein Name nicht sichtbar ist - oder null für Namen, die auch andere Dateien
     * per include/use sehen (oberste Ebene, Parameter von Modulen und Funktionen wegen benannter Argumente).
     */
    fun localScope(element: ScadNamedElement): PsiElement? = when (element) {
        is ScadParameter -> element.parent?.parent?.takeIf { it.node.elementType == ScadTypes.FUNCTION_LITERAL }
        is ScadArgument -> element.parent?.parent
        else -> element.parent?.takeIf { it !is ScadFile }
    }

    /** Parameter des Moduls bzw. der Funktion, an die [argument] übergeben wird. */
    fun calleeParameters(argument: ScadArgument): List<ScadParameter> {
        val owner = argument.parent?.parent ?: return emptyList()
        val targets = when (owner) {
            is ScadModuleCall -> (owner.reference as? ScadReference)?.multiResolve(false)
            is ScadCallExpression -> (owner.callee as? ScadReferenceExpression)?.reference?.let { it as? ScadReference }?.multiResolve(false)
            else -> null
        } ?: return emptyList()
        return targets.mapNotNull { it.element }.flatMap { target ->
            when (target) {
                is ScadCallableDeclaration -> target.parameters
                is ScadAssignment -> target.value?.takeIf { it.node.elementType == ScadTypes.FUNCTION_LITERAL }
                    ?.let { literalParameters(it).filterIsInstance<ScadParameter>() }.orEmpty()
                else -> emptyList()
            }
        }
    }
}
