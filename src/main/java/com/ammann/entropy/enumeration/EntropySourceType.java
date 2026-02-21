/* (C)2026 */
package com.ammann.entropy.enumeration;

/**
 * Canonical entropy source categories used in comparison workflows.
 */
public enum EntropySourceType {
    /** Baseline pseudo-random source used as a control reference. */
    BASELINE,
    /** Hardware-derived entropy source. */
    HARDWARE,
    /** Combined source produced by mixing baseline and hardware inputs. */
    MIXED
}
