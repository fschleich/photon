package com.netflix.imflibrary;

import com.netflix.imflibrary.exceptions.MXFException;
import com.netflix.imflibrary.utils.ByteArrayDataProvider;
import com.netflix.imflibrary.utils.ByteProvider;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Collections;
import java.util.List;

/**
 * Parses a JPEG 2000 codestream (ISO/IEC 15444-1 / Rec. ITU-T T.800, Annex A) into the {@link J2KHeaderParameters}
 * model that Photon already derives from the MXF/CPL JPEG 2000 sub-descriptor.
 *
 * <p>The main header is always parsed (SOC, SIZ, CAP, COD, QCD, TLM, ...). If the supplied buffer contains the whole
 * codestream (not just the main header), the tile-part structure is additionally walked so that the declared TLM
 * tile-part lengths can be verified against the actual tile-part lengths. When only the main-header prefix is supplied,
 * {@link #getTileParts()} is empty.</p>
 */
public final class JPEG2000Codestream {

    /* Delimiting and marker segment codes (ISO/IEC 15444-1 Table A.2) */
    public static final int MARKER_SOC = 0xFF4F; // Start of codestream
    public static final int MARKER_SIZ = 0xFF51; // Image and tile size
    public static final int MARKER_CAP = 0xFF50; // Extended capabilities
    public static final int MARKER_PRF = 0xFF56; // Profile
    public static final int MARKER_COD = 0xFF52; // Coding style default
    public static final int MARKER_COC = 0xFF53; // Coding style component
    public static final int MARKER_RGN = 0xFF5E; // Region of interest
    public static final int MARKER_QCD = 0xFF5C; // Quantization default
    public static final int MARKER_QCC = 0xFF5D; // Quantization component
    public static final int MARKER_POC = 0xFF5F; // Progression order change
    public static final int MARKER_TLM = 0xFF55; // Tile-part lengths, main header
    public static final int MARKER_PLM = 0xFF57; // Packet length, main header
    public static final int MARKER_PPM = 0xFF60; // Packed packet headers, main header
    public static final int MARKER_CRG = 0xFF63; // Component registration
    public static final int MARKER_COM = 0xFF64; // Comment
    public static final int MARKER_SOT = 0xFF90; // Start of tile-part (ends main header)
    public static final int MARKER_EOC = 0xFFD9; // End of codestream

    /** A tile-part length declared in a TLM marker segment (ISO/IEC 15444-1 A.7.1). */
    public static final class TLMEntry {
        public final Integer tileIndex; // null when Ttlm is not present (tile-parts are in order)
        public final long length;       // Ptlm: length in bytes from the SOT marker to the end of the tile-part data
        public final int ztlm;          // index of the originating TLM segment (used for ordering)

        public TLMEntry(Integer tileIndex, long length, int ztlm) {
            this.tileIndex = tileIndex;
            this.length = length;
            this.ztlm = ztlm;
        }
    }

    /** A tile-part discovered while walking the codestream (ISO/IEC 15444-1 A.4.2). */
    public static final class TilePart {
        public final int tileIndex;   // Isot
        public final long psot;       // Psot as declared in the SOT marker (0 = extends to EOC)
        public final int tpsot;       // tile-part index within the tile
        public final int tnsot;       // number of tile-parts of the tile (0 = not yet defined)
        public final long offset;     // byte offset of the SOT marker within the codestream
        public final long length;     // actual tile-part length (measured by navigating the structure)

        public TilePart(int tileIndex, long psot, int tpsot, int tnsot, long offset, long length) {
            this.tileIndex = tileIndex;
            this.psot = psot;
            this.tpsot = tpsot;
            this.tnsot = tnsot;
            this.offset = offset;
            this.length = length;
        }
    }

    private final J2KHeaderParameters headerParameters;
    private final List<Integer> markers;
    private final List<TLMEntry> tlmEntries;
    private final List<TilePart> tileParts;
    private final boolean tilePartStructureValid;
    private final boolean reachedEndOfCodestream;

    private JPEG2000Codestream(J2KHeaderParameters headerParameters, List<Integer> markers, List<TLMEntry> tlmEntries,
                               List<TilePart> tileParts, boolean tilePartStructureValid, boolean reachedEndOfCodestream) {
        this.headerParameters = headerParameters;
        this.markers = markers;
        this.tlmEntries = tlmEntries;
        this.tileParts = tileParts;
        this.tilePartStructureValid = tilePartStructureValid;
        this.reachedEndOfCodestream = reachedEndOfCodestream;
    }

    /**
     * Parses the supplied JPEG 2000 codestream.
     *
     * @param codestream the raw JPEG 2000 codestream bytes (at least the main header; the whole codestream is required
     *                   for tile-part / TLM conformance)
     * @return a parsed {@link JPEG2000Codestream}
     * @throws IOException if the buffer is exhausted before the main header is fully parsed
     * @throws MXFException if the codestream does not start with an SOC marker
     */
    public static JPEG2000Codestream fromBytes(byte[] codestream) throws IOException {
        ByteProvider bp = new ByteArrayDataProvider(codestream);
        List<Integer> markers = new ArrayList<>();
        List<TLMEntry> tlmEntries = new ArrayList<>();

        int pos = 0;
        int soc = readU16(bp);
        pos += 2;
        markers.add(soc);
        if (soc != MARKER_SOC) {
            throw new MXFException(String.format("JPEG 2000 codestream does not start with SOC marker (found 0x%04X)", soc));
        }

        J2KHeaderParameters p = new J2KHeaderParameters();
        int firstSotOffset = -1;

        while (true) {
            int markerPos = pos;
            int marker;
            try {
                marker = readU16(bp);
                pos += 2;
            } catch (IOException e) {
                break; // main header consumed without encountering SOT/EOC
            }

            if (marker == MARKER_SOT || marker == MARKER_EOC) {
                markers.add(marker);
                if (marker == MARKER_SOT) {
                    firstSotOffset = markerPos;
                }
                break;
            }

            markers.add(marker);

            // All remaining main-header markers carry a 2-byte segment length (including the length field itself).
            int segmentLength = readU16(bp);
            pos += 2;
            byte[] body = bp.getBytes(segmentLength - 2);
            pos += (segmentLength - 2);
            ByteProvider seg = new ByteArrayDataProvider(body);

            switch (marker) {
                case MARKER_SIZ:
                    parseSIZ(seg, p);
                    break;
                case MARKER_CAP:
                    parseCAP(seg, p);
                    break;
                case MARKER_COD:
                    parseCOD(seg, p, body.length);
                    break;
                case MARKER_QCD:
                    parseQCD(seg, p, body.length);
                    break;
                case MARKER_TLM:
                    parseTLM(body, tlmEntries);
                    break;
                default:
                    // not needed for profile validation; segment body already consumed
                    break;
            }
        }

        // Order TLM entries across (possibly multiple) TLM segments by their Ztlm index.
        tlmEntries.sort(Comparator.comparingInt(e -> e.ztlm));

        List<TilePart> tileParts = new ArrayList<>();
        boolean structureValid = true;
        boolean reachedEOC = false;
        if (firstSotOffset >= 0 && firstSotOffset + 12 <= codestream.length) {
            WalkResult result = walkTileParts(codestream, firstSotOffset);
            tileParts = result.tileParts;
            structureValid = result.structureValid;
            reachedEOC = result.reachedEndOfCodestream;
        }

        return new JPEG2000Codestream(p, markers, tlmEntries, tileParts, structureValid, reachedEOC);
    }

    /** @return the J2K header parameters recovered from the main header. */
    public J2KHeaderParameters getHeaderParameters() {
        return this.headerParameters;
    }

    /** @return the marker codes encountered in the main header, in order, starting with SOC. */
    public List<Integer> getMarkers() {
        return this.markers;
    }

    /** @return the tile-part lengths declared in the TLM marker segment(s), ordered by Ztlm. */
    public List<TLMEntry> getTLMEntries() {
        return Collections.unmodifiableList(this.tlmEntries);
    }

    /** @return the tile-parts discovered by walking the codestream (empty if only the main header was supplied). */
    public List<TilePart> getTileParts() {
        return Collections.unmodifiableList(this.tileParts);
    }

    /** @return true if the tile-part structure was navigated without inconsistency (each Psot landed on a boundary). */
    public boolean isTilePartStructureValid() {
        return this.tilePartStructureValid;
    }

    /** @return true if the tile-part walk reached the End of Codestream (EOC) marker. */
    public boolean reachedEndOfCodestream() {
        return this.reachedEndOfCodestream;
    }

    private static final class WalkResult {
        final List<TilePart> tileParts;
        final boolean structureValid;
        final boolean reachedEndOfCodestream;

        WalkResult(List<TilePart> tileParts, boolean structureValid, boolean reachedEndOfCodestream) {
            this.tileParts = tileParts;
            this.structureValid = structureValid;
            this.reachedEndOfCodestream = reachedEndOfCodestream;
        }
    }

    /*
     * Walks the tile-parts of a complete codestream by navigating from each SOT marker by its Psot length. Each jump is
     * verified to land on another SOT, on the EOC, or exactly at the end of the buffer; a jump that lands elsewhere
     * indicates an inconsistent Psot and marks the structure invalid. The measured length of each tile-part is recorded
     * for comparison against the TLM marker segment.
     */
    private static WalkResult walkTileParts(byte[] data, int start) {
        List<TilePart> parts = new ArrayList<>();
        boolean structureValid = true;
        boolean reachedEOC = false;
        int len = data.length;
        int pos = start;
        int guard = 0;

        while (pos + 12 <= len && guard++ < 1_000_000) {
            int marker = u16(data, pos);
            if (marker == MARKER_EOC) {
                reachedEOC = true;
                break;
            }
            if (marker != MARKER_SOT) {
                structureValid = false;
                break;
            }

            int isot = u16(data, pos + 4);
            long psot = u32(data, pos + 6);
            int tpsot = data[pos + 10] & 0xFF;
            int tnsot = data[pos + 11] & 0xFF;

            if (psot == 0) {
                // Last tile-part: extends to the EOC (or, failing that, the end of the buffer).
                int end = (len >= 2 && u16(data, len - 2) == MARKER_EOC) ? (len - 2) : len;
                reachedEOC = (end == len - 2);
                parts.add(new TilePart(isot, psot, tpsot, tnsot, pos, (long) (end - pos)));
                break;
            }

            parts.add(new TilePart(isot, psot, tpsot, tnsot, pos, psot));

            long nextPos = pos + psot;
            if (nextPos == len) {
                break; // codestream ends exactly at the tile-part boundary (no trailing EOC)
            }
            if (nextPos + 2 > len) {
                structureValid = false;
                break;
            }
            int nextMarker = u16(data, (int) nextPos);
            if (nextMarker == MARKER_EOC) {
                reachedEOC = true;
                break;
            }
            if (nextMarker != MARKER_SOT) {
                structureValid = false;
                break;
            }
            pos = (int) nextPos;
        }

        return new WalkResult(parts, structureValid, reachedEOC);
    }

    /* ISO/IEC 15444-1 A.5.1 Image and tile size (SIZ) */
    private static void parseSIZ(ByteProvider seg, J2KHeaderParameters p) throws IOException {
        p.rsiz = readU16(seg);
        p.xsiz = readU32(seg);
        p.ysiz = readU32(seg);
        p.xosiz = readU32(seg);
        p.yosiz = readU32(seg);
        p.xtsiz = readU32(seg);
        p.ytsiz = readU32(seg);
        p.xtosiz = readU32(seg);
        p.ytosiz = readU32(seg);
        int csiz = readU16(seg);
        p.csiz = new J2KHeaderParameters.CSiz[csiz];
        for (int i = 0; i < csiz; i++) {
            J2KHeaderParameters.CSiz c = new J2KHeaderParameters.CSiz();
            c.ssiz = (short) readU8(seg);
            c.xrsiz = (short) readU8(seg);
            c.yrsiz = (short) readU8(seg);
            p.csiz[i] = c;
        }
    }

    /* ISO/IEC 15444-1 A.5.2 Extended capabilities (CAP) */
    private static void parseCAP(ByteProvider seg, J2KHeaderParameters p) throws IOException {
        J2KHeaderParameters.CAP cap = new J2KHeaderParameters.CAP();
        cap.pcap = readU32(seg);
        int n = Long.bitCount(cap.pcap);
        cap.ccap = new int[n];
        for (int i = 0; i < n; i++) {
            cap.ccap[i] = readU16(seg);
        }
        p.cap = cap;
    }

    /* ISO/IEC 15444-1 A.6.1 Coding style default (COD) */
    private static void parseCOD(ByteProvider seg, J2KHeaderParameters p, int bodyLength) throws IOException {
        J2KHeaderParameters.COD cod = new J2KHeaderParameters.COD();
        cod.scod = (short) readU8(seg);
        // SGcod
        cod.progressionOrder = (short) readU8(seg);
        cod.numLayers = readU16(seg);
        cod.multiComponentTransform = (short) readU8(seg);
        // SPcod
        cod.numDecompLevels = (short) readU8(seg);
        cod.xcb = (short) (readU8(seg) + 2); // stored exponent offset; +2 matches J2KHeaderParameters convention
        cod.ycb = (short) (readU8(seg) + 2);
        cod.cbStyle = (short) readU8(seg);
        cod.transformation = (short) readU8(seg);
        // Precinct sizes occupy the remainder of the segment (present iff Scod bit 0 is set).
        int consumed = 1 /*Scod*/ + 1 + 2 + 1 /*SGcod*/ + 5 /*SPcod fixed*/;
        int precinctCount = Math.max(0, bodyLength - consumed);
        cod.precinctSizes = new short[precinctCount];
        for (int i = 0; i < precinctCount; i++) {
            cod.precinctSizes[i] = (short) readU8(seg);
        }
        p.cod = cod;
    }

    /* ISO/IEC 15444-1 A.6.4 Quantization default (QCD) */
    private static void parseQCD(ByteProvider seg, J2KHeaderParameters p, int bodyLength) throws IOException {
        J2KHeaderParameters.QCD qcd = new J2KHeaderParameters.QCD();
        qcd.sqcd = (short) readU8(seg);
        int spqcdSize = (qcd.sqcd & 0b11111) == 0 ? 1 : 2; // no quantization (reversible) -> 1 byte, else 2 bytes
        int count = (bodyLength - 1) / spqcdSize;
        qcd.spqcd = new int[count];
        for (int i = 0; i < count; i++) {
            if (spqcdSize == 1) {
                qcd.spqcd[i] = readU8(seg);
            } else {
                // For scalar quantization the SPqcd entry is 2 bytes (5-bit exponent + 11-bit mantissa). To keep a
                // single representation across all J2KHeaderParameters adapters, store only the high byte, mirroring
                // J2KHeaderParameters.fromDOMNode / fromJPEG2000PictureSubDescriptorBO. QCD coefficients are not
                // constrained by the App2E profiles, so no validation depends on the low byte.
                int high = readU8(seg);
                readU8(seg); // discard low byte
                qcd.spqcd[i] = high;
            }
        }
        p.qcd = qcd;
    }

    /* ISO/IEC 15444-1 A.7.1 Tile-part lengths, main header (TLM) */
    private static void parseTLM(byte[] body, List<TLMEntry> out) {
        if (body.length < 2) {
            return;
        }
        int ztlm = body[0] & 0xFF;
        int stlm = body[1] & 0xFF;
        int ttlmBytes = (stlm >> 4) & 0x3; // ST: 0, 1 or 2 bytes for the tile index Ttlm
        int ptlmBytes = ((stlm >> 6) & 0x1) == 1 ? 4 : 2; // SP: 2 or 4 bytes for the tile-part length Ptlm
        int entrySize = ttlmBytes + ptlmBytes;
        if (entrySize == 0) {
            return;
        }
        int count = (body.length - 2) / entrySize;
        int p = 2;
        for (int i = 0; i < count; i++) {
            Integer tileIndex = null;
            if (ttlmBytes == 1) {
                tileIndex = body[p] & 0xFF;
                p += 1;
            } else if (ttlmBytes == 2) {
                tileIndex = u16(body, p);
                p += 2;
            }
            long length = (ptlmBytes == 2) ? u16(body, p) : u32(body, p);
            p += ptlmBytes;
            out.add(new TLMEntry(tileIndex, length, ztlm));
        }
    }

    private static int readU8(ByteProvider bp) throws IOException {
        return bp.getBytes(1)[0] & 0xFF;
    }

    private static int readU16(ByteProvider bp) throws IOException {
        byte[] b = bp.getBytes(2);
        return ((b[0] & 0xFF) << 8) | (b[1] & 0xFF);
    }

    private static long readU32(ByteProvider bp) throws IOException {
        byte[] b = bp.getBytes(4);
        return ((long) (b[0] & 0xFF) << 24) | ((b[1] & 0xFF) << 16) | ((b[2] & 0xFF) << 8) | (b[3] & 0xFF);
    }

    private static int u16(byte[] b, int off) {
        return ((b[off] & 0xFF) << 8) | (b[off + 1] & 0xFF);
    }

    private static long u32(byte[] b, int off) {
        return ((long) (b[off] & 0xFF) << 24) | ((b[off + 1] & 0xFF) << 16) | ((b[off + 2] & 0xFF) << 8) | (b[off + 3] & 0xFF);
    }
}
