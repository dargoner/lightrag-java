package io.github.lightragjava.indexing;

public record ParentChildProfile(
    boolean enabled,
    int childWindowSize,
    int childOverlap
) {
    public ParentChildProfile {
        if (childWindowSize <= 0) {
            throw new IllegalArgumentException("childWindowSize must be positive");
        }
        if (childOverlap < 0) {
            throw new IllegalArgumentException("childOverlap must be non-negative");
        }
        if (childOverlap >= childWindowSize) {
            throw new IllegalArgumentException("childOverlap must be smaller than childWindowSize");
        }
    }

    public static ParentChildProfile disabled() {
        return new ParentChildProfile(false, 400, 40);
    }

    public static ParentChildProfile enabled(int childWindowSize, int childOverlap) {
        return new ParentChildProfile(true, childWindowSize, childOverlap);
    }
}
