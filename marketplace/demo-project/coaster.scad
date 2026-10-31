// Hexagonal coaster with two rings of holes.
diameter = 90;
height = 4;
hole = 6;

difference() {
    cylinder(d = diameter, h = height, $fn = 6);
    for (a = [0 : 60 : 359], r = [14, 28])
        rotate(a + r) translate([r, 0, -1])
            cylinder(d = hole, h = height + 2, $fn = 24);
}

assert(hole < diameter / 4, "hole too large");
echo(area = 3 * sqrt(3) / 8 * diameter ^ 2);
