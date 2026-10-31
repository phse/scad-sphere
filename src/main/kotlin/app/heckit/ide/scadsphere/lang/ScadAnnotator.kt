package app.heckit.ide.scadsphere.lang

import app.heckit.ide.scadsphere.psi.ScadArgument
import app.heckit.ide.scadsphere.psi.ScadCallableDeclaration
import app.heckit.ide.scadsphere.psi.ScadFunctionDeclaration
import app.heckit.ide.scadsphere.psi.ScadModuleCall
import app.heckit.ide.scadsphere.psi.ScadModuleDeclaration
import app.heckit.ide.scadsphere.psi.ScadParameter
import app.heckit.ide.scadsphere.psi.ScadReference
import app.heckit.ide.scadsphere.psi.ScadReferenceExpression
import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.Annotator
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.psi.PsiElement

/** Farben aus der Bedeutung: Deklarationen, Aufrufe eigener und eingebauter Module/Funktionen, Parameter. */
class ScadAnnotator : Annotator {
    override fun annotate(element: PsiElement, holder: AnnotationHolder) {
        when (element) {
            is ScadModuleDeclaration -> element.nameIdentifier?.let { holder.color(it, ScadColors.MODULE_DECLARATION) }
            is ScadFunctionDeclaration -> element.nameIdentifier?.let { holder.color(it, ScadColors.FUNCTION_DECLARATION) }
            is ScadParameter -> element.nameIdentifier?.let { holder.color(it, special(it.text) ?: ScadColors.PARAMETER) }
            is ScadArgument -> element.argumentName?.let { name ->
                val key = special(name.text) ?: if (element.isDeclaration) null else ScadColors.NAMED_ARGUMENT
                key?.let { holder.color(name, it) }
            }
            is ScadModuleCall -> annotateModuleCall(element, holder)
            is ScadReferenceExpression -> annotateReference(element, holder)
        }
    }

    private fun annotateModuleCall(call: ScadModuleCall, holder: AnnotationHolder) {
        var child = call.firstChild
        while (child != null && child.node.elementType in ScadTypes.MODIFIERS) {
            holder.color(child, ScadColors.MODIFIER)
            child = child.nextSibling
        }
        val name = call.nameElement ?: return
        if (name.node.elementType != ScadTypes.IDENTIFIER) return
        val resolved = (call.reference as? ScadReference)?.multiResolve(false).orEmpty().isNotEmpty()
        val key = when {
            resolved -> ScadColors.MODULE_CALL
            ScadBuiltins.module(name.text) != null -> ScadColors.BUILTIN_MODULE
            else -> ScadColors.MODULE_CALL
        }
        holder.color(name, key)
    }

    private fun annotateReference(reference: ScadReferenceExpression, holder: AnnotationHolder) {
        val name = reference.text
        special(name)?.let { return holder.color(reference, it) }
        val targets = (reference.reference as ScadReference).multiResolve(false).mapNotNull { it.element }
        val key = when {
            targets.any { it is ScadParameter } -> ScadColors.PARAMETER
            reference.isCallee && targets.any { it is ScadCallableDeclaration } -> ScadColors.FUNCTION_CALL
            reference.isCallee && targets.isEmpty() && ScadBuiltins.function(name) != null -> ScadColors.BUILTIN_FUNCTION
            reference.isCallee -> ScadColors.FUNCTION_CALL
            targets.isEmpty() && name == "PI" -> ScadColors.SPECIAL_VARIABLE
            else -> null
        }
        key?.let { holder.color(reference, it) }
    }

    /** `$fn` & Co. */
    private fun special(name: String): TextAttributesKey? = if (name.startsWith("$")) ScadColors.SPECIAL_VARIABLE else null

    private fun AnnotationHolder.color(element: PsiElement, key: TextAttributesKey) {
        newSilentAnnotation(HighlightSeverity.INFORMATION).range(element).textAttributes(key).create()
    }
}
