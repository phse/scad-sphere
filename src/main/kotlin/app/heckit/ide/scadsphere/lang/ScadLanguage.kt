package app.heckit.ide.scadsphere.lang

import app.heckit.ide.scadsphere.ScadSphereBundle
import com.intellij.lang.Language
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.fileTypes.LanguageFileType
import com.intellij.openapi.util.IconLoader
import javax.swing.Icon

/**
 * OpenSCAD. Die ID ist bewusst eigen (nicht „OpenSCAD“): Andere OpenSCAD-Plugins registrieren diese ID, und
 * zwei Sprachen mit derselben ID lassen sich nicht nebeneinander laden. Angezeigt wird überall „OpenSCAD“.
 */
object ScadLanguage : Language("heckit.OpenSCAD") {
    override fun getDisplayName(): String = "OpenSCAD"
    override fun isCaseSensitive(): Boolean = true
}

object ScadFileType : LanguageFileType(ScadLanguage) {
    override fun getName(): String = "heckit.OpenSCAD"
    override fun getDisplayName(): String = "OpenSCAD"
    override fun getDescription(): String = ScadSphereBundle.message("filetype.scad.description")
    override fun getDefaultExtension(): String = "scad"
    override fun getIcon(): Icon = ScadIcons.SCAD
}

/** 3D-Modelle (STL, 3MF, OBJ, OFF, AMF) - nur für den 3D-Viewer, kein Texteditor. */
abstract class ModelFileType(
    private val id: String,
    private val displayName: String,
    private val extension: String,
    private val descriptionKey: String,
) : FileType {
    override fun getName(): String = id
    override fun getDisplayName(): String = displayName
    override fun getDescription(): String = ScadSphereBundle.message(descriptionKey)
    override fun getDefaultExtension(): String = extension
    override fun getIcon(): Icon = ScadIcons.MODEL
    override fun isBinary(): Boolean = true
    override fun isReadOnly(): Boolean = true
}

/** STL (Text oder binär). */
object StlFileType : ModelFileType("heckit.STL", "STL", "stl", "filetype.stl.description")

/** 3MF (ZIP mit XML). */
object ThreeMfFileType : ModelFileType("heckit.3MF", "3MF", "3mf", "filetype.3mf.description")

/** Wavefront OBJ (Text). */
object ObjFileType : ModelFileType("heckit.OBJ", "OBJ", "obj", "filetype.obj.description")

/** Object File Format (Text, OpenSCAD schreibt Farben je Fläche). */
object OffFileType : ModelFileType("heckit.OFF", "OFF", "off", "filetype.off.description")

/** Additive Manufacturing File Format (XML, auch gezippt). */
object AmfFileType : ModelFileType("heckit.AMF", "AMF", "amf", "filetype.amf.description")

object ScadIcons {
    @JvmField
    val SCAD: Icon = IconLoader.getIcon("/icons/scad.svg", ScadIcons::class.java)

    @JvmField
    val MODEL: Icon = IconLoader.getIcon("/icons/model.svg", ScadIcons::class.java)

    @JvmField
    val CUSTOMIZER: Icon = IconLoader.getIcon("/icons/customizer.svg", ScadIcons::class.java)
}
