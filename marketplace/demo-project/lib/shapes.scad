// Small helpers shared by the models.

/** Box with rounded vertical edges. */
module rounded_box(size, radius = 2) {
    r = min(radius, size.x / 2, size.y / 2);
    hull()
        for (x = [r, size.x - r], y = [r, size.y - r])
            translate([x, y, 0]) cylinder(h = size.z, r = r);
}

/** Flat plate with rounded corners. */
module base_plate(size, radius = 4) {
    rounded_box(size, radius);
}

function inner(size, wall) = [for (i = [0 : 2]) size[i] - 2 * wall];
