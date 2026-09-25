"""Preview renders of an item module. Run from resourcepack/:

    py -m art.render <id>

Prints the path of a PNG contact sheet (plus a second sheet for extra states or
worn views). The views copy Minecraft 26.2's transforms: the inventory slot at GUI
scale 2 and 4 next to a vanilla item, third person with your item in the right hand
and the vanilla counterpart in the left hand for scale, first person, the bare
model from four sides, a night shot that shows glowing parts, and for wearables the
player wearing it. Textures are sampled per texel with a depth buffer, so what you
see is close to the game (minus fog, the enchantment glint and view bobbing).
"""
from __future__ import annotations

import importlib
import io
import math
import sys
import tempfile
import zipfile
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw

from art import kit

CLIENT_JAR = Path.home() / "AppData" / "Roaming" / ".dawn" / "cache" / "minecraft" / "versions" / "26.2" / "26.2.jar"
ROOT = Path(__file__).resolve().parent

# Vanilla counterparts shown next to each kind: (sprite, display set).
COUNTERPART = {
    "sword": ("item/diamond_sword", "handheld"), "pickaxe": ("item/diamond_pickaxe", "handheld"),
    "axe": ("item/diamond_axe", "handheld"), "shovel": ("item/diamond_shovel", "handheld"),
    "hoe": ("item/diamond_hoe", "handheld"), "mace": ("item/mace", "handheld_mace"),
    "bow": ("item/bow", "bow"), "crossbow": ("item/crossbow_standby", "crossbow"),
    "trident": ("item/trident", "generated"), "shield": (None, None),
    "helmet": ("item/golden_helmet", "generated"), "boots": ("item/diamond_boots", "generated"),
    "elytra": ("item/elytra", "generated"), "food": ("item/pumpkin_pie", "generated"),
    "cake": ("item/cake", "generated"), "item": (None, None),
    "leggings": ("item/diamond_leggings", "generated"),
}
WEAR_SLOTS = {"boots": ["feet"], "elytra": ["chest"], "helmet": ["head"], "leggings": ["legs"]}
# Animated textures from the last load(): name -> (full strip array, mcmeta animation block).
ANIMATED: dict = {}
GENERATED = {
    "ground": ([0, 0, 0], [0, 2, 0], 0.5), "head": ([0, 180, 0], [0, 13, 7], 1.0),
    "thirdperson_righthand": ([0, 0, 0], [0, 3, 1], 0.55),
    "firstperson_righthand": ([0, -90, 25], [1.13, 3.2, 1.13], 0.68),
    "fixed": ([0, 180, 0], [0, 0, 0], 1.0),
}

# ------------------------------------------------------------------------------------
# Textures
# ------------------------------------------------------------------------------------

_jar = None


def vanilla(path: str):
    """A vanilla texture (e.g. 'item/bow') as a float RGBA array, or None."""
    global _jar
    try:
        if _jar is None:
            _jar = zipfile.ZipFile(CLIENT_JAR)
        data = _jar.read(f"assets/minecraft/textures/{path}.png")
    except (OSError, KeyError):
        return None
    return to_array(Image.open(io.BytesIO(data)))


def to_array(image: Image.Image) -> np.ndarray:
    return np.asarray(image.convert("RGBA"), dtype=np.float32) / 255.0


MISSING = np.zeros((2, 2, 4), dtype=np.float32)
MISSING[0, 0] = MISSING[1, 1] = (0.97, 0, 0.97, 1)
MISSING[0, 1] = MISSING[1, 0] = (0, 0, 0, 1)

# ------------------------------------------------------------------------------------
# Matrices
# ------------------------------------------------------------------------------------


def T(x, y, z):
    m = np.eye(4)
    m[:3, 3] = (x, y, z)
    return m


def S(x, y=None, z=None):
    return np.diag([x, x if y is None else y, x if z is None else z, 1.0])


def R(axis: str, degrees: float):
    a = math.radians(degrees)
    c, s = math.cos(a), math.sin(a)
    m = np.eye(4)
    if axis == "x":
        m[1:3, 1:3] = ((c, -s), (s, c))
    elif axis == "y":
        m[0, 0], m[0, 2], m[2, 0], m[2, 2] = c, s, -s, c
    else:
        m[0:2, 0:2] = ((c, -s), (s, c))
    return m


def item_transform(d: dict | None, left: bool = False):
    """ItemTransform.apply(): translate, rotate (X*Y*Z), scale, then centre."""
    if not d:
        return T(-0.5, -0.5, -0.5)
    rx, ry, rz = d.get("rotation", [0, 0, 0])
    tx, ty, tz = (max(-80.0, min(80.0, v)) / 16 for v in d.get("translation", [0, 0, 0]))
    sx, sy, sz = (max(-4.0, min(4.0, v)) for v in d.get("scale", [1, 1, 1]))
    if left:
        ry, rz, tx = -ry, -rz, -tx
    return T(tx, ty, tz) @ R("x", rx) @ R("y", ry) @ R("z", rz) @ S(sx, sy, sz) @ T(-0.5, -0.5, -0.5)


def part(pivot, rot=(0, 0, 0)):
    """ModelPart.translateAndRotate(): pivot in pixels, rotationZYX(z, y, x)."""
    return T(*(v / 16 for v in pivot)) @ R("z", rot[2]) @ R("y", rot[1]) @ R("x", rot[0])


def entity(body_yaw: float = 0.0):
    """LivingEntityRenderer: face the body, flip into model space, player scale 0.9375."""
    return R("y", 180 - body_yaw) @ S(-1, -1, 1) @ S(0.9375) @ T(0, -1.501, 0)


# ------------------------------------------------------------------------------------
# Quads
# ------------------------------------------------------------------------------------
# A quad is (vertices 4x3, uvs 4x2 in texels, texture, cull, shade, glow).

FACE_VERTS = {
    "down": lambda a, b: [(a[0], a[1], b[2]), (a[0], a[1], a[2]), (b[0], a[1], a[2]), (b[0], a[1], b[2])],
    "up": lambda a, b: [(a[0], b[1], a[2]), (a[0], b[1], b[2]), (b[0], b[1], b[2]), (b[0], b[1], a[2])],
    "north": lambda a, b: [(b[0], b[1], a[2]), (b[0], a[1], a[2]), (a[0], a[1], a[2]), (a[0], b[1], a[2])],
    "south": lambda a, b: [(a[0], b[1], b[2]), (a[0], a[1], b[2]), (b[0], a[1], b[2]), (b[0], b[1], b[2])],
    "west": lambda a, b: [(a[0], b[1], a[2]), (a[0], a[1], a[2]), (a[0], a[1], b[2]), (a[0], b[1], b[2])],
    "east": lambda a, b: [(b[0], b[1], b[2]), (b[0], a[1], b[2]), (b[0], a[1], a[2]), (b[0], b[1], a[2])],
}


def _apply4(m, pts):
    pts = np.asarray(pts, dtype=np.float64)
    return (m[:3, :3] @ pts.T).T + m[:3, 3]


def model_quads(model: dict, textures: dict, m) -> list:
    """Quads of a JSON model (elements or a flat sprite) under matrix m (block units)."""
    quads = []
    if "elements" not in model:
        layer = model.get("textures", {}).get("layer0", "#")
        tex = textures.get(layer[1:], MISSING)
        mm = m @ S(1 / 16)
        front = [(0, 16, 8.5), (0, 0, 8.5), (16, 0, 8.5), (16, 16, 8.5)]
        back = [(16, 16, 7.5), (16, 0, 7.5), (0, 0, 7.5), (0, 16, 7.5)]
        h, w = tex.shape[:2]
        uv = np.array([(0, 0), (0, h), (w, h), (w, 0)], dtype=np.float64)
        quads.append((_apply4(mm, front), uv, tex, True, True, 0))
        quads.append((_apply4(mm, back), uv[[3, 2, 1, 0]], tex, True, True, 0))
        return quads
    mm = m @ S(1 / 16)
    for element in model["elements"]:
        a, b = element["from"], element["to"]
        rot, origin = kit.rotation_of(element)
        rot = np.asarray(rot, dtype=np.float64)
        glow = element.get("light_emission", 0)
        shade = element.get("shade", True)
        for side, face in element["faces"].items():
            pts = np.asarray(FACE_VERTS[side](a, b), dtype=np.float64)
            if origin is not None:
                o = np.asarray(origin)
                pts = (rot @ (pts - o).T).T + o
            tex = textures.get(face["texture"][1:], MISSING)
            h, w = tex.shape[:2]
            u0, v0, u1, v1 = face.get("uv", [0, 0, 16, 16])
            corners = [(u0, v0), (u0, v1), (u1, v1), (u1, v0)]
            shift = int(face.get("rotation", 0)) // 90
            uv = np.array([corners[(i + shift) % 4] for i in range(4)], dtype=np.float64)
            uv[:, 0] *= w / 16
            uv[:, 1] *= h / 16
            quads.append((_apply4(mm, pts), uv, tex, True, shade, glow))
    return quads


def box_quads(m, tex, origin_px, size_px, uv_px, tex_size, dilation=0.0, mirror=False, cull=False):
    """Quads of an entity model cube (ModelPart.Cube): pixels, y down, box UV unwrap."""
    x, y, z = origin_px
    w, h, d = size_px
    u, v = uv_px
    tw, th = tex_size
    a = np.array([x - dilation, y - dilation, z - dilation]) / 16
    b = np.array([x + w + dilation, y + h + dilation, z + d + dilation]) / 16
    # Texel rectangles (left, top, right, bottom) per face, as the skin layout reads.
    rect = {
        "up": (u + d, v, u + d + w, v + d), "down": (u + d + w, v, u + d + 2 * w, v + d),
        "west": (u, v + d, u + d, v + d + h), "north": (u + d, v + d, u + d + w, v + d + h),
        "east": (u + d + w, v + d, u + 2 * d + w, v + d + h), "south": (u + 2 * d + w, v + d, u + 2 * d + 2 * w, v + d + h),
    }
    # Face corners listed as top-left, bottom-left, bottom-right, top-right of the
    # texture rectangle, in model space (y down, the face looking at -z).
    x0, y0, z0 = a
    x1, y1, z1 = b
    corners = {
        "north": [(x0, y0, z0), (x0, y1, z0), (x1, y1, z0), (x1, y0, z0)],
        "south": [(x1, y0, z1), (x1, y1, z1), (x0, y1, z1), (x0, y0, z1)],
        "west": [(x0, y0, z1), (x0, y1, z1), (x0, y1, z0), (x0, y0, z0)],
        "east": [(x1, y0, z0), (x1, y1, z0), (x1, y1, z1), (x1, y0, z1)],
        "up": [(x0, y0, z1), (x0, y0, z0), (x1, y0, z0), (x1, y0, z1)],
        "down": [(x0, y1, z0), (x0, y1, z1), (x1, y1, z1), (x1, y1, z0)],
    }
    h_t, w_t = tex.shape[:2]
    quads = []
    for side, pts in corners.items():
        l, t_, r, btm = rect[side]
        uv = np.array([(l, t_), (l, btm), (r, btm), (r, t_)], dtype=np.float64)
        uv[:, 0] *= w_t / tw
        uv[:, 1] *= h_t / th
        pts = np.asarray(pts, dtype=np.float64)
        if mirror:  # reflect the textured box across its own centre (ModelPart mirror)
            pts = pts.copy()
            pts[:, 0] = (x0 + x1) - pts[:, 0]
        quads.append((_apply4(m, pts), uv, tex, cull, True, 0))
    return quads


# ------------------------------------------------------------------------------------
# Rasteriser
# ------------------------------------------------------------------------------------

L0 = np.array([0.2, 1.0, -0.7]) / np.linalg.norm([0.2, 1.0, -0.7])
L1 = np.array([-0.2, 1.0, 0.7]) / np.linalg.norm([-0.2, 1.0, 0.7])


class Camera:
    def __init__(self, width, height, view, ortho_scale=None, fov=40.0):
        self.w, self.h, self.view = width, height, view
        self.ortho = ortho_scale
        self.f = 1 / math.tan(math.radians(fov) / 2)

    def project(self, pts):
        v = _apply4(self.view, pts)
        if self.ortho:
            sx = self.w / 2 + v[:, 0] * self.ortho
            sy = self.h / 2 - v[:, 1] * self.ortho
            return np.stack([sx, sy, v[:, 2], np.ones(len(v))], 1), v
        z = np.minimum(v[:, 2], -1e-3)
        sx = self.w / 2 + self.f * v[:, 0] / -z * self.h / 2
        sy = self.h / 2 - self.f * v[:, 1] / -z * self.h / 2
        inv = 1 / -z
        return np.stack([sx, sy, inv, inv], 1), v


def look_at(eye, target, up=(0, 1, 0)):
    eye, target, up = (np.asarray(v, dtype=np.float64) for v in (eye, target, up))
    f = target - eye
    f /= np.linalg.norm(f)
    r = np.cross(f, up)
    r /= np.linalg.norm(r)
    u = np.cross(r, f)
    m = np.eye(4)
    m[0, :3], m[1, :3], m[2, :3] = r, u, -f
    m[:3, 3] = -m[:3, :3] @ eye
    return m


def _normal(a, b, c, d):
    n = np.cross(b - a, d - a)
    if np.linalg.norm(n) < 1e-12:
        n = np.cross(c - b, a - b)
    norm = np.linalg.norm(n)
    return n / norm if norm >= 1e-12 else None


def render(quads, camera: Camera, background=(0.62, 0.76, 0.9), night=False, light_space="world"):
    """Rasterise quads with a depth buffer; cutout below alpha 0.1, blend translucency.
    Lighting is Minecraft's entity lighting (two fixed lights, 0.4 ambient), in world
    space for scenes and view space for the GUI and turntables."""
    w, h = camera.w, camera.h
    colour = np.zeros((h, w, 3), dtype=np.float32)
    colour[:] = background
    depth = np.full((h, w), -np.inf, dtype=np.float64)
    translucent = []
    for verts, uv, tex, cull, shade, glow in quads:
        p, v = camera.project(verts)
        n = _normal(*v)
        if n is None:
            continue
        facing = n[2] if camera.ortho else float(np.dot(n, -v[0]))
        if cull and facing <= 0:
            continue
        ln = n if light_space == "view" else _normal(*verts)
        if ln is None:
            continue
        if facing < 0:
            ln = -ln
        bright = min(1.0, (max(0.0, float(ln @ L0)) + max(0.0, float(ln @ L1))) * 0.6 + 0.4) if shade else 1.0
        if night:
            bright = max(0.16 * bright, glow / 15.0) if glow else 0.16 * bright
        for tri in ((0, 1, 2), (0, 2, 3)):
            _raster_tri(p[list(tri)], uv[list(tri)], tex, bright, colour, depth, translucent)
    for order, ys, xs, rgb, alpha, z in sorted(translucent, key=lambda t: t[0]):
        keep = z >= depth[ys, xs]
        ys, xs, rgb, alpha = ys[keep], xs[keep], rgb[keep], alpha[keep]
        colour[ys, xs] = colour[ys, xs] * (1 - alpha[:, None]) + rgb * alpha[:, None]
    return Image.fromarray((np.clip(colour, 0, 1) * 255).astype(np.uint8), "RGB")


def _raster_tri(p, uv, tex, bright, colour, depth, translucent):
    h, w = depth.shape
    xs_, ys_ = p[:, 0], p[:, 1]
    x0, x1 = int(max(0, math.floor(xs_.min()))), int(min(w - 1, math.ceil(xs_.max())))
    y0, y1 = int(max(0, math.floor(ys_.min()))), int(min(h - 1, math.ceil(ys_.max())))
    if x0 > x1 or y0 > y1:
        return
    area = (xs_[1] - xs_[0]) * (ys_[2] - ys_[0]) - (xs_[2] - xs_[0]) * (ys_[1] - ys_[0])
    if abs(area) < 1e-9:
        return
    gx, gy = np.meshgrid(np.arange(x0, x1 + 1) + 0.5, np.arange(y0, y1 + 1) + 0.5)
    w0 = ((xs_[1] - gx) * (ys_[2] - gy) - (xs_[2] - gx) * (ys_[1] - gy)) / area
    w1 = ((xs_[2] - gx) * (ys_[0] - gy) - (xs_[0] - gx) * (ys_[2] - gy)) / area
    w2 = 1 - w0 - w1
    inside = (w0 >= -1e-6) & (w1 >= -1e-6) & (w2 >= -1e-6)
    if not inside.any():
        return
    w0, w1, w2 = w0[inside], w1[inside], w2[inside]
    px, py = (gx[inside] - 0.5).astype(int), (gy[inside] - 0.5).astype(int)
    inv = w0 * p[0, 3] + w1 * p[1, 3] + w2 * p[2, 3]
    z = w0 * p[0, 2] + w1 * p[1, 2] + w2 * p[2, 2]
    u = (w0 * uv[0, 0] * p[0, 3] + w1 * uv[1, 0] * p[1, 3] + w2 * uv[2, 0] * p[2, 3]) / inv
    v = (w0 * uv[0, 1] * p[0, 3] + w1 * uv[1, 1] * p[1, 3] + w2 * uv[2, 1] * p[2, 3]) / inv
    th, tw = tex.shape[:2]
    iu = np.clip(np.floor(u).astype(int), 0, tw - 1)
    iv = np.clip(np.floor(v).astype(int), 0, th - 1)
    texel = tex[iv, iu]
    alpha = texel[:, 3]
    rgb = texel[:, :3] * bright
    solid = alpha >= 0.99
    closer = z > depth[py, px]
    write = solid & closer
    depth[py[write], px[write]] = z[write]
    colour[py[write], px[write]] = rgb[write]
    semi = (alpha >= 0.1) & ~solid
    if semi.any():
        translucent.append((float(z[semi].mean()), py[semi], px[semi], rgb[semi], alpha[semi], z[semi]))


# ------------------------------------------------------------------------------------
# Scenes
# ------------------------------------------------------------------------------------

def steve():
    return vanilla("entity/player/wide/steve")


PLAYER_BOXES = [  # (pivot, box origin, size, uv) in pixels, 64x64 skin
    ("head", (0, 0, 0), (-4, -8, -4), (8, 8, 8), (0, 0)),
    ("body", (0, 0, 0), (-4, 0, -2), (8, 12, 4), (16, 16)),
    ("right_arm", (-5, 2, 0), (-3, -2, -2), (4, 12, 4), (40, 16)),
    ("left_arm", (5, 2, 0), (-1, -2, -2), (4, 12, 4), (32, 48)),
    ("right_leg", (-1.9, 12, 0), (-2, 0, -2), (4, 12, 4), (0, 16)),
    ("left_leg", (1.9, 12, 0), (-2, 0, -2), (4, 12, 4), (16, 48)),
]


def player(arm_pose=None, yaw: float = 0.0):
    """Steve's quads; arm_pose maps part name -> (xRot, yRot, zRot) degrees, or is a
    kit.ARM_POSES name."""
    if isinstance(arm_pose, str):
        right, left = kit.ARM_POSES[arm_pose]
        arm_pose = {"right_arm": right, "left_arm": left}
    skin = steve()
    if skin is None:
        skin = np.ones((64, 64, 4), dtype=np.float32) * np.array([0.55, 0.5, 0.45, 1], dtype=np.float32)
    quads = []
    base = entity(yaw)
    for name, pivot, origin, size, uv in PLAYER_BOXES:
        rot = (arm_pose or {}).get(name, (0, 0, 0))
        quads += box_quads(base @ part(pivot, rot), skin, origin, size, uv, (64, 64), cull=True)
    return quads


def held(model, textures, context="thirdperson_righthand", left=False, pose="item", yaw=0.0):
    """ItemInHandLayer: the item in a hand (right unless left=True)."""
    pivot = (5, 2, 0) if left else (-5, 2, 0)
    rot = (-18.0, 0.0, 0.0) if left else kit.ARM_POSES[pose][0]
    m = (entity(yaw) @ part(pivot, rot) @ R("x", -90) @ R("y", 180)
         @ T((-1 if left else 1) / 16, 2 / 16, -10 / 16) @ item_transform(model.get("display", {}).get(context), left))
    return model_quads(model, textures, m)


def worn_head(model, textures, yaw=0.0):
    """CustomHeadLayer: an item worn on the head through its "head" transform."""
    m = (entity(yaw) @ part((0, 0, 0)) @ T(0, -0.25, 0) @ R("y", 180) @ S(0.625, -0.625, -0.625)
         @ item_transform(model.get("display", {}).get("head")))
    return model_quads(model, textures, m)


ARMOR = {  # slot -> list of (pivot, origin, size, uv, mirror, dilation, layer)
    "head": [((0, 0, 0), (-4, -8, -4), (8, 8, 8), (0, 0), False, 1.0, "humanoid"),
             ((0, 0, 0), (-4, -8, -4), (8, 8, 8), (32, 0), False, 1.5, "humanoid")],
    "chest": [((0, 0, 0), (-4, 0, -2), (8, 12, 4), (16, 16), False, 1.0, "humanoid"),
              ((-5, 2, 0), (-3, -2, -2), (4, 12, 4), (40, 16), False, 1.0, "humanoid"),
              ((5, 2, 0), (-1, -2, -2), (4, 12, 4), (40, 16), True, 1.0, "humanoid")],
    "legs": [((0, 0, 0), (-4, 0, -2), (8, 12, 4), (16, 16), False, 0.5, "humanoid_leggings"),
             ((-1.9, 12, 0), (-2, 0, -2), (4, 12, 4), (0, 16), False, 0.5, "humanoid_leggings"),
             ((1.9, 12, 0), (-2, 0, -2), (4, 12, 4), (0, 16), True, 0.5, "humanoid_leggings")],
    "feet": [((-1.9, 12, 0), (-2, 0, -2), (4, 12, 4), (0, 16), False, 1.0, "humanoid"),
             ((1.9, 12, 0), (-2, 0, -2), (4, 12, 4), (0, 16), True, 1.0, "humanoid")],
}


def armour(layers: dict, slots, yaw=0.0):
    quads = []
    base = entity(yaw)
    for slot in slots:
        for pivot, origin, size, uv, mirror, dil, layer in ARMOR[slot]:
            tex = layers.get(layer)
            if tex is None:
                continue
            quads += box_quads(base @ part(pivot), tex, origin, size, uv, (64, 32), dil, mirror)
    return quads


def wings(tex, spread=False, yaw=0.0):
    """WingsLayer + ElytraModel (standing, or spread like gliding)."""
    x_rot, z_rot = (20.0, -90.0) if spread else (15.0, -15.0)
    base = entity(yaw) @ T(0, 0, 0.125)
    quads = box_quads(base @ part((5, 0, 0), (x_rot, 0, z_rot)), tex, (-10, 0, 0), (10, 20, 2), (22, 0), (64, 32), 1.0)
    quads += box_quads(base @ part((-5, 0, 0), (x_rot, 0, -z_rot)), tex, (0, 0, 0), (10, 20, 2), (22, 0), (64, 32), 1.0,
                       mirror=True)
    return quads



def floor(size: float = 3.0, colour=(0.40, 0.58, 0.30)):
    """A flat grass-coloured floor at y = 0 for placed-object previews."""
    tex = np.ones((2, 2, 4), dtype=np.float32)
    tex[..., :3] = colour
    pts = np.array([(-size, 0, -size), (-size, 0, size), (size, 0, size), (size, 0, -size)], dtype=np.float64)
    return [(pts, np.array([(0, 0), (0, 2), (2, 2), (2, 0)], dtype=np.float64), tex, True, True, 0)]


def placed(model, textures, block=(0, 0, 0), yaw: float = 0.0):
    """An ItemDisplay with no display transform on the block at `block` (its min corner):
    model (8, 8, 8) sits at the block centre, so model y = 0 is the block's floor.
    yaw turns it like the display entity's yaw (0 = model +Z faces +Z)."""
    centre = T(block[0] + 0.5, block[1] + 0.5, block[2] + 0.5)
    return model_quads(model, textures, centre @ R("y", -yaw) @ item_transform(None))

def gui(model, textures, scale=4, background=(0.545, 0.545, 0.545)):
    size = 16 * scale
    cam = Camera(size, size, np.eye(4), ortho_scale=16 * scale)
    quads = model_quads(model, textures, item_transform(model.get("display", {}).get("gui")))
    return render(quads, cam, background=background, light_space="view")


def first_person(model, textures, ghost=None, width=480, height=270, night=False):
    quads = model_quads(model, textures, T(0.56, -0.52, -0.72)
                        @ item_transform(model.get("display", {}).get("firstperson_righthand")))
    if ghost:
        gm, gt = ghost
        quads += model_quads(gm, gt, T(-0.56, -0.52, -0.72)
                             @ item_transform(gm.get("display", {}).get("firstperson_righthand"), left=True))
    cam = Camera(width, height, np.eye(4), fov=70)
    bg = (0.05, 0.06, 0.1) if night else (0.55, 0.72, 0.92)
    image = render(quads, cam, background=bg, night=night)
    draw = ImageDraw.Draw(image)  # crosshair: a held item should not cover it
    cx, cy = width // 2, height // 2
    draw.line((cx - 6, cy, cx + 6, cy), fill=(255, 255, 255))
    draw.line((cx, cy - 6, cx, cy + 6), fill=(255, 255, 255))
    return image


def scene(quads, eye, target=(0, 1.0, 0), size=(420, 420), fov=40, night=False):
    cam = Camera(size[0], size[1], look_at(eye, target), fov=fov)
    bg = (0.05, 0.06, 0.1) if night else (0.62, 0.76, 0.9)
    return render(quads, cam, background=bg, night=night)


def turntable(model, textures, yaw, pitch, size=240):
    m = R("x", pitch) @ R("y", yaw) @ T(-0.5, -0.5, -0.5)
    pts = [p for e in model.get("elements", []) for p in kit.corners(e)] or [(0, 0, 0), (16, 16, 16)]
    rot = m[:3, :3]
    proj = (rot @ ((np.asarray(pts) / 16) - 0.5).T).T
    extent = max(proj[:, 0].max() - proj[:, 0].min(), proj[:, 1].max() - proj[:, 1].min(), 1e-3)
    centre = np.array([(proj[:, 0].max() + proj[:, 0].min()) / 2, (proj[:, 1].max() + proj[:, 1].min()) / 2, 0])
    scale = size * 0.86 / extent
    cam = Camera(size, size, T(-centre[0], -centre[1], 0), ortho_scale=scale)
    return render(model_quads(model, textures, m), cam, background=(0.2, 0.22, 0.26), light_space="view")


# ------------------------------------------------------------------------------------
# Sheet
# ------------------------------------------------------------------------------------

def label(image: Image.Image, text: str) -> Image.Image:
    out = Image.new("RGB", (image.width, image.height + 16), (28, 30, 36))
    out.paste(image, (0, 16))
    ImageDraw.Draw(out).text((4, 2), text, fill=(235, 235, 235))
    return out


def row(images, gap=8, bg=(28, 30, 36)):
    h = max(i.height for i in images)
    out = Image.new("RGB", (sum(i.width for i in images) + gap * (len(images) - 1), h), bg)
    x = 0
    for i in images:
        out.paste(i, (x, 0))
        x += i.width + gap
    return out


def column(images, gap=8, bg=(28, 30, 36)):
    w = max(i.width for i in images)
    out = Image.new("RGB", (w, sum(i.height for i in images) + gap * (len(images) - 1)), bg)
    y = 0
    for i in images:
        out.paste(i, (0, y))
        y += i.height + gap
    return out


def slot_strip(model, textures, ghost, scale):
    """Three hotbar slots: the vanilla counterpart, this item, and an empty slot."""
    cells = []
    for m, t in ([ghost] if ghost else []) + [(model, textures)]:
        cell = gui(m, t, scale)
        framed = Image.new("RGB", (cell.width + 2 * scale, cell.height + 2 * scale), (55, 55, 55))
        framed.paste(cell, (scale, scale))
        cells.append(framed)
    strip = row(cells, gap=scale * 2, bg=(198, 198, 198))
    return strip.resize((strip.width * (8 // scale), strip.height * (8 // scale)), Image.NEAREST)


def ghost_for(kind, override=None):
    sprite, display_set = COUNTERPART.get(kind, (None, None))
    if override:
        sprite, display_set = override, display_set or "generated"
    if not sprite:
        return None
    tex = vanilla(sprite)
    if tex is None:
        return None
    display = {k: {"rotation": r, "translation": t, "scale": [s] * 3} for k, (r, t, s) in GENERATED.items()}
    if display_set != "generated":
        for k, (r, t, s) in kit.VANILLA[display_set].items():
            display[k] = {"rotation": r, "translation": t, "scale": [s] * 3}
    return {"textures": {"layer0": "#g"}, "display": display}, {"g": tex}


def sheet(module, models: dict, textures: dict, layers: dict) -> list[Path]:
    kind = module.KIND
    ghost = ghost_for(kind, getattr(module, "COUNTERPART", None))
    names = kit.KINDS[kind]
    main = models[names[0]]
    shown = models.get("gui", main)
    outputs = []

    top = [label(slot_strip(shown, textures, ghost, 2), "inventory, GUI scale 2 (vanilla | this)"),
           label(slot_strip(shown, textures, ghost, 4), "GUI scale 4")]
    held_right = held(main, textures)
    held_left = held(ghost[0], ghost[1], left=True) if ghost else []
    pose = {"right_arm": (-18, 0, 0), "left_arm": (-18, 0, 0) if ghost else (0, 0, 0)}
    body = player(pose)
    extra_wear = []
    if kind == "helmet":
        extra_wear = worn_head(main, textures)
    if "humanoid" in layers or "humanoid_leggings" in layers:
        slots = WEAR_SLOTS.get(kind, ["chest"])
        extra_wear = extra_wear + armour(layers, slots)
    if "wings" in layers:
        extra_wear = extra_wear + wings(layers["wings"])
    # The player faces +Z; their right hand is on the -X side.
    third = [label(scene(body + held_right + held_left + extra_wear, (-1.5, 1.7, 3.4)),
                   "third person, front (vanilla in left hand)"),
             label(scene(body + held_right + held_left + extra_wear, (-2.7, 1.9, -2.6)), "third person, back"),
             label(scene(body + held_right + extra_wear, (-3.6, 1.2, 0.3), fov=38), "right side")]
    fp = [label(first_person(main, textures, ghost), "first person (vanilla in off hand)"),
          label(first_person(main, textures, night=True), "first person, night (glow)")]
    turns = [label(turntable(main, textures, yaw, pitch, 220), name)
             for name, yaw, pitch in (("model, front", 0, 12), ("model, 3/4", 45, 20), ("model, side", 90, 0),
                                      ("model, back", 180, 12))]
    first = column([row(top + fp), row(third), row(turns)])
    out = Path(tempfile.gettempdir()) / f"art_render_{module.ID}.png"
    first.save(out)
    outputs.append(out)

    # Second sheet: every other state, and wearables on the player.
    extra = []
    if kind == "cake":
        # Placed on the floor like the plugin's ItemDisplay, slice 1 toward the camera (+Z).
        cells = []
        for name in names:
            view = scene(floor() + placed(models[name], textures, (-0.5, 0, -0.5)), (0.0, 1.35, 1.9),
                         (0, 0.35, 0), (260, 220), fov=45)
            cells.append(label(view, "whole" if name == "main" else f"{name[5:]} of 8 slices eaten"))
        extra.append(row(cells[:4]))
        extra.append(row(cells[4:]))
        scale_shot = scene(floor() + player({}) + placed(main, textures, (0.4, 0, 0.9)), (-1.6, 1.8, 3.6),
                           (0.4, 0.8, 0.6), (420, 340))
        night = scene(floor() + placed(main, textures, (-0.5, 0, -0.5)), (1.2, 1.2, 1.7), (0, 0.35, 0),
                      (420, 340), night=True)
        top_down = scene(floor() + placed(models[names[3]], textures, (-0.5, 0, -0.5)), (0.01, 2.6, 0.35),
                         (0, 0, 0), (340, 340), fov=40)
        extra.append(row([label(scale_shot, "placed next to a player"), label(night, "placed, at night"),
                          label(top_down, "3 of 8 eaten, from above")]))
    for name in (() if kind == "cake" else names[1:]) + tuple(n for n in kit.OPTIONAL_MODELS if n in models and n != "gui"):
        m = models[name]
        state_pose = kit.pose_for(kind, name)
        extra.append(row([label(gui(m, textures, 4).resize((128, 128), Image.NEAREST), f"{name}: GUI"),
                          label(scene(player(state_pose) + held(m, textures, pose=state_pose), (-1.5, 1.7, 3.4),
                                      size=(300, 300)), f"{name}: third person ({state_pose} pose)"),
                          label(scene(player(state_pose) + held(m, textures, pose=state_pose), (-3.4, 1.5, 0.6),
                                      size=(300, 300)), f"{name}: right side"),
                          label(first_person(m, textures, width=400, height=225), f"{name}: first person")]))
    if kind in ("helmet", "boots", "elytra", "leggings"):
        idle = player({})
        wear = []
        if kind == "helmet":
            wear = worn_head(main, textures)
        wear += armour(layers, WEAR_SLOTS[kind])
        views = [label(scene(idle + wear, (1.8, 1.6, 2.6), (0, 1.1, 0), (300, 340)), "worn, front"),
                 label(scene(idle + wear, (-2.0, 1.7, -2.4), (0, 1.1, 0), (300, 340)), "worn, back")]
        if kind == "elytra" and "wings" in layers:
            views = [label(scene(idle + wear + wings(layers["wings"]), (1.8, 1.6, 2.6), (0, 1.1, 0), (300, 340)),
                           "worn, front"),
                     label(scene(idle + wear + wings(layers["wings"]), (-2.0, 1.7, -2.6), (0, 1.1, 0), (300, 340)),
                           "worn, back"),
                     label(scene(idle + wear + wings(layers["wings"], spread=True), (-0.6, 3.2, -3.4), (0, 1.1, 0),
                                 (340, 340)), "gliding, from behind"),
                     label(scene(idle + wear + wings(layers["wings"]), (-2.0, 1.7, -2.6), (0, 1.1, 0), (300, 340),
                                 night=True), "night")]
        elif kind == "helmet":
            views.append(label(scene(idle + wear, (0.9, 1.75, 1.5), (0, 1.55, 0), (300, 300)), "close up"))
            views.append(label(scene(idle + wear, (1.8, 1.6, 2.6), (0, 1.1, 0), (300, 340), night=True), "night"))
        elif kind == "boots":
            views.append(label(scene(idle + wear, (1.2, 0.5, 1.4), (0, 0.25, 0), (300, 300)), "close up"))
        elif kind == "leggings":
            views.append(label(scene(idle + wear, (1.3, 1.0, 1.6), (0, 0.7, 0), (300, 300)), "close up"))
            views.append(label(scene(idle + wear, (1.8, 1.6, 2.6), (0, 1.1, 0), (300, 340), night=True), "night"))
        extra.append(row(views))
    if extra:
        second = column(extra)
        out2 = Path(tempfile.gettempdir()) / f"art_render_{module.ID}_more.png"
        second.save(out2)
        outputs.append(out2)
    if ANIMATED:
        outputs.append(animation_sheet(module, shown, main, textures))
    return outputs


def frame_at(name: str, tick: int):
    """The frame of an animated texture showing at game tick `tick` (no interpolation)."""
    strip, meta = ANIMATED[name]
    w = strip.shape[1]
    count = strip.shape[0] // w
    order = meta.get("frames") or list(range(count))
    index = order[(tick // meta["frametime"]) % len(order)]
    return strip[index * w:(index + 1) * w]


def animation_sheet(module, shown, main, textures, samples: int = 8) -> Path:
    """The inventory icon and the model through one loop of the longest animation, plus
    every frame of each animated texture, so the motion can be judged from stills."""
    def loop(meta, strip):
        count = strip.shape[0] // strip.shape[1]
        return len(meta.get("frames") or range(count)) * meta["frametime"]

    longest = max(loop(meta, strip) for strip, meta in ANIMATED.values())
    ticks = [round(i * longest / samples) for i in range(samples)]
    icons, models_ = [], []
    for tick in ticks:
        frame = dict(textures)
        for name in ANIMATED:
            frame[name] = frame_at(name, tick)
        icons.append(label(gui(shown, frame, 4).resize((128, 128), Image.NEAREST), f"tick {tick}"))
        if main.get("elements"):
            models_.append(label(turntable(main, frame, 35, 18, 160), f"tick {tick}"))
    rows = [label(row(icons), f"inventory icon over one {longest}-tick loop ({longest / 20:.1f} s)")]
    if models_:
        rows.append(label(row(models_), "model, 3/4 view"))
    for name, (strip, meta) in ANIMATED.items():
        w = strip.shape[1]
        count = strip.shape[0] // w
        scale = max(1, 96 // w)
        cells = [Image.fromarray((strip[i * w:(i + 1) * w] * 255).astype(np.uint8), "RGBA")
                 .resize((w * scale, w * scale), Image.NEAREST) for i in range(min(count, 16))]
        flat = []
        for c in cells:  # show transparency on a checkerboard
            bg = Image.new("RGBA", c.size, (70, 70, 78, 255))
            check = Image.new("RGBA", c.size, (100, 100, 110, 255))
            mask = Image.new("L", c.size, 0)
            ImageDraw.Draw(mask).rectangle((0, 0, c.width, c.height), fill=0)
            for yy in range(0, c.height, 8):
                for xx in range(0, c.width, 8):
                    if (xx // 8 + yy // 8) % 2:
                        ImageDraw.Draw(mask).rectangle((xx, yy, xx + 7, yy + 7), fill=255)
            bg.paste(check, (0, 0), mask)
            bg.alpha_composite(c)
            flat.append(bg.convert("RGB"))
        info = f"{name}: {count} frames x {meta['frametime']} ticks" + (", interpolated" if meta.get("interpolate") else "")
        rows.append(label(row(flat, gap=4), info + ("" if count <= 16 else " (first 16 shown)")))
    out = Path(tempfile.gettempdir()) / f"art_render_{module.ID}_anim.png"
    column(rows).save(out)
    return out


def _sprite_display(model: dict) -> dict:
    """The display a flat sprite model inherits from item/generated or item/handheld."""
    display = {k: {"rotation": r, "translation": t, "scale": [s] * 3} for k, (r, t, s) in GENERATED.items()}
    if model.get("parent", "").endswith("handheld"):
        for k, (r, t, s) in kit.VANILLA["handheld"].items():
            display[k] = {"rotation": r, "translation": t, "scale": [s] * 3}
    display.update(model.get("display", {}))
    return display


def load(item_id: str):
    """Import an item module, paint its textures into a temp folder and load them.
    Animated textures show their first frame (the strips are kept in ANIMATED)."""
    module = importlib.import_module(f"art.items.{item_id}")
    tmp = Path(tempfile.mkdtemp(prefix=f"art_{item_id}_"))
    kit.begin(item_id, tmp / "textures", tmp / "layers")
    module.textures()
    animations = kit.animations()
    ANIMATED.clear()
    textures = {}
    for p in (tmp / "textures").glob("*.png"):
        array = to_array(Image.open(p))
        if p.stem in animations:
            ANIMATED[p.stem] = (array, animations[p.stem])
            array = array[:array.shape[1]]
        textures[p.stem] = array
    layers = {layer: to_array(Image.open(tmp / "layers" / layer / f"{item_id}.png"))
              for layer in kit.LAYERS if (tmp / "layers" / layer / f"{item_id}.png").exists()}
    models = module.models()
    for m in models.values():
        if isinstance(m, dict) and "elements" not in m:
            m["display"] = _sprite_display(m)
    return module, models, textures, layers


def main(argv) -> int:
    if not argv:
        print(__doc__)
        return 2
    module, models, textures, layers = load(argv[0])
    for path in sheet(module, models, textures, layers):
        print(path)
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
