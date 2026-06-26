package com.netflix.imflibrary;

import org.testng.Assert;
import org.testng.annotations.Test;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.List;

@Test(groups = "unit")
public class JPEG2000CodestreamTest {

    private static final int MARKER_SOD = 0xFF93;

    /** Writes SOC, SIZ, COD and QCD (the part of the main header this parser models). */
    private static void writeMainHeader(DataOutputStream out) throws IOException {
        // SOC
        out.writeShort(JPEG2000Codestream.MARKER_SOC);

        // SIZ: 3 components -> Lsiz = 38 + 3*3 = 47
        out.writeShort(JPEG2000Codestream.MARKER_SIZ);
        out.writeShort(47);          // Lsiz
        out.writeShort(0x0401);      // Rsiz (2K IMF Single Tile Lossy, sublevel 0, mainlevel 1)
        out.writeInt(1920);          // Xsiz
        out.writeInt(1080);          // Ysiz
        out.writeInt(0);             // XOsiz
        out.writeInt(0);             // YOsiz
        out.writeInt(1920);          // XTsiz
        out.writeInt(1080);          // YTsiz
        out.writeInt(0);             // XTOsiz
        out.writeInt(0);             // YTOsiz
        out.writeShort(3);           // Csiz
        for (int i = 0; i < 3; i++) {
            out.writeByte(0x0B);     // Ssiz -> bit depth 12, unsigned
            out.writeByte(1);        // XRsiz
            out.writeByte(1);        // YRsiz
        }

        // COD: Scod(1) + SGcod(4) + SPcod fixed(5) + 6 precinct bytes -> Lcod = 2+1+4+5+6 = 18
        out.writeShort(JPEG2000Codestream.MARKER_COD);
        out.writeShort(18);          // Lcod
        out.writeByte(0x01);         // Scod (user-defined precincts present)
        out.writeByte(4);            // progression order: CPRL
        out.writeShort(1);           // number of layers
        out.writeByte(1);            // multiple component transformation
        out.writeByte(5);            // number of decomposition levels (NL)
        out.writeByte(3);            // code-block width  (raw 3 -> xcb exponent 5)
        out.writeByte(3);            // code-block height (raw 3 -> ycb exponent 5)
        out.writeByte(0x00);         // code-block style
        out.writeByte(0x00);         // transformation: 9-7 irreversible
        out.writeByte(0x77);         // precinct NLLL
        for (int i = 0; i < 5; i++) out.writeByte(0x88); // remaining precincts

        // QCD: Sqcd(1) + 3 SPqcd bytes -> Lqcd = 2+1+3 = 6
        out.writeShort(JPEG2000Codestream.MARKER_QCD);
        out.writeShort(6);           // Lqcd
        out.writeByte(0x20);         // Sqcd (no quantization style -> 1 byte SPqcd entries)
        out.writeByte(0x80);
        out.writeByte(0x90);
        out.writeByte(0xA0);
    }

    /** Builds a minimal main header (SOC, SIZ, COD, QCD) followed by an SOT. */
    private static byte[] buildMainHeader() throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(baos);
        writeMainHeader(out);
        out.writeShort(JPEG2000Codestream.MARKER_SOT); // ends the main header
        out.flush();
        return baos.toByteArray();
    }

    /** A single tile-part: SOT(12) + SOD(2) + dataLen bytes; the declared Psot can be overridden to corrupt it. */
    private static void writeTilePart(DataOutputStream out, int tileIndex, int tpsot, int tnsot, int dataLen, long psot) throws IOException {
        out.writeShort(JPEG2000Codestream.MARKER_SOT);
        out.writeShort(10);             // Lsot
        out.writeShort(tileIndex);      // Isot
        out.writeInt((int) psot);       // Psot
        out.writeByte(tpsot);           // TPsot
        out.writeByte(tnsot);           // TNsot
        out.writeShort(MARKER_SOD);     // SOD
        for (int i = 0; i < dataLen; i++) out.writeByte(0x00);
    }

    /**
     * Builds a complete codestream: main header, a TLM listing two tile-parts, the two tile-parts, and EOC.
     * The two TLM-declared lengths can be overridden to simulate a non-conformant TLM.
     */
    private static byte[] buildFullCodestream(long tlmLen0, long tlmLen1) throws IOException {
        // Tile-part actual lengths: 12 (SOT) + 2 (SOD) + dataLen.
        int data0 = 10, data1 = 6;
        long psot0 = 12 + 2 + data0; // 24
        long psot1 = 12 + 2 + data1; // 20

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(baos);
        writeMainHeader(out);

        // TLM: Ztlm(1) + Stlm(1) + 2 entries; ST=1 (1-byte tile index), SP=0 (2-byte Ptlm) -> entrySize 3
        out.writeShort(JPEG2000Codestream.MARKER_TLM);
        out.writeShort(2 + 2 + 2 * 3);  // Ltlm = 10
        out.writeByte(0x00);            // Ztlm
        out.writeByte(0x10);            // Stlm: ST=1, SP=0
        out.writeByte(0x00);            // Ttlm[0]
        out.writeShort((int) tlmLen0);  // Ptlm[0]
        out.writeByte(0x00);            // Ttlm[1]
        out.writeShort((int) tlmLen1);  // Ptlm[1]

        writeTilePart(out, 0, 0, 2, data0, psot0);
        writeTilePart(out, 0, 1, 2, data1, psot1);
        out.writeShort(JPEG2000Codestream.MARKER_EOC);
        out.flush();
        return baos.toByteArray();
    }

    @Test
    public void testParseMainHeader() throws IOException {
        JPEG2000Codestream cs = JPEG2000Codestream.fromBytes(buildMainHeader());
        J2KHeaderParameters p = cs.getHeaderParameters();

        Assert.assertEquals((int) p.rsiz, 0x0401);
        Assert.assertEquals((long) p.xsiz, 1920L);
        Assert.assertEquals((long) p.ysiz, 1080L);
        Assert.assertEquals(p.csiz.length, 3);
        Assert.assertEquals(p.csiz[0].ssiz, (short) 0x0B);
        Assert.assertEquals(p.cod.progressionOrder, (short) 4); // CPRL
        Assert.assertEquals(p.cod.numDecompLevels, (short) 5);
        Assert.assertEquals(p.cod.xcb, (short) 5); // raw 3 + 2
        Assert.assertEquals(p.cod.transformation, (short) 0);
        Assert.assertEquals(p.cod.precinctSizes.length, 6);
        Assert.assertEquals(p.cod.precinctSizes[0], (short) 0x77);
        Assert.assertEquals(p.qcd.spqcd.length, 3);

        // Header-only buffer: no tile-parts walked.
        Assert.assertTrue(cs.getTileParts().isEmpty());
    }

    @Test(expectedExceptions = com.netflix.imflibrary.exceptions.MXFException.class)
    public void testMissingSOCThrows() throws IOException {
        JPEG2000Codestream.fromBytes(new byte[]{(byte) 0xAB, (byte) 0xCD, 0x00, 0x00});
    }

    @Test
    public void testTilePartWalkAndConformantTLM() throws IOException {
        JPEG2000Codestream cs = JPEG2000Codestream.fromBytes(buildFullCodestream(24, 20));

        Assert.assertTrue(cs.isTilePartStructureValid());
        Assert.assertTrue(cs.reachedEndOfCodestream());

        List<JPEG2000Codestream.TilePart> tps = cs.getTileParts();
        Assert.assertEquals(tps.size(), 2);
        Assert.assertEquals(tps.get(0).length, 24L);
        Assert.assertEquals(tps.get(1).length, 20L);

        List<JPEG2000Codestream.TLMEntry> tlm = cs.getTLMEntries();
        Assert.assertEquals(tlm.size(), 2);
        // Conformant: TLM-declared lengths equal the measured tile-part lengths.
        Assert.assertEquals(tlm.get(0).length, tps.get(0).length);
        Assert.assertEquals(tlm.get(1).length, tps.get(1).length);
    }

    @Test
    public void testNonConformantTLMIsDetectable() throws IOException {
        // TLM declares 99 for tile-part 0, but the actual length is 24.
        JPEG2000Codestream cs = JPEG2000Codestream.fromBytes(buildFullCodestream(99, 20));

        List<JPEG2000Codestream.TilePart> tps = cs.getTileParts();
        List<JPEG2000Codestream.TLMEntry> tlm = cs.getTLMEntries();
        Assert.assertEquals(tps.size(), 2);
        Assert.assertEquals(tlm.size(), 2);
        Assert.assertNotEquals(tlm.get(0).length, tps.get(0).length); // 99 != 24
        Assert.assertEquals(tlm.get(1).length, tps.get(1).length);    // 20 == 20
    }
}
