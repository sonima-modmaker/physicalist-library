"""Render transparent side-profile sprites from Create: The Air War's own model JSONs.

Run with the bundled Python runtime (Pillow + NumPy installed):
    python render_ordnance.py PATH_TO_CREATE_THE_AIR_WAR_ASSETS

The output is checked in so GitHub Pages needs no Python or game assets at runtime.
"""

from __future__ import annotations

import argparse
import json
import math
from pathlib import Path

import numpy as np
from PIL import Image, ImageOps


ROCKETS = {
    "c75": ("exact_models/c-75.json", "block/c-75.png"),
    "c25": ("models/custom/c-25active.json", "block/c-25.png"),
    "aim9x": ("models/custom/aim9xactive.json", "block/aim9x.png"),
    "rim7": ("models/custom/rim-7active.json", "block/rim-7.png"),
    "nine_k_119m": ("models/custom/9k119mactive.json", "block/9k119m.png"),
    "kh101": ("exact_models/kh101.json", "entity/kh101.png"),
    "x25ml": ("models/custom/x25ml_entity.json", "block/x25ml.png"),
    "vihr": ("models/custom/vihr_rocket_exact.json", "block/vihr_rocket.png"),
    "tomahawk": ("models/custom/tomahawk_true.json", "block/tomahawk.png"),
    "s8": ("models/custom/c-8_rocket.json", "block/c-8.png"),
}
VANILLA_LAYOUT = {"c25", "aim9x", "rim7", "nine_k_119m", "x25ml", "tomahawk"}

FACE_VERTICES = {
    "north": lambda a, b: [(b[0], b[1], a[2]), (a[0], b[1], a[2]), (a[0], a[1], a[2]), (b[0], a[1], a[2])],
    "south": lambda a, b: [(a[0], b[1], b[2]), (b[0], b[1], b[2]), (b[0], a[1], b[2]), (a[0], a[1], b[2])],
    "east": lambda a, b: [(b[0], b[1], a[2]), (b[0], b[1], b[2]), (b[0], a[1], b[2]), (b[0], a[1], a[2])],
    "west": lambda a, b: [(a[0], b[1], b[2]), (a[0], b[1], a[2]), (a[0], a[1], a[2]), (a[0], a[1], b[2])],
    "up": lambda a, b: [(a[0], b[1], a[2]), (b[0], b[1], a[2]), (b[0], b[1], b[2]), (a[0], b[1], b[2])],
    "down": lambda a, b: [(a[0], a[1], b[2]), (b[0], a[1], b[2]), (b[0], a[1], a[2]), (a[0], a[1], a[2])],
}
VANILLA_VERTICES = {
    "down": lambda a, b: [(a[0], a[1], b[2]), (a[0], a[1], a[2]), (b[0], a[1], a[2]), (b[0], a[1], b[2])],
    "up": lambda a, b: [(a[0], b[1], a[2]), (a[0], b[1], b[2]), (b[0], b[1], b[2]), (b[0], b[1], a[2])],
    "north": lambda a, b: [(b[0], b[1], a[2]), (b[0], a[1], a[2]), (a[0], a[1], a[2]), (a[0], b[1], a[2])],
    "south": lambda a, b: [(a[0], b[1], b[2]), (a[0], a[1], b[2]), (b[0], a[1], b[2]), (b[0], b[1], b[2])],
    "west": lambda a, b: [(a[0], b[1], a[2]), (a[0], a[1], a[2]), (a[0], a[1], b[2]), (a[0], b[1], b[2])],
    "east": lambda a, b: [(b[0], b[1], b[2]), (b[0], a[1], b[2]), (b[0], a[1], a[2]), (b[0], b[1], a[2])],
}
FACE_NORMALS = {
    "north": (0, 0, -1), "south": (0, 0, 1),
    "east": (1, 0, 0), "west": (-1, 0, 0),
    "up": (0, 1, 0), "down": (0, -1, 0),
}


def rotated(vertex: np.ndarray, rotation: dict, vihr: bool) -> np.ndarray:
    if not rotation:
        return vertex
    origin = np.asarray(rotation.get("origin", [0, 0, 0]), dtype=float)
    result = vertex - origin
    if "axis" in rotation:
        angles = {rotation["axis"]: float(rotation.get("angle", 0))}
    else:
        angles = {axis: float(rotation.get(axis, 0)) for axis in "xyz"}
    order = "xyz" if vihr else "zyx"
    for axis in order:
        theta = math.radians(angles.get(axis, 0))
        if not theta:
            continue
        c, s = math.cos(theta), math.sin(theta)
        x, y, z = result
        if axis == "x":
            result = np.array([x, c * y - s * z, s * y + c * z])
        elif axis == "y":
            result = np.array([c * x + s * z, y, -s * x + c * z])
        else:
            result = np.array([c * x - s * y, s * x + c * y, z])
    return result + origin


def face_uv(face: dict, direction: str, vanilla: bool) -> np.ndarray:
    if "ctaw_vertices" in face:
        return np.asarray([vertex[3:5] for vertex in face["ctaw_vertices"]], dtype=float)
    u1, v1, u2, v2 = (float(value) / 16 for value in face.get("uv", [0, 0, 16, 16]))
    if vanilla:
        corners = [(u1, v1), (u1, v2), (u2, v2), (u2, v1)]
        turns = (int(face.get("rotation", 0)) // 90) % 4
        return np.asarray([corners[(index + turns) % 4] for index in range(4)])
    # The exact in-game renderer reverses U on north-facing quads.  Without
    # this, the nose and fin artwork is sampled from the wrong atlas side.
    if direction == "north":
        uv = np.array([[u2, v1], [u1, v1], [u1, v2], [u2, v2]], dtype=float)
    else:
        uv = np.array([[u1, v1], [u2, v1], [u2, v2], [u1, v2]], dtype=float)
    for _ in range((int(face.get("rotation", 0)) // 90) % 4):
        uv = uv[[3, 0, 1, 2]]
    return uv


def load_faces(model: dict, vihr: bool, vanilla: bool, correct_wing_uv: bool):
    faces = []
    for element in model.get("elements", []):
        a, b = element["from"], element["to"]
        rotation = element.get("ctaw_rotation", element.get("rotation", {}))
        for direction, face in element.get("faces", {}).items():
            if direction not in FACE_VERTICES:
                continue
            # Only the near side belongs in a side-profile sprite.  The game
            # draws many thin wings without face culling, which otherwise puts
            # their reverse artwork on top and makes the texture shimmer.
            normal = rotated(np.asarray(FACE_NORMALS[direction], dtype=float),
                             {**rotation, "origin": [0, 0, 0]}, vihr)
            # Use the model's left side; its wing artwork is the correctly
            # oriented side in the source asset.
            if normal[0] >= -1e-6:
                continue
            if "ctaw_vertices" in face:
                vertices = np.asarray([vertex[:3] for vertex in face["ctaw_vertices"]], dtype=float)
            else:
                layout = VANILLA_VERTICES if vanilla else FACE_VERTICES
                vertices = np.asarray(layout[direction](a, b), dtype=float)
            vertices = np.asarray([rotated(vertex, rotation, vihr) for vertex in vertices])
            uv = face_uv(face, direction, vanilla)
            if correct_wing_uv and a[1] == b[1] and direction == "down":
                # The C-75 fin pairs were authored with opposite UV handedness:
                # their underside artwork appears inverted in a one-side photo.
                # Mirror only that flat fin's U, not the fuselage or all wings.
                uv[:, 0] = uv[:, 0].min() + uv[:, 0].max() - uv[:, 0]
            faces.append((vertices, uv, direction))
    return faces


def project(point: np.ndarray) -> np.ndarray:
    # Strict, centred side view: no perspective or camera-dependent offset.
    x, y, z = point
    return np.array([z, y, -x])


def draw_triangle(pixels: np.ndarray, depth: np.ndarray, vertices: np.ndarray,
                  uvs: np.ndarray, texture: np.ndarray, shade: float) -> None:
    height, width = depth.shape
    x0 = max(0, int(math.floor(vertices[:, 0].min())))
    x1 = min(width - 1, int(math.ceil(vertices[:, 0].max())))
    y0 = max(0, int(math.floor(vertices[:, 1].min())))
    y1 = min(height - 1, int(math.ceil(vertices[:, 1].max())))
    if x1 < x0 or y1 < y0:
        return
    a, b, c = vertices
    determinant = (b[1] - c[1]) * (a[0] - c[0]) + (c[0] - b[0]) * (a[1] - c[1])
    if abs(determinant) < 1e-5:
        return
    yy, xx = np.mgrid[y0:y1 + 1, x0:x1 + 1]
    xx = xx + .5
    yy = yy + .5
    wa = ((b[1] - c[1]) * (xx - c[0]) + (c[0] - b[0]) * (yy - c[1])) / determinant
    wb = ((c[1] - a[1]) * (xx - c[0]) + (a[0] - c[0]) * (yy - c[1])) / determinant
    wc = 1 - wa - wb
    visible = (wa >= -.0001) & (wb >= -.0001) & (wc >= -.0001)
    z = wa * a[2] + wb * b[2] + wc * c[2]
    current_depth = depth[y0:y1 + 1, x0:x1 + 1]
    visible &= z >= current_depth
    if not visible.any():
        return
    u = wa * uvs[0, 0] + wb * uvs[1, 0] + wc * uvs[2, 0]
    v = wa * uvs[0, 1] + wb * uvs[1, 1] + wc * uvs[2, 1]
    th, tw = texture.shape[:2]
    tx = np.clip((u * tw).astype(int), 0, tw - 1)
    ty = np.clip((v * th).astype(int), 0, th - 1)
    color = texture[ty, tx].copy()
    visible &= color[:, :, 3] > 8
    if not visible.any():
        return
    color[:, :, :3] = np.clip(color[:, :, :3].astype(float) * shade, 0, 255).astype(np.uint8)
    target = pixels[y0:y1 + 1, x0:x1 + 1]
    target[visible] = color[visible]
    current_depth[visible] = z[visible]


def render(model_path: Path, texture_path: Path, output: Path, vihr: bool,
           vanilla: bool, correct_wing_uv: bool) -> None:
    model = json.loads(model_path.read_text(encoding="utf-8"))
    texture = np.asarray(Image.open(texture_path).convert("RGBA"))
    faces = load_faces(model, vihr, vanilla, correct_wing_uv)
    if not faces:
        raise ValueError(f"No faces in {model_path}")
    projected = [np.asarray([project(vertex) for vertex in vertices]) for vertices, _, _ in faces]
    all_points = np.vstack(projected)
    minimum = all_points[:, :2].min(axis=0)
    maximum = all_points[:, :2].max(axis=0)
    width, height = 400, 150
    margin_x, margin_y = 13, 15
    span = np.maximum(maximum - minimum, .01)
    scale = min((width - 2 * margin_x) / span[0], (height - 2 * margin_y) / span[1])
    pixels = np.zeros((height, width, 4), dtype=np.uint8)
    depth = np.full((height, width), -np.inf)
    for (vertices, uv, direction), points in zip(faces, projected):
        screen = np.empty_like(points)
        screen[:, 0] = (points[:, 0] - minimum[0]) * scale + (width - span[0] * scale) / 2
        screen[:, 1] = (maximum[1] - points[:, 1]) * scale + (height - span[1] * scale) / 2
        screen[:, 2] = points[:, 2]
        shade = {"up": 1.12, "down": .73, "east": 1.0, "west": .82,
                 "north": .85, "south": .93}[direction]
        for indices in ((0, 1, 2), (0, 2, 3)):
            draw_triangle(pixels, depth, screen[list(indices)], uv[list(indices)], texture, shade)
    image = Image.fromarray(pixels, "RGBA")
    box = image.getbbox()
    if box is None:
        raise ValueError(f"Empty rendering: {model_path}")
    image = ImageOps.mirror(image.crop(box))  # Nose faces right in the playground.
    # Keep model pixels crisp. CSS scales these images down in the game field.
    image.save(output, optimize=True)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("assets", type=Path, help="Create: The Air War assets/create_the_air_wars directory")
    args = parser.parse_args()
    output = Path(__file__).parents[1] / "sprites"
    output.mkdir(parents=True, exist_ok=True)
    for name, (model_name, texture_name) in ROCKETS.items():
        render(args.assets / model_name, args.assets / "textures" / texture_name,
               output / f"{name}.png", name == "vihr", name in VANILLA_LAYOUT,
               name == "c75")
        print(name, (output / f"{name}.png").stat().st_size)


if __name__ == "__main__":
    main()
