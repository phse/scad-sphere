// Key rack, too long for small print beds: split it in the section
// view (scissors button) and join the two parts with pins.
length = 240;        // [160:10:320]
height = 50;
thickness = 12;
hooks = 6;           // [3:1:9]

$fn = 48;

module board() {
    difference() {
        hull()
            for (x = [6, length - 6], y = [6, height - 6])
                translate([x, y, 0]) cylinder(r = 6, h = thickness);
        // Countersunk screw holes
        for (x = [18, length - 18]) translate([x, height * 0.7, 0]) {
            translate([0, 0, -1]) cylinder(d = 4.5, h = thickness + 2);
            translate([0, 0, thickness - 3]) cylinder(d1 = 4.5, d2 = 9, h = 3.01);
        }
    }
}

module peg() {
    cylinder(d = 8, h = 22);
    translate([0, 0, 22]) cylinder(d1 = 8, d2 = 12, h = 4);
}

board();
spacing = (length - 70) / (hooks - 1);
for (i = [0:hooks - 1])
    translate([35 + i * spacing, height * 0.35, thickness]) peg();
