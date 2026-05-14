#!/usr/bin/env node
/**
 * Generates 16x16 enchant scroll textures for the JoshyMC resource pack.
 * Each scroll has a scroll silhouette with a parchment color themed to the enchant category.
 * Uses only Node.js stdlib — no npm dependencies required.
 */

"use strict";

const fs   = require("fs");
const path = require("path");
const zlib = require("zlib");

const OUT = path.join(__dirname, "assets/joshymc/textures/item");

// ── PNG encoder ──────────────────────────────────────────────────────────────

function chunk(tag, data) {
    const tagBuf  = Buffer.from(tag, "ascii");
    const lenBuf  = Buffer.alloc(4);
    lenBuf.writeUInt32BE(data.length, 0);
    const crcSrc  = Buffer.concat([tagBuf, data]);
    const crcVal  = crc32(crcSrc);
    const crcBuf  = Buffer.alloc(4);
    crcBuf.writeUInt32BE(crcVal >>> 0, 0);
    return Buffer.concat([lenBuf, tagBuf, data, crcBuf]);
}

// CRC-32 table
const CRC_TABLE = (() => {
    const t = new Uint32Array(256);
    for (let n = 0; n < 256; n++) {
        let c = n;
        for (let k = 0; k < 8; k++) c = (c & 1) ? (0xEDB88320 ^ (c >>> 1)) : (c >>> 1);
        t[n] = c;
    }
    return t;
})();

function crc32(buf) {
    let c = 0xFFFFFFFF;
    for (let i = 0; i < buf.length; i++) c = CRC_TABLE[(c ^ buf[i]) & 0xFF] ^ (c >>> 8);
    return (c ^ 0xFFFFFFFF) >>> 0;
}

function makePNG(pixels, w = 16, h = 16) {
    const sig = Buffer.from([0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A]);

    // IHDR: width(4) height(4) bit_depth(1) color_type(1) comp(1) filter(1) interlace(1)
    const ihdrData = Buffer.alloc(13);
    ihdrData.writeUInt32BE(w, 0);
    ihdrData.writeUInt32BE(h, 4);
    ihdrData[8] = 8;  // bit depth
    ihdrData[9] = 6;  // color type RGBA
    const ihdr = chunk("IHDR", ihdrData);

    // Raw image bytes: each row prefixed with filter byte 0 (None)
    const raw = Buffer.alloc(h * (1 + w * 4));
    let pos = 0;
    for (let y = 0; y < h; y++) {
        raw[pos++] = 0; // filter None
        for (let x = 0; x < w; x++) {
            const p = pixels[y * w + x];
            raw[pos++] = p[0];
            raw[pos++] = p[1];
            raw[pos++] = p[2];
            raw[pos++] = p[3];
        }
    }

    const compressed = zlib.deflateSync(raw, { level: 9 });
    const idat = chunk("IDAT", compressed);
    const iend = chunk("IEND", Buffer.alloc(0));

    return Buffer.concat([sig, ihdr, idat, iend]);
}

// ── Scroll shape ─────────────────────────────────────────────────────────────
//
// 16×16 layout (see generate_scroll_textures.py for full description)
const T=0, H=1, M=2, S=3, B=4, P=5, L=6;

const GRID = [
    [T,T,T,T,T,T,T,T,T,T,T,T,T,T,T,T],
    [T,T,H,H,H,H,H,H,H,H,H,H,H,H,T,T],
    [T,T,M,M,M,M,M,M,M,M,M,M,M,M,T,T],
    [T,T,S,S,S,S,S,S,S,S,S,S,S,S,T,T],
    [T,T,B,P,P,P,P,P,P,P,P,P,P,B,T,T],
    [T,T,B,P,L,L,L,L,L,L,L,L,P,B,T,T],
    [T,T,B,P,P,P,P,P,P,P,P,P,P,B,T,T],
    [T,T,B,P,L,L,L,L,L,L,L,L,P,B,T,T],
    [T,T,B,P,P,P,P,P,P,P,P,P,P,B,T,T],
    [T,T,B,P,L,L,L,L,L,L,L,L,P,B,T,T],
    [T,T,B,P,P,P,P,P,P,P,P,P,P,B,T,T],
    [T,T,B,P,P,P,P,P,P,P,P,P,P,B,T,T],
    [T,T,S,S,S,S,S,S,S,S,S,S,S,S,T,T],
    [T,T,M,M,M,M,M,M,M,M,M,M,M,M,T,T],
    [T,T,H,H,H,H,H,H,H,H,H,H,H,H,T,T],
    [T,T,T,T,T,T,T,T,T,T,T,T,T,T,T,T],
];

const ROLL_H  = [210, 165, 100, 255];
const ROLL_M  = [168, 118,  58, 255];
const ROLL_S  = [112,  72,  32, 255];
const BORDER  = [148, 102,  48, 255];
const TRANS   = [  0,   0,   0,   0];

function makeScroll(parchment, textLine) {
    const cmap = { [T]: TRANS, [H]: ROLL_H, [M]: ROLL_M, [S]: ROLL_S, [B]: BORDER, [P]: parchment, [L]: textLine };
    const pixels = [];
    for (const row of GRID) for (const code of row) pixels.push(cmap[code]);
    return makePNG(pixels);
}

// ── Per-enchant parchment colours ────────────────────────────────────────────

const SWORD      = [[235,185,160,255], [180, 95, 85,255]];
const AXE        = [[240,205,155,255], [195,120, 55,255]];
const HELMET     = [[175,220,215,255], [ 75,155,160,255]];
const CHESTPLATE = [[175,200,240,255], [ 75,110,185,255]];
const LEGGINGS   = [[215,180,240,255], [135, 85,185,255]];
const BOOTS      = [[180,235,190,255], [ 75,160,100,255]];
const SHOVEL     = [[240,230,150,255], [180,160, 60,255]];
const PICKAXE    = [[185,210,230,255], [ 80,120,170,255]];
const HOE        = [[245,210,125,255], [185,140, 48,255]];
const ALL_TOOLS  = [[210,205,200,255], [125,120,115,255]];
const BASE       = [[240,220,165,255], [160,128, 75,255]];

const ENCHANT_COLORS = {
    lifesteal:    SWORD,      execute:     SWORD,
    bleed:        SWORD,      adrenaline:  SWORD,      striker:     SWORD,
    cleave:       AXE,        berserk:     AXE,
    paralysis:    AXE,        blizzard:    AXE,
    night_vision: HELMET,     clarity:     HELMET,
    focus:        HELMET,     xray:        HELMET,
    overload:     CHESTPLATE, dodge:       CHESTPLATE, guardian:    CHESTPLATE,
    shockwave:    LEGGINGS,   valor:       LEGGINGS,   curse_swap:  LEGGINGS,
    gears:        BOOTS,      springs:     BOOTS,
    featherweight: BOOTS,     rockets:     BOOTS,
    glass_breaker: SHOVEL,
    magnet:       ALL_TOOLS,
    autosmelt:    PICKAXE,    experience:  PICKAXE,
    condenser:    PICKAXE,    explosive:   PICKAXE,
    ground_pound: HOE,        great_harvest: HOE,      blessing:    HOE,
};

// ── Main ─────────────────────────────────────────────────────────────────────

fs.mkdirSync(OUT, { recursive: true });

// Base scroll
const basePath = path.join(OUT, "enchant_scroll.png");
fs.writeFileSync(basePath, makeScroll(...BASE));
console.log(`Wrote ${basePath}`);

// Per-enchant variants
for (const [id, colours] of Object.entries(ENCHANT_COLORS)) {
    const p = path.join(OUT, `enchant_scroll_${id}.png`);
    fs.writeFileSync(p, makeScroll(...colours));
    console.log(`Wrote ${p}`);
}

console.log(`\nDone — ${1 + Object.keys(ENCHANT_COLORS).length} scroll textures generated.`);
