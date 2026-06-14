#!/usr/bin/env node
/**
 * Generates sunflower-themed (green + yellow) textures for the flower armor set.
 * Overwrites existing flower_*.png item icons and equipment layer textures in-place.
 *
 * Run from the repo root:
 *   node resourcepack/scripts/generate_flower_sunflower.js
 */

'use strict';

const zlib = require('zlib');
const fs   = require('fs');
const path = require('path');

const BASE = path.join(__dirname, '..', 'assets', 'joshymc', 'textures');
const ITEM = path.join(BASE, 'item');
const EQ   = path.join(BASE, 'entity', 'equipment');

// ── Colour palette [R, G, B, A] ──────────────────────────────────────────────
const T  = [  0,   0,   0,   0];   // transparent
const DG = [ 28,  80,  18, 255];   // dark green    (outline / shadow)
const MG = [ 55, 130,  35, 255];   // medium green  (main body)
const LG = [ 95, 175,  55, 255];   // light green   (highlight)
const DB = [ 55,  25,   5, 255];   // dark brown    (sunflower centre – seeds)
const MB = [ 95,  55,  15, 255];   // medium brown  (sunflower centre)
// eslint-disable-next-line no-unused-vars
const DY = [200, 155,   0, 255];   // dark yellow   (petal shadow)
const MY = [245, 200,  10, 255];   // medium yellow (petal main)
const LY = [255, 235,  95, 255];   // light yellow  (petal highlight)

// ── PNG encode ────────────────────────────────────────────────────────────────
function crc32(buf) {
  const table = (() => {
    const t = new Uint32Array(256);
    for (let i = 0; i < 256; i++) {
      let c = i;
      for (let k = 0; k < 8; k++) c = (c & 1) ? (0xEDB88320 ^ (c >>> 1)) : (c >>> 1);
      t[i] = c;
    }
    return t;
  })();
  let c = 0xFFFFFFFF;
  for (let i = 0; i < buf.length; i++) c = table[(c ^ buf[i]) & 0xFF] ^ (c >>> 8);
  return (c ^ 0xFFFFFFFF) >>> 0;
}

function makeChunk(tag, data) {
  const tagBuf  = Buffer.from(tag, 'ascii');
  const lenBuf  = Buffer.alloc(4);
  lenBuf.writeUInt32BE(data.length, 0);
  const body    = Buffer.concat([tagBuf, data]);
  const crcBuf  = Buffer.alloc(4);
  crcBuf.writeUInt32BE(crc32(body), 0);
  return Buffer.concat([lenBuf, body, crcBuf]);
}

function toPng(pixels, w, h) {
  // Build raw scanlines (filter byte 0 = None per row)
  const rawRows = [];
  for (let r = 0; r < h; r++) {
    const row = [0];   // filter type
    for (let c = 0; c < w; c++) {
      row.push(...pixels[r * w + c]);
    }
    rawRows.push(Buffer.from(row));
  }
  const raw        = Buffer.concat(rawRows);
  const compressed = zlib.deflateSync(raw, { level: 9 });

  const ihdrData = Buffer.alloc(13);
  ihdrData.writeUInt32BE(w, 0);
  ihdrData.writeUInt32BE(h, 4);
  ihdrData[8] = 8;    // bit depth
  ihdrData[9] = 6;    // RGBA
  ihdrData[10] = 0;
  ihdrData[11] = 0;
  ihdrData[12] = 0;

  return Buffer.concat([
    Buffer.from([0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A]),  // PNG sig
    makeChunk('IHDR', ihdrData),
    makeChunk('IDAT', compressed),
    makeChunk('IEND', Buffer.alloc(0)),
  ]);
}

function save(filePath, pixels, w, h) {
  fs.mkdirSync(path.dirname(filePath), { recursive: true });
  fs.writeFileSync(filePath, toPng(pixels, w, h));
  console.log(`  ${filePath}`);
}

// ── 16×16 item icon helpers ───────────────────────────────────────────────────
const K = {
  '.': T,   // transparent
  G: DG,    // dark green
  g: MG,    // medium green
  l: LG,    // light green
  b: DB,    // dark brown
  B: MB,    // brown
  d: DY,    // dark yellow
  y: MY,    // medium yellow
  Y: LY,    // light yellow
};

function icon(rows) {
  if (rows.length !== 16) throw new Error(`expected 16 rows, got ${rows.length}`);
  rows.forEach((r, i) => {
    if (r.length !== 16) throw new Error(`row ${i} has ${r.length} chars, expected 16: ${JSON.stringify(r)}`);
  });
  return rows.flatMap(row => [...row].map(c => K[c]));
}

// ── 16×16 designs ─────────────────────────────────────────────────────────────
//
// Column guide: 0  1  2  3  4  5  6  7  8  9 10 11 12 13 14 15
// Key: . = transparent, g = green, G = dark green, l = light green
//      y = yellow, Y = light yellow, b = dark brown, B = brown

// Helmet – green dome, sunflower disk on the front face
const HELMET = icon([
  '................',  // 0
  '....yyyyyyyy....',  // 1  top rim         (4+8+4=16)
  '...yggggggggy...',  // 2  dome            (3+1+8+1+3=16)
  '..yggggggggggy..',  // 3                  (2+1+10+1+2=16)
  '..gggYYYYYYggg..',  // 4  petal ring top  (2+3+6+3+2=16)
  '..ggYbbbbbbYgg..',  // 5  seed ring       (2+2+1+6+1+2+2=16)
  '..ggYbBBBBbYgg..',  // 6  seed centre     (2+2+1+1+4+1+1+2+2=16)
  '..ggYbBBBBbYgg..',  // 7
  '..ggYbbbbbbYgg..',  // 8
  '..gggYYYYYYggg..',  // 9  petal ring btm
  '..gggggggggggg..',  // 10 lower dome      (2+12+2=16)
  '..gg........gg..',  // 11 cheeks open     (2+2+8+2+2=16)
  '..gg........gg..',  // 12
  '................',  // 13
  '................',  // 14
  '................',  // 15
]);

// Chestplate – shoulder straps, sunflower chest piece
const CHESTPLATE = icon([
  '.ggg........ggg.',  // 0  shoulder straps (1+3+8+3+1=16)
  '.ggg........ggg.',  // 1
  '.gggyyyyyyyygg..',  // 2  neckline yellow (1+3+8+2+2=16)
  '..gggggggggggg..',  // 3  body top        (2+12+2=16)
  '..gggYYYYYYggg..',  // 4  petal ring top  (2+3+6+3+2=16)
  '..ggYbbbbbbYgg..',  // 5
  '..ggYbBBBBbYgg..',  // 6
  '..ggYbBBBBbYgg..',  // 7
  '..ggYbbbbbbYgg..',  // 8
  '..gggYYYYYYggg..',  // 9
  '..gggggggggggg..',  // 10
  '..gggggggggggg..',  // 11
  '...gggggggggg...',  // 12 body taper      (3+10+3=16)
  '................',  // 13
  '................',  // 14
  '................',  // 15
]);

// Leggings – waistband with yellow trim, two leg tubes
const LEGGINGS = icon([
  '................',  // 0
  '..gggggggggggg..',  // 1  waistband    (2+12+2=16)
  '..ggYYYYYYYYgg..',  // 2  yellow strip (2+2+8+2+2=16)
  '..ggYBBBBBBYgg..',  // 3  brown accent (2+2+1+6+1+2+2=16)
  '..ggYYYYYYYYgg..',  // 4
  '..gggggggggggg..',  // 5
  '..gggg....gggg..',  // 6  leg tubes    (2+4+4+4+2=16)
  '..gggg....gggg..',  // 7
  '..gggg....gggg..',  // 8
  '..gggg....gggg..',  // 9
  '..gggg....gggg..',  // 10
  '..gggg....gggg..',  // 11
  '................',  // 12
  '................',  // 13
  '................',  // 14
  '................',  // 15
]);

// Boots – two green boot shapes with yellow trim panel
const BOOTS = icon([
  '................',  // 0
  '.ggggg..ggggg...',  // 1              (1+5+2+5+3=16)
  '.gYYYg..gYYYg...',  // 2              (1+1+3+1+2+1+3+1+3=16)
  '.gYYYg..gYYYg...',  // 3
  '.gYYYg..gYYYg...',  // 4
  '.ggggg..ggggg...',  // 5
  '.ggggg..ggggg...',  // 6
  '.ggggg..ggggg...',  // 7
  '.gggggg.gggggg..',  // 8  toe extends (1+6+1+6+2=16)
  '................',  // 9
  '................',  // 10
  '................',  // 11
  '................',  // 12
  '................',  // 13
  '................',  // 14
  '................',  // 15
]);

// Flower Spade – sunflower bloom (petals + seed centre) atop a green stem
// The blade IS the sunflower; the stem is the handle.
const SPADE = icon([
  '................',  // 0
  '...yYYYYYYy.....',  // 1  outer top petals (3+1+6+1+5=16)
  '..yYbbbbbbYy....',  // 2  inner petals      (2+1+1+6+1+1+4=16)
  '..YybBBBBbyY....',  // 3  seed centre       (2+1+1+1+4+1+1+1+4=16)
  '..YybBBBBbyY....',  // 4
  '..yYbbbbbbYy....',  // 5
  '...yYYYYYYy.....',  // 6  outer btm petals
  '........g.......',  // 7  stem              (8+1+7=16)
  '........g.......',  // 8
  '.......gGg......',  // 9  leaf node         (7+1+1+1+6=16)
  '........g.......',  // 10
  '.......gGg......',  // 11
  '........g.......',  // 12
  '........g.......',  // 13
  '........g.......',  // 14
  '................',  // 15
]);

// ── Equipment layer textures (64×32) ─────────────────────────────────────────
//
// Standard Minecraft humanoid UV layout (64×32):
//   HEAD:    top(8,0,15,7)  btm(16,0,23,7)
//            right(0,8,7,15) face(8,8,15,15) left(16,8,23,15) back(24,8,31,15)
//   BODY:    top(20,16,27,19) btm(28,16,35,19)
//            right(16,20,19,31) front(20,20,27,31) left(28,20,31,31) back(32,20,39,31)
//   R.ARM:   top(44,16,47,19) btm(48,16,51,19)
//            right(40,20,43,31) front(44,20,47,31) left(48,20,51,31) back(52,20,55,31)
//   R.LEG:   top(4,16,7,19)  btm(8,16,11,19)
//            right(0,20,3,31) front(4,20,7,31) left(8,20,11,31) back(12,20,15,31)
//
// humanoid_leggings UV (64×32):
//   R.LEG:   same as above
//   L.LEG:   top(20,16,23,19) btm(24,16,27,19)
//            right(16,20,19,31) front(20,20,23,31) left(24,20,27,31) back(28,20,31,31)

function newCanvas(w, h) {
  return Array.from({ length: w * h }, () => [0, 0, 0, 0]);
}

function put(c, w, x, y, col) {
  const h = c.length / w;
  if (x >= 0 && x < w && y >= 0 && y < h) c[y * w + x] = col;
}

function fillRect(c, w, x1, y1, x2, y2, col) {
  for (let y = y1; y <= y2; y++)
    for (let x = x1; x <= x2; x++)
      put(c, w, x, y, col);
}

function drawBorder(c, w, x1, y1, x2, y2, col) {
  for (let x = x1; x <= x2; x++) { put(c, w, x, y1, col); put(c, w, x, y2, col); }
  for (let y = y1 + 1; y < y2; y++) { put(c, w, x1, y, col); put(c, w, x2, y, col); }
}

function sunflowerDisk(c, w, cx, cy, rSeed, rCentre, rPetal) {
  for (let dy = -rPetal; dy <= rPetal; dy++) {
    for (let dx = -rPetal; dx <= rPetal; dx++) {
      const d = Math.sqrt(dx * dx + dy * dy);
      let col;
      if (d <= rSeed)        col = DB;
      else if (d <= rCentre) col = MB;
      else if (d <= rPetal) {
        if (dx === 0 || dy === 0 || Math.abs(dx) === Math.abs(dy)) col = MY;
        else continue;
      } else continue;
      put(c, w, cx + dx, cy + dy, col);
    }
  }
}

function makeHumanoidTexture(useYellowTint) {
  const W = 64, H = 32;
  const c = newCanvas(W, H);

  const base   = useYellowTint ? LY : MG;
  const mid    = useYellowTint ? MY : LG;
  const dark   = useYellowTint ? DY : DG;

  // HEAD
  fillRect(c, W,  8, 0, 15,  7, base);
  fillRect(c, W, 16, 0, 23,  7, dark);
  fillRect(c, W,  0, 8,  7, 15, mid);
  fillRect(c, W,  8, 8, 15, 15, base);   // front face
  fillRect(c, W, 16, 8, 23, 15, mid);
  fillRect(c, W, 24, 8, 31, 15, mid);
  sunflowerDisk(c, W, 11, 11, 1, 2, 3);
  drawBorder(c, W,  8, 0, 15,  7, dark);
  drawBorder(c, W,  0, 8, 31, 15, dark);

  // BODY
  fillRect(c, W, 20, 16, 27, 19, mid);
  fillRect(c, W, 28, 16, 35, 19, dark);
  fillRect(c, W, 16, 20, 19, 31, mid);
  fillRect(c, W, 20, 20, 27, 31, base);   // front
  fillRect(c, W, 28, 20, 31, 31, mid);
  fillRect(c, W, 32, 20, 39, 31, mid);
  for (let x = 20; x <= 27; x++) { put(c, W, x, 23, MY); put(c, W, x, 27, MY); }
  drawBorder(c, W, 16, 20, 39, 31, dark);

  // RIGHT ARM
  fillRect(c, W, 44, 16, 47, 19, mid);
  fillRect(c, W, 48, 16, 51, 19, dark);
  fillRect(c, W, 40, 20, 43, 31, mid);
  fillRect(c, W, 44, 20, 47, 31, base);   // front
  fillRect(c, W, 48, 20, 51, 31, mid);
  fillRect(c, W, 52, 20, 55, 31, mid);
  for (let y = 23; y <= 28; y++) { put(c, W, 45, y, MY); put(c, W, 46, y, MY); }
  drawBorder(c, W, 40, 20, 55, 31, dark);

  // RIGHT LEG (boots area in humanoid layer)
  fillRect(c, W,  4, 16,  7, 19, mid);
  fillRect(c, W,  8, 16, 11, 19, dark);
  fillRect(c, W,  0, 20,  3, 31, mid);
  fillRect(c, W,  4, 20,  7, 31, base);   // front
  fillRect(c, W,  8, 20, 11, 31, mid);
  fillRect(c, W, 12, 20, 15, 31, mid);
  for (let y = 23; y <= 28; y++) { put(c, W, 5, y, MY); put(c, W, 6, y, MY); }
  drawBorder(c, W, 0, 20, 15, 31, dark);

  return c;
}

function makeLeggingsTexture() {
  const W = 64, H = 32;
  const c = newCanvas(W, H);

  // RIGHT LEG
  fillRect(c, W,  4, 16,  7, 19, LG);
  fillRect(c, W,  8, 16, 11, 19, DG);
  fillRect(c, W,  0, 20,  3, 31, MG);
  fillRect(c, W,  4, 20,  7, 31, LG);   // front
  fillRect(c, W,  8, 20, 11, 31, MG);
  fillRect(c, W, 12, 20, 15, 31, MG);
  for (let y = 22; y <= 29; y++) { put(c, W, 5, y, MY); put(c, W, 6, y, MY); }
  drawBorder(c, W, 0, 20, 15, 31, DG);

  // LEFT LEG
  fillRect(c, W, 20, 16, 23, 19, LG);
  fillRect(c, W, 24, 16, 27, 19, DG);
  fillRect(c, W, 16, 20, 19, 31, MG);
  fillRect(c, W, 20, 20, 23, 31, LG);   // front
  fillRect(c, W, 24, 20, 27, 31, MG);
  fillRect(c, W, 28, 20, 31, 31, MG);
  for (let y = 22; y <= 29; y++) { put(c, W, 21, y, MY); put(c, W, 22, y, MY); }
  drawBorder(c, W, 16, 20, 31, 31, DG);

  return c;
}

// ── Write everything ──────────────────────────────────────────────────────────
console.log('Generating sunflower flower-armor textures...');

save(path.join(ITEM, 'flower_helmet.png'),     HELMET,     16, 16);
save(path.join(ITEM, 'flower_chestplate.png'), CHESTPLATE, 16, 16);
save(path.join(ITEM, 'flower_leggings.png'),   LEGGINGS,   16, 16);
save(path.join(ITEM, 'flower_boots.png'),      BOOTS,      16, 16);
save(path.join(ITEM, 'flower_spade.png'),      SPADE,      16, 16);

save(path.join(EQ, 'humanoid',          'flower.png'),        makeHumanoidTexture(false), 64, 32);
save(path.join(EQ, 'humanoid',          'flower_yellow.png'), makeHumanoidTexture(true),  64, 32);
save(path.join(EQ, 'humanoid_leggings', 'flower.png'),        makeLeggingsTexture(),       64, 32);

console.log('Done.');
