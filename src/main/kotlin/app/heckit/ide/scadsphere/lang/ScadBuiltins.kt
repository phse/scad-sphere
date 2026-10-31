package app.heckit.ide.scadsphere.lang

/**
 * Eingebaute Module, Funktionen und Variablen von OpenSCAD mit Signatur und kurzer Beschreibung
 * (für Vervollständigung, Parameter-Info und Dokumentation). Die Texte folgen dem OpenSCAD-Handbuch.
 */
object ScadBuiltins {
    enum class Kind { MODULE, FUNCTION, VARIABLE }

    class Builtin(
        val name: String,
        val kind: Kind,
        /** Parameter, wie sie in der Signatur stehen, z. B. `size = 1` */
        val params: List<String>,
        val description: String,
        /** Seite im OpenSCAD User Manual (Wikibooks), mit Anker */
        val page: String,
        val experimental: Boolean = false,
    ) {
        val signature: String get() = if (kind == Kind.VARIABLE) name else "$name(${params.joinToString(", ")})"
        val url: String get() = "https://en.wikibooks.org/wiki/OpenSCAD_User_Manual/$page"
        /** Reine Parameternamen (für benannte Argumente) */
        val paramNames: List<String>
            get() = params.flatMap { it.split('|') }.map { it.substringBefore('=').trim() }
                .filter { it.isNotEmpty() && it != "…" && !it.endsWith("…") }
    }

    private fun module(name: String, params: String, page: String, description: String, experimental: Boolean = false) =
        Builtin(name, Kind.MODULE, split(params), description, page, experimental)

    private fun function(name: String, params: String, page: String, description: String, experimental: Boolean = false) =
        Builtin(name, Kind.FUNCTION, split(params), description, page, experimental)

    private fun variable(name: String, page: String, description: String) =
        Builtin(name, Kind.VARIABLE, emptyList(), description, page)

    private fun split(params: String): List<String> = if (params.isBlank()) emptyList() else params.split(", ").map { it.trim() }

    val modules: Map<String, Builtin> = listOf(
        module("cube", "size = 1, center = false", "Primitive_Solids#cube",
            "Creates a cube or cuboid in the first octant. <code>size</code> is a single number or <code>[x, y, z]</code>; with <code>center = true</code> it is centered on the origin."),
        module("sphere", "r = 1 | d", "Primitive_Solids#sphere",
            "Creates a sphere at the origin, with radius <code>r</code> or diameter <code>d</code>. Resolution via <code>\$fn</code>, <code>\$fa</code>, <code>\$fs</code>."),
        module("cylinder", "h = 1, r1, r2, center = false | r | d | d1 | d2", "Primitive_Solids#cylinder",
            "Creates a cylinder or cone along the z axis. <code>r1</code>/<code>d1</code> at the bottom, <code>r2</code>/<code>d2</code> at the top; with <code>center = true</code> centered in z."),
        module("polyhedron", "points, faces, convexity = 1", "Primitive_Solids#polyhedron",
            "Creates a solid from a list of points and faces (point indices, clockwise when looking at the face from outside)."),
        module("square", "size = 1, center = false", "Using_the_2D_Subsystem#square",
            "Creates a 2D square or rectangle in the first quadrant. <code>size</code> is a number or <code>[x, y]</code>."),
        module("circle", "r = 1 | d", "Using_the_2D_Subsystem#circle",
            "Creates a 2D circle at the origin, with radius <code>r</code> or diameter <code>d</code>."),
        module("polygon", "points, paths, convexity = 1", "Using_the_2D_Subsystem#polygon",
            "Creates a 2D shape from a list of <code>[x, y]</code> points; optional <code>paths</code> define holes."),
        module("text", "text, size = 10, font, halign = \"left\", valign = \"baseline\", spacing = 1, direction = \"ltr\", language = \"en\", script = \"latin\"", "Text",
            "Creates 2D text. <code>halign</code>: left, center, right; <code>valign</code>: top, center, baseline, bottom."),
        module("import", "file, convexity, layer, center = false, dpi = 96, id", "Importing_Geometry#import",
            "Imports geometry from a file: STL, 3MF, OFF, OBJ, AMF (3D) or DXF, SVG (2D). Relative paths start at the folder of the .scad file."),
        module("surface", "file, center = false, invert = false, convexity", "Other_Language_Features#Surface",
            "Creates a height map from a text file of numbers or a PNG image."),
        module("translate", "v", "Transformations#translate",
            "Moves the children by the vector <code>v = [x, y, z]</code>."),
        module("rotate", "a, v", "Transformations#rotate",
            "Rotates the children by <code>a</code> degrees. <code>a = [x, y, z]</code> rotates about the axes in this order; with <code>v</code> about that axis."),
        module("scale", "v", "Transformations#scale",
            "Scales the children by <code>v = [x, y, z]</code> (or one number for all axes)."),
        module("resize", "newsize, auto = false, convexity", "Transformations#resize",
            "Scales the children to the size <code>newsize = [x, y, z]</code>; 0 keeps an axis, <code>auto</code> scales it proportionally."),
        module("mirror", "v", "Transformations#mirror",
            "Mirrors the children on the plane through the origin with the normal <code>v</code>."),
        module("multmatrix", "m", "Transformations#multmatrix",
            "Transforms the children with the 4×4 (or 4×3) affine matrix <code>m</code>."),
        module("color", "c, alpha = 1.0", "Transformations#color",
            "Colors the children: a name (<code>\"red\"</code>), <code>\"#rrggbb\"</code> or <code>[r, g, b, a]</code> with values from 0 to 1."),
        module("offset", "r | delta, chamfer = false", "Transformations#offset",
            "Grows or shrinks a 2D shape: <code>r</code> with round corners, <code>delta</code> with sharp (or chamfered) corners."),
        module("hull", "", "Transformations#hull",
            "Creates the convex hull of the children."),
        module("minkowski", "convexity", "Transformations#minkowski",
            "Creates the Minkowski sum of the children, e.g. a cube with rounded edges."),
        module("union", "", "CSG_Modelling#union",
            "Combines all children into one object."),
        module("difference", "", "CSG_Modelling#difference",
            "Subtracts the second and all further children from the first."),
        module("intersection", "", "CSG_Modelling#intersection",
            "Keeps only the part that all children have in common."),
        module("linear_extrude", "height, v, center = false, convexity, twist = 0, slices, scale = 1, segments", "2D_to_3D_Extrusion#Linear_Extrude",
            "Extrudes 2D children along the z axis (or <code>v</code>), optionally twisted and scaled."),
        module("rotate_extrude", "angle = 360, start, convexity", "2D_to_3D_Extrusion#Rotate_Extrude",
            "Rotates 2D children (in the positive x half) about the z axis to a solid of revolution."),
        module("projection", "cut = false", "3D_to_2D_Projection",
            "Projects 3D children onto the xy plane; with <code>cut = true</code> only the section at z = 0."),
        module("render", "convexity", "Other_Language_Features#Render",
            "Renders the children fully, also in the preview (slower preview, but correct for complex CSG)."),
        module("children", "index", "User-Defined_Functions_and_Modules#Children",
            "Inside a module: the children passed to it, all or by <code>index</code> (number, vector or range)."),
        module("echo", "…", "Other_Language_Features#Echo_module",
            "Prints values to the console."),
        module("assert", "condition, message", "User-Defined_Functions_and_Modules#Assert",
            "Stops with an error when <code>condition</code> is false."),
        module("for", "variable = range_or_list", "Conditional_and_Iterator_Functions#For_Loop",
            "Repeats the children for each value of a range <code>[start : step : end]</code> or list."),
        module("intersection_for", "variable = range_or_list", "Conditional_and_Iterator_Functions#Intersection_For_Loop",
            "Like <code>for</code>, but intersects the results instead of combining them."),
        module("let", "variable = value, …", "User-Defined_Functions_and_Modules#Let_Statement",
            "Defines variables for its children."),
        module("roof", "method = \"voronoi\", convexity", "Transformations#roof",
            "Creates a roof-like solid from a 2D shape (experimental).", experimental = true),
        module("fill", "", "Transformations#fill",
            "Removes holes from 2D children."),
    ).associateBy { it.name }

    val functions: Map<String, Builtin> = listOf(
        function("abs", "x", "Mathematical_Functions#abs", "Absolute value."),
        function("sign", "x", "Mathematical_Functions#sign", "-1, 0 or 1 depending on the sign."),
        function("sin", "degrees", "Mathematical_Functions#sin", "Sine of an angle in degrees."),
        function("cos", "degrees", "Mathematical_Functions#cos", "Cosine of an angle in degrees."),
        function("tan", "degrees", "Mathematical_Functions#tan", "Tangent of an angle in degrees."),
        function("asin", "x", "Mathematical_Functions#asin", "Arc sine in degrees."),
        function("acos", "x", "Mathematical_Functions#acos", "Arc cosine in degrees."),
        function("atan", "x", "Mathematical_Functions#atan", "Arc tangent in degrees."),
        function("atan2", "y, x", "Mathematical_Functions#atan2", "Angle of the vector <code>[x, y]</code> in degrees (-180 to 180)."),
        function("floor", "x", "Mathematical_Functions#floor", "Largest integer not greater than x."),
        function("ceil", "x", "Mathematical_Functions#ceil", "Smallest integer not less than x."),
        function("round", "x", "Mathematical_Functions#round", "Rounds to the nearest integer (.5 away from zero)."),
        function("ln", "x", "Mathematical_Functions#ln", "Natural logarithm."),
        function("log", "x | base, x", "Mathematical_Functions#log", "Logarithm to base 10 (or <code>base</code>)."),
        function("exp", "x", "Mathematical_Functions#exp", "e to the power of x."),
        function("pow", "base, exponent", "Mathematical_Functions#pow", "Power; same as <code>base ^ exponent</code>."),
        function("sqrt", "x", "Mathematical_Functions#sqrt", "Square root."),
        function("min", "values…", "Mathematical_Functions#min", "Smallest of the arguments or of a vector."),
        function("max", "values…", "Mathematical_Functions#max", "Largest of the arguments or of a vector."),
        function("norm", "v", "Mathematical_Functions#norm", "Length (Euclidean norm) of a vector."),
        function("cross", "a, b", "Mathematical_Functions#cross", "Cross product of two 3D (or 2D) vectors."),
        function("len", "value", "Mathematical_Functions#len", "Number of elements of a vector or characters of a string."),
        function("concat", "lists…", "Mathematical_Functions#concat", "Joins vectors into one."),
        function("lookup", "key, table", "Mathematical_Functions#lookup", "Linear interpolation in a table of <code>[key, value]</code> pairs."),
        function("rands", "min, max, count, seed", "Mathematical_Functions#rands", "Vector of <code>count</code> random numbers."),
        function("str", "values…", "String_Functions#str", "Converts all arguments to strings and joins them."),
        function("chr", "codes…", "String_Functions#chr", "String of the given Unicode code points."),
        function("ord", "character", "String_Functions#ord", "Unicode code point of a single character."),
        function("search", "match, string_or_vector, num_returns_per_match = 1, index_col_num = 0", "Other_Language_Features#Search",
            "Finds values in a vector or string and returns their indices."),
        function("version", "", "Other_Language_Features#version", "OpenSCAD version as <code>[year, month, day]</code>."),
        function("version_num", "", "Other_Language_Features#version_num", "OpenSCAD version as a number, e.g. 20210100."),
        function("parent_module", "n", "Other_Language_Features#parent_module", "Name of the module n levels up the call stack."),
        function("is_undef", "x", "Type_Test_Functions", "True if x is undef."),
        function("is_bool", "x", "Type_Test_Functions", "True if x is a boolean."),
        function("is_num", "x", "Type_Test_Functions", "True if x is a number (not NaN)."),
        function("is_string", "x", "Type_Test_Functions", "True if x is a string."),
        function("is_list", "x", "Type_Test_Functions", "True if x is a vector."),
        function("is_function", "x", "Type_Test_Functions", "True if x is a function literal."),
        function("is_object", "x", "Type_Test_Functions", "True if x is an object (experimental).", experimental = true),
        function("object", "…", "Other_Language_Features", "Creates an object with named members (experimental).", experimental = true),
        function("textmetrics", "text, size = 10, font, halign, valign, spacing = 1, direction, language, script", "Text#textmetrics",
            "Size and position of text without creating it (experimental).", experimental = true),
        function("fontmetrics", "size = 10, font", "Text#fontmetrics", "Metrics of a font (experimental).", experimental = true),
        function("import", "file", "Importing_Geometry", "Reads data from a JSON file (experimental).", experimental = true),
    ).associateBy { it.name }

    val variables: Map<String, Builtin> = listOf(
        variable("\$fn", "Other_Language_Features#\$fn", "Fixed number of fragments for circles, spheres and cylinders (overrides <code>\$fa</code> and <code>\$fs</code>)."),
        variable("\$fa", "Other_Language_Features#\$fa", "Minimum angle of a fragment in degrees (default 12)."),
        variable("\$fs", "Other_Language_Features#\$fs", "Minimum size of a fragment in mm (default 2)."),
        variable("\$t", "Other_Language_Features#\$t", "Animation step, from 0 to 1."),
        variable("\$preview", "Other_Language_Features#\$preview", "True in the preview (F5), false when rendering or exporting."),
        variable("\$children", "User-Defined_Functions_and_Modules#Children", "Number of children passed to the current module."),
        variable("\$parent_modules", "Other_Language_Features#parent_module", "Number of modules in the call stack."),
        variable("\$vpr", "Other_Language_Features#\$vpr", "Viewport rotation."),
        variable("\$vpt", "Other_Language_Features#\$vpt", "Viewport translation."),
        variable("\$vpd", "Other_Language_Features#\$vpd", "Viewport camera distance."),
        variable("\$vpf", "Other_Language_Features#\$vpf", "Viewport field of view."),
        variable("PI", "Mathematical_Functions#PI", "The constant π."),
    ).associateBy { it.name }

    fun module(name: String): Builtin? = modules[name]
    fun function(name: String): Builtin? = functions[name]
    fun variable(name: String): Builtin? = variables[name]
}
