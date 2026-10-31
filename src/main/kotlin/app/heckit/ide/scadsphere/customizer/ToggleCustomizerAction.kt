package app.heckit.ide.scadsphere.customizer

import app.heckit.ide.scadsphere.ScadSphereBundle
import app.heckit.ide.scadsphere.ScadSphereSettings
import app.heckit.ide.scadsphere.editor.splitEditor
import app.heckit.ide.scadsphere.lang.ScadIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.actionSystem.ToggleAction
import com.intellij.util.messages.Topic

/** Customizer neben der Vorschau ein- und ausblenden (gilt für alle Dateien). */
class ToggleCustomizerAction : ToggleAction(
    ScadSphereBundle.messagePointer("action.customizer"), ScadSphereBundle.messagePointer("action.customizer.description"), ScadIcons.CUSTOMIZER,
), DumbAware {
    override fun getActionUpdateThread() = ActionUpdateThread.EDT

    override fun update(e: AnActionEvent) {
        super.update(e)
        val preview = e.splitEditor()?.preview
        e.presentation.isVisible = preview != null
        e.presentation.isEnabled = preview?.hasParameters == true
        e.presentation.description = ScadSphereBundle.message(if (preview?.hasParameters == true) "action.customizer.description" else "action.customizer.none")
    }

    override fun isSelected(e: AnActionEvent): Boolean = ScadSphereSettings.showCustomizer

    override fun setSelected(e: AnActionEvent, state: Boolean) = setVisible(state)

    companion object {
        fun setVisible(visible: Boolean) {
            ScadSphereSettings.showCustomizer = visible
            ApplicationManager.getApplication().messageBus.syncPublisher(CustomizerVisibilityListener.TOPIC).changed()
        }
    }
}

fun interface CustomizerVisibilityListener {
    fun changed()

    companion object {
        @Topic.AppLevel
        val TOPIC = Topic.create("SCAD Sphere customizer visibility", CustomizerVisibilityListener::class.java)
    }
}
