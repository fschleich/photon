# JPEG 2000 Codestream Validation — Profile Constraint Reference

Planning reference for the App2E (SMPTE ST 2067-21) JPEG 2000 codestream-level
validation feature. This document pins down, per profile, the codestream
constraints that the new validator must check, mapped to the marker fields
Photon already models in `J2KHeaderParameters`.

## Sources

- **ISO/IEC 15444-1:2024 (Rec. ITU-T T.800 V4, 07/2024)** — codestream syntax
  (Annex A) and profile definitions. Local copy:
  `~/Downloads/ISO-15444-1-2024_ITU-T-REC-T.800-202407-I!!PDF-E.pdf`.
  Key tables: A.10 (Rsiz), A.48–A.50 (Broadcast), A.51–A.54 (IMF), plus marker
  syntax A.5 (SIZ/CAP/PRF) and A.6 (COD/COC/QCD/QCC).
- **SMPTE ST 2067-21 (IMF App #2E)** — https://doc.smpte-doc.org/st2067-21-private/main/
  §6.5 (Encoding Profile), Annex F (compression labels), **Annex H (HTJ2K
  constraints, normative)**. App2E selects the allowed ISO profiles by image
  dimension and adds HTJ2K constraint sets.
- **ISO/IEC 15444-15** — HTJ2K (Part 15) capabilities signalled in the CAP
  marker (`Pcap` bit 15 / `Ccap15`). Already implemented in
  `IMFApp2E5EDConstraintsValidator.validateHTConstraints(...)`.

---

## 1. Profile identification (Rsiz, ISO Table A.10)

The codestream `SIZ.Rsiz` field (16-bit) identifies the profile. Photon already
identifies profiles from the MXF *picture-essence-coding UL* in
`JPEG2000.java`; the codestream `Rsiz` is the in-band equivalent and **must be
cross-checked against the UL-identified profile** (consistency check).

| Rsiz (binary)        | Profile                                                | App2E? |
|----------------------|--------------------------------------------------------|--------|
| `0000 0001 0000 wwww` | Broadcast Contribution **Single Tile** (Mainlevel w, A.48/A.49) | ✓ |
| `0000 0010 0000 wwww` | Broadcast Contribution **Multi-tile** (Mainlevel w, A.48/A.49)  | ✓ |
| `0000 0011 0000 0110` | Broadcast Contribution **Multi-tile Reversible** ML6 (A.48/A.50) | ✓ |
| `0000 0011 0000 0111` | Broadcast Contribution **Multi-tile Reversible** ML7 (A.48/A.50) | ✓ |
| `0000 0100 yyyy wwww` | **2K IMF Single Tile Lossy** (A.51; Sublevel y, Mainlevel w) | ✓ |
| `0000 0101 yyyy wwww` | **4K IMF Single Tile Lossy** (A.51) | ✓ |
| `0000 0110 yyyy wwww` | **8K IMF Single Tile Lossy** (A.51) | ✓ |
| `0000 0111 yyyy wwww` | **2K IMF Single/Multi Tile Reversible** (A.52) | ✓ |
| `0000 1000 yyyy wwww` | **4K IMF Single/Multi Tile Reversible** (A.52) | ✓ |
| `0000 1001 yyyy wwww` | **8K IMF Single/Multi Tile Reversible** (A.52) | ✓ |
| `0100 0000 0000 0000` | Extended capabilities required → see CAP marker (HTJ2K) | ✓ (HT) |
| `0000 0000 0000 0011`–`0111` | 2K/4K Digital Cinema, etc. (Table A.46) | ✗ (App #1) |

- `wwww` = Mainlevel (lower 4 bits), `yyyy` = Sublevel (bits 4–7) for IMF.
- For HTJ2K the profile is signalled by the **CAP** marker (`Pcap` bit 15), with
  `Rsiz` typically `0100 0000 0000 0000` (extended capabilities required).
- App2E (ST 2067-21 §6.5.2, Table 5) restricts the *allowed* profile per image
  size; see §6 below.

---

## 2. Marker parsing reference (for `J2KHeaderParameters.fromCodestream`)

Main header order: **SOC → SIZ → [CAP] → [PRF] → COD → [COC…] → QCD → [QCC…] →
[other] → (first SOT ends main header)**. We only need the main header; stop at
the first `SOT` (`0xFF90`).

| Marker | Code     | Fields we parse |
|--------|----------|-----------------|
| SOC    | `0xFF4F` | (delimiter, no segment) — must be first 2 bytes |
| SIZ    | `0xFF51` | Rsiz(16), Xsiz/Ysiz/XOsiz/YOsiz/XTsiz/YTsiz/XTOsiz/YTOsiz(32 each), Csiz(16), then per-component Ssiz(8)/XRsiz(8)/YRsiz(8) |
| CAP    | `0xFF50` | Pcap(32), then Ccap_i(16) for each set bit in Pcap |
| PRF    | `0xFF56` | Lprf, Pprf_i(16…) — only present if Rsiz==`0000 1111 1111 1111` |
| COD    | `0xFF52` | Scod(8); SGcod = progression(8)/numLayers(16)/MCT(8); SPcod = NL(8)/xcb(8)/ycb(8)/cbStyle(8)/transform(8)/precincts(var) |
| QCD    | `0xFF5C` | Sqcd(8), SPqcd(var) |
| SOT    | `0xFF90` | start of first tile-part → stop main-header parse |

**Field encodings (ISO A.13–A.21), with the convention already used by
`J2KHeaderParameters`:**

- **Scod** (A.13): bit0 = user-defined precincts present; bit1 = SOP allowed;
  bit2 = EPH used. (Profiles require bits 1–2 handling per "SOP/EPH" rules.)
- **Progression order** (A.16): `0=LRCP, 1=RLCP, 2=RPCL, 3=PCRL, 4=CPRL`.
  - ⚠️ **IMF & Broadcast profiles require `CPRL` (=4)** with **POC disallowed**.
  - ⚠️ **HTJ2K (App2.HT) requires `RPCL` (=2)** (ST 2067-21 Annex H).
- **Multiple component transformation** (A.17): `0=none, 1=used` (MCT; with 9-7
  for irreversible, 5-3 for reversible). `J2KHeaderParameters.cod.multiComponentTransform`.
- **NL** = number of decomposition levels (0–32). `cod.numDecompLevels`.
- **xcb/ycb** (A.18): stored value `v` → exponent `= v + 2`, code-block dim
  `= 2^(v+2)`. ⚠️ **`J2KHeaderParameters` already stores the +2 exponent**
  (`xcb = parsed + 2`), so compare against the *exponent* (e.g. 32×32 → xcb=ycb=5).
  Constraint `xcb + ycb ≤ 12` (on raw values) / dim ≤ 2^10.
- **Code-block style** (A.19): `cod.cbStyle`. IMF/Broadcast require `0x00`;
  HTJ2K requires `0x40` (HT bit).
- **Transformation** (A.20): `0 = 9-7 irreversible`, `1 = 5-3 reversible`.
  `J2KHeaderParameters.cod.transformation` (note: HT checker treats `==1` as reversible).
- **Precinct size byte** (A.21): low nibble = PPx, high nibble = PPy.
  "PPx=PPy=7 for NLLL band, else 8" → first byte `0x77`, remaining `0x88`.
- **Ssiz** (A.11): bit depth `= (Ssiz & 0x7F) + 1`; MSB set = signed.

---

## 3. IMF Single Tile **Lossy** profiles — ISO Table A.51

(Rsiz `0000 0100/0101/0110 yyyy wwww` for 2K/4K/8K)

| Constraint | 2K | 4K | 8K |
|---|---|---|---|
| Image size | Xsiz ≤ 2048, Ysiz ≤ 1556 | Xsiz ≤ 4096, Ysiz ≤ 3112 | Xsiz ≤ 8192, Ysiz ≤ 6224 |
| Tiles | one tile: XTsiz+XTOsiz ≥ Xsiz, YTsiz+YTOsiz ≥ Ysiz | same | same |
| Origin | XOsiz=YOsiz=XTOsiz=YTOsiz=0 | same | same |
| Sub-sampling | (XRsiz_i=1 all) or (XRsiz_1=1, XRsiz_i=2 rest); YRsiz_i=1 | same | same |
| Components | Csiz ≤ 3 | same | same |
| Bit depth | 7 ≤ Ssiz_i ≤ 15 (8–16 bit unsigned) | same | same |
| RGN | disallowed | same | same |
| PPM/PPT | disallowed | same | same |
| COD/COC/QCD/QCC | main header only | same | same |
| **Decomposition NL** | 1 ≤ NL ≤ 5 | 1 ≤ NL ≤ 6 | 1 ≤ NL ≤ 7 |
| Layers | exactly 1 | same | same |
| Code-block | xcb=ycb=5 (32×32) | same | same |
| Code-block style | SPcod=0x00 | same | same |
| Transformation | 9-7 irreversible (=0) | same | same |
| Precinct | NLLL `0x77`, else `0x88` | same | same |
| Progression | CPRL; POC disallowed | same | same |
| Tile-parts | ≤3; one per component | same | same |
| TLM | required in each image | same | same |
| Bit rate / sampling | per A.53/A.54 (level-dependent) | same | same |

## 4. IMF Single/Multi-tile **Reversible** profiles — ISO Table A.52

(Rsiz `0000 0111/1000/1001 yyyy wwww` for 2K/4K/8K)

Same as A.51 **except**:

| Constraint | 2K | 4K | 8K |
|---|---|---|---|
| Tiles | single tile (as A.51) **or** multi-tile XTsiz=YTsiz=1024 | …or 1024 or 2048 | …or 1024/2048/4096 |
| **Decomposition NL** | 1 ≤ NL ≤ 4 (XTsiz≥1024) or ≤ 5 (XTsiz≥2048) | + ≤ 6 (XTsiz≥4096) | + ≤ 7 (XTsiz≥8192) |
| Transformation | **5-3 reversible (=1)** | same | same |
| Tile-parts | one per tile-component | same | same |

All other rows (origin, sub-sampling, Csiz≤3, bit depth 7–15, RGN/PPM
disallowed, layers=1, xcb=ycb=5, cbStyle=0, precinct 0x77/0x88, CPRL/POC
disallowed, TLM required) identical to A.51.

## 5. Broadcast Contribution profiles — ISO Table A.48

(Rsiz `0000 0001/0010/0011 …`)

| Constraint | Single Tile | Multi-tile | Multi-tile Reversible |
|---|---|---|---|
| Tiles | one tile (XTsiz+XTOsiz ≥ Xsiz, …) | 1 or 4 equal tiles (Ysiz/4 ≤ YTsiz+YTOsiz ≤ Ysiz; Xsiz/2 ≤ XTsiz+XTOsiz ≤ Xsiz) | same as multi-tile |
| Origin | XOsiz=YOsiz=XTOsiz=YTOsiz=0 | same | same |
| Sub-sampling | (XRsiz_i=1 all) or (XRsiz_1=1, XRsiz_4=1, XRsiz_i=2 rest); YRsiz_i=1 | same | same |
| Components | Csiz ≤ 4 | same | same |
| Bit depth | 7 ≤ Ssiz_i ≤ 11 (8–12 bit unsigned) | same | same |
| RGN | disallowed | same | same |
| PPM/PPT | disallowed | same | same |
| COD/COC/QCD/QCC | main header only | same | same |
| Decomposition NL | 1 ≤ NL ≤ 5 | same | same |
| Layers | exactly 1 | same | same |
| Code-block | 5 ≤ xcb ≤ 7 and 5 ≤ ycb ≤ 6 (and xcb+ycb≤12 via A.18) | same | same |
| Code-block style | SPcod=0x00 | same | same |
| Transformation | 9-7 irreversible (=0) | 9-7 irreversible (=0) | **5-3 reversible (=1)** |
| Precinct | NLLL `0x77`, else `0x88` | same | same |
| Progression | CPRL; POC disallowed | same | same |
| Tile-parts | ≤4; one per component | ≤16; one per tile-component | ≤16; one per tile-component |
| TLM | required in each image | same | same |
| Bit rate / sampling | per A.49 | per A.49 | per A.50 (ML6/ML7 only) |

## 6. HTJ2K / BCP constraints — ST 2067-21 Annex H *(already implemented)*

Implemented in `IMFApp2E5EDConstraintsValidator.validateHTConstraints(...)`.
Key differences from the classic profiles above:

- **CAP** required: `Pcap` has only bit 15 set (`pcap == 131072`), exactly one
  `Ccap` (Ccap15). Bits 12–15 of Ccap15 = 0 (HTONLY/SINGLEHT/RGNFREE/HOMOGENEOUS).
  Bit 5 = 0 → reversible (HTREV / APP2.HT.REV); bit 5 = 1 → irreversible
  (HTIRV / APP2.HT.IRV). Bits 0–4 encode parameter B.
- **Progression order: RPCL (=2)** — *not* CPRL.
- **Code-block style: `0x40`** (HT bit) — *not* `0x00`.
- Code-block: 5 ≤ xcb ≤ 7, 5 ≤ ycb ≤ 6.
- Decomposition NL by size: ≤5 (≤2048), ≤6 (≤4096), ≤7 (≤8192).
- Csiz ≤ 4, bit depth 7 ≤ Ssiz ≤ 15, identical across components.
- Precinct NLLL `0x77`, else `0x88`. Layers = 1. POC/PPM disallowed, TLM present.
- Parameter B (MAGBP) check vs Ssiz/NL/MCT (Tables H.2/H.3); IRV requires B ≤ 31.

The new codestream validator can call this same routine with codestream-derived
`J2KHeaderParameters`.

---

## 7. App2E-specific rules — ST 2067-21 §6.5

- **§6.5.2 / Table 5 — allowed profile per image dimension** (this is the
  cross-check between image size and the identified profile):

  | Image (W × H) | Allowed ISO profiles | HTJ2K option |
  |---|---|---|
  | ≤ 3840 × ≤ 2160 | Broadcast Contribution Single Tile / Multi-tile Reversible | APP2.HT.REV / .IRV |
  | ≤ 2048 × ≤ 1556 | 2K IMF Reversible / 2K IMF Lossy | APP2.HT.REV / .IRV |
  | 2049–4096 × ≤ 3112 | 4K IMF Reversible / 4K IMF Lossy | APP2.HT.REV / .IRV |
  | 4097–8192 × ≤ 6224 | 8K IMF Reversible / 8K IMF Lossy | APP2.HT.REV / .IRV |

- **§6.5.1** — each frame (or field) is a **single codestream**.
- **§6.5.3 / Table 6** — component ordering: RGB → R'/G'/B'; YCbCr → Y'/C'B/C'R.
  (Codestream carries only Csiz/sub-sampling; component *identity* comes from the
  descriptor — this is a descriptor/codestream consistency item.)
- **Annex F** — compression-label ULs for Broadcast Single Tile L1–5 (`0x11`–`0x15`)
  and Multi-tile Reversible L6–7 (`0x16`–`0x17`); these are the UL bytes
  `JPEG2000.isBroadcastProfile` already switches on.

---

## 8. Checkability notes

- **Fully checkable from the codestream main header** (SIZ/CAP/COD/QCD): image
  size, origin, tiling, sub-sampling, Csiz, bit depth, NL, layers, code-block
  size/style, transformation, precinct, progression order, CAP/HT parameters,
  marker presence (SOC/SIZ/COD/QCD) and disallowed markers (RGN/POC/PPM in main
  header; COD/COC/QCD/QCC main-header-only).
- **Needs full codestream scan (beyond main header):** PPT/EPH/SOP presence in
  tile-part headers, tile-part counts, TLM presence — require walking tile-part
  headers. Decide whether to parse to first SOT only (cheap) or scan tile-parts
  (more complete). *Recommendation: phase 1 = main header only; flag tile-part
  checks as a later enhancement.*
- **Not checkable from a single codestream:** Max compressed bit rate / sampling
  rate (A.49/A.50/A.53/A.54) need frame rate + per-frame compressed size (frame
  rate from CPL edit rate, size from the index table) — a cross-frame check, not
  a single-codestream check.
- **Consistency checks (codestream ↔ descriptor):** every `J2KHeaderParameters`
  field parsed from the codestream should equal the value in the MXF/CPL
  JPEG2000 sub-descriptor (which Photon already parses via
  `J2KHeaderParameters.fromDOMNode` / `fromJPEG2000PictureSubDescriptorBO`).
  Rsiz ↔ UL-identified profile consistency belongs here too.

## 9. Mapping summary → `J2KHeaderParameters`

| Constraint area | Field(s) |
|---|---|
| Image/tile size & origin | `xsiz, ysiz, xosiz, yosiz, xtsiz, ytsiz, xtosiz, ytosiz` |
| Components / sub-sampling / bit depth | `csiz[].ssiz, csiz[].xrsiz, csiz[].yrsiz` |
| Profile / capabilities | `rsiz`, `cap.pcap`, `cap.ccap[]` |
| Decomposition / layers / MCT | `cod.numDecompLevels, cod.numLayers, cod.multiComponentTransform` |
| Code-block / style / transform | `cod.xcb, cod.ycb` (already +2 exponent), `cod.cbStyle, cod.transformation` |
| Progression / precinct | `cod.progressionOrder, cod.precinctSizes[]` |
| Quantization | `qcd.sqcd, qcd.spqcd[]` |

New work: a `fromCodestream(...)` adapter populating this same struct, plus
per-profile checkers for the A.51/A.52/A.48 tables (the HT checker in
`IMFApp2E5EDConstraintsValidator` already covers Annex H).
