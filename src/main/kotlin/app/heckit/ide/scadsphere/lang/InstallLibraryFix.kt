package app.heckit.ide.scadsphere.lang

import app.heckit.ide.scadsphere.ScadSphereBundle
import app.heckit.ide.scadsphere.editor.NOTIFICATIONS
import app.heckit.ide.scadsphere.psi.ScadFile
import app.heckit.ide.scadsphere.psi.ScadLibraries
import app.heckit.ide.scadsphere.psi.ScadLibraryCatalog
import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.codeInsight.intention.IntentionAction
import com.intellij.codeInsight.intention.preview.IntentionPreviewInfo
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import java.io.File

/**
 * Quick-Fix an einem `include`/`use`, dessen Datei fehlt und dessen Ordner eine bekannte Bibliothek ist
 * ([ScadLibraryCatalog]): nach Rückfrage von GitHub in den Bibliotheksordner des Benutzers laden, den auch OpenSCAD liest.
 */
class InstallLibraryFix(private val library: ScadLibraryCatalog.Library) : IntentionAction {
    override fun getText(): String = ScadSphereBundle.message("fix.installLibrary", library.folder)
    override fun getFamilyName(): String = ScadSphereBundle.message("fix.installLibrary.family")
    override fun isAvailable(project: Project, editor: Editor?, file: PsiFile?): Boolean = file is ScadFile
    override fun startInWriteAction(): Boolean = false

    /** Keine Vorschau: Sie würde [invoke] auf einer Kopie ausführen (Rückfrage, Download). */
    override fun generatePreview(project: Project, editor: Editor, file: PsiFile): IntentionPreviewInfo = IntentionPreviewInfo.EMPTY

    override fun invoke(project: Project, editor: Editor?, file: PsiFile?) {
        val root = File(ScadLibraries.userLibraryDir())
        val target = File(root, library.folder)
        val answer = Messages.showOkCancelDialog(
            project,
            ScadSphereBundle.message("fix.installLibrary.confirm", library.folder, library.page, library.license, target.path),
            ScadSphereBundle.message("fix.installLibrary", library.folder),
            ScadSphereBundle.message("fix.installLibrary.ok"), Messages.getCancelButton(), Messages.getQuestionIcon(),
        )
        if (answer != Messages.OK) return
        object : Task.Backgroundable(project, ScadSphereBundle.message("fix.installLibrary.progress", library.folder), true) {
            private var error: String? = null

            override fun run(indicator: ProgressIndicator) {
                try {
                    val installed = ScadLibraryCatalog.install(library, root, indicator)
                    LocalFileSystem.getInstance().refreshAndFindFileByIoFile(installed)?.let { VfsUtil.markDirtyAndRefresh(false, true, true, it) }
                } catch (e: ProcessCanceledException) {
                    throw e
                } catch (e: Exception) {
                    error = e.message ?: e.javaClass.simpleName
                }
            }

            override fun onSuccess() {
                val group = NotificationGroupManager.getInstance().getNotificationGroup(NOTIFICATIONS)
                error?.let {
                    return group.createNotification(ScadSphereBundle.message("fix.installLibrary.failed", library.folder, it), NotificationType.ERROR).notify(project)
                }
                // include/use neu auflösen: Bibliotheksordner und alle offenen OpenSCAD-Dateien
                ScadLibraries.invalidate()
                PsiManager.getInstance(project).dropPsiCaches()
                DaemonCodeAnalyzer.getInstance(project).restart("SCAD Sphere: library installed")
                group.createNotification(ScadSphereBundle.message("fix.installLibrary.done", library.folder, target.path), NotificationType.INFORMATION).notify(project)
            }
        }.queue()
    }
}
