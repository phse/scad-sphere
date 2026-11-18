package app.heckit.ide.scadsphere.lang

import app.heckit.ide.scadsphere.ScadSphereBundle
import app.heckit.ide.scadsphere.editor.ScadPreviewEditor
import app.heckit.ide.scadsphere.editor.ScadSplitEditor
import app.heckit.ide.scadsphere.psi.ScadFile
import app.heckit.ide.scadsphere.psi.ScadModuleDeclaration
import com.intellij.codeInsight.daemon.LineMarkerInfo
import com.intellij.codeInsight.daemon.LineMarkerProviderDescriptor
import com.intellij.icons.AllIcons
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.TextEditorWithPreview
import com.intellij.psi.PsiElement
import javax.swing.Icon

/**
 * Symbol am Zeilenrand neben jedem Modul der obersten Ebene: zeigt nur dieses Modul (mit seinen Standardwerten) in der
 * Vorschau - auch in Bibliotheksdateien, die selbst nichts erzeugen. Ein zweiter Klick zeigt wieder die ganze Datei.
 * Abschaltbar unter Settings | Editor | General | Gutter Icons.
 */
class ScadModuleLineMarkerProvider : LineMarkerProviderDescriptor() {
    override fun getName(): String = ScadSphereBundle.message("gutter.module.name")
    override fun getIcon(): Icon = ScadIcons.MODEL

    override fun getLineMarkerInfo(element: PsiElement): LineMarkerInfo<*>? {
        if (element.node.elementType != ScadTypes.IDENTIFIER) return null
        val module = element.parent as? ScadModuleDeclaration ?: return null
        if (module.nameIdentifier != element || module.parent !is ScadFile) return null
        val name = module.name ?: return null
        val shown = element.containingFile.virtualFile?.getUserData(ScadPreviewEditor.FOCUS_MODULE) == name
        val tooltip = if (shown) ScadSphereBundle.message("gutter.module.showAll") else ScadSphereBundle.message("gutter.module.show", name)
        return LineMarkerInfo(
            element, element.textRange, if (shown) AllIcons.Actions.Close else ScadIcons.MODEL, { tooltip },
            { _, target -> toggle(target) }, GutterIconRenderer.Alignment.LEFT, { tooltip },
        )
    }

    private fun toggle(element: PsiElement) {
        val module = element.parent as? ScadModuleDeclaration ?: return
        val name = module.name ?: return
        val file = element.containingFile.virtualFile ?: return
        val editors = element.project.service<FileEditorManager>().getAllEditors(file).filterIsInstance<ScadSplitEditor>()
        for (editor in editors) {
            val show = editor.preview.focusModule != name
            if (show && editor.getLayout() == TextEditorWithPreview.Layout.SHOW_EDITOR) editor.setLayout(TextEditorWithPreview.Layout.SHOW_EDITOR_AND_PREVIEW)
            editor.preview.showModule(if (show) name else null)
        }
    }
}
