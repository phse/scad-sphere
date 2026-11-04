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
