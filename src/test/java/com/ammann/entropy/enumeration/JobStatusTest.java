/* (C)2026 */
package com.ammann.entropy.enumeration;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class JobStatusTest {

    @Test
    void enumHasFourValues() {
        JobStatus[] values = JobStatus.values();

        assertThat(values).hasSize(4);
        assertThat(values)
                .contains(
                        JobStatus.QUEUED, JobStatus.RUNNING, JobStatus.COMPLETED, JobStatus.FAILED);
    }

    @Test
    void valueOfReturnsCorrectEnum() {
        assertThat(JobStatus.valueOf("QUEUED")).isEqualTo(JobStatus.QUEUED);
        assertThat(JobStatus.valueOf("RUNNING")).isEqualTo(JobStatus.RUNNING);
        assertThat(JobStatus.valueOf("COMPLETED")).isEqualTo(JobStatus.COMPLETED);
        assertThat(JobStatus.valueOf("FAILED")).isEqualTo(JobStatus.FAILED);
    }
}
