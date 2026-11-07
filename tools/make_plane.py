"""Generates the built-in soft-body trainer plane (metres, +Y up, +Z forward, +X to the pilot's left).

Part names drive the physics: engine (heavy), seat (where the pilot sits), propeller/spinner (the rotor, drawn
spinning, no nodes), aileron_left/aileron_right/elevator/rudder (control surfaces), wheel_* (rolling nodes).
"""
import math, sys

objects = []  # (name, material, verts, tris)

def add(name, mat, verts, tris):
    objects.append((name, mat, verts, tris))

def sub(a, b): return (a[0]-b[0], a[1]-b[1], a[2]-b[2])
def cross(a, b): return (a[1]*b[2]-a[2]*b[1], a[2]*b[0]-a[0]*b[2], a[0]*b[1]-a[1]*b[0])
def dot(a, b): return a[0]*b[0]+a[1]*b[1]+a[2]*b[2]
def centre(points): n = len(points); return tuple(sum(p[k] for p in points)/n for k in range(3))

def oriented(verts, tri, inside):
    a, b, c = (verts[i] for i in tri)
    n = cross(sub(b, a), sub(c, a))
    m = centre([a, b, c])
    return tri if dot(n, sub(m, inside)) >= 0 else (tri[0], tri[2], tri[1])

def loft(rings, cap0=True, cap1=True, skip=None):
    """Closed rings of equal size joined in order; triangles face away from each ring's centre."""
    verts, tris = [], []
    n = len(rings[0])
    centres = [centre(r) for r in rings]
    for r in rings: verts.extend(r)
    for i in range(len(rings) - 1):
        inside = centre([centres[i], centres[i+1]])
        for j in range(n):
            if skip and skip(i, j): continue
            a, b = i*n + j, i*n + (j+1) % n
            c, d = (i+1)*n + (j+1) % n, (i+1)*n + j
            tris.append(oriented(verts, (a, b, c), inside))
            tris.append(oriented(verts, (a, c, d), inside))
    for cap, i, other in ((cap0, 0, 1), (cap1, len(rings)-1, len(rings)-2)):
        if not cap: continue
        k = len(verts); verts.append(centres[i])
        # Outward along the loft: away from the neighbouring ring.
        away = sub(centres[i], centres[other])
        inside = sub(centres[i], away)
        for j in range(n):
            tris.append(oriented(verts, (k, i*n + j, i*n + (j+1) % n), inside))
    return verts, tris

def box(lo, hi):
    (x0, y0, z0), (x1, y1, z1) = lo, hi
    return loft([[(x0, y0, z), (x1, y0, z), (x1, y1, z), (x0, y1, z)] for z in (z0, z1)])

def strut(p, q, w):
    d = sub(q, p); l = math.sqrt(dot(d, d)); d = (d[0]/l, d[1]/l, d[2]/l)
    u = cross(d, (0, 0, 1)) if abs(d[2]) < 0.9 else cross(d, (1, 0, 0))
    lu = math.sqrt(dot(u, u)); u = (u[0]/lu, u[1]/lu, u[2]/lu)
    v = cross(d, u)
    ring = lambda c: [tuple(c[k] + (su*u[k] + sv*v[k]) * w for k in range(3)) for su, sv in ((-1, -1), (1, -1), (1, 1), (-1, 1))]
    return loft([ring(p), ring(q)])

def naca(f, t):
    return 5*t*(0.2969*math.sqrt(f) - 0.126*f - 0.3516*f*f + 0.2843*f**3 - 0.1036*f**4)

FRACS = [0, 0.03, 0.1, 0.2, 0.32, 0.45, 0.6, 0.72, 0.74, 0.86, 1.0]

def airfoil(f0, f1, chord, le, incidence, thick, place):
    """Section between chord fractions f0..f1: upper surface forwards to back, lower back to front.
    place(c, y) maps (distance behind the leading edge, height above the chord) to 3D."""
    fs = [f for f in FRACS if f0 - 1e-9 <= f <= f1 + 1e-9]
    if fs[0] > f0: fs.insert(0, f0)
    if fs[-1] < f1: fs.append(f1)
    ci, si = math.cos(incidence), math.sin(incidence)
    pts = []
    for f in fs: pts.append((f, naca(max(f, 1e-4), thick)))
    for f in reversed(fs): pts.append((f, -naca(max(f, 1e-4), thick)))
    if f0 == 0: pts = pts[:-1]  # leading edge point appears once
    out = []
    for f, y in pts:
        c = f * chord
        # Incidence: the trailing edge sits lower (rotation about the leading edge).
        out.append(place(c * ci + y * chord * si, y * chord * ci - c * si))
    return out

# ---------------------------------------------------------------- fuselage
STATIONS = [  # z, half width, bottom, top
    (-4.62, 0.06, 1.52, 1.70),
    (-3.9, 0.17, 1.38, 1.78),
    (-3.0, 0.29, 1.20, 1.85),
    (-1.9, 0.43, 1.03, 1.92),
    (-0.8, 0.53, 0.92, 1.97),
    (0.3, 0.56, 0.86, 2.00),
    (1.4, 0.56, 0.85, 2.00),
    (2.55, 0.50, 0.90, 1.90),
]
def section(z, w, yb, yt, sides=10):
    pts = []
    cy, hy = (yb + yt) / 2, (yt - yb) / 2
    for j in range(sides):
        a = 2 * math.pi * j / sides
        # Superellipse: flattish sides, rounded corners.
        cx, cyy = math.cos(a), math.sin(a)
        e = 0.6
        x = w * math.copysign(abs(cx) ** e, cx)
        y = cy + hy * math.copysign(abs(cyy) ** e, cyy)
        pts.append((x, y, z))
    return pts
rings = [section(*s) for s in STATIONS]
# Open cockpit: no skin on top between z = -0.8 and 0.3 (ring segment 4, the top faces).
def cockpit(i, j):
    return i == 4 and j in (2, 3)
v, t = loft(rings, cap0=True, cap1=False, skip=cockpit)
add("fuselage", "white", v, t)

# Engine cowling (heavy), spinner and propeller.
v, t = loft([section(2.55, 0.50, 0.90, 1.90), section(3.0, 0.46, 0.98, 1.82), section(3.32, 0.33, 1.10, 1.66)])
add("engine", "grey", v, t)
HUB = (0.0, 1.38, 3.42)
spin = []
for k, (dz, r) in enumerate(((-0.1, 0.2), (0.12, 0.17), (0.28, 0.08), (0.34, 0.012))):
    spin.append([(HUB[0] + r * math.cos(2*math.pi*j/10), HUB[1] + r * math.sin(2*math.pi*j/10), HUB[2] + dz) for j in range(10)])
v, t = loft(spin)
add("spinner", "red", v, t)
R, PITCH = 0.95, 1.55
blade_v, blade_t = [], []
for side in (1, -1):
    secs = []
    for r in (0.14, 0.3, 0.5, 0.7, 0.85, 0.95):
        theta = math.atan(PITCH / (2 * math.pi * max(r, 0.2)))
        chord = 0.15 * (1 - 0.45 * (r / R) ** 2) + 0.03
        # Blade along +-Y; it moves along -X at +Y (clockwise from the cockpit), leading edge first and forwards.
        motion = (-side, 0, 0)
        le = (motion[0] * math.cos(theta), 0, math.sin(theta))
        th = 0.022 * (1.2 - r / R)
        nrm = (-le[2] * (1 if motion[0] < 0 else 1), 0, le[0]) if False else (le[2] * -motion[0] * 0 + 0, 0, 0)
        # Section: thin rectangle, chord along le, thickness along the blade's face normal.
        n = (-le[2], 0, le[0]) if le[0] != 0 else (1, 0, 0)
        y = HUB[1] + side * r
        def P(c, s):
            return (HUB[0] + le[0] * c + n[0] * s, y, HUB[2] + le[2] * c + n[2] * s)
        secs.append([P(0.3 * chord, -th), P(0.3 * chord, th), P(-0.7 * chord, th / 2), P(-0.7 * chord, -th / 2)])
    v, t = loft(secs)
    off = len(blade_v)
    blade_v += v
    blade_t += [(a + off, b + off, c + off) for a, b, c in t]
add("propeller", "wood", blade_v, blade_t)

# Seat (the pilot sits on top of the cushion) and windscreen.
v, t = box((-0.28, 1.0, -0.55), (0.28, 1.32, -0.02))
add("seat", "dark", v, t)
v, t = box((-0.28, 1.0, -0.66), (0.28, 1.85, -0.56))
add("seat_back", "dark", v, t)
ws = [(-0.5, 1.99, 0.38), (0.5, 1.99, 0.38), (0.42, 2.34, 0.16), (-0.42, 2.34, 0.16)]
ws2 = [(p[0], p[1], p[2] + 0.025) for p in ws]
v, t = loft([ws, ws2])
add("windscreen", "glass", v, t)

# ---------------------------------------------------------------- wings
DIHEDRAL = math.radians(4)
def wing(side):
    # Stations along the span: x, chord, leading-edge z.
    def at(x):
        f = (x - 0.0) / 5.0
        return 1.62 - 0.5 * f, 0.95 - 0.18 * f
    inner, outer, ail = [], [], []
    for x in (0.0, 0.5, 1.3, 2.1, 2.7):
        ch, le = at(x)
        y0 = 1.0 + x * math.tan(DIHEDRAL)
        place = lambda c, y, x=x, le=le, y0=y0: (side * x, y0 + y, le - c)
        inner.append(airfoil(0, 1, ch, le, math.radians(3), 0.13, place))
    for x in (2.7, 3.5, 4.3, 4.85):
        ch, le = at(x)
        y0 = 1.0 + x * math.tan(DIHEDRAL)
        place = lambda c, y, x=x, le=le, y0=y0: (side * x, y0 + y, le - c)
        outer.append(airfoil(0, 0.72, ch, le, math.radians(3), 0.13, place))
        ail.append(airfoil(0.74, 1.0, ch, le, math.radians(3), 0.13, place))
    name = "left" if side > 0 else "right"
    v1, t1 = loft(inner)
    v2, t2 = loft(outer)
    add("wing_" + name, "red", v1 + v2, t1 + [(a + len(v1), b + len(v1), c + len(v1)) for a, b, c in t2])
    v, t = loft(ail)
    add("aileron_" + name, "yellow", v, t)
wing(1)
wing(-1)

# ---------------------------------------------------------------- tail
TAIL = math.radians(-6)  # the tailplane pushes down a little at cruise
def hplace(x):
    return lambda c, y: (x, 1.62 + y, -3.72 - c)
stab = [airfoil(0, 0.58, 0.95 - 0.25 * abs(x) / 1.75, -3.72, TAIL, 0.1, lambda c, y, x=x: (x, 1.62 + y, -3.72 - 0.06 * abs(x) - c)) for x in (-1.75, -0.9, 0, 0.9, 1.75)]
elev = [airfoil(0.6, 1.0, 0.95 - 0.25 * abs(x) / 1.75, -3.72, TAIL, 0.1, lambda c, y, x=x: (x, 1.62 + y, -3.72 - 0.06 * abs(x) - c)) for x in (-1.75, -0.9, 0, 0.9, 1.75)]
v, t = loft(stab); add("stabilizer", "red", v, t)
v, t = loft(elev); add("elevator", "yellow", v, t)
def fin_sec(f0, f1, y):
    k = (y - 1.7) / 1.25
    chord, le = 1.05 - 0.45 * k, -3.55 - 0.5 * k
    return airfoil(f0, f1, chord, le, 0, 0.1, lambda c, s: (s, y, le - c))
v, t = loft([fin_sec(0, 0.58, y) for y in (1.7, 2.3, 2.95)]); add("fin", "red", v, t)
v, t = loft([fin_sec(0.6, 1.0, y) for y in (1.7, 2.3, 2.95)]); add("rudder", "yellow", v, t)

# ---------------------------------------------------------------- landing gear
def wheel(name, c, r, width):
    rings = []
    for dx in (-width / 2, width / 2):
        rings.append([(c[0] + dx, c[1] + r * math.cos(2*math.pi*j/14), c[2] + r * math.sin(2*math.pi*j/14)) for j in range(14)])
    v, t = loft(rings)
    add(name, "dark", v, t)
wheel("wheel_nose", (0, 0.25, 2.25), 0.25, 0.16)
v, t = strut((0, 0.25, 2.25), (0, 0.92, 2.1), 0.07); add("gear_nose", "grey", v, t)
for side, name in ((1, "left"), (-1, "right")):
    wheel("wheel_" + name, (side * 1.25, 0.3, -0.42), 0.3, 0.2)
    v, t = strut((side * 1.25, 0.3, -0.42), (side * 0.95, 1.06, -0.15), 0.08); add("gear_" + name, "grey", v, t)

# ---------------------------------------------------------------- write
out = sys.argv[1]
with open(out + ".obj", "w", newline="\n") as f:
    f.write("# Physicalist trainer: generated by make_plane.py\nmtllib plane.mtl\n")
    base = 1
    for name, mat, verts, tris in objects:
        f.write(f"o {name}\nusemtl {mat}\n")
        for p in verts: f.write("v %.4f %.4f %.4f\n" % p)
        for a, b, c in tris: f.write(f"f {a + base} {b + base} {c + base}\n")
        base += len(verts)
with open(out + ".mtl", "w", newline="\n") as f:
    for name, kd, d in (("white", "0.93 0.93 0.91", 1), ("red", "0.78 0.13 0.1", 1), ("yellow", "0.95 0.75 0.15", 1), ("grey", "0.5 0.52 0.55", 1),
                        ("dark", "0.12 0.12 0.13", 1), ("wood", "0.45 0.3 0.17", 1), ("glass", "0.55 0.75 0.95", 0.45)):
        f.write(f"newmtl {name}\nKd {kd}\n" + (f"d {d}\n" if d < 1 else ""))
# Area centroid (mass is spread over the surface; the engine counts ENGINE times).
ENGINE = float(sys.argv[2]) if len(sys.argv) > 2 else 1
tot = [0, 0, 0, 0]
for name, mat, verts, tris in objects:
    if name in ("propeller", "spinner"): continue
    w = ENGINE if name == "engine" else 1
    for a, b, c in tris:
        A = math.sqrt(dot(*(lambda n: (n, n))(cross(sub(verts[b], verts[a]), sub(verts[c], verts[a]))))) / 2 * w
        m = centre([verts[a], verts[b], verts[c]])
        for k in range(3): tot[k] += m[k] * A
        tot[3] += A
print("weighted area %.1f, centroid z %.3f y %.3f" % (tot[3], tot[2] / tot[3], tot[1] / tot[3]))
