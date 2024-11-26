package com.netflix.imflibrary.validation;

import com.netflix.imflibrary.IMFErrorLogger;
import com.netflix.imflibrary.IMFErrorLoggerImpl;
import com.netflix.imflibrary.RESTfulInterfaces.PayloadRecord;
import com.netflix.imflibrary.st2067_2.IMFCompositionPlaylist;
import com.netflix.imflibrary.utils.ErrorLogger;
import jakarta.annotation.Nonnull;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class IMFCoreConstraints2020Validator extends IMFCoreConstraintsValidator {

    public static final List<String> SUPPORTED_TIMED_TEXT_SEQUENCES = Collections.unmodifiableList(Arrays.asList(
            SUBTITLES_SEQUENCE, HEARING_IMPAIRED_CAPTIONS_SEQUENCE, VISUALLY_IMPAIRED_SEQUENCE, COMMENTARY_SEQUENCE, KARAOKE_SEQUENCE, FORCED_NARRATIVE_SEQUENCE));

    @Override
    public String getConstraintsSpecification() {
        return "SMPTE ST 2067-2:2020 IMF Core Constraints";
    }

    @Override
    public List<ErrorLogger.ErrorObject> validateCompositionConstraints(@Nonnull IMFCompositionPlaylist imfCompositionPlaylist, @Nonnull List<PayloadRecord> headerPartitionPayloads) {

        IMFErrorLogger imfErrorLogger = new IMFErrorLoggerImpl();

        imfErrorLogger.addAllErrors(checkVirtualTracks(imfCompositionPlaylist));

        return imfErrorLogger.getErrors();
    }

}
