package com.netflix.imflibrary;

import com.netflix.imflibrary.exceptions.MXFException;
import com.netflix.imflibrary.utils.ByteArrayDataProvider;
import com.netflix.imflibrary.utils.ByteProvider;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Parses the main header of a JPEG 2000 codestream (ISO/IEC 15444-1 / Rec. ITU-T T.800, Annex A) into the
 * {@link J2KHeaderParameters} model that Photon already derives from the MXF/CPL JPEG 2000 sub-descriptor.
 *
 * <p>Only the main header is parsed: parsing begins at the SOC marker and stops at the first SOT (start of tile-part)
 * or EOC marker. This is sufficient to recover the SIZ, CAP, COD and QCD marker segments that the App2E
 * (SMPTE ST 2067-21) profile constraints are expressed against. The ordered list of markers encountered in the main
 * header is retained for marker presence / ordering checks.</p>
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

    private final J2KHeaderParameters headerParameters;
    private final List<Integer> markers;

    private JPEG2000Codestream(J2KHeaderParameters headerParameters, List<Integer> markers) {
        this.headerParameters = headerParameters;
        this.markers = markers;
    }

    /**
     * Parses the main header of the supplied JPEG 2000 codestream.
     *
     * @param codestream the raw JPEG 2000 codestream bytes (at least the main header; trailing bytes are ignored)
     * @return a parsed {@link JPEG2000Codestream}
     * @throws IOException if the buffer is exhausted before the main header is fully parsed
     * @throws MXFException if the codestream does not start with an SOC marker
     */
    public static JPEG2000Codestream fromBytes(byte[] codestream) throws IOException {
        ByteProvider bp = new ByteArrayDataProvider(codestream);
        List<Integer> markers = new ArrayList<>();

        int soc = readU16(bp);
        markers.add(soc);
        if (soc != MARKER_SOC) {
            throw new MXFException(String.format("JPEG 2000 codestream does not start with SOC marker (found 0x%04X)", soc));
        }

        J2KHeaderParameters p = new J2KHeaderParameters();

        while (true) {
            int marker;
            try {
                marker = readU16(bp);
            } catch (IOException e) {
                break; // main header consumed without encountering SOT/EOC
            }

            if (marker == MARKER_SOT || marker == MARKER_EOC) {
                markers.add(marker);
                break;
            }

            markers.add(marker);

            // All remaining main-header markers carry a 2-byte segment length (including the length field itself).
            int segmentLength = readU16(bp);
            byte[] body = bp.getBytes(segmentLength - 2);
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
                default:
                    // not needed for profile validation; segment body already consumed
                    break;
            }
        }

        return new JPEG2000Codestream(p, markers);
    }

    /** @return the J2K header parameters recovered from the main header. */
    public J2KHeaderParameters getHeaderParameters() {
        return this.headerParameters;
    }

    /** @return the marker codes encountered in the main header, in order, starting with SOC. */
    public List<Integer> getMarkers() {
        return this.markers;
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
}
