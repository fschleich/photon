package com.netflix.imflibrary.validation;

import java.util.ArrayList;
import java.util.List;

/**
 * Describes which edit units (frames) of an essence track file should be sampled for codestream-level validation.
 *
 * <ul>
 *   <li>{@link Mode#NONE} – no codestream validation (default; preserves historical behaviour)</li>
 *   <li>{@link Mode#FIRST} – validate the first frame only</li>
 *   <li>{@link Mode#EVERY_N} – validate every Nth frame (frames 0, N, 2N, ...)</li>
 *   <li>{@link Mode#ALL} – validate every frame</li>
 * </ul>
 */
public final class EssenceSamplingPolicy {

    public enum Mode { NONE, FIRST, EVERY_N, ALL }

    private final Mode mode;
    private final int interval;

    private EssenceSamplingPolicy(Mode mode, int interval) {
        this.mode = mode;
        this.interval = interval;
    }

    public static EssenceSamplingPolicy none() {
        return new EssenceSamplingPolicy(Mode.NONE, 0);
    }

    public static EssenceSamplingPolicy first() {
        return new EssenceSamplingPolicy(Mode.FIRST, 0);
    }

    public static EssenceSamplingPolicy everyN(int interval) {
        if (interval < 1) {
            throw new IllegalArgumentException("Sampling interval must be >= 1");
        }
        return new EssenceSamplingPolicy(Mode.EVERY_N, interval);
    }

    public static EssenceSamplingPolicy all() {
        return new EssenceSamplingPolicy(Mode.ALL, 0);
    }

    /**
     * Parses a textual specification of the form {@code none | first | every:N | all} (case-insensitive).
     *
     * @param spec the specification, or null/empty for {@link #none()}
     * @return the parsed policy
     * @throws IllegalArgumentException if the specification is not recognised
     */
    public static EssenceSamplingPolicy parse(String spec) {
        if (spec == null || spec.trim().isEmpty()) {
            return none();
        }
        String s = spec.trim().toLowerCase();
        switch (s) {
            case "none":
                return none();
            case "first":
                return first();
            case "all":
                return all();
            default:
                if (s.startsWith("every:")) {
                    try {
                        return everyN(Integer.parseInt(s.substring("every:".length())));
                    } catch (NumberFormatException e) {
                        throw new IllegalArgumentException("Invalid sampling interval in '" + spec + "'");
                    }
                }
                throw new IllegalArgumentException("Unrecognised sampling specification '" + spec + "' (expected none|first|every:N|all)");
        }
    }

    public Mode getMode() {
        return this.mode;
    }

    public int getInterval() {
        return this.interval;
    }

    /**
     * Returns the zero-based edit unit indices to sample for an essence of the given length.
     *
     * @param frameCount the total number of edit units (frames) in the essence
     * @return the ordered list of frame indices to validate (empty for {@link Mode#NONE} or an empty essence)
     */
    public List<Integer> frameIndices(long frameCount) {
        List<Integer> indices = new ArrayList<>();
        if (frameCount <= 0 || this.mode == Mode.NONE) {
            return indices;
        }
        switch (this.mode) {
            case FIRST:
                indices.add(0);
                break;
            case ALL:
                for (int i = 0; i < frameCount; i++) {
                    indices.add(i);
                }
                break;
            case EVERY_N:
                for (long i = 0; i < frameCount; i += this.interval) {
                    indices.add((int) i);
                }
                break;
            default:
                break;
        }
        return indices;
    }
}
