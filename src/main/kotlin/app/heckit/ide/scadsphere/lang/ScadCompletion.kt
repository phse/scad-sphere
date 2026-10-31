package app.heckit.ide.scadsphere.lang

import app.heckit.ide.scadsphere.ScadSphereBundle
import app.heckit.ide.scadsphere.psi.ScadArgument
import app.heckit.ide.scadsphere.psi.ScadCallExpression
import app.heckit.ide.scadsphere.psi.ScadCallableDeclaration
import app.heckit.ide.scadsphere.psi.ScadKind
import app.heckit.ide.scadsphere.psi.ScadModuleCall
import app.heckit.ide.scadsphere.psi.ScadNamedElement
import app.heckit.ide.scadsphere.psi.ScadReferenceExpression
import app.heckit.ide.scadsphere.psi.ScadResolver
import com.intellij.codeInsight.completion.CompletionContributor
import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionProvider
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.codeInsight.completion.CompletionType
import com.intellij.codeInsight.completion.InsertHandler
import com.intellij.codeInsight.completion.util.ParenthesesInsertHandler
import com.intellij.codeInsight.completion.PrioritizedLookupElement
import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.icons.AllIcons
import com.intellij.patterns.PlatformPatterns
import com.intellij.psi.PsiElement
import com.intellij.util.ProcessingContext

class ScadCompletionContributor : CompletionContributor() {
    init {
        extend(CompletionType.BASIC, PlatformPatterns.psiElement(ScadTypes.IDENTIFIER).withLanguage(ScadLanguage), ScadCompletionProvider())
    }
}

private class ScadCompletionProvider : CompletionProvider<CompletionParameters>() {
    override fun addCompletions(parameters: CompletionParameters, context: ProcessingContext, result: CompletionResultSet) {
        val position = parameters.position
        when (val parent = position.parent) {
            is ScadModuleCall -> if (parent.nameElement == position) moduleContext(position, result)
            is ScadReferenceExpression -> expressionContext(parent, result)
            else -> {}
        }
    }

    /** Anweisung: Module (eigene und eingebaute) und Schlüsselwörter. */
    private fun moduleContext(position: PsiElement, result: CompletionResultSet) {
        val seen = mutableSetOf<String>()
        for (declaration in ScadResolver.visible(position)) {
            if (declaration.kind != ScadKind.MODULE) continue
            val name = declaration.name ?: continue
            if (seen.add(name)) result.addElement(declaration(declaration, 100.0))
        }
        for (builtin in ScadBuiltins.modules.values) {
            if (seen.add(builtin.name)) result.addElement(builtin(builtin, 50.0))
        }
        for (keyword in listOf("module", "function", "if", "include", "use")) {
            result.addElement(PrioritizedLookupElement.withPriority(LookupElementBuilder.create(keyword).bold(), 10.0))
        }
    }

    /** Ausdruck: Variablen, Parameter, Funktionen; in Argumentlisten zusätzlich die Parameternamen des Aufgerufenen. */
    private fun expressionContext(reference: ScadReferenceExpression, result: CompletionResultSet) {
        val argument = reference.parent as? ScadArgument
        if (argument != null && argument.argumentName == null) addParameterNames(argument, result)

        val seen = mutableSetOf<String>()
        var priority = 100.0
        for (declaration in ScadResolver.visible(reference)) {
            val name = declaration.name ?: continue
            val key = "${declaration.kind == ScadKind.FUNCTION}:$name"
            if (declaration.kind == ScadKind.MODULE || !seen.add(key)) continue
            result.addElement(declaration(declaration, priority))
            // Innere Bereiche zuerst, danach leicht absteigend
            priority = maxOf(60.0, priority - 0.01)
        }
        for (builtin in ScadBuiltins.functions.values) {
            if (seen.add("true:${builtin.name}")) result.addElement(builtin(builtin, 40.0))
        }
        for (builtin in ScadBuiltins.variables.values) {
            if (seen.add("false:${builtin.name}")) result.addElement(builtin(builtin, 30.0))
        }
        for (keyword in listOf("true", "false", "undef", "let", "function")) {
            result.addElement(PrioritizedLookupElement.withPriority(LookupElementBuilder.create(keyword).bold(), 10.0))
        }
    }

    private fun addParameterNames(argument: ScadArgument, result: CompletionResultSet) {
        val owner = argument.parent?.parent
        val names = ScadResolver.calleeParameters(argument).mapNotNull { it.name }.ifEmpty {
            when (owner) {
                is ScadModuleCall -> owner.moduleName?.let { ScadBuiltins.module(it) }?.paramNames
                is ScadCallExpression -> owner.callee?.text?.let { ScadBuiltins.function(it) }?.paramNames
                else -> null
            }.orEmpty()
        }
        val used = owner?.let { (it as? ScadModuleCall)?.arguments }?.mapNotNull { it.argumentName?.text }.orEmpty().toSet()
        for (name in names.distinct()) {
            if (name in used) continue
            val element = LookupElementBuilder.create("$name = ")
                .withPresentableText(name)
                .withTailText(" = ", true)
                .withIcon(AllIcons.Nodes.Parameter)
                .withTypeText(ScadSphereBundle.message("completion.parameter"))
            result.addElement(PrioritizedLookupElement.withPriority(element, 200.0))
        }
    }

    private fun declaration(element: ScadNamedElement, priority: Double): LookupElement {
        val callable = element as? ScadCallableDeclaration
        var builder = LookupElementBuilder.create(element, element.name!!)
            .withIcon(element.icon())
            .withTypeText(element.containingFile?.name)
        if (callable != null) {
            builder = builder.withTailText("(${callable.parameters.joinToString(", ") { it.signature() }})", true)
                .withInsertHandler(parentheses(callable.parameters.isNotEmpty()))
        }
        return PrioritizedLookupElement.withPriority(builder, priority)
    }

    private fun builtin(builtin: ScadBuiltins.Builtin, priority: Double): LookupElement {
        val icon = when (builtin.kind) {
            ScadBuiltins.Kind.MODULE -> AllIcons.Nodes.Method
            ScadBuiltins.Kind.FUNCTION -> AllIcons.Nodes.Function
            ScadBuiltins.Kind.VARIABLE -> AllIcons.Nodes.Constant
        }
        var builder = LookupElementBuilder.create(builtin.name)
            .withIcon(icon)
            .withTypeText(ScadSphereBundle.message(if (builtin.experimental) "completion.experimental" else "completion.builtin"), true)
        if (builtin.kind != ScadBuiltins.Kind.VARIABLE) {
            builder = builder.withTailText("(${builtin.params.joinToString(", ")})", true)
                .withInsertHandler(parentheses(builtin.params.isNotEmpty()))
        }
        return PrioritizedLookupElement.withPriority(builder, priority)
    }

    private fun parentheses(withParameters: Boolean): InsertHandler<LookupElement> =
        if (withParameters) ParenthesesInsertHandler.WITH_PARAMETERS else ParenthesesInsertHandler.NO_PARAMETERS
}
