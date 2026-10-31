package app.heckit.ide.scadsphere

import app.heckit.ide.scadsphere.lang.ScadLexer
import app.heckit.ide.scadsphere.lang.ScadParserDefinition
import app.heckit.ide.scadsphere.lang.ScadTypes
import com.intellij.psi.PsiErrorElement
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.ParsingTestCase
import java.io.File

/**
 * Lexer und Parser. `./gradlew test -Dscadsphere.samples=<ordner>` (bzw. `-Psamples=…`) parst zusätzlich alle
 * .scad-Dateien eines Ordners (z. B. BOSL2, NopSCADlib) und meldet Dateien mit Syntaxfehlern.
 */
class ScadParserTest : ParsingTestCase("", "scad", ScadParserDefinition()) {
    override fun getTestDataPath(): String = "src/test/testData"

    private fun tokens(text: String): List<Pair<String, String>> {
        val lexer = ScadLexer()
        lexer.start(text)
        val result = mutableListOf<Pair<String, String>>()
        while (lexer.tokenType != null) {
            if (lexer.tokenType.toString() != "WHITE_SPACE") result += lexer.tokenType.toString() to lexer.tokenText
            lexer.advance()
        }
        return result
    }

    fun testLexerIncludePath() {
        assertEquals(
            listOf("include" to "include", "IMPORT_PATH" to "<BOSL2/std.scad>", "IDENTIFIER" to "a", "<" to "<", "IDENTIFIER" to "b"),
            tokens("include <BOSL2/std.scad>\na < b"),
        )
    }

    fun testLexerNumbersAndNames() {
        assertEquals(
            listOf("NUMBER" to "1.5e-3", "NUMBER" to ".5", "NUMBER" to "0x1F", "IDENTIFIER" to "2d", "IDENTIFIER" to "\$fn", "NUMBER" to "10"),
            tokens("1.5e-3 .5 0x1F 2d \$fn 10"),
        )
    }

    fun testLexerComments() {
        assertEquals(
            listOf("DOC_COMMENT" to "/** doc */", "BLOCK_COMMENT" to "/**/", "LINE_COMMENT" to "// x", "STRING" to "\"a\\\"b\""),
            tokens("/** doc */ /**/ // x\n\"a\\\"b\""),
        )
    }

    /** Ein Ausschnitt aller Sprachmittel muss ohne Fehler durchgehen. */
    fun testLanguageFeatures() {
        assertNoErrors(
            """
            include <lib/a.scad>
            use <b.scad>;
            /* [Ansicht] */
            teil = "alles"; // [alles, schiene]
            ${'$'}fn = 48;
            function f(x, y = 2) = x < 0 ? -x : let(z = x * y) z ^ 2 ^ 0.5;
            g = function (a) a + 1;
            module m(size = [1, 2, 3], center = false) {
                difference() {
                    cube(size, center = center);
                    #translate([1, 0, 0]) rotate([0, 0, 45]) cylinder(h = 2, d = 1);
                    !sphere(1);
                    %square(2);
                    *circle(1);
                }
                for (i = [0 : 2 : 10], j = [1, 2]) translate([i, j]) children();
                if (center) { echo("c"); } else if (size.x > 1) cube(1); else ;
                intersection_for (k = [0 : 3]) rotate(k * 90) cube(2);
                let (q = 2) cube(q);
                assert(len(size) == 3, "size");
            }
            points = [for (i = [0 : 5]) if (i % 2 == 0) let (a = i * 60) [cos(a), sin(a)] else each [[0, 0]]];
            nested = [for (i = 0, s = 0; i < 5; i = i + 1, s = s + i) s];
            paren = [(for (i = [1 : 3]) i), each [4, 5], -[1, 2][0], !true && false || undef == undef];
            x = assert(true) echo("x") 1;
            bits = ~1 | 2 & 3 << 4 >> 1;
            v = [1, 2, 3].x + g(1)(2)[0];
            m();
            """.trimIndent(),
        )
    }

    fun testIncompleteCodeTerminates() {
        // Halbe Eingaben beim Tippen: dürfen Fehler haben, aber den Parser nie hängen lassen
        for (text in listOf("cube(", "module", "module m(", "x = [", "for (i = [0:", "translate([1,0,0])", "}}}", "function f(x) =", "[for (", "include <", "a = b ? c", "#", "if (")) {
            parseFile("incomplete", text)
        }
    }

    /** Ein Fehler in einer Zuweisung bleibt ein Fehler und reicht nicht in die nächste Zeile. */
    fun testErrorRecoveryStaysOnLine() {
        val psi = parseFile("recovery", "x = = 2;\ny = 1\ncube(2);")
        val errors = PsiTreeUtil.collectElementsOfType(psi, PsiErrorElement::class.java)
        assertEquals(errors.joinToString { it.errorDescription }, 2, errors.size)
        assertTrue(psi.node.getChildren(null).any { it.elementType == ScadTypes.MODULE_CALL && it.text == "cube(2);" })
    }

    fun testSampleFolder() {
        val folder = System.getProperty("scadsphere.samples") ?: return
        val files = File(folder).walkTopDown().filter { it.isFile && it.extension == "scad" }.toList()
        val failures = mutableListOf<String>()
        for (file in files) {
            val psi = parseFile(file.nameWithoutExtension, file.readText())
            val errors = PsiTreeUtil.collectElementsOfType(psi, PsiErrorElement::class.java)
            if (errors.isNotEmpty()) {
                val error = errors.first()
                val line = psi.text.substring(0, error.textOffset).count { it == '\n' } + 1
                failures += "${file.relativeTo(File(folder))}:$line: ${error.errorDescription} (near '${psi.text.drop(error.textOffset).take(30).replace('\n', ' ')}')"
            }
        }
        println("Parsed ${files.size} files, ${failures.size} with errors")
        failures.forEach(::println)
        assertTrue("${failures.size} files with syntax errors", failures.isEmpty())
    }

    private fun assertNoErrors(text: String) {
        val psi: PsiFile = parseFile("test", text)
        val errors = PsiTreeUtil.collectElementsOfType(psi, PsiErrorElement::class.java)
        assertTrue(errors.joinToString("\n") { "${it.errorDescription} at ${it.textOffset}: '${text.drop(it.textOffset).take(20)}'" }, errors.isEmpty())
        // Anweisungen landen als eigene Knoten unter der Datei
        assertTrue(psi.node.getChildren(null).any { it.elementType == ScadTypes.MODULE_DECLARATION })
    }
}
