package com.ammann.entropy.enumeration;

/**
 * Test type enumeration for NIST SP 800-90B assessments.
 *
 * <ul>
 *   <li>IID: Independent and Identically Distributed tests (4 tests)
 *   <li>NON_IID: Non-IID estimators (10 estimators)
 * </ul>
 */
public enum TestType {
    /** IID (Independent and Identically Distributed) tests. */
    IID,

    /** Non-IID estimators. */
    NON_IID;

    /**
     * Case-insensitive conversion from string.
     *
     * @param value String value ("IID", "NON_IID", case-insensitive)
     * @return TestType enum value
     * @throws IllegalArgumentException if value is invalid
     */
    public static TestType fromString(String value) {
        if (value == null) {
            throw new IllegalArgumentException("TestType value cannot be null");
        }
        for (TestType t : values()) {
            if (t.name().equalsIgnoreCase(value)) {
                return t;
            }
        }
        throw new IllegalArgumentException(
                "Invalid test type: " + value + ". Must be IID or NON_IID (case-insensitive).");
    }
}