package app.heckit.ide.scadsphere.lang

import app.heckit.ide.scadsphere.ScadSphereBundle
import app.heckit.ide.scadsphere.psi.ScadFile
import app.heckit.ide.scadsphere.render.RenderMessage
import app.heckit.ide.scadsphere.render.RenderMessages
import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.Annotator
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement

/** Fehler und Warnungen von OpenSCAD aus dem letzten Lauf der Vorschau, an ihrer Zeile im Code. */
class ScadRenderAnnotator : Annotator {
    override fun annotate(element: PsiElement, holder: AnnotationHolder) {
        if (element !is ScadFile) return
        val path = element.originalFile.virtualFile?.path ?: return
        val length = element.textLength
        for (marked in RenderMessages.getInstance(element.project).forFile(path)) {
            val range = TextRange(marked.marker.startOffset, marked.marker.endOffset)
            if (range.isEmpty || range.endOffset > length) continue
            val severity = if (marked.message.level == RenderMessage.Level.ERROR) HighlightSeverity.ERROR else HighlightSeverity.WARNING
            holder.newAnnotation(severity, ScadSphereBundle.message("annotation.openscad", marked.message.text))
                .range(range)
                .create()
        }
    }
}
