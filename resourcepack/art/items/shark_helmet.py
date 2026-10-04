"""Shark Helmet: a dark shark-head hood worn on the head.

Frame (helmet): the wearer's head is the cube [1.6, 14.4]^3, face at -Z, +X = wearer's right.
The face stays open. A shell wraps the crown, back and cheeks; a snout juts forward over the
brow with a ring of white teeth framing the face (brow, both cheeks and a chin guard), small
eyes and gill slits sit on the sides, and a short dorsal fin rises from the crown.
"""
from __future__ import annotations

from art import shark
from art.kit import box, display, model, save

ID = "shark_helmet"
NAME = "Shark Helmet"
KIND = "helmet"
COUNTERPART = "item/netherite_helmet"


def textures() -> None:
    save(shark.shell_tile(1), "shell")
    save(shark.belly_tile(), "belly")
    save(shark.tooth_tile(), "tooth")
    save(shark.eye_tile(), "eye")
    save(shark.gill_tile(), "gill")
    save(shark.fin_tile(), "fin")
    save(shark.band_tile(), "band")


def _elements() -> list[dict]:
    full = {"uv": "full"}
    els = [
        # Crown, back of the head and cheek plates (open at the front).
        box((0.6, 14.2, 0.6), (15.4, 15.8, 15.6), "shell", **full),
        box((0.6, 3.0, 14.2), (15.4, 15.8, 15.6), "shell", **full),
        box((0.4, 4.0, 1.0), (1.6, 15.8, 15.6), "shell", **full),
        box((14.4, 4.0, 1.0), (15.6, 15.8, 15.6), "shell", **full),
        # Snout over the brow, with a paler underside.
        box((3.0, 11.6, -3.0), (13.0, 14.4, 1.0), "shell", faces={"down": "belly"}, **full),
        box((4.6, 12.0, -4.6), (11.4, 14.0, -3.0), "shell", faces={"down": "belly"}, **full),
        # Chin guard under the face.
        box((2.4, 0.8, 0.2), (13.6, 2.0, 1.6), "belly", **full),
        # Ocean-blue band along the cheek plates' lower edge.
        box((0.3, 3.4, 1.0), (0.5, 4.2, 15.6), "band", **full),
        box((15.5, 3.4, 1.0), (15.7, 4.2, 15.6), "band", **full),
        # Eyes and gills on both cheeks.
        box((0.2, 8.4, 2.4), (0.4, 10.4, 4.4), "eye", **full),
        box((15.6, 8.4, 2.4), (15.8, 10.4, 4.4), "eye", **full),
        box((0.2, 5.0, 6.0), (0.4, 9.0, 11.0), "gill", **full),
        box((15.6, 5.0, 6.0), (15.8, 9.0, 11.0), "gill", **full),
        # Dorsal fin rising from the crown, stepped to a point and swept back.
        box((7.4, 15.8, 5.0), (8.6, 16.8, 11.6), "fin", **full),
        box((7.4, 16.8, 7.0), (8.6, 17.8, 11.4), "fin", **full),
        box((7.4, 17.8, 9.0), (8.6, 18.6, 11.2), "fin", **full),
    ]
    # Teeth: brow row, two cheek columns, chin row. All stick out of the front of the ring.
    for i in range(7):
        x = 2.6 + i * 1.7
        els.append(box((x, 10.2, 0.2), (x + 1.2, 11.6, 1.2), "tooth", **full))
    for i in range(4):
        y = 3.4 + i * 1.8
        els.append(box((1.6, y, 0.2), (2.6, y + 1.2, 1.2), "tooth", **full))
        els.append(box((13.4, y, 0.2), (14.4, y + 1.2, 1.2), "tooth", **full))
    for i in range(6):
        x = 3.2 + i * 1.8
        els.append(box((x, 2.0, 0.2), (x + 1.2, 3.2, 1.2), "tooth", **full))
    return els


def models() -> dict:
    els = _elements()
    return {"main": model(els, display("helmet", els))}
