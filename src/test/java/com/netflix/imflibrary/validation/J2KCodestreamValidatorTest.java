package com.netflix.imflibrary.validation;

import com.netflix.imflibrary.IMFErrorLogger;
import com.netflix.imflibrary.IMFErrorLoggerImpl;
import com.netflix.imflibrary.app.IMFTrackFileReader;
import com.netflix.imflibrary.app.IMPAnalyzer;
import com.netflix.imflibrary.utils.ErrorLogger;
import com.netflix.imflibrary.utils.FileByteRangeProvider;
import org.testng.Assert;
import org.testng.annotations.Test;
import testUtils.TestHelper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

@Test(groups = "unit")
public class J2KCodestreamValidatorTest {

    private IMFTrackFileReader htVideoReader() throws IOException {
        Path inputMXFFile = TestHelper.findResourceByPath("TestIMP/HT/IMP/VIDEO_6ed567b7-c030-46d6-9c1c-0f09bab4b962.mxf");
        Path workingDirectory = Files.createTempDirectory("j2k-validator-test");
        return new IMFTrackFileReader(workingDirectory, new FileByteRangeProvider(inputMXFFile));
    }

    @Test
    public void testNoneProducesNoErrors() throws IOException {
        IMFErrorLogger logger = new IMFErrorLoggerImpl();
        List<ErrorLogger.ErrorObject> errors = J2KCodestreamValidator.validate(htVideoReader(), EssenceSamplingPolicy.none(), logger);
        Assert.assertTrue(errors.isEmpty(), "NONE policy must not produce errors");
    }

    /**
     * On the HT sample the codestream-vs-descriptor consistency check and the tile-part structural walk must pass: the
     * codestream main header matches the sub-descriptor, the tile-part chain is internally consistent, and it ends with
     * EOC. (This particular sample legitimately lacks a TLM marker and uses CPRL progression, which are reported
     * separately by the TLM-presence and HT-profile checks - those are out of scope for this assertion.)
     */
    @Test
    public void testFirstFrameConsistencyAndStructureClean() throws IOException {
        IMFErrorLogger logger = new IMFErrorLoggerImpl();
        List<ErrorLogger.ErrorObject> errors = J2KCodestreamValidator.validate(htVideoReader(), EssenceSamplingPolicy.first(), logger);

        List<ErrorLogger.ErrorObject> structuralOrConsistency = errors.stream()
                .filter(e -> e.toString().contains("differs")
                        || e.toString().contains("tile-part structure is inconsistent")
                        || e.toString().contains("does not terminate with an EOC"))
                .collect(java.util.stream.Collectors.toList());
        for (ErrorLogger.ErrorObject e : structuralOrConsistency) {
            System.out.println("UNEXPECTED: " + e.toString());
        }
        Assert.assertTrue(structuralOrConsistency.isEmpty(),
                "codestream/descriptor consistency and tile-part structure must be clean on the HT sample");
    }

    /** End-to-end: the --j2k-codestream flag must flow through IMPAnalyzer.analyzeDelivery to the validator. */
    @Test
    public void testAnalyzeDeliveryWiresCodestreamValidation() throws IOException {
        Path htImp = TestHelper.findResourceByPath("TestIMP/HT/IMP");

        Map<String, List<ErrorLogger.ErrorObject>> none = IMPAnalyzer.analyzeDelivery(htImp, EssenceSamplingPolicy.none());
        Map<String, List<ErrorLogger.ErrorObject>> first = IMPAnalyzer.analyzeDelivery(htImp, EssenceSamplingPolicy.first());

        // Codestream findings attach to the VIDEO track file's error entry (CPL-level findings attach to the CPL
        // entry). The reused HT checker flags this sample's progression order, so it appears against the VIDEO file
        // only when codestream validation is enabled - proving the flag reaches the validator.
        Assert.assertFalse(videoFileHasCodestreamFinding(none), "codestream findings must not appear with NONE policy");
        Assert.assertTrue(videoFileHasCodestreamFinding(first), "codestream findings must appear with FIRST policy");
    }

    /** End-to-end single-file path: analyzeFile on an MXF must run codestream validation when a policy is given. */
    @Test
    public void testAnalyzeFileWiresCodestreamValidation() throws IOException {
        Path videoMxf = TestHelper.findResourceByPath("TestIMP/HT/IMP/VIDEO_6ed567b7-c030-46d6-9c1c-0f09bab4b962.mxf");

        List<ErrorLogger.ErrorObject> none = IMPAnalyzer.analyzeFile(videoMxf, null, EssenceSamplingPolicy.none());
        List<ErrorLogger.ErrorObject> first = IMPAnalyzer.analyzeFile(videoMxf, null, EssenceSamplingPolicy.first());

        boolean noneHasCodestreamFinding = none.stream().anyMatch(e -> e.toString().contains("APP2.HT:") || e.toString().contains("J2K-CS"));
        boolean firstHasCodestreamFinding = first.stream().anyMatch(e -> e.toString().contains("APP2.HT:") || e.toString().contains("J2K-CS"));
        Assert.assertFalse(noneHasCodestreamFinding, "codestream findings must not appear with NONE policy");
        Assert.assertTrue(firstHasCodestreamFinding, "codestream findings must appear with FIRST policy");
    }

    private static boolean videoFileHasCodestreamFinding(Map<String, List<ErrorLogger.ErrorObject>> errorMap) {
        return errorMap.entrySet().stream()
                .filter(entry -> entry.getKey().contains("VIDEO_6ed567b7"))
                .flatMap(entry -> entry.getValue().stream())
                .anyMatch(e -> e.toString().contains("APP2.HT:") || e.toString().contains("J2K-CS"));
    }
}
