package app.heckit.ide.scadsphere.lang

import app.heckit.ide.scadsphere.ScadSphereBundle
import app.heckit.ide.scadsphere.psi.ScadCallExpression
import app.heckit.ide.scadsphere.psi.ScadCallableDeclaration
import app.heckit.ide.scadsphere.psi.ScadModuleCall
import app.heckit.ide.scadsphere.psi.ScadReference
import app.heckit.ide.scadsphere.psi.ScadReferenceExpression
import com.intellij.lang.parameterInfo.CreateParameterInfoContext
import com.intellij.lang.parameterInfo.ParameterInfoHandler
import com.intellij.lang.parameterInfo.ParameterInfoUIContext
import com.intellij.lang.parameterInfo.ParameterInfoUtils
import com.intellij.lang.parameterInfo.UpdateParameterInfoContext
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile

/** Parameter-Info (Strg+P / ⌘P) in den Klammern eines Modul- oder Funktionsaufrufs. */
class ScadParameterInfoHandler : ParameterInfoHandler<PsiElement, ScadParameterInfoHandler.Signature> {
    class Signature(val params: List<String>)

    override fun findElementForParameterInfo(context: CreateParameterInfoContext): PsiElement? {
        val list = argumentList(context.file, context.offset) ?: return null
        val signatures = signatures(list.parent)
        if (signatures.isEmpty()) return null
        context.itemsToShow = signatures.toTypedArray()
        return list
    }

    override fun showParameterInfo(element: PsiElement, context: CreateParameterInfoContext) {
        context.showHint(element, element.textRange.startOffset, this)
    }

    override fun findElementForUpdatingParameterInfo(context: UpdateParameterInfoContext): PsiElement? =
        argumentList(context.file, context.offset)

    override fun updateParameterInfo(parameterOwner: PsiElement, context: UpdateParameterInfoContext) {
        if (context.parameterOwner != null && context.parameterOwner != parameterOwner) {
            context.removeHint()
            return
        }
        context.setCurrentParameter(ParameterInfoUtils.getCurrentParameterIndex(parameterOwner.node, context.offset, ScadTypes.COMMA))
    }

    override fun updateUI(p: Signature, context: ParameterInfoUIContext) {
        if (p.params.isEmpty()) {
            context.setupUIComponentPresentation(ScadSphereBundle.message("parameterInfo.none"), -1, -1, false, false, false, context.defaultParameterColor)
            return
        }
        val text = StringBuilder()
        var start = -1
        var end = -1
        p.params.forEachIndexed { index, param ->
            if (index > 0) text.append(", ")
            if (index == context.currentParameterIndex) start = text.length
            text.append(param)
            if (index == context.currentParameterIndex) end = text.length
        }
        context.setupUIComponentPresentation(text.toString(), start, end, false, false, false, context.defaultParameterColor)
    }

    /** Argumentliste eines Aufrufs an [offset] (auch direkt hinter der öffnenden Klammer). */
    private fun argumentList(file: PsiFile, offset: Int): PsiElement? {
        for (at in listOf(offset, offset - 1)) {
            var element = file.findElementAt(at)
            while (element != null && element !is PsiFile) {
                if (element.node.elementType == ScadTypes.ARGUMENT_LIST && (element.parent is ScadModuleCall || element.parent is ScadCallExpression) &&
                    offset > element.textRange.startOffset && (offset < element.textRange.endOffset || !element.text.endsWith(")"))
                ) {
                    return element
                }
                element = element.parent
            }
        }
        return null
    }

    private fun signatures(call: PsiElement): List<Signature> {
        val (reference, builtin) = when (call) {
            is ScadModuleCall -> (call.reference as? ScadReference) to call.moduleName?.let { ScadBuiltins.module(it) }
            is ScadCallExpression -> {
                val callee = call.callee as? ScadReferenceExpression
                (callee?.reference as? ScadReference) to callee?.text?.let { ScadBuiltins.function(it) }
            }
            else -> return emptyList()
        }
        val declarations = reference?.multiResolve(false)?.mapNotNull { it.element as? ScadCallableDeclaration }.orEmpty()
        if (declarations.isNotEmpty()) return declarations.map { declaration -> Signature(declaration.parameters.map { it.signature() }) }
        // Alternativen wie `r = 1 | d` als eigene Zeilen
        return builtin?.let { listOf(Signature(it.params)) }.orEmpty()
    }
}
