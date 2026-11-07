package app.heckit.ide.scadsphere.lang

import app.heckit.ide.scadsphere.ScadSphereBundle
import app.heckit.ide.scadsphere.psi.ScadCallExpression
import app.heckit.ide.scadsphere.psi.ScadCallableDeclaration
import app.heckit.ide.scadsphere.psi.ScadFile
import app.heckit.ide.scadsphere.psi.ScadModuleCall
import app.heckit.ide.scadsphere.render.RenderMessage
import app.heckit.ide.scadsphere.render.RenderMessages
import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.Annotator
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.editor.Document
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.xml.util.XmlStringUtil

/** Fehler und Warnungen von OpenSCAD aus dem letzten Lauf der Vorschau, an ihrer Zeile im Code. */
class ScadRenderAnnotator : Annotator {
    override fun annotate(element: PsiElement, holder: AnnotationHolder) {
        if (element !is ScadFile) return
        val path = element.originalFile.virtualFile?.path ?: return
        val length = element.textLength
        val document = element.viewProvider.document
        // Meldungen derselben Stelle zusammenfassen: Ein Modul, das zweimal aufgerufen wird, meldet dieselbe Zeile
        // zweimal (je Aufruf mit anderen Werten) - die IDE zeigte sonst die Liste der Aufrufe doppelt
        val byRange = RenderMessages.getInstance(element.project).forFile(path)
            .groupBy { TextRange(it.marker.startOffset, it.marker.endOffset) }
        for ((range, list) in byRange) {
            if (range.isEmpty || range.endOffset > length) continue
            val severity = if (list.any { it.message.level == RenderMessage.Level.ERROR }) HighlightSeverity.ERROR else HighlightSeverity.WARNING
            val messages = list.map { ScadSphereBundle.message("annotation.openscad", it.message.text) }.distinct()
            val calls = document?.let { callsWithoutArgument(element, range, it) }.orEmpty()
            var builder = holder.newAnnotation(severity, messages.joinToString(" ") + summary(calls)).range(range)
            if (messages.size > 1 || calls.isNotEmpty()) builder = builder.tooltip(tooltip(messages, calls))
            builder.create()
        }
    }

    /** Aufruf ohne Argument für [missing]: Zeile (ab 1) und Code des Aufrufs. */
    private class Call(val name: String, val missing: List<String>, val undefined: Boolean, val line: Int, val code: String)

    private fun quoted(names: List<String>) = names.joinToString(", ") { "'$it'" }

    /** Kurzform für Problems-Fenster und Tests: ` (wand() ohne 'h': Zeile 29, 32)` je Kombination. */
    private fun summary(calls: List<Call>): String = calls.groupBy { Triple(it.name, it.missing, it.undefined) }.entries.joinToString("") { (key, list) ->
        " " + ScadSphereBundle.message(if (key.third) "annotation.undefinedArgument" else "annotation.missingArgument", key.first, quoted(key.second), list.joinToString(", ") { it.line.toString() })
    }

    /** Meldungen untereinander, darunter die Aufrufe je mit Zeile und Code. */
    private fun tooltip(messages: List<String>, calls: List<Call>): String = buildString {
        append("<html>").append(messages.joinToString("<br>") { XmlStringUtil.escapeString(it) })
        for ((key, list) in calls.groupBy { Triple(it.name, it.missing, it.undefined) }) {
            val heading = if (key.third) "annotation.undefinedArgument.heading" else "annotation.missingArgument.heading"
            append("<br><br>").append(XmlStringUtil.escapeString(ScadSphereBundle.message(heading, key.first, quoted(key.second))))
            for (call in list) {
                append("<br>&nbsp;&nbsp;").append(XmlStringUtil.escapeString(ScadSphereBundle.message("annotation.missingArgument.line", call.line)))
                append(" <code>").append(XmlStringUtil.escapeString(call.code)).append("</code>")
            }
        }
        append("</html>")
    }

    /**
     * OpenSCAD meldet z. B. `undef` nur an der Zeile im Modul, nicht am Aufruf. Liegt die Meldung in einem Modul oder
     * einer Funktion und verwendet die Zeile einen Parameter, für den ein Aufruf in dieser Datei kein Argument
     * mitgibt, werden diese Aufrufe genannt (höchstens fünf).
     */
    private fun callsWithoutArgument(file: ScadFile, range: TextRange, document: Document): List<Call> {
        val declaration = PsiTreeUtil.getParentOfType(file.findElementAt(range.startOffset), ScadCallableDeclaration::class.java) ?: return emptyList()
        val lineText = range.substring(file.text)
        val used = declaration.parameters.mapNotNull { it.name }
            .filter { Regex("\\b${Regex.escape(it)}\\b").containsMatchIn(lineText) }.toSet()
        if (used.isEmpty()) return emptyList()
        return PsiTreeUtil.findChildrenOfAnyType(file, ScadModuleCall::class.java, ScadCallExpression::class.java).flatMap { call ->
            val line = document.getLineNumber(call.textOffset) + 1
            // Code nur bis zum Zeilenende (bei Modulen mit Kindern nicht den ganzen Block)
            val code = call.text.lineSequence().first().trim().let { if (it.length > 60) it.take(59) + "…" else it }
            listOfNotNull(missingArguments(call) to false, undefinedArguments(call) to true).mapNotNull { (found, undefined) ->
                val (callee, names) = found ?: return@mapNotNull null
                val relevant = names.filter { it in used }
                if (callee != declaration || relevant.isEmpty()) null else Call(declaration.name ?: "", relevant, undefined, line, code)
            }
        }.take(5)
    }
}
