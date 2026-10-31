package app.heckit.ide.scadsphere

import app.heckit.ide.scadsphere.customizer.CustomizerPanel
import app.heckit.ide.scadsphere.customizer.ParameterSets
import app.heckit.ide.scadsphere.customizer.CustomizerParameter
import app.heckit.ide.scadsphere.customizer.ParameterType
import app.heckit.ide.scadsphere.customizer.ParameterValue
import app.heckit.ide.scadsphere.customizer.ScadCustomizer
import app.heckit.ide.scadsphere.customizer.formatNumber
import app.heckit.ide.scadsphere.lang.ScadFileType
import app.heckit.ide.scadsphere.psi.ScadFile
import app.heckit.ide.scadsphere.render.OpenScad
import app.heckit.ide.scadsphere.render.OpenScadFeatures
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.CapturingProcessHandler
import com.intellij.openapi.util.io.FileUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.io.File

/** Customizer: Parameter nach den Regeln von OpenSCAD, Werte, Parametersätze, Fähigkeiten von OpenSCAD. */
class ScadCustomizerTest : BasePlatformTestCase() {
    private fun parameters(text: String): List<CustomizerParameter> {
        val file = myFixture.configureByText(ScadFileType, text) as ScadFile
        return ScadCustomizer.parameters(file).map { it.parameter }
    }

    fun testWidgets() {
        val byName = parameters(SAMPLE).associateBy { it.name }
        assertEquals(DEFAULT_NAMES, byName.keys.toList())

        val slider = byName.getValue("stepSlider")
        assertEquals(listOf(0.0, 5.0, 100.0), listOf(slider.min, slider.step, slider.max))
        assertEquals("Slider", slider.group)
        assertEquals("step slider for number", slider.description)
        assertTrue(slider.isSlider)

        val maxOnly = byName.getValue("maxonly")
        assertEquals(listOf(0.0, 1.0, 50.0), listOf(maxOnly.min, maxOnly.step, maxOnly.max))
        assertEquals(ParameterValue.Number(-5.0), byName.getValue("neg").initial)
        assertEquals(ParameterValue.Number(3.0), byName.getValue("plus").initial)
        assertEquals(ParameterValue.Number(16.0), byName.getValue("hex").initial)

        val labelled = byName.getValue("Labeled_value")
        assertEquals(ParameterType.STRING, labelled.type)
        assertEquals(listOf("S" to "Small", "M" to "Medium", "L" to "Large"), labelled.options!!.map { (it.value as ParameterValue.Text).value to it.label })
        assertEquals(listOf(10.0, 20.0, 30.0), byName.getValue("Labeled_values").options!!.map { (it.value as ParameterValue.Number).value })

        assertEquals(8, byName.getValue("String8").maxLength)
        assertEquals(ParameterValue.Text("it's \"q\""), byName.getValue("quoted").initial)
        assertEquals(ParameterType.BOOLEAN, byName.getValue("Variable").type)
        assertEquals(ParameterValue.Vector(listOf(12.0, 34.0)), byName.getValue("Vector2").initial)
        assertEquals(50.0, byName.getValue("Vector2").max)
        assertNull(byName.getValue("inline").options)
        assertEquals("Global", byName.getValue("glob").group)
        assertEquals("file header comment", byName.getValue("x0").description)
        assertEquals(ScadCustomizer.DEFAULT_GROUP, byName.getValue("x0").group)
    }

    fun testStopsAtFirstLineWithBrace() {
        val names = parameters("a = 1;\ncube(1); b = 2;\nc = \"x { y\";\nd = 4;\n").map { it.name }
        assertEquals(listOf("a", "b"), names)
        // Klammern in Kommentaren zählen nicht
        assertEquals(listOf("a", "b"), parameters("a = 1;\n/* { */\nb = 2;\n").map { it.name })
    }

    fun testValueRoundTrip() {
        val values = listOf(
            ParameterValue.Number(80.0) to ParameterType.NUMBER,
            ParameterValue.Number(-0.25) to ParameterType.NUMBER,
            ParameterValue.Text("a \"b\" \\ c") to ParameterType.STRING,
            ParameterValue.Bool(false) to ParameterType.BOOLEAN,
            ParameterValue.Vector(listOf(1.0, 2.5)) to ParameterType.VECTOR,
        )
        for ((value, type) in values) assertEquals(value, ScadCustomizer.parseScad(value.toScad(), type))
        assertEquals("80", formatNumber(80.0))
        assertEquals("0.3", formatNumber(CustomizerPanel.roundTo(0.1 + 0.2, 0.1)))
        assertEquals(0.01, CustomizerPanel.defaultStep(1.25))
        assertEquals(1.0, CustomizerPanel.defaultStep(70.0))
        assertNull(ScadCustomizer.parseScad("\"text\"", ParameterType.NUMBER))
    }

    fun testSetValues() {
        assertEquals(ParameterValue.Number(80.0), ScadCustomizer.parseSetValue(JsonPrimitive("80"), ParameterType.NUMBER))
        assertEquals(ParameterValue.Number(80.0), ScadCustomizer.parseSetValue(JsonPrimitive(80), ParameterType.NUMBER))
        assertEquals(ParameterValue.Text("80"), ScadCustomizer.parseSetValue(JsonPrimitive("80"), ParameterType.STRING))
        assertEquals(ParameterValue.Bool(true), ScadCustomizer.parseSetValue(JsonPrimitive("true"), ParameterType.BOOLEAN))
        assertEquals(ParameterValue.Vector(listOf(1.0, 2.0)), ScadCustomizer.parseSetValue(JsonPrimitive("[1, 2]"), ParameterType.VECTOR))
        assertEquals("Small", ParameterValue.Text("Small").toSetValue())
    }

    fun testDefinesOnlyForKnownParametersOfTheRightType() {
        val file = myFixture.configureByText("part.scad", "width = 80; // [60:120]\nlabel = \"x\";\n")
        ScadFileStates.of(project).setCustomizer(file.virtualFile, mapOf("width" to "100", "label" to "7", "gone" to "1"), null, null)
        assertEquals(listOf("width=100"), ScadCustomizer.defines(project, file.virtualFile))
    }

    fun testWriteToCode() {
        val file = myFixture.configureByText("stand.scad", "width = 80; // [60:120]\nlabel = \"a\";\nshow = true;\n")
        assertTrue(ScadCustomizer.writeToCode(project, file.virtualFile, mapOf("width" to "100", "label" to "\"b \\\"c\\\"\"", "show" to "false")))
        assertEquals("width = 100; // [60:120]\nlabel = \"b \\\"c\\\"\";\nshow = false;\n", myFixture.editor.document.text)
    }

    fun testParameterSetsFile() {
        val scad = myFixture.addFileToProject("stand.scad", "width = 80;\n").virtualFile
        ParameterSets.write(scad, "Small", mapOf("width" to "60"))
        ParameterSets.write(scad, "Large", mapOf("width" to "120"))
        ParameterSets.write(scad, "Small", null)
        val sets = ParameterSets.read(scad)
        assertEquals(listOf("Large"), sets.keys.toList())
        assertEquals("120", sets.getValue("Large").getValue("width").asString)
        val json = JsonParser.parseString(String(scad.parent.findChild("stand.json")!!.contentsToByteArray())).asJsonObject
        assertEquals("1", json.get("fileFormatVersion").asString)
    }

    fun testFeatures() {
        val features = OpenScadFeatures.parse(
            "--export-format arg ... specify 'binstl'",
            """
            Section 'export-pdf':
              - paper-size (enum): [a6,a5,<a4>,a3,letter]
              - show-grid (bool): true/<false>
            Section 'export-3mf':
              - color-mode (enum): [<model>,none,selected-only]
            """.trimIndent(),
        )
        assertTrue(features.binaryStl)
        assertEquals("a4", features.setting("export-pdf", "paper-size")?.default)
        assertEquals(listOf("model", "none", "selected-only"), features.setting("export-3mf", "color-mode")?.values)
        assertEquals("false", features.setting("export-pdf", "show-grid")?.default)
        assertNull(features.setting("export-3mf", "unit"))
        assertFalse(OpenScadFeatures.parse("", "").binaryStl)
    }

    /**
     * Gleiche Parameter wie OpenSCADs eigener Export (`-o datei.param`). Läuft nur, wenn OpenSCAD installiert ist und
     * den Export kennt.
     */
    fun testSameAsOpenScad() {
        val exe = OpenScad.detect() ?: return println("OpenSCAD not found, skipping comparison")
        val dir = FileUtil.createTempDirectory("scadsphere-customizer", null, true)
        try {
            val source = File(dir, "sample.scad").apply { writeText(SAMPLE) }
            val target = File(dir, "sample.param")
            CapturingProcessHandler(GeneralCommandLine(exe, "-o", target.path, source.path)).runProcess(60_000)
            if (!target.isFile) return println("OpenSCAD cannot export .param, skipping comparison")
            val expected = JsonParser.parseString(target.readText()).asJsonObject.getAsJsonArray("parameters").map { it.asJsonObject }
            val actual = parameters(SAMPLE)
            assertEquals(expected.map { it.get("name").asString }, actual.map { it.name })
            for ((json, parameter) in expected.zip(actual)) {
                val name = parameter.name
                assertEquals(name, json.get("group")?.asString ?: "", parameter.group)
                assertEquals(name, json.get("caption")?.asString, parameter.description)
                assertEquals(name, openScadValue(json, parameter), parameter.initial)
                if (parameter.isSlider || json.has("max")) {
                    assertEquals(name, json.get("min")?.asDouble, parameter.min)
                    assertEquals(name, json.get("max")?.asDouble, parameter.max)
                    assertEquals(name, json.get("step")?.asDouble, parameter.step)
                }
                assertEquals(name, json.get("maxLength")?.asInt, parameter.maxLength)
                val options = json.getAsJsonArray("options")?.map { option ->
                    val o = option.asJsonObject
                    val value = if (parameter.type == ParameterType.NUMBER) ParameterValue.Number(o.get("value").asDouble) else ParameterValue.Text(o.get("value").asString)
                    value to o.get("name").asString
                }
                assertEquals(name, options, parameter.options?.map { it.value to it.label })
            }
        } finally {
            FileUtil.delete(dir)
        }
    }

    private fun openScadValue(json: JsonObject, parameter: CustomizerParameter): ParameterValue {
        val initial = json.get("initial")
        return when {
            initial.isJsonArray -> ParameterValue.Vector(initial.asJsonArray.map { it.asDouble })
            json.get("type").asString == "boolean" -> ParameterValue.Bool(initial.asBoolean)
            json.get("type").asString == "string" -> ParameterValue.Text(initial.asString)
            else -> ParameterValue.Number(initial.asDouble).also { assertEquals(ParameterType.NUMBER, parameter.type) }
        }
    }

    private companion object {
        val DEFAULT_NAMES = listOf(
            "x0", "Numbers", "Strings", "Labeled_values", "Labeled_value", "slider", "stepSlider", "maxonly", "neg", "plus", "hex", "dec",
            "Variable", "Spinbox", "String", "String8", "quoted", "Vector1", "Vector2", "v3", "inline", "glob",
        )

        /** Beispiele aus dem OpenSCAD-Handbuch (Customizer) plus Grenzfälle. */
        val SAMPLE = """
            // file header comment
            x0 = 5;
            /* [Drop down box:] */
            // combo box for number
            Numbers=2; // [0, 1, 2, 3]
            // combo box for string
            Strings="foo"; // [foo, bar, baz]
            //labeled combo box for numbers
            Labeled_values=10; // [10:L, 20:M, 30:XL]
            //labeled combo box for string
            Labeled_value="S"; // [S:Small, M:Medium, L:Large]
            /*[ Slider ]*/
            // slider widget for number
            slider =34; // [10:100]
            //step slider for number
            stepSlider=2; //[0:5:100]
            maxonly = 3; // [50]
            neg = -5; // [-10:0.5:10]
            plus = +3;
            hex = 0x10;
            dec = 1.25;
            /* [Checkbox] */
            //description for checkbox
            Variable = true;
            /*[Spinbox] */
            // spinbox with step size 1
            Spinbox= 5;
            /* [Textbox] */
            //Text box for vector with more than 4 elements
            Vector6=[12,34,44,43,23,23];
            // Text box for string
            String="hello";
            // Text box with max length
            String8="length"; //8
            quoted = "it's \"q\"";
            /* [Special vector] */
            //Text box for vector with less than or equal to 4 elements
            Vector1=[12]; //[0:2:50]
            Vector2=[12,34]; //[0:2:50]
            v3 = [1.5, 2, 3];
            inline = 4; // just a note
            expr = 2 * 3;
            ref = slider;
            nothing = undef;
            /* [Hidden] */
            hidden = 1;
            /* [Global] */
            glob = 7;
            module m() { cube(1); }
            after = 3;
        """.trimIndent() + "\n"
    }
}
