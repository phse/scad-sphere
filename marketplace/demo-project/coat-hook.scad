// Coat hook with a pin hanging down from its arm: the print check
// shows where a layer would start in mid-air, "Add support pillars"
// puts a breakaway pillar under it.
width = 30;
plate = 6;
arm = 50;
pin_d = 8;

$fn = 48;

// Wall plate with two screw holes
difference() {
    cube([width, plate, 80]);
    for (z = [15, 65]) translate([width / 2, -1, z]) rotate([-90, 0, 0]) cylinder(d = 4.5, h = plate + 2);
}
// Arm sticking out of the wall
translate([0, plate, 62]) cube([width, arm, 10]);
// Pin hanging down at the end of the arm
translate([width / 2, plate + arm - pin_d, 40]) {
    cylinder(d = pin_d, h = 22);
    sphere(d = pin_d + 4);
}
