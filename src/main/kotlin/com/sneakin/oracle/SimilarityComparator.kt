package com.sneakin.oracle

import com.sneakin.core.NoiseMap

/**
 * Compares non-JSON bodies (Section 7.5). Status class first, then body length within the learned
 * baseline variance band, plus a token-based similarity ratio computed **after** masking the
 * injected payload. Maps to SAME / SIMILAR / DIFFERENT.
 */
class SimilarityComparator(
    private val sameThreshold: Double,
    private val similarThreshold: Double,
    private val lengthTolerancePercent: Double,
) {
    fun compare(
        baseline: ResponseFingerprint,
        mutated: ResponseFingerprint,
        noiseMap: NoiseMap,
        payload: String,
    ): CompareResult {
        if (mutated.statusClass != baseline.statusClass) return CompareResult.DIFFERENT

        val baseMasked = ReflectionDetector.mask(baseline.body, payload)
        val mutMasked = ReflectionDetector.mask(mutated.body, payload)

        val lengthOk = withinBand(mutMasked.length, noiseMap)
        val similarity = jaccard(tokenize(baseMasked), tokenize(mutMasked))

        return when {
            similarity >= sameThreshold && lengthOk -> CompareResult.SAME
            similarity >= similarThreshold -> CompareResult.SIMILAR
            else -> CompareResult.DIFFERENT
        }
    }

    private fun withinBand(length: Int, noiseMap: NoiseMap): Boolean {
        val tol = lengthTolerancePercent / 100.0
        val low = noiseMap.lengthMin * (1.0 - tol)
        val high = noiseMap.lengthMax * (1.0 + tol)
        return length in low.toInt()..high.toInt()
    }

    private fun tokenize(text: String): Set<String> =
        text.split(Regex("[^A-Za-z0-9]+")).filter { it.isNotEmpty() }.toSet()

    private fun jaccard(a: Set<String>, b: Set<String>): Double {
        if (a.isEmpty() && b.isEmpty()) return 1.0
        val intersection = a.intersect(b).size.toDouble()
        val union = a.union(b).size.toDouble()
        return if (union == 0.0) 1.0 else intersection / union
    }
}
