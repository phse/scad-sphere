package app.heckit.ide.scadsphere.lang

import app.heckit.ide.scadsphere.ScadSphereSettings
import app.heckit.ide.scadsphere.render.RenderMessages
import com.intellij.openapi.editor.DefaultLanguageHighlighterColors
import com.intellij.openapi.editor.EditorLinePainter
import com.intellij.openapi.editor.LineExtensionInfo
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile

/**
 * Ausgaben von `echo` aus dem letzten Lauf der Vorschau am Ende ihrer Zeile, wie die Werte im Debugger. Eine
 * Schleife gibt mehrere Werte: die ersten stehen da, der Rest als Anzahl. Davor der Wert einer gerechneten Zuweisung
 * (`spacing = (length - 70) / (hooks - 1)` → `= 34`, [app.heckit.ide.scadsphere.render.EchoLines]).
 */
class ScadEchoLinePainter : EditorLinePainter() {
    override fun getLineExtensions(project: Project, file: VirtualFile, lineNumber: Int): Collection<LineExtensionInfo>? {
        if (!file.name.endsWith(".scad", ignoreCase = true)) return null
        val messages = RenderMessages.getInstance(project)
        val echoes = if (ScadSphereSettings.echoInline) messages.echoes(file.path) else emptyList()
        val values = if (ScadSphereSettings.valuesInline) messages.values(file.path) else emptyList()
        if (echoes.isEmpty() && values.isEmpty()) return null
        val document = FileDocumentManager.getInstance().getCachedDocument(file) ?: return null
        fun onLine(list: List<RenderMessages.Echo>) =
            list.firstOrNull { it.marker.isValid && it.marker.startOffset <= document.textLength && document.getLineNumber(it.marker.startOffset) == lineNumber }
        val value = onLine(values)
        val echo = onLine(echoes)
        if (value == null && echo == null) return null
        val attributes = EditorColorsManager.getInstance().globalScheme.getAttributes(DefaultLanguageHighlighterColors.INLAY_TEXT_WITHOUT_BACKGROUND)
        // Wert der Zuweisung als „= 34“, danach die echo-Ausgaben derselben Zeile
        val text = listOfNotNull(value?.let { "= " + text(it.values) }, echo?.let { text(it.values) }).joinToString("   ")
        return listOf(LineExtensionInfo("  $text", attributes))
    }

    companion object {
        private const val SHOWN = 3
        private const val MAX_LENGTH = 60

        /** `w = 5  ·  10  ·  15  … (+9)`: die ersten Ausgaben, lange gekürzt, der Rest als Anzahl. */
        fun text(values: List<String>): String {
            val shown = values.take(SHOWN).joinToString("  ·  ") { if (it.length > MAX_LENGTH) it.take(MAX_LENGTH - 1) + "…" else it }
            return if (values.size > SHOWN) "$shown  … (+${values.size - SHOWN})" else shown
        }
    }
}
