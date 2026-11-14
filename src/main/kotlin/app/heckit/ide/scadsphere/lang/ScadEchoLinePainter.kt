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
 * Schleife gibt mehrere Werte: die ersten stehen da, der Rest als Anzahl.
 */
class ScadEchoLinePainter : EditorLinePainter() {
    override fun getLineExtensions(project: Project, file: VirtualFile, lineNumber: Int): Collection<LineExtensionInfo>? {
        if (!ScadSphereSettings.echoInline || !file.name.endsWith(".scad", ignoreCase = true)) return null
        val echoes = RenderMessages.getInstance(project).echoes(file.path)
        if (echoes.isEmpty()) return null
        val document = FileDocumentManager.getInstance().getCachedDocument(file) ?: return null
        val echo = echoes.firstOrNull { it.marker.isValid && it.marker.startOffset <= document.textLength && document.getLineNumber(it.marker.startOffset) == lineNumber }
            ?: return null
        val attributes = EditorColorsManager.getInstance().globalScheme.getAttributes(DefaultLanguageHighlighterColors.INLAY_TEXT_WITHOUT_BACKGROUND)
        return listOf(LineExtensionInfo("  " + text(echo.values), attributes))
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
