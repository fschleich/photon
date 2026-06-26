package com.netflix.imflibrary.validation;

import com.netflix.imflibrary.IMFErrorLogger;
import com.netflix.imflibrary.IMFErrorLoggerImpl;
import com.netflix.imflibrary.J2KHeaderParameters;
import com.netflix.imflibrary.JPEG2000;
import com.netflix.imflibrary.JPEG2000Codestream;
import com.netflix.imflibrary.app.IMFTrackFileReader;
import com.netflix.imflibrary.st0377.header.UL;
import com.netflix.imflibrary.utils.ErrorLogger;

import java.io.IOException;
import java.math.BigInteger;
import java.util.Arrays;
import java.util.List;

/**
 * Dedicated JPEG 2000 codestream-level validation pass for IMF App #2E (SMPTE ST 2067-21).
 *
 * <p>For the sampled frames of a track file this pass: (a) verifies the codestream main header conforms to the profile
 * identified by the picture essence coding UL, (b) cross-checks the codestream against the MXF JPEG 2000 sub-descriptor,
 * and (c) verifies required markers are present and disallowed markers are absent. All findings are NON_FATAL.</p>
 */
public final class J2KCodestreamValidator {

    /** Bytes read from each sampled essence element; the main header (SOC..first SOT) is well within this. */
    private static final int MAX_HEADER_BYTES = 8192;

    private J2KCodestreamValidator() {}

    /**
     * Runs codestream validation over the frames selected by {@code policy}.
     *
     * @param trackFileReader an MXF track file reader
     * @param policy          the frame sampling policy (NONE returns no errors)
     * @param parentLogger    error logger to which findings are added
     * @return the list of findings (also added to {@code parentLogger})
     */
    public static List<ErrorLogger.ErrorObject> validate(IMFTrackFileReader trackFileReader,
                                                         EssenceSamplingPolicy policy,
                                                         IMFErrorLogger parentLogger) {
        IMFErrorLogger logger = new IMFErrorLoggerImpl();
        try {
            if (policy.getMode() == EssenceSamplingPolicy.Mode.NONE) {
                return logger.getErrors();
            }

            UL pictureEssenceCoding = trackFileReader.getPictureEssenceCodingUL(logger);
            if (pictureEssenceCoding == null || !isSupportedJ2KProfile(pictureEssenceCoding)) {
                // Not a JPEG 2000 essence we validate at codestream level; nothing to do.
                parentLogger.addAllErrors(logger.getErrors());
                return logger.getErrors();
            }

            J2KHeaderParameters descriptorParams = trackFileReader.getDescriptorJ2KHeaderParameters(logger);

            long frameCount = 0;
            BigInteger duration = trackFileReader.getEssenceDuration(logger);
            if (duration != null) {
                frameCount = duration.longValueExact();
            }
            List<Integer> indices = policy.frameIndices(frameCount);
            if (indices.isEmpty()) {
                parentLogger.addAllErrors(logger.getErrors());
                return logger.getErrors();
            }

            for (int frameIndex : indices) {
                validateFrame(trackFileReader, frameIndex, pictureEssenceCoding, descriptorParams, logger);
            }
        } catch (IOException | RuntimeException e) {
            logger.addError(IMFErrorLogger.IMFErrors.ErrorCodes.IMF_ESSENCE_COMPONENT_ERROR,
                    IMFErrorLogger.IMFErrors.ErrorLevels.NON_FATAL,
                    "J2K-CS: codestream validation could not be completed: " + e.getMessage());
        }
        parentLogger.addAllErrors(logger.getErrors());
        return logger.getErrors();
    }

    private static void validateFrame(IMFTrackFileReader trackFileReader, int frameIndex, UL pictureEssenceCoding,
                                      J2KHeaderParameters descriptorParams, IMFErrorLogger logger) {
        String prefix = String.format("J2K-CS [frame %d]: ", frameIndex);
        byte[] header;
        try {
            header = trackFileReader.getEssenceElementHeaderBytes(frameIndex, MAX_HEADER_BYTES, logger);
        } catch (IOException e) {
            logger.addError(IMFErrorLogger.IMFErrors.ErrorCodes.IMF_ESSENCE_COMPONENT_ERROR,
                    IMFErrorLogger.IMFErrors.ErrorLevels.NON_FATAL, prefix + "could not read essence: " + e.getMessage());
            return;
        }
        if (header == null) {
            logger.addError(IMFErrorLogger.IMFErrors.ErrorCodes.IMF_ESSENCE_COMPONENT_ERROR,
                    IMFErrorLogger.IMFErrors.ErrorLevels.NON_FATAL, prefix + "could not locate essence element");
            return;
        }

        JPEG2000Codestream codestream;
        try {
            codestream = JPEG2000Codestream.fromBytes(header);
        } catch (Exception e) {
            logger.addError(IMFErrorLogger.IMFErrors.ErrorCodes.IMF_ESSENCE_COMPONENT_ERROR,
                    IMFErrorLogger.IMFErrors.ErrorLevels.NON_FATAL, prefix + "could not parse codestream: " + e.getMessage());
            return;
        }
        J2KHeaderParameters p = codestream.getHeaderParameters();

        validateMarkers(codestream.getMarkers(), logger, prefix);
        validateProfileConformance(p, pictureEssenceCoding, logger, prefix);
        validateConsistency(p, descriptorParams, logger, prefix);
    }

    private static boolean isSupportedJ2KProfile(UL ul) {
        return JPEG2000.isAPP2HT(ul)
                || JPEG2000.isIMF2KProfile(ul) || JPEG2000.isIMF4KProfile(ul) || JPEG2000.isIMF8KProfile(ul)
                || JPEG2000.isBroadcastProfile(ul);
    }

    /** Dispatches to the appropriate profile checker, using the codestream Rsiz to refine lossy/reversible/variant. */
    private static void validateProfileConformance(J2KHeaderParameters p, UL ul, IMFErrorLogger logger, String prefix) {
        if (JPEG2000.isAPP2HT(ul)) {
            // Reuse the established HTJ2K (ST 2067-21 Annex H) checker.
            logger.addAllErrors(IMFApp2E5EDConstraintsValidator.validateHTConstraints(p));
            return;
        }

        int family = p.rsiz != null ? (p.rsiz >> 8) & 0xFF : -1;
        if (JPEG2000.isBroadcastProfile(ul)) {
            J2KProfileConstraints.BroadcastVariant variant;
            switch (family) {
                case 0x02: variant = J2KProfileConstraints.BroadcastVariant.MULTI_TILE; break;
                case 0x03: variant = J2KProfileConstraints.BroadcastVariant.MULTI_TILE_REVERSIBLE; break;
                case 0x01: default: variant = J2KProfileConstraints.BroadcastVariant.SINGLE_TILE; break;
            }
            J2KProfileConstraints.validateBroadcast(p, variant, logger, prefix + "Broadcast: ");
            return;
        }

        J2KProfileConstraints.ResolutionClass resolution;
        if (JPEG2000.isIMF8KProfile(ul)) resolution = J2KProfileConstraints.ResolutionClass.R8K;
        else if (JPEG2000.isIMF4KProfile(ul)) resolution = J2KProfileConstraints.ResolutionClass.R4K;
        else resolution = J2KProfileConstraints.ResolutionClass.R2K;

        // Rsiz families 4/5/6 are lossy (single tile), 7/8/9 are reversible.
        boolean reversible = (family >= 0x07 && family <= 0x09);
        if (family < 0x04 || family > 0x09) {
            // Fall back to the codestream transform when Rsiz does not identify an IMF family.
            reversible = p.cod != null && p.cod.transformation == 1;
        }
        J2KProfileConstraints.validateIMF(p, resolution, reversible, logger, prefix + "IMF-" + resolution.name().substring(1) + ": ");
    }

    /** Required markers present, SIZ immediately after SOC, and disallowed main-header markers absent. */
    private static void validateMarkers(List<Integer> markers, IMFErrorLogger logger, String prefix) {
        if (!markers.contains(JPEG2000Codestream.MARKER_SIZ)) {
            error(logger, prefix, "missing required SIZ marker");
        } else if (markers.size() < 2 || markers.get(1) != JPEG2000Codestream.MARKER_SIZ) {
            error(logger, prefix, "SIZ marker shall immediately follow SOC");
        }
        if (!markers.contains(JPEG2000Codestream.MARKER_COD)) {
            error(logger, prefix, "missing required COD marker");
        }
        if (!markers.contains(JPEG2000Codestream.MARKER_QCD)) {
            error(logger, prefix, "missing required QCD marker");
        }
        if (markers.contains(JPEG2000Codestream.MARKER_POC)) {
            error(logger, prefix, "POC marker is disallowed");
        }
        if (markers.contains(JPEG2000Codestream.MARKER_PPM)) {
            error(logger, prefix, "PPM marker is disallowed");
        }
        if (markers.contains(JPEG2000Codestream.MARKER_RGN)) {
            error(logger, prefix, "RGN marker (region of interest) is disallowed");
        }
    }

    /** Cross-checks the codestream main header against the MXF JPEG 2000 sub-descriptor. */
    private static void validateConsistency(J2KHeaderParameters cs, J2KHeaderParameters desc, IMFErrorLogger logger, String prefix) {
        if (desc == null) {
            error(logger, prefix, "no JPEG 2000 sub-descriptor to cross-check against");
            return;
        }
        check(logger, prefix, "Rsiz", desc.rsiz, cs.rsiz);
        check(logger, prefix, "Xsiz", desc.xsiz, cs.xsiz);
        check(logger, prefix, "Ysiz", desc.ysiz, cs.ysiz);
        check(logger, prefix, "XOsiz", desc.xosiz, cs.xosiz);
        check(logger, prefix, "YOsiz", desc.yosiz, cs.yosiz);
        check(logger, prefix, "XTsiz", desc.xtsiz, cs.xtsiz);
        check(logger, prefix, "YTsiz", desc.ytsiz, cs.ytsiz);
        check(logger, prefix, "XTOsiz", desc.xtosiz, cs.xtosiz);
        check(logger, prefix, "YTOsiz", desc.ytosiz, cs.ytosiz);

        if (!csizEquals(cs.csiz, desc.csiz)) {
            error(logger, prefix, "component sizing (Csiz) differs between codestream and descriptor");
        }
        if (!java.util.Objects.equals(cs.cod, desc.cod)) {
            error(logger, prefix, "COD differs between codestream and descriptor");
        }
        if (!java.util.Objects.equals(cs.qcd, desc.qcd)) {
            error(logger, prefix, "QCD differs between codestream and descriptor");
        }
        if (!java.util.Objects.equals(cs.cap, desc.cap)) {
            error(logger, prefix, "CAP differs between codestream and descriptor");
        }
    }

    private static boolean csizEquals(J2KHeaderParameters.CSiz[] a, J2KHeaderParameters.CSiz[] b) {
        return Arrays.equals(a, b);
    }

    private static void check(IMFErrorLogger logger, String prefix, String field, Object descValue, Object csValue) {
        if (!java.util.Objects.equals(descValue, csValue)) {
            error(logger, prefix, String.format("%s differs (descriptor=%s, codestream=%s)", field, descValue, csValue));
        }
    }

    private static void error(IMFErrorLogger logger, String prefix, String message) {
        logger.addError(IMFErrorLogger.IMFErrors.ErrorCodes.IMF_ESSENCE_COMPONENT_ERROR,
                IMFErrorLogger.IMFErrors.ErrorLevels.NON_FATAL, prefix + message);
    }
}
