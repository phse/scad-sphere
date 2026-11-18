// Wall hook: back plate with two screw holes and an arm with a raised tip.
width = 24;          // [16:2:40]
height = 60;
thickness = 4;
reach = 32;          // [20:2:50]
arm = 8;             // [6:1:12]

$fn = 48;

module plate() {
    difference() {
        translate([-width / 2, 0, 0]) cube([width, thickness, height]);
        for (z = [12, height - 12])
            translate([0, -1, z]) rotate([-90, 0, 0]) cylinder(d = 4.5, h = thickness + 2);
    }
}

module hook_arm() {
    translate([0, thickness, arm / 2 + 6]) rotate([-90, 0, 0]) cylinder(d = arm, h = reach);
    // Raised tip so nothing slides off
    translate([0, thickness + reach, arm / 2 + 6]) sphere(d = arm);
    translate([0, thickness + reach, arm / 2 + 6]) cylinder(d = arm, h = 10);
}

plate();
hook_arm();
