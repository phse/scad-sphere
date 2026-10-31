// Phone stand with a cable slot.
include <lib/shapes.scad>

/* [Size] */
width = 80;          // [60:5:120]
depth = 70;
angle = 65;          // [50:5:80]
thickness = 4;

/* [Cable] */
cable_slot = true;
slot_width = 12;

$fn = 48;

module stand() {
    color("SteelBlue") base_plate([width, depth, thickness], radius = 6);
    color("Orange") translate([0, depth * 0.35, thickness])
        rotate([angle - 90, 0, 0])
            back_rest();
    color("Orange") lip();
}

/** Back rest, tilted by `angle`. */
module back_rest() {
    difference() {
        rounded_box([width, thickness, depth * 1.1], radius = 2);
        if (cable_slot)
            translate([(width - slot_width) / 2, -1, -1])
                cube([slot_width, thickness + 2, depth * 0.4]);
    }
}

module lip(height = 12) {
    translate([0, 0, thickness])
        rounded_box([width, thickness, height], radius = 1.5);
}

stand();
echo(str("Footprint: ", width, " × ", depth, " mm"));
