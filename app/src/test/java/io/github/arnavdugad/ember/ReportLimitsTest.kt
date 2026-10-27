package io.github.arnavdugad.ember

import io.github.arnavdugad.ember.data.repo.ReportLimits
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant

class ReportLimitsTest {
    @Test
    fun dayIdIsTheUtcDate() {
        // 23:30 in New York on Jan 1 is already Jan 2 in UTC; the rules use UTC.
        assertEquals("2026-01-02", ReportLimits.dayId(Instant.parse("2026-01-02T04:30:00Z")))
        assertEquals("2026-10-27", ReportLimits.dayId(Instant.parse("2026-10-27T00:00:00Z")))
        assertEquals("2026-10-27", ReportLimits.dayId(Instant.parse("2026-10-27T23:59:59Z")))
    }

    @Test
    fun reportIdNamesTheCounterStep() {
        assertEquals("alice_2026-10-27_3", ReportLimits.reportId("alice", "2026-10-27", 3))
    }
}
