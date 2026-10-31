package app.heckit.ide.scadsphere

import app.heckit.ide.scadsphere.customizer.ToggleCustomizerAction
import app.heckit.ide.scadsphere.editor.ScadPreviewEditor
import app.heckit.ide.scadsphere.editor.ScadSplitEditor
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.PlatformCoreDataKeys
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.fileEditor.impl.text.TextEditorProvider
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.ui.OnePixelSplitter
import com.intellij.util.ui.UIUtil

/** Customizer neben der Vorschau ein- und ausblenden. */
class CustomizerToggleTest : BasePlatformTestCase() {
    fun testToggleHidesPanel() {
        val file = myFixture.configureByText("stand.scad", "width = 80; // [60:120]\ncube(width);\n").virtualFile
        val text = TextEditorProvider.getInstance().createEditor(project, file) as TextEditor
        val preview = ScadPreviewEditor(project, file)
        val split = ScadSplitEditor(text, preview)
        Disposer.register(testRootDisposable, split)
        ScadSphereSettings.showCustomizer = true
        repeat(50) { PlatformTestUtil.dispatchAllEventsInIdeEventQueue(); Thread.sleep(20) }
        val splitter = UIUtil.findComponentOfType(preview.component, OnePixelSplitter::class.java) ?: preview.component as OnePixelSplitter
        assertTrue("has parameters", preview.hasParameters)
        assertNotNull("panel shown", splitter.secondComponent)

        val action = ToggleCustomizerAction()
        val context = SimpleDataContext.builder().add(CommonDataKeys.PROJECT, project).add(CommonDataKeys.VIRTUAL_FILE, file)
            .add(PlatformCoreDataKeys.FILE_EDITOR, split).build()
        val event = AnActionEvent.createEvent(context, null, ActionPlaces.EDITOR_TOOLBAR, com.intellij.openapi.actionSystem.ActionUiKind.TOOLBAR, null)
        action.update(event)
        assertTrue("enabled", event.presentation.isEnabled)
        action.actionPerformed(event)
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
        assertFalse(ScadSphereSettings.showCustomizer)
        assertNull("panel hidden", splitter.secondComponent)
        ScadSphereSettings.showCustomizer = true
    }
}
