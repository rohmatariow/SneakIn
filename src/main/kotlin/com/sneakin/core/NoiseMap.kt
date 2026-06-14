package com.sneakin.core

import com.sneakin.oracle.ResponseFingerprint

/**
 * Captures what naturally varies across identical baseline requests so later comparisons can mask
 * it. Masking is **value-level**: a noise path's value is ignored, but its presence and type are
 * still compared — a noise field disappearing or changing type is a real signal (Section 6.2).
 */
class NoiseMap(
    /** JSON paths whose value differs across baseline samples (same presence + type). */
    val noisePaths: Set<String>,
    val lengthMin: Int,
    val lengthMax: Int,
    val timeMin: Long,
    val timeMax: Long,
    val baselineStatuses: Set<Int>,
    val statusStable: Boolean,
    val isJson: Boolean,
    /** User force-masked paths/regexes, applied on top of the auto map. */
    private val manualIgnore: List<String>,
) {
    /** True if this JSON path's value should be ignored when diffing. */
    fun isNoisePath(path: String): Boolean = path in noisePaths || matchesManual(path)

    private fun matchesManual(path: String): Boolean = manualIgnore.any { rule ->
        rule.isNotBlank() && (path == rule || runCatching { Regex(rule).containsMatchIn(path) }.getOrDefault(false))
    }

    companion object {
        fun build(fingerprints: List<ResponseFingerprint>, manualIgnore: List<String>): NoiseMap {
            if (fingerprints.isEmpty()) {
                return NoiseMap(emptySet(), 0, 0, 0, 0, emptySet(), true, false, manualIgnore)
            }
            val statuses = fingerprints.map { it.statusCode }.toSet()
            val lengths = fingerprints.map { it.bodyLength }
            val times = fingerprints.map { it.responseTimeMs }
            val isJson = fingerprints.all { it.isJson }
            val noise = if (isJson) computeJsonNoise(fingerprints) else emptySet()
            return NoiseMap(
                noisePaths = noise,
                lengthMin = lengths.min(),
                lengthMax = lengths.max(),
                timeMin = times.min(),
                timeMax = times.max(),
                baselineStatuses = statuses,
                statusStable = statuses.size == 1,
                isJson = isJson,
                manualIgnore = manualIgnore,
            )
        }

        /** A path is noise if present in every sample with one consistent type but differing values. */
        private fun computeJsonNoise(fingerprints: List<ResponseFingerprint>): Set<String> {
            val keySets = fingerprints.map { it.jsonValues?.keys ?: emptySet() }
            val common = keySets.reduce { a, b -> a intersect b }
            val noise = HashSet<String>()
            for (path in common) {
                val kinds = fingerprints.map { it.jsonShape?.get(path) }.toSet()
                if (kinds.size > 1) continue // type varies across baseline -> signal, never mask
                val values = fingerprints.map { it.jsonValues?.get(path) }.toSet()
                if (values.size > 1) noise.add(path)
            }
            return noise
        }
    }
}
