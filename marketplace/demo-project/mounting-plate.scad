// Mounting plate: four countersunk screw holes and a grommet ring around the cable hole.
width = 60;          // [40:5:100]
depth = 40;          // [30:5:80]
thickness = 6;
screw = 4;           // [3:0.5:6]

$fn = 48;

difference() {
    cube([width, depth, thickness]);
    for (x = [8, width - 8], y = [8, depth - 8])
        translate([x, y, 0]) {
            translate([0, 0, -1]) cylinder(d = screw, h = thickness + 2);
            // 90° countersink
            translate([0, 0, thickness - screw / 2]) cylinder(d1 = screw, d2 = screw * 2, h = screw / 2 + 0.01);
        }
    translate([width / 2, depth / 2, -1]) cylinder(d = 14, h = thickness + 2);
}

// Grommet ring
translate([width / 2, depth / 2, thickness]) rotate_extrude() translate([9, 0]) circle(r = 2);
