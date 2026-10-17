"""Peanut Butter Cup: a milk-chocolate cup with fluted, ridged walls, a crimped rim and a
softly domed top. One bite is taken out of the front, showing the golden peanut butter
centre under a thin chocolate lid. A retextured Cookie (issue #1198).

The cup is built from radial pieces so the bite can be a real scoop: 32 wall staves lean
outward like a paper candy cup (alternate staves stand proud, which gives the ridges), and
the filling and the dome are wedges round the centre, the filling in three tiers that
follow the leaning wall. Inside the bite the staves stop at a floor that rises toward the
bite's edges, everything above it is cut back, and the cut faces show the chocolate lid
over the peanut butter.

Wedge top faces use uv "true", so their u runs outward from the centre: lid and dome
textures are painted in columns by radius (2 px per unit).
"""
from __future__ import annotations

import math

from art.kit import box, canvas, display, fill, lathe, model, rgba, save, turn

ID = "peanut_butter_cup"
NAME = "Peanut Butter Cup"
KIND = "food"
COUNTERPART = "item/cookie"

# --------------------------------------------------------------------------------------
# Palettes (darkest -> lightest)
# --------------------------------------------------------------------------------------
# Hand-picked, cool red-brown: ramp() highlights drift toward orange and salmon, which reads
# as wood, not chocolate.
CHOC = ["#1a0c09", "#26130d", "#341a11", "#432317", "#532d1d", "#643825", "#77452e", "#8c5638"]
PB = ["#7a3f0e", "#a05a17", "#c27822", "#d99234", "#e8a94a", "#f2bf63", "#fbd583"]

# --------------------------------------------------------------------------------------
# Layout (model units). The cup stands upright, centred on (CX, *, CZ).
# --------------------------------------------------------------------------------------
CX, CZ = 8.0, 8.0
Y0, Y1 = 4.0, 9.0                       # wall foot and wall top
R_FOOT, R_TOP = 5.0, 6.4                # wall centre radius at the foot and the top
WALL = 1.0                              # wall thickness
N = 32                                  # staves / wedges round the cup
STEP = 360.0 / N
Y_LID = 9.4                             # top of the filling = the chocolate lid
TIERS = (Y0 + 0.6, 6.2, 7.6, 8.6, Y_LID)  # filling tier boundaries
RIM_IN = 5.25                           # inner edge of the crimped rim
DOME = ((0.3, 4.2), (0.6, 3.2))         # soft dome steps as wedges: (height above the lid, radius)
DOME_TOP = (0.9, 2.0)                   # the crown, a round lathe (always clear of the bite)
BITE_AT = 320.0                         # centre of the bite, degrees round Y from +X toward +Z
# |sector offset| from the bite centre -> (radius the filling keeps, floor height): a
# rounded scoop that opens at the lid and is deepest in the middle
BITE = {0.5: (2.6, 6.4), 1.5: (3.1, 6.8), 2.5: (3.9, 7.6), 3.5: (4.8, 8.4)}
GLOSS_AT = 110.0                        # the dome's highlight, on the side facing the light


def wall_radius(y: float) -> float:
    return R_FOOT + (y - Y0) / (Y1 - Y0) * (R_TOP - R_FOOT)


def offset(i: int) -> float:
    """Sector i's distance from the bite centre, in sectors (always a half step)."""
    a = (i * STEP - BITE_AT + 180) % 360 - 180
    return abs(math.floor(a / STEP) + 0.5)


def bitten(i: int) -> tuple[float, float] | None:
    """(radius, floor) of the bite in sector i, or None if the sector is whole."""
    return BITE.get(offset(i))


# --------------------------------------------------------------------------------------
# Textures
# --------------------------------------------------------------------------------------

def wall(name: str, shades: list[int]) -> None:
    """A fluted wall stave, rounded across its width (shades left -> right), a touch
    darker toward the foot, with a glossy line under the lip."""
    img = canvas(32)
    px = img.load()
    for x in range(32):
        col = shades[x * len(shades) // 32]
        for y in range(32):
            px[x, y] = rgba(CHOC[max(0, col - (1 if y >= 22 else 0))])
        px[x, 0] = px[x, 1] = rgba(CHOC[min(7, col + 1)])
    save(img, name)


def radial(name: str, bands: list[tuple[float, str]]) -> None:
    """A wedge-top texture painted by radius: bands of (from radius, colour)."""
    img = canvas(32)
    for r0, colour in bands:
        fill(img, (int(r0 * 2), 0, 31, 31), colour)
    save(img, name)


def textures() -> None:
    wall("wall_ridge", [5, 6, 7, 7, 7, 7, 6, 5])     # proud ridge: rounded, brightest on its crest
    wall("wall_valley", [4, 4, 5, 5, 5, 5, 4, 4])    # the groove between ridges

    # Crimped rim: proud pieces catch the light, recessed ones sit in shadow.
    for name, top in (("rim_hi", 6), ("rim_lo", 5)):
        rim = canvas(32, fill=CHOC[top - 1])
        fill(rim, (0, 0, 31, 1), CHOC[top])
        fill(rim, (0, 24, 31, 31), CHOC[top - 2])
        save(rim, name)
        save(canvas(32, fill=CHOC[top]), name + "_top")

    # Lid and dome: smooth chocolate, a shade darker where the lid meets the rim.
    radial("lid", [(0, CHOC[4]), (4.75, CHOC[3])])   # 1 px seam at the rim
    radial("dome_low", [(0, CHOC[5]), (3.6, CHOC[4])])
    radial("dome_mid", [(0, CHOC[5])])
    save(canvas(32, fill=CHOC[6]), "dome_top")
    save(canvas(16, fill=CHOC[7]), "gloss")

    # Peanut butter: golden, grainy, darker roasted flecks and a few bright oily ones.
    pb = canvas(32, fill=PB[4])
    px = pb.load()
    for y in range(32):
        for x in range(32):
            h = (x * 73 + y * 151 + x * y * 7) % 23
            if h < 3:
                px[x, y] = rgba(PB[5])
            elif h < 5:
                px[x, y] = rgba(PB[2])
            elif h == 5 and (x + y) % 3 == 0:
                px[x, y] = rgba(PB[6])
    save(pb, "pb")
    save(canvas(16, fill=PB[2]), "pb_edge")

    # A cut face: a bright lip, the chocolate lid flecked with peanut, then the filling.
    cut = pb.copy()
    fill(cut, (0, 0, 31, 0), CHOC[6])
    fill(cut, (0, 1, 31, 1), CHOC[3])
    for x in (5, 17, 26):
        cut.putpixel((x, 1), rgba(PB[5]))
    fill(cut, (0, 2, 31, 2), PB[2])
    save(cut, "cut")

    base = canvas(32, fill=CHOC[2])
    fill(base, (0, 0, 31, 0), CHOC[3])
    save(base, "base")


# --------------------------------------------------------------------------------------
# Geometry
# --------------------------------------------------------------------------------------

def stave(i: int, top: float) -> dict:
    """One wall stave in sector i, leaning outward, standing from the foot to `top`."""
    proud = 0.35 if i % 2 == 0 else 0.0
    lean = math.degrees(math.atan((R_TOP - R_FOOT) / (Y1 - Y0)))
    height = (top - Y0) / math.cos(math.radians(lean))
    half = R_TOP * math.tan(math.radians(STEP / 2)) * 1.12
    r = R_FOOT + proud
    tex = "wall_ridge" if i % 2 == 0 else "wall_valley"
    e = box((CX + r - WALL / 2, Y0, CZ - half), (CX + r + WALL / 2, Y0 + height, CZ + half), tex,
            faces={"up": "rim_lo", "down": "base"})
    turn(e, -lean, "z", (CX + r, Y0, CZ))
    return turn(e, -i * STEP, "y", (CX, Y0, CZ))


def wedge(i: int, y0: float, y1: float, r: float, up: str, sides: str, r0: float = 0.0,
          shade: bool = True, widen: float = 1.05) -> dict:
    """A slice of sector i from radius r0 out to r, between heights y0 and y1."""
    half = r * math.tan(math.radians(STEP / 2)) * widen
    e = box((CX + r0, y0, CZ - half), (CX + r, y1, CZ + half), sides,
            faces={"up": up, "down": "pb"}, shade=shade, offset=(r0, 0))
    return turn(e, -i * STEP, "y", (CX, y0, CZ))


def rim_piece(i: int) -> dict:
    proud = i % 2 == 0
    out = R_TOP + 0.5 + (0.05 if proud else 0.0)
    top = Y_LID + (0.35 if proud else 0.2)
    half = out * math.tan(math.radians(STEP / 2)) * 1.08
    tex = "rim_hi" if proud else "rim_lo"
    e = box((CX + RIM_IN, Y1 - 0.1, CZ - half), (CX + out, top, CZ + half), tex, faces={"up": tex + "_top"})
    return turn(e, -i * STEP, "y", (CX, Y1, CZ))


def gloss(angle: float, r0: float, r1: float, y: float, width: float) -> dict:
    e = box((CX + r0, y, CZ - width / 2), (CX + r1, y + 0.04, CZ + width / 2), "gloss", uv="full",
            shade=False, skip=("down",))
    return turn(e, -angle, "y", (CX, y, CZ))


def build() -> list[dict]:
    parts = []
    # Foot: a dark disc under the walls.
    parts += lathe((CX, Y0, CZ), [(0.0, R_FOOT - 0.3), (0.7, R_FOOT - 0.3)], "base", sides=16, cap="base")
    for i in range(N):
        bite = bitten(i)
        floor = bite[1] if bite else Y_LID
        lift = 0.03 if i % 2 else 0.0               # neighbouring top faces never z-fight
        parts.append(stave(i, floor if bite else Y1))
        # Filling up to the floor, each tier as wide as the wall lets it be at its base.
        for y0, y1 in zip(TIERS, TIERS[1:]):
            if y0 >= floor:
                break
            top = min(y1, floor)
            if top == Y_LID:
                top += lift
            parts.append(wedge(i, y0, top, wall_radius(y0) - WALL / 2, "lid" if top >= Y_LID else "pb", "pb",
                               shade=not bite))
        if bite:
            # Above the floor the bite took the outside; what's left shows a clean cut face.
            # Unshaded: directional shade turns the gold olive on these inward faces.
            parts.append(wedge(i, floor, Y_LID + lift, bite[0], "lid", "cut", shade=False))
        else:
            parts.append(rim_piece(i))
            if offset(i) == 4.5:
                # A thin seam of peanut butter between the lid and the rim beside the bite.
                parts.append(wedge(i, Y_LID - 0.2, Y_LID + 0.1, RIM_IN + 0.05, "pb_edge", "pb_edge",
                                   r0=RIM_IN - 0.45))
        # The dome swells in soft steps, cut back where the bite reaches it.
        y = Y_LID
        for (h, r), tex in zip(DOME, ("dome_low", "dome_mid")):
            reach = min(r, bite[0] - 0.25) if bite else r
            # Unshaded so the short step edges don't turn into a ring of dark dots.
            parts.append(wedge(i, y, Y_LID + h + lift, reach, tex, tex, shade=False, widen=1.12))
            y = Y_LID + h
    h, r = DOME_TOP
    parts += lathe((CX, Y_LID + DOME[-1][0], CZ), [(0.0, r), (h - DOME[-1][0] + 0.03, r)], "dome_top",
                   sides=16, cap="dome_top")
    # Glossy streaks on the lit side of the crown and the step below it.
    parts.append(gloss(GLOSS_AT, 0.5, 1.7, Y_LID + h + 0.03, 0.5))
    parts.append(gloss(GLOSS_AT + 8, 2.2, 3.0, Y_LID + DOME[-1][0] + 0.03, 0.45))
    return parts


def models() -> dict:
    parts = build()
    d = display("food", parts, gui_rotation=(30, 225, 0), gui_span=14.5)
    fp = d["firstperson_righthand"]
    fp["translation"][1] = round(fp["translation"][1] + 2.0, 3)
    fp["scale"] = [round(s * 0.9, 4) for s in fp["scale"]]
    tp = d["thirdperson_righthand"]
    tp["scale"] = [round(s * 1.15, 4) for s in tp["scale"]]
    return {"main": model(parts, d)}
