package app.heckit.ide.scadsphere.lang

import com.intellij.codeInsight.template.TemplateActionContext
import com.intellij.codeInsight.template.TemplateContextType
import com.intellij.psi.PsiComment

/** Kontext „OpenSCAD“ für Live Templates (`mod`, `fun`, `diff`, …), nicht in Kommentaren. */
class ScadTemplateContextType : TemplateContextType("OpenSCAD") {
    override fun isInContext(context: TemplateActionContext): Boolean {
        val file = context.file
        if (file.language != ScadLanguage) return false
        return file.findElementAt(maxOf(0, context.startOffset - 1)) !is PsiComment
    }
}
