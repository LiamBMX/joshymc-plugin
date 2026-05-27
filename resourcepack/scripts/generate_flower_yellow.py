#!/usr/bin/env python3
"""
Generates yellow variants of the flower armor textures for the resource pack.
Run from the repo root: python3 resourcepack/scripts/generate_flower_yellow.py

Reads the existing green flower PNGs and shifts green-dominant pixels to yellow,
producing flower_yellow.png for the equipment layers used by helmet/chestplate.
"""

import struct
import zlib
import os

BASE = os.path.join(os.path.dirname(__file__), "..", "assets", "joshymc", "textures")


def read_png(path):
    with open(path, "rb") as f:
        data = f.read()
    assert data[:8] == b"\x89PNG\r\n\x1a\n", f"Not a PNG: {path}"
    pos = 8
    chunks = []
    while pos < len(data):
        length = struct.unpack(">I", data[pos:pos + 4])[0]
        ctype = data[pos + 4:pos + 8]
        cdata = data[pos + 8:pos + 8 + length]
        chunks.append((ctype, cdata))
        pos += 12 + length
    return chunks


def parse_ihdr(cdata):
    w, h = struct.unpack(">II", cdata[:8])
    bit_depth = cdata[8]
    color_type = cdata[9]
    return w, h, bit_depth, color_type


def decode_pixels(chunks, w, h):
    """Decode RGBA pixels, applying PNG row filters."""
    raw = b"".join(c for t, c in chunks if t == b"IDAT")
    decompressed = zlib.decompress(raw)
    bpp = 4  # RGBA
    stride = w * bpp + 1

    def paeth(a, b, c):
        p = a + b - c
        pa, pb, pc = abs(p - a), abs(p - b), abs(p - c)
        if pa <= pb and pa <= pc:
            return a
        if pb <= pc:
            return b
        return c

    rows = []
    prev = [0] * (w * bpp)
    for row in range(h):
        filt = decompressed[row * stride]
        raw_row = list(decompressed[row * stride + 1:row * stride + stride])
        if filt == 0:
            recon = raw_row
        elif filt == 1:
            recon = []
            for i, v in enumerate(raw_row):
                a = recon[i - bpp] if i >= bpp else 0
                recon.append((v + a) & 0xFF)
        elif filt == 2:
            recon = [(v + prev[i]) & 0xFF for i, v in enumerate(raw_row)]
        elif filt == 3:
            recon = []
            for i, v in enumerate(raw_row):
                a = recon[i - bpp] if i >= bpp else 0
                b = prev[i]
                recon.append((v + (a + b) // 2) & 0xFF)
        elif filt == 4:
            recon = []
            for i, v in enumerate(raw_row):
                a = recon[i - bpp] if i >= bpp else 0
                b = prev[i]
                c = prev[i - bpp] if i >= bpp else 0
                recon.append((v + paeth(a, b, c)) & 0xFF)
        else:
            raise ValueError(f"Unknown PNG filter {filt}")
        rows.append(recon)
        prev = recon
    return rows


def encode_png(w, h, rows):
    def make_chunk(ctype, cdata):
        length = struct.pack(">I", len(cdata))
        body = ctype + cdata
        crc = struct.pack(">I", zlib.crc32(body) & 0xFFFFFFFF)
        return length + body + crc

    raw = b"".join(bytes([0] + row) for row in rows)
    compressed = zlib.compress(raw, 9)

    ihdr = struct.pack(">IIBBBBB", w, h, 8, 6, 0, 0, 0)
    out = b"\x89PNG\r\n\x1a\n"
    out += make_chunk(b"IHDR", ihdr)
    out += make_chunk(b"IDAT", compressed)
    out += make_chunk(b"IEND", b"")
    return out


def green_to_yellow(rows):
    """Shift green-dominant pixels to yellow (keep R+G, reduce B)."""
    result = []
    for row in rows:
        new_row = []
        for i in range(0, len(row), 4):
            r, g, b, a = row[i], row[i + 1], row[i + 2], row[i + 3]
            if a > 0 and g > r and g > b:
                new_r = min(255, int(g))
                new_g = g
                new_b = int(b * 0.1)
                new_row += [new_r, new_g, new_b, a]
            else:
                new_row += [r, g, b, a]
        result.append(new_row)
    return result


def process(src, dst):
    chunks = read_png(src)
    ihdr_data = next(c for t, c in chunks if t == b"IHDR")
    w, h, _, _ = parse_ihdr(ihdr_data)
    rows = decode_pixels(chunks, w, h)
    yellow = green_to_yellow(rows)
    png = encode_png(w, h, yellow)
    os.makedirs(os.path.dirname(dst), exist_ok=True)
    with open(dst, "wb") as f:
        f.write(png)
    print(f"  {os.path.basename(src)} -> {dst}")


eq = os.path.join(BASE, "entity", "equipment")
process(os.path.join(eq, "humanoid", "flower.png"),
        os.path.join(eq, "humanoid", "flower_yellow.png"))

it = os.path.join(BASE, "item")
process(os.path.join(it, "flower_helmet.png"),
        os.path.join(it, "flower_helmet_yellow.png"))
process(os.path.join(it, "flower_chestplate.png"),
        os.path.join(it, "flower_chestplate_yellow.png"))

print("Done — yellow textures written.")
