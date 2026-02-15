/* (C)2026 */
package com.ammann.entropy.support;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * Utility methods for time-based test assertions.
 */
public final class TimeTestUtils {

    private TimeTestUtils() {}

    /**
     * Create an Instant a specified number of minutes ago.
     *
     * @param minutes Number of minutes in the past
     * @return Instant representing that time
     */
    public static Instant minutesAgo(int minutes) {
        return Instant.now().minus(minutes, ChronoUnit.MINUTES);
    }

    /**
     * Create an Instant a specified number of days ago.
     *
     * @param days Number of days in the past
     * @return Instant representing that time
     */
    public static Instant daysAgo(int days) {
        return Instant.now().minus(days, ChronoUnit.DAYS);
    }

    /**
     * Check if two Instants are within a specified number of seconds of each other.
     *
     * @param a First instant
     * @param b Second instant
     * @param seconds Maximum allowed difference in seconds
     * @return true if the instants are within the specified range
     */
    public static boolean withinSeconds(Instant a, Instant b, int seconds) {
        long diff = Math.abs(a.getEpochSecond() - b.getEpochSecond());
        return diff <= seconds;
    }
}
