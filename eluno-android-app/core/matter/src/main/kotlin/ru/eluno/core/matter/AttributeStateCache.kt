package ru.eluno.core.matter

import ru.eluno.core.domain.ReportedState
import kotlin.math.roundToInt
import java.time.Instant

/** SDK data versions are unsigned 32-bit counters, shared by a cluster. */
internal data class ClusterUpdate(val cluster: Long, val version: Long?, val values: Map<Long, Any?>)
data class MatterObservation(val reported: ReportedState, val sequence: Long = 0, val observedAt: Instant? = null)

/** One accumulator per endpoint for reads, command read-back and subscription deltas. */
internal class AttributeStateCache(private val now: () -> Instant = Instant::now) {
    private val confirmedAt = mutableMapOf<Pair<Long, Long>, Instant>()
    private val versions = mutableMapOf<Long, Long>()
    private val values = mutableMapOf<Pair<Long, Long>, Any?>()
    private var sequence = 0L
    private val changedAt = mutableMapOf<Long, Long>()
    @Synchronized fun currentSequence(): Long = sequence

    @Synchronized fun resetVersions() { versions.clear() }

    @Synchronized fun update(updates: List<ClusterUpdate>, readStartedAt: Long? = null): MatterObservation {
        updates.forEach { update ->
            val old = versions[update.cluster]
            val version = update.version?.and(0xffffffffL)
            if (old != null && version != null) {
                val distance = (version - old) and 0xffffffffL
                // A fresh read with no intervening report may establish a new version
                // after a device reboot. In-flight old reads cannot replace newer reports.
                if (distance >= 0x80000000L && (readStartedAt == null || (changedAt[update.cluster] ?: 0) > readStartedAt)) return@forEach
            }
            changedAt[update.cluster] = sequence + 1
            if (version != null) versions[update.cluster] = version
            val observedAt = now()
            update.values.forEach { (attribute, value) ->
                values[update.cluster to attribute] = value
                confirmedAt[update.cluster to attribute] = observedAt
            }
        }
        fun number(cluster: Long, attribute: Long) = (values[cluster to attribute] as? Number)?.toInt()
        val hue = number(0x300, 0)
        val saturation = number(0x300, 1)
        return MatterObservation(ReportedState(
            isOn = values[6L to 0L] as? Boolean,
            brightnessPercent = number(8, 0)?.takeIf { it in 0..254 }?.let { (it * 100.0 / 254).roundToInt() },
            colorTemperatureKelvin = number(0x300, 7)?.takeIf { it > 0 }?.let { (1_000_000.0 / it).roundToInt() },
            color = if (hue != null && saturation != null && hue in 0..254 && saturation in 0..254) MatterColor.fromHueSaturation(hue, saturation) else null,
        ), ++sequence, confirmedAt.values.minOrNull())
    }
}
