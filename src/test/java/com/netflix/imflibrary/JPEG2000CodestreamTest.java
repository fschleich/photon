package com.netflix.imflibrary;

import org.testng.Assert;
import org.testng.annotations.Test;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;

@Test(groups = "unit")
public class JPEG2000CodestreamTest {

    /** Builds a minimal but well-formed J2K main header (SOC, SIZ, COD, QCD) followed by an SOT. */
    private static byte[] buildMainHeader() throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(baos); // DataOutputStream is big-endian

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

        // SOT (ends the main header)
        out.writeShort(JPEG2000Codestream.MARKER_SOT);

        out.flush();
        return baos.toByteArray();
    }

    @Test
    public void testParseMainHeader() throws IOException {
        JPEG2000Codestream cs = JPEG2000Codestream.fromBytes(buildMainHeader());
        J2KHeaderParameters p = cs.getHeaderParameters();

        // SIZ
        Assert.assertEquals((int) p.rsiz, 0x0401);
        Assert.assertEquals((long) p.xsiz, 1920L);
        Assert.assertEquals((long) p.ysiz, 1080L);
        Assert.assertEquals((long) p.xosiz, 0L);
        Assert.assertEquals((long) p.xtsiz, 1920L);
        Assert.assertEquals(p.csiz.length, 3);
        Assert.assertEquals(p.csiz[0].ssiz, (short) 0x0B);
        Assert.assertEquals(p.csiz[0].xrsiz, (short) 1);
        Assert.assertEquals(p.csiz[2].yrsiz, (short) 1);

        // COD
        Assert.assertEquals(p.cod.progressionOrder, (short) 4); // CPRL
        Assert.assertEquals(p.cod.numLayers, 1);
        Assert.assertEquals(p.cod.multiComponentTransform, (short) 1);
        Assert.assertEquals(p.cod.numDecompLevels, (short) 5);
        Assert.assertEquals(p.cod.xcb, (short) 5); // raw 3 + 2
        Assert.assertEquals(p.cod.ycb, (short) 5);
        Assert.assertEquals(p.cod.cbStyle, (short) 0);
        Assert.assertEquals(p.cod.transformation, (short) 0);
        Assert.assertEquals(p.cod.precinctSizes.length, 6);
        Assert.assertEquals(p.cod.precinctSizes[0], (short) 0x77);
        Assert.assertEquals(p.cod.precinctSizes[1], (short) 0x88);

        // QCD
        Assert.assertEquals(p.qcd.sqcd, (short) 0x20);
        Assert.assertEquals(p.qcd.spqcd.length, 3);
        Assert.assertEquals(p.qcd.spqcd[0], 0x80);

        // Markers in order: SOC, SIZ, COD, QCD, SOT
        Assert.assertEquals(cs.getMarkers().get(0).intValue(), JPEG2000Codestream.MARKER_SOC);
        Assert.assertEquals(cs.getMarkers().get(1).intValue(), JPEG2000Codestream.MARKER_SIZ);
        Assert.assertEquals(cs.getMarkers().get(cs.getMarkers().size() - 1).intValue(), JPEG2000Codestream.MARKER_SOT);
    }

    @Test(expectedExceptions = com.netflix.imflibrary.exceptions.MXFException.class)
    public void testMissingSOCThrows() throws IOException {
        JPEG2000Codestream.fromBytes(new byte[]{(byte) 0xAB, (byte) 0xCD, 0x00, 0x00});
    }
}
