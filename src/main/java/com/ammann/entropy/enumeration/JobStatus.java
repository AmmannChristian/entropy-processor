/* (C)2026 */
package com.ammann.entropy.enumeration;

/**
 * Current job status.
 * <p>
 * Expected transition sequence is QUEUED to RUNNING and then to either COMPLETED or FAILED.
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
