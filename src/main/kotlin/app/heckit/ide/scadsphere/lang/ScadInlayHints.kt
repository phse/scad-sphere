package app.heckit.ide.scadsphere.lang

import app.heckit.ide.scadsphere.psi.ScadArgument
import app.heckit.ide.scadsphere.psi.ScadCallExpression
import app.heckit.ide.scadsphere.psi.ScadCallableDeclaration
import app.heckit.ide.scadsphere.psi.ScadModuleCall
import app.heckit.ide.scadsphere.psi.ScadReference
import app.heckit.ide.scadsphere.psi.ScadReferenceExpression
import com.intellij.codeInsight.hints.declarative.InlayHintsCollector
import com.intellij.codeInsight.hints.declarative.InlayHintsProvider
import com.intellij.codeInsight.hints.declarative.InlayTreeSink
import com.intellij.codeInsight.hints.declarative.InlineInlayPosition
import com.intellij.codeInsight.hints.declarative.SharedBypassCollector
import com.intellij.openapi.editor.Editor
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile

/**
 * Parameternamen vor Argumenten ohne Namen: `cube([1, 2, 3], center: true)`. Nur bei Aufrufen mit mindestens zwei
 * Argumenten (`translate([1, 0, 0])` bleibt ruhig) und nur vor Literalen - bei `cube(size)` sagt der Name schon genug.
 */
class ScadInlayParameterHintsProvider : InlayHintsProvider {
    class Hint(val name: String, val offset: Int)

    override fun createCollector(file: PsiFile, editor: Editor): InlayHintsCollector = object : SharedBypassCollector {
        override fun collectFromElement(element: PsiElement, sink: InlayTreeSink) {
            for (hint in hints(element)) {
                sink.addPresentation(InlineInlayPosition(hint.offset, false), hintFormat = com.intellij.codeInsight.hints.declarative.HintFormat.default) {
                    text("${hint.name}:")
                }
            }
        }
    }

    fun hints(element: PsiElement): List<Hint> {
        val (arguments, names) = when (element) {
            is ScadModuleCall -> {
                if (element.moduleName in ScadArgument.SCOPE_MODULES) return emptyList()
                element.arguments to parameterNames(element.reference as? ScadReference, element.moduleName?.let { ScadBuiltins.module(it) })
            }
            is ScadCallExpression -> {
                val callee = element.callee as? ScadReferenceExpression ?: return emptyList()
                val args = element.argumentList?.children?.filterIsInstance<ScadArgument>().orEmpty()
                args to parameterNames(callee.reference as? ScadReference, ScadBuiltins.function(callee.text))
            }
            else -> return emptyList()
        }
        if (arguments.size < 2 || names.isEmpty()) return emptyList()
        val hints = mutableListOf<Hint>()
        for ((index, argument) in arguments.withIndex()) {
            if (argument.argumentName != null) break // ab dem ersten benannten zählt die Position nicht mehr
            val name = names.getOrNull(index) ?: break
            val value = argument.value ?: continue
            if (!isLiteral(value)) continue
            hints += Hint(name, argument.textRange.startOffset)
        }
        return hints
    }

    private fun parameterNames(reference: ScadReference?, builtin: ScadBuiltins.Builtin?): List<String> {
        val declaration = reference?.multiResolve(false)?.firstNotNullOfOrNull { it.element as? ScadCallableDeclaration }
        if (declaration != null) return declaration.parameters.map { it.name ?: return emptyList() }
        // `r = 1 | d`: Position zählt nur für die erste Alternative
        return builtin?.params?.map { it.substringBefore('|').substringBefore('=').trim() }
            ?.takeWhile { it.isNotEmpty() && !it.contains('…') }.orEmpty()
    }

    private fun isLiteral(value: PsiElement): Boolean = when (value.node.elementType) {
        ScadTypes.LITERAL_EXPRESSION, ScadTypes.VECTOR_EXPRESSION -> true
        ScadTypes.UNARY_EXPRESSION -> value.lastChild?.node?.elementType == ScadTypes.LITERAL_EXPRESSION
        else -> false
    }
}
