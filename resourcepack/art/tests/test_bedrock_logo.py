"""Tests for the Bedrock logo glyph sheet (issue #1333). Run from resourcepack/:

    python3 -m unittest discover -s art/tests -t .
"""
from __future__ import annotations

import unittest

from PIL import Image

from art import logo


def _java_sheet() -> Image.Image:
    left = Image.open(logo.FONT_TEXTURES / "logo_left.png").convert("RGBA")
    right = Image.open(logo.FONT_TEXTURES / "logo_right.png").convert("RGBA")
    sheet = Image.new("RGBA", (2 * logo.HALF_W, logo.H), (0, 0, 0, 0))
    sheet.paste(left, (0, 0))
    sheet.paste(right, (logo.HALF_W, 0))
    return sheet


class BedrockLogoSheetTest(unittest.TestCase):

    def setUp(self):
        self.committed = Image.open(logo.BEDROCK_SHEET).convert("RGBA")
        self.cell = logo.BEDROCK_CELL

    def test_sheet_is_a_square_16x16_grid(self):
        self.assertEqual(self.committed.size, (16 * self.cell, 16 * self.cell))

    def test_only_the_logo_cells_are_drawn(self):
        for cy in range(16):
            for cx in range(16):
                box = (cx * self.cell, cy * self.cell, (cx + 1) * self.cell, (cy + 1) * self.cell)
                used = self.committed.crop(box).getchannel("A").getbbox() is not None
                self.assertEqual(used, cy == 0 and cx < logo.BEDROCK_CELLS, f"cell {cx},{cy}")

    def test_cells_keep_their_full_width(self):
        # Bedrock trims empty edge columns; every logo cell must keep both edges.
        alpha = self.committed.getchannel("A")
        for i in range(logo.BEDROCK_CELLS):
            for x in (i * self.cell, (i + 1) * self.cell - 1):
                self.assertTrue(any(alpha.getpixel((x, y)) for y in range(self.cell)), f"column {x}")

    def test_committed_sheet_matches_the_java_logo(self):
        expected = logo.bedrock_sheet(_java_sheet())
        self.assertEqual(expected.size, self.committed.size)
        diff = sum(1 for a, b in zip(expected.getdata(), self.committed.getdata())
                   if max(abs(p - q) for p, q in zip(a, b)) > 8)
        self.assertLessEqual(diff, 0.01 * expected.width * expected.height,
                             "glyph_E9.png is stale; run python3 -m art.logo")


if __name__ == "__main__":
    unittest.main()
