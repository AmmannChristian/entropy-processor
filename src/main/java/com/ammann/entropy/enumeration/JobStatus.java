/* (C)2026 */
package com.ammann.entropy.enumeration;

/**
 * Current job status.
 * <p>
 * Transitions: QUEUED → RUNNING → (COMPLETED | FAILED)
 */
public enum JobStatus {
    /** Job created but not yet started */
    QUEUED,
    /** Job is actively processing chunks */
    RUNNING,
    /** Job completed successfully */
    COMPLETED,
    /** Job failed with an error */
    FAILED
}
