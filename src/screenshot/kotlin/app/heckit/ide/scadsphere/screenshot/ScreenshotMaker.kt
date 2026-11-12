package app.heckit.ide.scadsphere.screenshot

import app.heckit.ide.scadsphere.ScadFileStates
import app.heckit.ide.scadsphere.ScadSphereSettings
import app.heckit.ide.scadsphere.customizer.CustomizerVisibilityListener
import app.heckit.ide.scadsphere.ScadSphereBundle
import app.heckit.ide.scadsphere.editor.ScadSplitEditor
import app.heckit.ide.scadsphere.export.ExportDialog
import app.heckit.ide.scadsphere.export.ExportFormat
import app.heckit.ide.scadsphere.render.OpenScad
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ide.ui.LafManager
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.TextEditorWithPreview
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.openapi.wm.WindowManager
import com.intellij.openapi.wm.ex.ToolWindowEx
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import javax.swing.JFrame

/**
 * Erzeugt Screenshots in der Sandbox-IDE (./gradlew runIde -Pscreenshots=...). Zeichnet den Frame selbst in ein
 * Bild, braucht also keine Bildschirmaufnahme-Rechte. Welche Dateien, steht in marketplace/screenshots.txt
 * (bzw. `screenshots.txt` im Projekt bei -PscreenshotProject), je Zeile
 * `bildname: pfad [dark] [preview|editor] [messages] [nocustomizer] [customizerbottom] [set:name=wert …] [export:stl] [section:x:0.5[:flip]] [measure:x,y,z;x,y,z] [up:y]` (`set:` ändert
 * Customizer-Werte, `export:` nimmt zusätzlich den Export-Dialog für das Format auf, als `bildname-dialog.png`).
 * Ist nur in Builds mit -Pscreenshots enthalten.
 */
object ScreenshotMaker {
    private const val SCALE = 2.0
    private val log = logger<ScreenshotMaker>()

    @JvmStatic
    fun start(project: Project, dir: String) {
        val out = File(dir).apply { mkdirs() }
        Thread({
            try {
                run(project, out)
            } catch (e: Throwable) {
                log.error("Screenshots failed", e)
            }
        }, "SCAD Sphere screenshots").start()
    }

    private fun run(project: Project, out: File) {
        val frame = edt { WindowManager.getInstance().getFrame(project) } ?: error("no frame")
        val laf = LafManager.getInstance()
        val previousTheme = laf.currentUIThemeLookAndFeel
        val base = edt { project.guessProjectDir() } ?: error("no project dir")
        val stepsFile = System.getProperty("scadsphere.screenshotSteps")?.let(::File) ?: File(base.path, "screenshots.txt")
        val steps = stepsFile.takeIf { it.isFile }?.readLines()
            ?.map { it.trim() }?.filter { it.isNotEmpty() && !it.startsWith("#") } ?: error("$stepsFile missing")
        edt {
            frame.extendedState = JFrame.NORMAL
            frame.setBounds(60, 40, 1280, 860)
            // Projektbaum schmal für mehr Platz im Editor
            (ToolWindowManager.getInstance(project).getToolWindow("Project") as? ToolWindowEx)?.let { window ->
                window.stretchWidth(260 - window.component.width)
            }
        }
        Thread.sleep(3000)
        for (step in steps) {
            val name = step.substringBefore(':').trim()
            val words = step.substringAfter(':').trim().split(Regex("\\s+"))
            val path = words.first()
            val dark = "dark" in words
            val layout = when {
                "preview" in words -> TextEditorWithPreview.Layout.SHOW_PREVIEW
                "editor" in words -> TextEditorWithPreview.Layout.SHOW_EDITOR
                else -> TextEditorWithPreview.Layout.SHOW_EDITOR_AND_PREVIEW
            }
            val file = edt { base.findFileByRelativePath(path) } ?: error("$path missing")
            val overrides = words.filter { it.startsWith("set:") }.associate { it.removePrefix("set:").substringBefore('=') to it.substringAfter('=') }
            edt {
                ScadSphereSettings.showCustomizer = "nocustomizer" !in words
                ApplicationManager.getApplication().messageBus.syncPublisher(CustomizerVisibilityListener.TOPIC).changed()
                ScadFileStates.of(project).setCustomizer(file, overrides, null, null)
                theme(laf, dark)
                // up:y bzw. up:x: Achse oben für Mesh-Dateien (wie vom Nutzer im Viewer gewählt)
                words.firstOrNull { it.startsWith("up:") }?.let {
                    com.intellij.ide.util.PropertiesComponent.getInstance().setValue("scadsphere.upAxis.${file.path}", it.removePrefix("up:"), "z")
                }
                // Nur die Datei des Schritts offen (auch kein „What's New“)
                FileEditorManager.getInstance(project).openFiles.forEach { FileEditorManager.getInstance(project).closeFile(it) }
                FileEditorManager.getInstance(project).openFile(file, true)
                FileEditorManager.getInstance(project).getEditors(file).filterIsInstance<ScadSplitEditor>().firstOrNull()?.setLayout(layout)
            }
            Thread.sleep(if (file.extension == "scad") 9000 else 5000)
            if ("customizerbottom" in words) {
                edt {
                    val preview = FileEditorManager.getInstance(project).getEditors(file).filterIsInstance<ScadSplitEditor>().firstOrNull()?.preview
                    val scroll = preview?.component?.let { com.intellij.util.ui.UIUtil.findComponentsOfType(it, javax.swing.JScrollPane::class.java) }?.lastOrNull()
                    scroll?.verticalScrollBar?.let { it.value = it.maximum }
                }
                Thread.sleep(500)
            }
            // section:x:0.5[:flip] und measure:x,y,z;x,y,z stellen den Viewer ein
            val section = words.firstOrNull { it.startsWith("section:") }?.split(':')
            val measure = words.firstOrNull { it.startsWith("measure:") }?.removePrefix("measure:")
                ?.split(';')?.map { point -> point.split(',').map { it.toDouble() } }
            if (section != null || measure != null) {
                edt {
                    val preview = FileEditorManager.getInstance(project).getEditors(file).filterIsInstance<ScadSplitEditor>().firstOrNull()?.preview
                    section?.let { preview?.invokeViewer("setSection", mapOf("axis" to it[1], "fraction" to it[2].toDouble(), "flip" to (it.getOrNull(3) == "flip"))) }
                    measure?.let { preview?.invokeViewer("setMeasure", it) }
                }
                Thread.sleep(800)
            }
            if ("messages" in words) {
                edt { FileEditorManager.getInstance(project).getEditors(file).filterIsInstance<ScadSplitEditor>().firstOrNull()?.preview?.expandMessages() }
                Thread.sleep(800)
            }
            shot(frame.rootPane, File(out, "$name.png"))
            words.firstOrNull { it.startsWith("export:") }?.let { word ->
                val format = ExportFormat.byExtension(word.removePrefix("export:")) ?: error("$word: unknown format")
                val features = OpenScad.features(OpenScad.executable() ?: error("OpenSCAD missing"))
                val dialog = edt {
                    ExportDialog(
                        project, format, ScadSphereBundle.message("export.dialog.title", format.label), ScadSphereBundle.message("export.dialog.ok"),
                        java.io.File(base.path, "${file.nameWithoutExtension}.${format.extension}"), emptyList(), format.choices(features),
                        ScadSphereBundle.message("export.dialog.customizer", overrides.size),
                    ).also { it.isModal = false; it.show() }
                }
                Thread.sleep(1000)
                shot((dialog.window as javax.swing.RootPaneContainer).rootPane, File(out, "$name-dialog.png"))
                edt { dialog.close(DialogWrapper.CANCEL_EXIT_CODE) }
            }
            edt { FileEditorManager.getInstance(project).closeFile(file) }
            Thread.sleep(800)
        }
        edt {
            previousTheme?.let { laf.setCurrentLookAndFeel(it, false); laf.updateUI() }
            ScadSphereSettings.showCustomizer = true
        }
        log.warn("SCAD Sphere: screenshots written to $out")
    }

    private fun theme(laf: LafManager, dark: Boolean) {
        if (laf.currentUIThemeLookAndFeel?.isDark == dark) return
        val target = laf.installedThemes.firstOrNull { it.isDark == dark } ?: return
        laf.setCurrentLookAndFeel(target, false)
        laf.updateUI()
    }

    private fun shot(root: javax.swing.JRootPane, file: File) = edt {
        val image = BufferedImage((root.width * SCALE).toInt(), (root.height * SCALE).toInt(), BufferedImage.TYPE_INT_ARGB)
        val g = image.createGraphics()
        g.scale(SCALE, SCALE)
        root.paint(g)
        g.dispose()
        ImageIO.write(image, "png", file)
    }

    private fun <T> edt(block: () -> T): T {
        var result: Result<T>? = null
        ApplicationManager.getApplication().invokeAndWait { result = runCatching(block) }
        return result!!.getOrThrow()
    }
}
