package ru.eluno.core.matter

import org.junit.Assert.*
import org.junit.Test

class AttributeStateCacheTest {
    @Test fun `partial subscription preserves newer power read and rejects older cluster version`() {
        val cache = AttributeStateCache()
        cache.update(listOf(ClusterUpdate(6, 10, mapOf(0L to false)), ClusterUpdate(8, 1, mapOf(0L to 25))))
        val read = cache.update(listOf(ClusterUpdate(6, 11, mapOf(0L to true))))
        val report = cache.update(listOf(ClusterUpdate(8, 2, mapOf(0L to 127))))
        assertEquals(true, report.reported.isOn)
        assertEquals(50, report.reported.brightnessPercent)
        assertTrue(report.sequence > read.sequence)
        assertEquals(true, cache.update(listOf(ClusterUpdate(6, 10, mapOf(0L to false)))).reported.isOn)
    }
    @Test fun `versions wrap and reset after reconnect without retaining obsolete null attributes`() {
        val cache = AttributeStateCache()
        cache.update(listOf(ClusterUpdate(6, 0xffffffffL, mapOf(0L to false))))
        assertEquals(true, cache.update(listOf(ClusterUpdate(6, 0, mapOf(0L to true)))).reported.isOn)
        cache.resetVersions()
        assertEquals(false, cache.update(listOf(ClusterUpdate(6, 0x90000000L, mapOf(0L to false)))).reported.isOn)
        assertNull(cache.update(listOf(ClusterUpdate(6, 0x90000001L, mapOf(0L to null)))).reported.isOn)
    }
    @Test fun `fresh read accepts device reboot while late read cannot replace concurrent report`() {
        val cache = AttributeStateCache()
        cache.update(listOf(ClusterUpdate(6, 100, mapOf(0L to true))))
        val start = cache.currentSequence()
        cache.update(listOf(ClusterUpdate(6, 101, mapOf(0L to false))))
        assertEquals(false, cache.update(listOf(ClusterUpdate(6, 100, mapOf(0L to true))), start).reported.isOn)
        val afterReboot = cache.currentSequence()
        assertEquals(true, cache.update(listOf(ClusterUpdate(6, 2, mapOf(0L to true))), afterReboot).reported.isOn)
    }
    @Test fun `partial report does not refresh confirmation time of untouched attributes`() {
        var now = java.time.Instant.ofEpochSecond(10)
        val cache = AttributeStateCache { now }
        cache.update(listOf(ClusterUpdate(6, 1, mapOf(0L to true)), ClusterUpdate(8, 1, mapOf(0L to 100))))
        now = java.time.Instant.ofEpochSecond(20)
        assertEquals(java.time.Instant.ofEpochSecond(10), cache.update(listOf(ClusterUpdate(8, 2, mapOf(0L to 127)))).observedAt)
        assertEquals(now, cache.update(listOf(ClusterUpdate(6, 1, mapOf(0L to true)))).observedAt)
    }
    @Test fun `partial hue uses saturation from latest read`() {
        val cache = AttributeStateCache()
        cache.update(listOf(ClusterUpdate(0x300, 1, mapOf(0L to 0, 1L to 254))))
        cache.update(listOf(ClusterUpdate(0x300, 2, mapOf(0L to 0, 1L to 0))))
        val next = cache.update(listOf(ClusterUpdate(0x300, 3, mapOf(0L to 120))))
        assertEquals(ru.eluno.core.domain.RgbColor(255,255,255), next.reported.color)
    }
}
