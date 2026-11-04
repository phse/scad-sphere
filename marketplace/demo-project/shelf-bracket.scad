// Shelf bracket: screw holes marked with #, the shelf board as % (not printed).
width = 40;          // [30:5:60]
height = 40;
depth = 30;
thickness = 4;
hole = 5;            // [3:0.5:6]

$fn = 32;

module screw_hole() #cylinder(d = hole, h = thickness * 3, center = true);

difference() {
    union() {
        cube([width, depth, thickness]);
        cube([width, thickness, height]);
        // Gusset in the middle
        hull() {
            translate([width / 2 - 2, thickness, thickness]) cube([4, 0.1, height * 0.7]);
            translate([width / 2 - 2, thickness, thickness]) cube([4, depth * 0.7, 0.1]);
        }
    }
    for (x = [width * 0.25, width * 0.75]) {
        translate([x, thickness / 2, height * 0.7]) rotate([90, 0, 0]) screw_hole();
        translate([x, depth * 0.7, thickness / 2]) screw_hole();
    }
}

%translate([-10, 0, thickness]) cube([width + 20, depth + 20, 18]);
