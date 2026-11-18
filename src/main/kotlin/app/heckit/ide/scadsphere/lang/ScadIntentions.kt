package app.heckit.ide.scadsphere.lang

import app.heckit.ide.scadsphere.ScadSphereBundle
import app.heckit.ide.scadsphere.customizer.ScadCustomizer
import app.heckit.ide.scadsphere.customizer.formatNumber
import app.heckit.ide.scadsphere.psi.ScadArgument
import app.heckit.ide.scadsphere.psi.ScadAssignment
import app.heckit.ide.scadsphere.psi.ScadCallableDeclaration
import app.heckit.ide.scadsphere.psi.ScadFile
import app.heckit.ide.scadsphere.psi.ScadModuleCall
import app.heckit.ide.scadsphere.psi.ScadNamedElement
import app.heckit.ide.scadsphere.psi.Namespace
import app.heckit.ide.scadsphere.psi.ScadReference
import app.heckit.ide.scadsphere.psi.ScadReferenceExpression
import app.heckit.ide.scadsphere.psi.ScadResolver
import com.intellij.codeInsight.intention.IntentionAction
import com.intellij.codeInsight.intention.PsiElementBaseIntentionAction
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.InputValidator
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiComment
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiWhiteSpace
import com.intellij.psi.codeStyle.CodeStyleManager
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.psi.util.PsiTreeUtil

private val IDENTIFIER = Regex("[A-Za-z_][A-Za-z0-9_]*")

/** Anlegen eines unbekannten Moduls bzw. einer unbekannten Funktion am Ende der Datei (Quick-Fix am Hinweis). */
class CreateDeclarationFix(private val name: String, private val module: Boolean, private val argumentNames: List<String>) : IntentionAction {
    override fun getText(): String = ScadSphereBundle.message(if (module) "fix.createModule" else "fix.createFunction", name)
    override fun getFamilyName(): String = ScadSphereBundle.message("fix.createDeclaration")
    override fun isAvailable(project: Project, editor: Editor?, file: PsiFile?): Boolean = file is ScadFile
    override fun startInWriteAction(): Boolean = true

    override fun invoke(project: Project, editor: Editor?, file: PsiFile?) {
        val document = file?.let { PsiDocumentManager.getInstance(project).getDocument(it) } ?: return
        val params = argumentNames.joinToString(", ")
        val text = document.text
        val separator = if (text.isEmpty()) "" else if (text.endsWith("\n\n")) "" else if (text.endsWith("\n")) "\n" else "\n\n"
        val start = document.textLength + separator.length
        val (declaration, caret) = if (module) {
            val head = "module $name($params) {\n    "
            "$head\n}\n" to start + head.length
        } else {
            val head = "function $name($params) = "
            "${head}undef;\n" to start + head.length
        }
        document.insertString(document.textLength, separator + declaration)
        PsiDocumentManager.getInstance(project).commitDocument(document)
        editor?.caretModel?.moveToOffset(caret)
        if (!module) editor?.selectionModel?.setSelection(caret, caret + "undef".length)
        editor?.scrollingModel?.scrollToCaret(com.intellij.openapi.editor.ScrollType.MAKE_VISIBLE)
    }

    companion object {
        /** Parameternamen aus dem Aufruf: benannte Argumente behalten ihren Namen, sonst `a`, `b`, … */
        fun argumentNames(arguments: List<ScadArgument>): List<String> {
            val used = arguments.mapNotNull { it.argumentName?.text }.toMutableSet()
            var next = 'a'
            return arguments.map { argument ->
                argument.argumentName?.text ?: run {
                    while (next.toString() in used) next++
                    next.toString().also { used += it; next++ }
                }
            }
        }
    }
}

/** `#` vor einem Modulaufruf setzen oder entfernen (hervorheben zum Prüfen von difference()). */
class ToggleHighlightIntention : PsiElementBaseIntentionAction() {
    override fun getFamilyName(): String = ScadSphereBundle.message("intention.toggleHighlight")

    override fun isAvailable(project: Project, editor: Editor?, element: PsiElement): Boolean {
        val call = call(element) ?: return false
        text = ScadSphereBundle.message(if (hasHash(call)) "intention.toggleHighlight.remove" else "intention.toggleHighlight.add")
        return true
    }

    override fun invoke(project: Project, editor: Editor?, element: PsiElement) {
        val call = call(element) ?: return
        val document = PsiDocumentManager.getInstance(project).getDocument(call.containingFile) ?: return
        PsiDocumentManager.getInstance(project).doPostponedOperationsAndUnblockDocument(document)
        val hash = call.node.findChildByType(ScadTypes.HASH)
        if (hash != null) {
            val end = hash.textRange.endOffset
            val after = if (end < document.textLength && document.charsSequence[end] == ' ') 1 else 0
            document.deleteString(hash.startOffset, end + after)
        } else {
            document.insertString(call.textRange.startOffset, "#")
        }
        PsiDocumentManager.getInstance(project).commitDocument(document)
    }

    private fun hasHash(call: ScadModuleCall) = call.node.findChildByType(ScadTypes.HASH) != null

    /** Aufruf, auf dessen Namen oder Modifikator der Cursor steht (keine for/let/echo/assert). */
    private fun call(element: PsiElement): ScadModuleCall? {
        val call = element.parent as? ScadModuleCall ?: return null
        if (element != call.nameElement && element.node.elementType !in ScadTypes.MODIFIERS) return null
        if (call.moduleName in setOf("for", "let", "echo", "assert", "each", "intersection_for")) return null
        return call
    }
}

/** Variable durch ihren Wert ersetzen und die Zuweisung entfernen. */
class InlineVariableIntention : PsiElementBaseIntentionAction() {
    override fun getFamilyName(): String = ScadSphereBundle.message("intention.inline")

    override fun isAvailable(project: Project, editor: Editor?, element: PsiElement): Boolean {
        val assignment = assignment(element) ?: return false
        if (assignment.value == null) return false
        // Nur eine Zuweisung dieses Namens im Bereich, alle Verwendungen in dieser Datei
        if (assignment.parent.children.count { it is ScadAssignment && it.name == assignment.name } != 1) return false
        val references = ReferencesSearch.search(assignment).findAll()
        if (references.isEmpty() || references.any { it.element.containingFile != assignment.containingFile }) return false
        text = ScadSphereBundle.message("intention.inline.text", assignment.name!!)
        return true
    }

    override fun invoke(project: Project, editor: Editor?, element: PsiElement) {
        val assignment = assignment(element) ?: return
        val value = assignment.value ?: return
        val document = PsiDocumentManager.getInstance(project).getDocument(assignment.containingFile) ?: return
        val references = ReferencesSearch.search(assignment).findAll().map { it.element }.sortedByDescending { it.textRange.startOffset }
        val simple = value.node.elementType in SIMPLE
        val replacements = references.map { reference ->
            val parentType = reference.parent?.node?.elementType
            val bare = simple || parentType == ScadTypes.ARGUMENT || parentType == ScadTypes.ASSIGNMENT || parentType == ScadTypes.PAREN_EXPRESSION ||
                parentType == ScadTypes.VECTOR_EXPRESSION || reference.parent is ScadArgument
            reference.textRange to if (bare) value.text else "(${value.text})"
        }
        PsiDocumentManager.getInstance(project).doPostponedOperationsAndUnblockDocument(document)
        for ((range, text) in replacements) document.replaceString(range.startOffset, range.endOffset, text)
        PsiDocumentManager.getInstance(project).commitDocument(document)
        // Zuweisung erst danach entfernen (liegt im Bereich, Offsets davor bleiben gültig)
        val fresh = PsiTreeUtil.findElementOfClassAtOffset(assignment.containingFile, assignment.textRange.startOffset, ScadAssignment::class.java, true)
            ?: PsiDocumentManager.getInstance(project).getPsiFile(document)?.let { file ->
                PsiTreeUtil.findChildrenOfType(file, ScadAssignment::class.java).firstOrNull { it.name == assignment.name }
            } ?: return
        removeStatement(fresh)
    }

    private fun assignment(element: PsiElement): ScadAssignment? {
        (element.parent as? ScadAssignment)?.let { if (it.nameIdentifier == element) return it }
        val reference = element.parent as? ScadReferenceExpression ?: return null
        if (reference.isCallee) return null
        return (reference.reference as ScadReference).multiResolve(false).mapNotNull { it.element as? ScadAssignment }.singleOrNull()
    }

    private companion object {
        val SIMPLE = setOf(
            ScadTypes.LITERAL_EXPRESSION, ScadTypes.REFERENCE_EXPRESSION, ScadTypes.VECTOR_EXPRESSION, ScadTypes.CALL_EXPRESSION,
            ScadTypes.PAREN_EXPRESSION, ScadTypes.INDEX_EXPRESSION, ScadTypes.MEMBER_EXPRESSION,
        )
    }
}

/**
 * Markierte Anweisungen als neues Modul am Ende der Datei. Lokale Namen von außerhalb (Parameter, Schleifen- und
 * Blockvariablen) werden zu Parametern, Namen der obersten Ebene sieht das Modul ohnehin.
 */
class ExtractModuleIntention : IntentionAction {
    override fun getText(): String = ScadSphereBundle.message("intention.extractModule")
    override fun getFamilyName(): String = text
    override fun startInWriteAction(): Boolean = false

    override fun isAvailable(project: Project, editor: Editor?, file: PsiFile?): Boolean =
        editor != null && file is ScadFile && statements(editor, file) != null

    override fun invoke(project: Project, editor: Editor?, file: PsiFile?) {
        if (editor == null || file !is ScadFile) return
        val statements = statements(editor, file) ?: return
        val inside = TextRange(statements.first().textRange.startOffset, statements.last().textRange.endOffset)
        val parameters = statements.flatMap { PsiTreeUtil.findChildrenOfType(it, ScadReferenceExpression::class.java) }
            .filter { !it.isCallee }
            .mapNotNull { reference ->
                val target = (reference.reference as ScadReference).multiResolve(false).firstOrNull()?.element as? ScadNamedElement ?: return@mapNotNull null
                target.takeIf { ScadResolver.isLocalDeclaration(it) && !inside.contains(it.textRange) }?.name
            }
            .distinct()
        val taken = file.children.filterIsInstance<ScadCallableDeclaration>().mapNotNull { it.name }.toSet()
        val suggestion = generateSequence(1) { it + 1 }.map { if (it == 1) "part" else "part$it" }.first { it !in taken }
        val name = if (ApplicationManager.getApplication().isUnitTestMode) suggestion else Messages.showInputDialog(
            project, ScadSphereBundle.message("intention.extractModule.name"), text, null, suggestion,
            object : InputValidator {
                override fun checkInput(input: String?) = input != null && IDENTIFIER.matches(input) && input !in taken
                override fun canClose(input: String?) = checkInput(input)
            },
        ) ?: return
        val document = editor.document
        val body = document.getText(TextRange(document.getLineStartOffset(document.getLineNumber(inside.startOffset)), inside.endOffset))
        val lines = body.lines()
        val indent = lines.filter { it.isNotBlank() }.minOfOrNull { line -> line.takeWhile { it == ' ' || it == '\t' }.length } ?: 0
        val moved = lines.joinToString("\n") { if (it.isBlank()) "" else "    " + it.drop(indent) }
        val args = parameters.joinToString(", ")
        WriteCommandAction.runWriteCommandAction(project, text, null, {
            val text = document.text
            val separator = if (text.endsWith("\n\n")) "" else if (text.endsWith("\n")) "\n" else "\n\n"
            val start = document.textLength + separator.length
            document.insertString(document.textLength, "${separator}module $name($args) {\n$moved\n}\n")
            document.replaceString(inside.startOffset, inside.endOffset, "$name($args);")
            PsiDocumentManager.getInstance(project).commitDocument(document)
            val shift = "$name($args);".length - inside.length
            CodeStyleManager.getInstance(project).reformatText(file, start + shift, document.textLength)
            editor.selectionModel.removeSelection()
            editor.caretModel.moveToOffset(inside.startOffset)
        }, file)
    }

    /** Ganze Anweisungen (Modulaufrufe, if) eines Bereichs, die die Auswahl genau abdeckt - sonst null. */
    private fun statements(editor: Editor, file: PsiFile): List<PsiElement>? {
        val selection = editor.selectionModel
        if (!selection.hasSelection()) return null
        val start = skipBlank(file, selection.selectionStart, true) ?: return null
        val end = skipBlank(file, selection.selectionEnd - 1, false) ?: return null
        val first = topStatement(start) ?: return null
        val last = topStatement(end) ?: return null
        if (first.parent != last.parent) return null
        val list = generateSequence(first) { it.nextSibling }.takeWhile { it != last.nextSibling }
            .filter { it !is PsiWhiteSpace && it !is PsiComment }.toList()
        if (list.isEmpty() || list.any { it !is ScadModuleCall && it.node.elementType != ScadTypes.IF_STATEMENT }) return null
        // children() gehört zum umgebenden Modul
        if (list.any { s -> PsiTreeUtil.findChildrenOfType(s, ScadModuleCall::class.java).plus(listOfNotNull(s as? ScadModuleCall)).any { it.moduleName == "children" } }) return null
        if (start.textRange.startOffset < selection.selectionStart || first.textRange.startOffset != start.textRange.startOffset ||
            last.textRange.endOffset > selection.selectionEnd
        ) return null
        return list
    }

    private fun skipBlank(file: PsiFile, offset: Int, forward: Boolean): PsiElement? {
        var at = offset
        while (at in 0 until file.textLength) {
            val element = file.findElementAt(at) ?: return null
            if (element !is PsiWhiteSpace && element !is PsiComment) return element
            at = if (forward) element.textRange.endOffset else element.textRange.startOffset - 1
        }
        return null
    }

    /** Äußerste Anweisung unter einer Datei bzw. einem Block, die [leaf] enthält. */
    private fun topStatement(leaf: PsiElement): PsiElement? {
        var element: PsiElement = leaf
        while (element.parent != null && element.parent !is ScadFile && element.parent.node.elementType != ScadTypes.BLOCK) element = element.parent
        return element.takeIf { it.parent != null }
    }
}

/**
 * Zahl im Code als Customizer-Parameter: neue Zuweisung mit Schieberegler-Kommentar hinter dem letzten Parameter der
 * Datei (bzw. oben, vor der ersten Anweisung), die Zahl wird durch den Namen ersetzt.
 */
class MakeCustomizerParameterIntention : PsiElementBaseIntentionAction() {
    override fun getFamilyName(): String = ScadSphereBundle.message("intention.makeParameter")
    override fun startInWriteAction(): Boolean = false

    override fun isAvailable(project: Project, editor: Editor?, element: PsiElement): Boolean {
        val number = number(element, editor) ?: return false
        val file = number.containingFile as? ScadFile ?: return false
        // Schon der Wert eines Parameters
        if (ScadCustomizer.parameters(file).any { it.valueRange == number.textRange }) return false
        text = ScadSphereBundle.message("intention.makeParameter")
        return true
    }

    override fun invoke(project: Project, editor: Editor?, element: PsiElement) {
        val number = number(element, editor) ?: return
        val file = number.containingFile as? ScadFile ?: return
        val document = PsiDocumentManager.getInstance(project).getDocument(file) ?: return
        val taken = { name: String ->
            ScadResolver.resolve(number, name, Namespace.VARIABLE).isNotEmpty() ||
                file.children.any { it is ScadAssignment && it.name == name }
        }
        val base = suggestName(number)
        val suggestion = generateSequence(1) { it + 1 }.map { if (it == 1) base else "$base$it" }.first { !taken(it) }
        val name = if (ApplicationManager.getApplication().isUnitTestMode) suggestion else Messages.showInputDialog(
            project, ScadSphereBundle.message("intention.makeParameter.name"), familyName, null, suggestion,
            object : InputValidator {
                override fun checkInput(input: String?) = input != null && IDENTIFIER.matches(input) && !taken(input)
                override fun canClose(input: String?) = checkInput(input)
            },
        ) ?: return
        val valueText = number.text
        val declaration = "$name = $valueText; // ${sliderRange(valueText)}"
        val (insertAt, insertText) = insertion(file, document, declaration)
        val replace = number.textRange
        WriteCommandAction.runWriteCommandAction(project, familyName, null, {
            if (insertAt >= replace.endOffset) {
                document.insertString(insertAt, insertText)
                document.replaceString(replace.startOffset, replace.endOffset, name)
            } else {
                document.replaceString(replace.startOffset, replace.endOffset, name)
                document.insertString(insertAt, insertText)
            }
            PsiDocumentManager.getInstance(project).commitDocument(document)
            val shift = if (insertAt < replace.startOffset) insertText.length else 0
            editor?.caretModel?.moveToOffset(replace.startOffset + shift)
        }, file)
    }

    /**
     * Zahl-Literal unter dem Cursor oder direkt davor (Cursor hinter `5,`), mit Vorzeichen (`-5`); nicht, wenn sie schon
     * allein einer Variablen zugewiesen ist.
     */
    private fun number(element: PsiElement, editor: Editor?): PsiElement? {
        val offset = editor?.caretModel?.offset
        val leaf = if (element.node.elementType != ScadTypes.NUMBER && offset != null && offset > 0) {
            element.containingFile.findElementAt(offset - 1)
        } else element
        if (leaf == null || leaf.node.elementType != ScadTypes.NUMBER) return null
        val literal = leaf.parent?.takeIf { it.node.elementType == ScadTypes.LITERAL_EXPRESSION } ?: return null
        val parent = literal.parent
        val number = if (parent != null && parent.node.elementType == ScadTypes.UNARY_EXPRESSION && parent.firstChild?.node?.elementType == ScadTypes.MINUS) parent else literal
        return number.takeIf { it.parent !is ScadAssignment }
    }

    /** Name aus dem Parameter, an den die Zahl geht: `cylinder(r = 2)` → `cylinder_r`, `cube([20, 10, 5])` → `size_x`, `translate([0, 0, 5])` → `translate_z`. */
    private fun suggestName(number: PsiElement): String {
        var value: PsiElement = number
        var axis: String? = null
        val parent = value.parent
        if (parent != null && parent.node.elementType == ScadTypes.VECTOR_EXPRESSION) {
            val items = parent.children.toList()
            if (items.size in 2..4) axis = listOf("x", "y", "z", "w")[items.indexOf(value)]
            value = parent
        }
        (value.parent as? ScadAssignment)?.let { assignment ->
            return listOfNotNull(assignment.name, axis).joinToString("_")
        }
        val argument = value.parent as? ScadArgument ?: return "value"
        val (callee, parameter) = ScadInlayParameterHintsProvider.calleeAndParameter(argument) ?: return "value"
        val head = when {
            parameter == null || (parameter.length <= 2 && axis != null) -> callee
            parameter.length <= 2 -> "${callee}_$parameter"
            else -> parameter
        }
        return listOfNotNull(head, axis).joinToString("_").replace(Regex("[^A-Za-z0-9_]"), "_").let {
            if (it.first().isDigit()) "_$it" else it
        }
    }

    /** Stelle und Text: Zeile hinter dem letzten Customizer-Parameter, sonst vor der ersten Anweisung der Datei. */
    private fun insertion(file: ScadFile, document: com.intellij.openapi.editor.Document, declaration: String): Pair<Int, String> {
        val last = ScadCustomizer.parameters(file).lastOrNull()
        if (last != null) {
            val line = document.getLineNumber(last.valueRange.endOffset)
            return document.getLineEndOffset(line) to "\n$declaration"
        }
        val first = file.children.firstOrNull {
            it !is PsiComment && it !is PsiWhiteSpace && it.node.elementType != ScadTypes.INCLUDE_STATEMENT && it.node.elementType != ScadTypes.USE_STATEMENT
        } ?: return document.textLength to (if (document.textLength == 0 || document.text.endsWith("\n")) "" else "\n") + "$declaration\n"
        val start = document.getLineStartOffset(document.getLineNumber(first.textRange.startOffset))
        return start to "$declaration\n\n"
    }

    companion object {
        /** Schieberegler um den Wert: `[0:20]` für 10, `[0:0.1:5]` für 1.5, `[-20:20]` für -10. */
        fun sliderRange(valueText: String): String {
            val value = valueText.toDoubleOrNull() ?: return "[0:100]"
            val magnitude = kotlin.math.abs(value)
            val max = niceCeil(magnitude * 2)
            val min = if (value < 0) -max else 0.0
            val decimals = valueText.substringAfter('.', "").takeIf { '.' in valueText && 'e' !in valueText.lowercase() }?.length ?: 0
            if (decimals == 0) return "[${formatNumber(min)}:${formatNumber(max)}]"
            val step = java.math.BigDecimal.ONE.movePointLeft(decimals.coerceAtMost(4)).toPlainString()
            return "[${formatNumber(min)}:$step:${formatNumber(max)}]"
        }

        private fun niceCeil(x: Double): Double {
            if (x <= 0.0) return 10.0
            val power = Math.pow(10.0, kotlin.math.floor(kotlin.math.log10(x)))
            return listOf(1.0, 2.0, 5.0, 10.0).map { it * power }.first { it >= x - 1e-9 }
        }
    }
}
