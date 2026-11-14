## Settings

**Settings | Tools | SCAD Sphere** (IDE-wide)

| Option | Values | Default |
| --- | --- | --- |
| Application | path to the OpenSCAD executable | found automatically (usual locations on macOS, Windows, Linux) |
| Geometry backend | OpenSCAD default / Manifold (fast) / CGAL (classic); passed as `--backend` if the installed version knows it | OpenSCAD default |
| Library folders | one folder per line | – |
| Show the preview next to the editor when opening .scad files | on / off | on |
| Render | While typing / When saved / Only on request | While typing |
| Pause before rendering | 200–10000 ms | 800 ms |
| Highlight the call at the caret in the preview | on / off | on |
| Show echo output at the end of its line | on / off | on |
| Slicer: Application | path to Bambu Studio, OrcaSlicer, PrusaSlicer or UltiMaker Cura | first one found, in this order |
| Show the print bed in the 3D view | on / off | on |
| Printer | Bambu Lab X2D, X1 / P1 / A1, A1 mini, H2D · Prusa MK4, Core One, MINI · Creality Ender-3 V3 · Custom size | Bambu Lab X2D |
| Size (W × D × H) | mm | 256 × 256 × 260 |
| Position of the origin | Center of the bed (drawn under the lowest point of the model, only the size is checked) / Front left corner (parts outside the bed or below z = 0 are reported) | Center |

Parameter name hints: *Settings | Editor | Inlay Hints | Parameter names*.

## Library lookup

`include <…>` and `use <…>` are resolved in this order:

1. next to the file
2. *Library folders* from the settings
3. `OPENSCADPATH`
4. user library folder (`~/Documents/OpenSCAD/libraries` on macOS, `Documents\OpenSCAD\libraries` on Windows, `$XDG_DATA_HOME/OpenSCAD/libraries` (default `~/.local/share/…`) on Linux)
5. libraries of the OpenSCAD installation

OpenSCAD runs with the *Library folders* prepended to `OPENSCADPATH`, so preview and export find the same files.

## Installing libraries

A missing `include <…>`/`use <…>` whose first path segment is one of these folders offers *Download library … from
GitHub* (⌥⏎ / Alt+Enter). The ZIP of the branch is unpacked into the user library folder as `<folder>/`; an existing
folder is never overwritten.

| Folder | Repository | Branch | License |
| --- | --- | --- | --- |
| `BOSL2` | BelfrySCAD/BOSL2 | master | BSD-2-Clause |
| `BOSL` | revarbat/BOSL | master | BSD-2-Clause |
| `NopSCADlib` | nophead/NopSCADlib | master | GPL-3.0 |
| `MCAD` | openscad/MCAD | master | LGPL-2.1 |
| `Round-Anything` | Irev-Dev/Round-Anything | master | MIT |
| `threads-scad` | rcolyer/threads-scad | master | CC0-1.0 |
| `smooth-prim` | rcolyer/smooth-prim | master | CC0-1.0 |
| `scad-utils` | openscad/scad-utils | master | MIT |
| `YAPP_Box` | mrWheel/YAPP_Box | main | MIT |

## Customizer syntax

Parameters are assignments with a literal value (number, string, `true`/`false`, vector of up to 4 numbers) before the first line containing `{`. Same rules as OpenSCAD.

```openscad
/* [Size] */
// Width of the box
width = 40;        // [10:5:100]
height = 20;       // [50]
style = "round";   // [round, square]
wall = 2;          // [1.2:Thin, 2:Normal, 3:Strong]
label = "Box";     // 8
lid = true;

/* [Hidden] */
eps = 0.01;
```

| Comment after the value | Field |
| --- | --- |
| `// [min:step:max]`, `// [min:max]`, `// [max]` | slider |
| `// [a, b, c]` | drop-down |
| `// [value:Label, …]` | drop-down with labels |
| `// 8` (string) | text field, max. length |
| none, `true`/`false` | checkbox |
| none, number or vector | number fields |

| Element | Meaning |
| --- | --- |
| `/* [Group] */` | collapsible group |
| `/* [Hidden] */` | following parameters are not shown |
| `//` line above | description |

## Values and parameter sets

| What | Stored in |
| --- | --- |
| Changed values (per file) | workspace (`.idea/workspace.xml`), passed as `-D name=value` |
| Parameter sets | `<file>.json` next to the file, OpenSCAD GUI format |
| Last export target and options, "all parameter sets" | workspace / IDE |
| Export of all parameter sets | one file per set, `<name>-<set>.<ext>` next to the target, via `-p <file>.json -P <set>`; characters not allowed in file names become `_` |

*Write Values to Code* replaces the literals in the file (one undo step). Export and *Open in OpenSCAD* use the current values.

## Preview details

| Topic | Behavior |
| --- | --- |
| Unsaved text | sent via stdin, working directory = folder of the file |
| Output | 3MF with colors for 3D, SVG for 2D |
| Re-render triggers | typing / saving (see settings), changes to any file of the last dependency list |
| `#` / `%` parts | second run with `-o tree.csg`, only when the code contains `#` or `%` |
| Messages | errors and warnings at their line in the code until the next run |
| Call at the caret | caret on the name or arguments of a module call (not `echo`, `assert`, `let`, `each`, not with `*` or `!`): the call is wrapped in a marker color, one run writes the CSG tree, the marked subtree is rendered separately and shown in blue; the file is not changed |
| `echo` at the line | every `echo(…)` of the file gets its line as first argument for the preview run (same line, removed from the output); first three values of a line, then the count; not for included files |

## Inspections

*Settings | Editor | Inspections | OpenSCAD*, all on by default (warning).

| Inspection | Reports | Not reported | Quick fix |
| --- | --- | --- | --- |
| Unused parameter or variable | module/function parameters, variables in blocks, `let` variables | top-level variables, `for` variables, names starting with `$` or `_` | remove variable (blocks) |
| Variable assigned twice in the same scope | all but the last assignment (OpenSCAD uses the last value in the whole scope) | assignments in different files or blocks | remove assignment |
| Unused use statement | `use <…>` without any used module or function (including the files it includes) | `include` | remove statement |
| Missing argument | calls of own modules/functions without a parameter that has no default value | built-ins, parameters starting with `$` or `_` | – |
| Deprecated module or argument | `assign`, `child`, `import_stl`, `import_off`, `import_dxf`, `dxf_linear_extrude`, `dxf_rotate_extrude`; `import(filename =)`, `polyhedron(triangles =)`, `file`/`layer` of `linear_extrude`/`rotate_extrude` | own modules with these names | replace with `let`, `children`, `import`, `file`, `faces` |

## Intentions (⌥⏎ / Alt+Enter)

| Intention | Available |
| --- | --- |
| Create module / function | on an unknown name; parameters from the call (named arguments keep their name, others `a`, `b`, …) |
| Highlight with # / Remove # highlight | on the name of a module call |
| Inline variable | on a variable assigned once and only used in this file; adds parentheses where needed |
| Extract module | on selected statements (module calls, `if`); local names from outside become parameters; not with `children()` |

## Live templates

*Settings | Editor | Live Templates | OpenSCAD*

| Abbreviation | Expands to |
| --- | --- |
| `mod` / `fun` | module / function declaration |
| `for` | `for (i = [0 : 3]) { }` |
| `diff` | `difference()` with the cut part marked `#` |
| `uni`, `inter`, `hull` | `union()`, `intersection()`, `hull()` block |
| `tr`, `rot` | `translate([x, y, z])`, `rotate([x, y, z])` |
| `col` | `color("…")` |
| `cust` | Customizer group with a described slider parameter |
| `hid` | `/* [Hidden] */` with `$fn` |

## Open in Slicer

`.scad` files are exported as 3MF (colors, current Customizer values, last 3MF export options) to the IDE's temp
folder (`scad-sphere/<file>.3mf`) and opened in the slicer. `.stl`, `.3mf`, `.obj` and `.amf` files are passed directly.

## Measuring

| Mode | Points | Shows |
| --- | --- | --- |
| Distance | 2 | distance, Δx/Δy/Δz |
| Angle | 3, the second is the vertex | angle in degrees, length of both legs |
| Diameter | 3 on a circle (e.g. the edge of a hole) | diameter, radius, the circle; "–" if the points lie on a line |

Corners of the clicked triangle snap within 12 px. Choosing the active mode again or Esc ends measuring.

## MCP tools

Available when the IDE's MCP server is enabled (*Settings | Tools | MCP Server*); they run the OpenSCAD from the settings
with its library folders and backend. Paths are absolute or relative to the project root.

| Tool | Argument | Type | Default | Meaning |
| --- | --- | --- | --- | --- |
| `scad_render` | `filePath` | string | – | `.scad` file or mesh (`.stl`, `.3mf`, `.obj`, `.off`, `.amf`); unsaved editor content is used |
| | `views` | string | `iso` | comma separated: `iso`, `top`, `bottom`, `front`, `back`, `right`, `left`; empty = no images |
| | `parameters` | string | – | one `name=value` per line, values as OpenSCAD expressions (`-D`) |
| | `parameterSet` | string | – | name of a set from `<file>.json` |
| | `imageSize` | int | 800 | width and height in pixels, 200–2000 |
| | `outputDir` | string | temp folder | folder for the PNG files (`<name>-<view>.png`) |
| `scad_parameters` | `filePath` | string | – | lists Customizer parameters and parameter sets |
| `scad_export` | `filePath` | string | – | `.scad` file (saved state) |
| | `targetPath` | string | – | format from the extension: 3mf, stl, obj, off, amf, svg, dxf, pdf |
| | `parameters`, `parameterSet` | string | – | as for `scad_render` |

`scad_render` returns the result (ok, failed, empty, 2D), size and bounding box in mm, triangle count, volume (cm³) and
surface (cm²), the messages with their line and the paths of the images.
