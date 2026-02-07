package com.ammann.entropy.enumeration;

/**
 * Classification of entropy data quality based on a composite quality score.
 *
 * <p>Each level defines a minimum threshold. A score is classified into the highest
 * level whose threshold it meets or exceeds.
 */
public enum QualityStatus
{
    /** Quality score of 0.95 or above. */
    EXCELLENT(0.95),
    /** Quality score of 0.85 or above. */
    GOOD(0.85),
    /** Quality score of 0.70 or above. */
    WARNING(0.70),
    /** Quality score below 0.70. */
    CRITICAL(0.50);

    private final double threshold;

    QualityStatus(double threshold) {
        this.threshold = threshold;
    }

    /**
     * Returns the quality status corresponding to the given score.
     *
     * @param score composite quality score in the range [0.0, 1.0]
     * @return the highest status whose threshold the score meets
     */
    public static QualityStatus fromScore(double score) {
        if (score >= EXCELLENT.threshold) return EXCELLENT;
        if (score >= GOOD.threshold) return GOOD;
        if (score >= WARNING.threshold) return WARNING;
        return CRITICAL;
    }

    public double getThreshold() { return threshold; }
}
