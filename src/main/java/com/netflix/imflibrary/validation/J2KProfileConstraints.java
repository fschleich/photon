package com.netflix.imflibrary.validation;

import com.netflix.imflibrary.IMFErrorLogger;
import com.netflix.imflibrary.J2KHeaderParameters;

/**
 * Per-profile JPEG 2000 codestream constraint checks for IMF (ISO/IEC 15444-1 Tables A.51/A.52) and Broadcast
 * Contribution (Table A.48) profiles, expressed against the {@link J2KHeaderParameters} model. High-Throughput
 * (HTJ2K / BCP) constraints are handled separately by
 * {@link IMFApp2E5EDConstraintsValidator#validateHTConstraints(J2KHeaderParameters)}.
 *
 * <p>All findings are reported as NON_FATAL. The checks here are derived from
 * {@code docs/jpeg2000-codestream-validation-constraints.md} and are expected to be refined over time.</p>
 */
public final class J2KProfileConstraints {

    /** JPEG 2000 progression orders (ISO/IEC 15444-1 Table A.16). */
    private static final int PROG_RPCL = 2;
    private static final int PROG_CPRL = 4;

    private J2KProfileConstraints() {}

    /** Resolution class of an IMF profile, carrying the maximum image dimensions and decomposition-level cap. */
    public enum ResolutionClass {
        R2K(2048, 1556, 5),
        R4K(4096, 3112, 6),
        R8K(8192, 6224, 7);

        final long maxXsiz;
        final long maxYsiz;
        final int maxDecompLevels;

        ResolutionClass(long maxXsiz, long maxYsiz, int maxDecompLevels) {
            this.maxXsiz = maxXsiz;
            this.maxYsiz = maxYsiz;
            this.maxDecompLevels = maxDecompLevels;
        }
    }

    /** Broadcast Contribution profile variant. */
    public enum BroadcastVariant { SINGLE_TILE, MULTI_TILE, MULTI_TILE_REVERSIBLE }

    private static void error(IMFErrorLogger logger, String prefix, String message) {
        logger.addError(IMFErrorLogger.IMFErrors.ErrorCodes.IMF_ESSENCE_COMPONENT_ERROR,
                IMFErrorLogger.IMFErrors.ErrorLevels.NON_FATAL, prefix + message);
    }

    /**
     * Validates an IMF Single Tile Lossy (A.51) or Single/Multi-tile Reversible (A.52) codestream.
     *
     * @param p          parsed codestream parameters
     * @param resolution resolution class (2K/4K/8K)
     * @param reversible true for the reversible profile (5-3 transform), false for the lossy profile (9-7 transform)
     * @param logger     error logger
     * @param prefix     message prefix (e.g. "J2K-CS [frame 0] IMF-2K: ")
     */
    public static void validateIMF(J2KHeaderParameters p, ResolutionClass resolution, boolean reversible,
                                   IMFErrorLogger logger, String prefix) {
        if (p == null) {
            error(logger, prefix, "missing or incomplete JPEG 2000 main header");
            return;
        }

        // SIZ
        if (p.xsiz > resolution.maxXsiz || p.ysiz > resolution.maxYsiz) {
            error(logger, prefix, String.format("image size %dx%d exceeds profile maximum %dx%d",
                    p.xsiz, p.ysiz, resolution.maxXsiz, resolution.maxYsiz));
        }
        if (p.xosiz != 0 || p.yosiz != 0 || p.xtosiz != 0 || p.ytosiz != 0) {
            error(logger, prefix, "image and tile origin (XOsiz/YOsiz/XTOsiz/YTOsiz) shall be 0");
        }
        if (p.csiz == null || p.csiz.length == 0 || p.csiz.length > 3) {
            error(logger, prefix, String.format("number of components (%d) shall be <= 3",
                    p.csiz == null ? 0 : p.csiz.length));
            return;
        }
        validateSubSamplingIMF(p, logger, prefix);
        validateBitDepth(p, 7, 15, logger, prefix);

        // COD
        if (p.cod == null) {
            error(logger, prefix, "missing COD marker");
            return;
        }
        if (p.cod.numLayers != 1) {
            error(logger, prefix, String.format("number of layers (%d) shall be exactly 1", p.cod.numLayers));
        }
        if (p.cod.xcb != 5 || p.cod.ycb != 5) {
            error(logger, prefix, String.format("code-block size shall be 32x32 (xcb=ycb=5); found xcb=%d ycb=%d",
                    p.cod.xcb, p.cod.ycb));
        }
        if (p.cod.cbStyle != 0) {
            error(logger, prefix, String.format("code-block style (SPcod) shall be 0; found 0x%02X", p.cod.cbStyle));
        }
        if (reversible && p.cod.transformation != 1) {
            error(logger, prefix, "reversible profile requires the 5-3 reversible transform");
        } else if (!reversible && p.cod.transformation != 0) {
            error(logger, prefix, "lossy profile requires the 9-7 irreversible transform");
        }
        if (p.cod.progressionOrder != PROG_CPRL) {
            error(logger, prefix, "progression order shall be CPRL");
        }
        validatePrecincts(p, logger, prefix);

        int maxDecomp = reversible ? maxReversibleDecompLevels(p, resolution) : resolution.maxDecompLevels;
        if (p.cod.numDecompLevels < 1 || p.cod.numDecompLevels > maxDecomp) {
            error(logger, prefix, String.format("number of decomposition levels (%d) shall be in 1..%d",
                    p.cod.numDecompLevels, maxDecomp));
        }
    }

    /**
     * Validates a Broadcast Contribution codestream (A.48).
     */
    public static void validateBroadcast(J2KHeaderParameters p, BroadcastVariant variant,
                                         IMFErrorLogger logger, String prefix) {
        if (p == null) {
            error(logger, prefix, "missing or incomplete JPEG 2000 main header");
            return;
        }
        if (p.xosiz != 0 || p.yosiz != 0 || p.xtosiz != 0 || p.ytosiz != 0) {
            error(logger, prefix, "image and tile origin (XOsiz/YOsiz/XTOsiz/YTOsiz) shall be 0");
        }
        if (p.csiz == null || p.csiz.length == 0 || p.csiz.length > 4) {
            error(logger, prefix, String.format("number of components (%d) shall be <= 4",
                    p.csiz == null ? 0 : p.csiz.length));
            return;
        }
        validateBitDepth(p, 7, 11, logger, prefix);

        if (p.cod == null) {
            error(logger, prefix, "missing COD marker");
            return;
        }
        if (p.cod.numLayers != 1) {
            error(logger, prefix, String.format("number of layers (%d) shall be exactly 1", p.cod.numLayers));
        }
        if (p.cod.xcb < 5 || p.cod.xcb > 7) {
            error(logger, prefix, String.format("horizontal code-block size (xcb=%d) shall be in 5..7", p.cod.xcb));
        }
        if (p.cod.ycb < 5 || p.cod.ycb > 6) {
            error(logger, prefix, String.format("vertical code-block size (ycb=%d) shall be in 5..6", p.cod.ycb));
        }
        if (p.cod.xcb + p.cod.ycb > 14) { // exponents include +2 each; raw xcb+ycb <= 12 (A.18)
            error(logger, prefix, "code-block size violates xcb + ycb <= 12 (raw)");
        }
        if (p.cod.cbStyle != 0) {
            error(logger, prefix, String.format("code-block style (SPcod) shall be 0; found 0x%02X", p.cod.cbStyle));
        }
        boolean reversible = variant == BroadcastVariant.MULTI_TILE_REVERSIBLE;
        if (reversible && p.cod.transformation != 1) {
            error(logger, prefix, "multi-tile reversible profile requires the 5-3 reversible transform");
        } else if (!reversible && p.cod.transformation != 0) {
            error(logger, prefix, "profile requires the 9-7 irreversible transform");
        }
        if (p.cod.progressionOrder != PROG_CPRL) {
            error(logger, prefix, "progression order shall be CPRL");
        }
        validatePrecincts(p, logger, prefix);
        if (p.cod.numDecompLevels < 1 || p.cod.numDecompLevels > 5) {
            error(logger, prefix, String.format("number of decomposition levels (%d) shall be in 1..5",
                    p.cod.numDecompLevels));
        }
    }

    /* (XRsiz_i = 1 for all components) or (XRsiz_1 = 1 and XRsiz_i = 2 for the remaining); YRsiz_i = 1. */
    private static void validateSubSamplingIMF(J2KHeaderParameters p, IMFErrorLogger logger, String prefix) {
        for (int i = 0; i < p.csiz.length; i++) {
            if (p.csiz[i].yrsiz != 1) {
                error(logger, prefix, String.format("vertical sub-sampling for component %d shall be 1", i));
            }
        }
        if (p.csiz[0].xrsiz != 1) {
            error(logger, prefix, "horizontal sub-sampling for component 1 shall be 1");
        }
    }

    private static void validateBitDepth(J2KHeaderParameters p, int min, int max, IMFErrorLogger logger, String prefix) {
        short ssiz0 = p.csiz[0].ssiz;
        for (int i = 0; i < p.csiz.length; i++) {
            short ssiz = (short) (p.csiz[i].ssiz & 0x7F); // strip sign bit
            if (ssiz < min || ssiz > max) {
                error(logger, prefix, String.format("component %d bit depth (%d) shall be in %d..%d",
                        i, ssiz + 1, min + 1, max + 1));
            }
            if (p.csiz[i].ssiz != ssiz0) {
                error(logger, prefix, "all components shall have the same bit depth");
            }
        }
    }

    /* PPx = PPy = 7 for the N_L LL band, else 8 (precinct bytes 0x77 then 0x88). */
    private static void validatePrecincts(J2KHeaderParameters p, IMFErrorLogger logger, String prefix) {
        if (p.cod.precinctSizes == null || p.cod.precinctSizes.length == 0 || p.cod.precinctSizes[0] != 0x77) {
            error(logger, prefix, "invalid N_L LL band precinct size (expected 0x77)");
            return;
        }
        for (int i = 1; i < p.cod.precinctSizes.length; i++) {
            if (p.cod.precinctSizes[i] != (short) 0x88) {
                error(logger, prefix, "invalid non-N_L LL band precinct size (expected 0x88)");
                break;
            }
        }
    }

    private static int maxReversibleDecompLevels(J2KHeaderParameters p, ResolutionClass resolution) {
        long tile = p.xtsiz != null ? p.xtsiz : 0L;
        int byTile;
        if (tile >= 8192) byTile = 7;
        else if (tile >= 4096) byTile = 6;
        else if (tile >= 2048) byTile = 5;
        else byTile = 4; // XTsiz >= 1024
        return Math.min(byTile, resolution.maxDecompLevels);
    }
}
