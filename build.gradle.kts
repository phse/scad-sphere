import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType
import java.util.Properties

plugins {
    id("org.jetbrains.kotlin.jvm") version "2.4.20"
    id("org.jetbrains.intellij.platform") version "2.19.0"
}

// Rechnerspezifische Einstellungen (nicht eingecheckt), z. B. platformLocalPath=/Applications/PhpStorm.app
val localProperties = Properties().apply {
    file("local.properties").takeIf { it.exists() }?.reader()?.use { load(it) }
}
val platformLocalPath: String? = localProperties.getProperty("platformLocalPath")?.takeIf { file(it).exists() }

group = providers.gradleProperty("pluginGroup").get()
version = providers.gradleProperty("pluginVersion").get()

kotlin {
    jvmToolchain(25)
    // Ohne das erzeugt Kotlin Brücken auf Default-Methoden von Plattform-Interfaces (McpToolset), die es in
    // älteren IDEs nicht gibt bzw. die der Verifier als veraltete API meldet
    compilerOptions {
        jvmDefault.set(org.jetbrains.kotlin.gradle.dsl.JvmDefaultMode.NO_COMPATIBILITY)
    }
}

// ./gradlew runIde -Pscreenshots=<ordner> baut den Screenshot-Helfer mit ein (nie für Releases verwenden).
val screenshotDir = providers.gradleProperty("screenshots").orNull

// Der 3D-Viewer (three.js) wird per esbuild aus web/ gebaut und unter /scadsphere-web/ ins Plugin gepackt.
val webDir = layout.projectDirectory.dir("web")
val webResources = layout.buildDirectory.dir("web")

val installWeb = tasks.register<Exec>("installWeb") {
    workingDir = webDir.asFile
    commandLine(providers.gradleProperty("npmCommand").get(), "ci", "--no-audit", "--no-fund")
    inputs.files(webDir.file("package.json"), webDir.file("package-lock.json"))
    outputs.dir(webDir.dir("node_modules"))
}

val buildWeb = tasks.register<Exec>("buildWeb") {
    dependsOn(installWeb)
    workingDir = webDir.asFile
    commandLine(providers.gradleProperty("npmCommand").get(), "run", "build")
    inputs.dir(webDir.dir("src"))
    inputs.files(webDir.file("package.json"), webDir.file("package-lock.json"), webDir.file("build.mjs"))
    outputs.dir(webDir.dir("dist"))
}

val copyWeb = tasks.register<Sync>("copyWeb") {
    dependsOn(buildWeb)
    from(webDir.dir("dist"))
    into(webResources.map { it.dir("scadsphere-web") })
}

sourceSets {
    main {
        resources.srcDir(webResources)
        if (screenshotDir != null) kotlin.srcDir("src/screenshot/kotlin")
    }
}

tasks.processResources {
    dependsOn(copyWeb)
}

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")

    intellijPlatform {
        if (platformLocalPath != null) {
            local(platformLocalPath)
        } else {
            create(IntelliJPlatformType.PhpStorm, providers.gradleProperty("platformVersion").get())
        }
        // Eingebetteter Browser (seit 2026.x ein eigenes, mitgeliefertes Plugin).
        bundledPlugin("com.intellij.modules.jcef")
        // MCP-Server der IDE: optional, für die Werkzeuge scad_render, scad_parameters und scad_export
        bundledPlugin("com.intellij.mcpServer")
        testFramework(org.jetbrains.intellij.platform.gradle.TestFrameworkType.Platform)
    }
}

intellijPlatform {
    pluginConfiguration {
        version = providers.gradleProperty("pluginVersion")
        ideaVersion {
            sinceBuild = providers.gradleProperty("pluginSinceBuild")
            untilBuild = provider { null }
        }
        changeNotes = """
            <h3>1.5.0</h3>
            <ul>
              <li>The call at the caret is highlighted in the preview, also parts subtracted in a <code>difference()</code>;
                  the file is not changed.</li>
              <li><code>echo</code> output appears at the end of its line in the editor, like values in a debugger; the
                  messages below the model jump to the line.</li>
              <li>New inspection: deprecated or removed modules and arguments (<code>assign</code>, <code>child</code>,
                  <code>import_stl</code>, <code>filename</code>, <code>triangles</code>, ...) with a quick fix.</li>
              <li>Export all parameter sets at once, one file per set; Export Again repeats it.</li>
              <li>Missing libraries (BOSL2, BOSL, NopSCADlib, MCAD, Round-Anything, threads-scad, smooth-prim, scad-utils,
                  YAPP_Box): quick fix on the <code>include</code>/<code>use</code> downloads them from GitHub into the library folder.</li>
              <li>Measure angles (three points) and diameters (three points on a circle, e.g. the edge of a hole) besides distances.</li>
              <li>MCP tools for AI assistants (<code>scad_render</code>, <code>scad_parameters</code>, <code>scad_export</code>):
                  an assistant connected to the IDE's MCP server can render a model and gets messages, size, volume and images back.</li>
            </ul>
            <h3>1.4.0</h3>
            <ul>
              <li>Mesh editing: parts list grouped by material or color with show/hide and a filter; rename and delete parts,
                  assign a part to another material (also by drag and drop).</li>
              <li>Transform tool: move, rotate and scale a part or the whole model, relative or absolute, per axis or
                  uniformly, around a chosen origin; rows can be shown or hidden. Values follow the up axis of the file.</li>
              <li>Merge two OBJ or two 3MF files.</li>
              <li>STL (binary and ASCII) and OFF can be transformed; AMF gets the parts list, colors and all edit tools.</li>
              <li>Every edit can be undone with Undo in the view.</li>
            </ul>
            <h3>1.3.0</h3>
            <ul>
              <li>Recolor parts of OBJ and 3MF files: a color list in the viewer, click a part to highlight it, pick a new
                  color with live preview. OBJ colors are written to the <code>.mtl</code> (created if missing, a part can
                  get a material of its own), 3MF colors to <code>basematerials</code> and color groups. Undo with ⌘Z in the view.</li>
              <li><code>.mtl</code> files: own file type with highlighting, color swatches with a color picker and folding per
                  material; the 3D view of the OBJ follows while you type.</li>
              <li>Up axis per file (Z, Y or X) for models from Blender, Sweet Home 3D and other Y-up tools.</li>
            </ul>
            <h3>1.2.0</h3>
            <ul>
              <li>Animation: files using <code>${'$'}t</code> get a play bar in the viewer; each step is one OpenSCAD run,
                  computed steps are kept until the code changes.</li>
              <li>Save the current 3D view as PNG.</li>
              <li>Open in OpenSCAD passes the values changed in the Customizer.</li>
              <li>Setting for the geometry backend (Manifold or CGAL) if the installed OpenSCAD supports it.</li>
              <li>Library documentation: BOSL2 doc blocks and NopSCADlib <code>//!</code> comments in the quick
                  documentation and as summary in code completion.</li>
              <li>Library folders outside the project appear under External Libraries, so Go to Symbol finds their modules.</li>
              <li>OpenSCAD warnings inside a module name the calls that pass no or an undefined value for the parameter used
                  in that line; repeated messages of the same line are merged.</li>
              <li>Section view with hatched cut faces; <code>#</code> parts inside <code>linear_extrude</code> are shown
                  extruded instead of flat; two previews of the same file keep their messages when one is closed.</li>
            </ul>
            <h3>1.1.0</h3>
            <ul>
              <li>Go to Symbol for modules and functions across the project.</li>
              <li>Color swatch in the gutter next to <code>color()</code>, with a color picker that writes the color back.</li>
              <li>Parameter name hints before literal arguments, e.g. <code>cube([1, 2, 3], center: true)</code>.</li>
              <li>Live templates: <code>mod</code>, <code>fun</code>, <code>for</code>, <code>diff</code>, <code>cust</code> and more.</li>
              <li>Inspections: unused parameters and variables, a variable assigned twice in the same scope, unused
                  <code>use</code>, missing arguments.</li>
              <li>Quick fixes and intentions: create an unknown module or function, inline a variable, extract the selection
                  into a module, toggle <code>#</code>.</li>
              <li>Open in Slicer (Bambu Studio, OrcaSlicer, PrusaSlicer, UltiMaker Cura) with colors and Customizer values.</li>
              <li>Print bed with the origin in the center: drawn under the lowest point of the model and centered below it,
                  where the slicer will place it.</li>
              <li>Toolbar menus of the viewer open on hover only and no longer show the menu of the previous button.</li>
            </ul>
            <h3>1.0.0</h3>
            <ul>
              <li>First stable release.</li>
              <li>Errors and warnings of the last OpenSCAD run at their line in the code; unknown modules and functions
                  and missing <code>include</code>/<code>use</code> files are marked while you type.</li>
              <li>Print bed: outline in the 3D view (size, origin and presets for common printers in the settings) and a
                  warning when the model does not fit.</li>
              <li>Parts marked with <code>#</code> and <code>%</code> appear transparent, like in OpenSCAD's own preview.</li>
              <li>Measure distances between two points and a section view along X, Y or Z.</li>
              <li>New toolbar: views and display options in menus, bottom, left and back views, tooltips, and a » menu
                  when the preview is narrow.</li>
              <li>Fixed: an <code>assert</code> that fails is reported as an error instead of an empty model.</li>
            </ul>
            <h3>0.1.0</h3>
            <ul>
              <li>First release: OpenSCAD language support (highlighting, navigation across <code>include</code>/<code>use</code>,
                  find usages, rename, completion, documentation of the built-in modules and functions, structure view,
                  folding, formatting).</li>
              <li>3D preview of .scad files next to the editor, rendered with the installed OpenSCAD; colors or one color.</li>
              <li>Customizer next to the preview: sliders, drop-downs and checkboxes for the parameters, parameter sets
                  compatible with the OpenSCAD GUI, write the values back into the code.</li>
              <li>Export as 3MF, STL (binary or ASCII), OBJ, OFF, AMF, SVG, DXF or PDF with options; Export Again.</li>
              <li>Viewer for .stl, .3mf, .obj, .off and .amf files; convert them to another format or import them into a new
                  .scad file.</li>
            </ul>
        """.trimIndent()
    }

    // Optional: Plugin signieren (Zertifikat siehe README). Ohne Umgebungsvariablen wird nicht signiert.
    signing {
        certificateChain = providers.environmentVariable("CERTIFICATE_CHAIN")
        privateKey = providers.environmentVariable("PRIVATE_KEY")
        password = providers.environmentVariable("PRIVATE_KEY_PASSWORD")
    }

    // Für ./gradlew publishPlugin (erst nach dem ersten manuellen Upload möglich).
    publishing {
        token = providers.environmentVariable("PUBLISH_TOKEN")
            .orElse(providers.gradleProperty("jetbrainsMarketplaceToken"))
    }

    // ./gradlew verifyPlugin prüft gegen die lokale IDE und die älteste unterstützte Version.
    pluginVerification {
        ides {
            platformLocalPath?.let { local(it) }
            create(IntelliJPlatformType.PhpStorm, providers.gradleProperty("verifyOldestVersion").get())
            // Neueste EAP: Der Marketplace prüft auch neuere Builds und meldet dort Deprecations
            create(IntelliJPlatformType.PhpStorm, providers.gradleProperty("verifyNewestVersion").get())
        }
    }
}

// Apache 2.0 verlangt, dass LICENSE und NOTICE mit dem Plugin verteilt werden.
tasks.jar {
    from(files("LICENSE", "NOTICE")) { into("META-INF") }
}

// ./gradlew test -Psamples=<ordner mit .scad> parst zusätzlich alle Dateien des Ordners (z. B. BOSL2).
tasks.test {
    providers.gradleProperty("samples").orNull?.let { systemProperty("scadsphere.samples", it) }
    testLogging { showStandardStreams = true }
}

// Für Screenshots wird das Demo-Projekt an einen neutralen Ort kopiert, damit kein lokaler
// Benutzerpfad im Bild erscheint.
val screenshotProjectDir = "/Users/Shared/scad-demo"

val prepareScreenshotProject = tasks.register<Sync>("prepareScreenshotProject") {
    from("marketplace/demo-project")
    into(screenshotProjectDir)
}

tasks.runIde {
    // Kein „Trust Project“-Dialog für die Demo- und Testprojekte
    jvmArgs("-Didea.trust.all.projects=true")
    // ./gradlew runIde -PjcefDebugPort=9333 öffnet die DevTools-Schnittstelle der eingebetteten Browser.
    providers.gradleProperty("jcefDebugPort").orNull?.let { jvmArgs("-Dide.browser.jcef.debug.port=$it") }
    if (screenshotDir != null) {
        // -PscreenshotProject=<ordner> nimmt ein anderes Projekt (mit screenshots.txt) statt des Demo-Projekts
        val project = providers.gradleProperty("screenshotProject").orNull
        if (project == null) {
            dependsOn(prepareScreenshotProject)
            jvmArgs("-Dscadsphere.screenshotSteps=${file("marketplace/screenshots.txt").absolutePath}")
        }
        args(project ?: screenshotProjectDir)
        jvmArgs("-Dscadsphere.screenshots=${file(screenshotDir).absolutePath}")
    } else {
        // ./gradlew runIde -PopenProject=/pfad/zum/projekt öffnet direkt ein Projekt in der Sandbox.
        providers.gradleProperty("openProject").orNull?.let { args(it) }
    }
}

// Ohne -Pscreenshots bleibt aus dem Build-Cache sonst ein leeres screenshot/-Verzeichnis in den Jars.
tasks.withType<Zip>().configureEach {
    includeEmptyDirs = false
}
