package app.heckit.ide.scadsphere

import com.intellij.testFramework.fixtures.BasePlatformTestCase

/** Die Einstellungsseite lässt sich aufbauen (ein Fehler im Aufbau zeigt sonst nur eine leere Seite). */
class SettingsPanelTest : BasePlatformTestCase() {
    fun testPanelOpens() {
        val configurable = ScadSphereConfigurable()
        try {
            assertNotNull(configurable.createComponent())
            configurable.reset()
            assertFalse(configurable.isModified)
        } finally {
            configurable.disposeUIResources()
        }
    }
}
