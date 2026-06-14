package com.sneakin.oracle

import com.sneakin.core.NoiseMap
import com.sneakin.model.JsonKind

/**
 * Compares the **shape** of a JSON response against the baseline: the set of leaf paths and the
 * value type at each (Section 7.4).
 *
 * - Same shape, only noise differs -> SAME
 * - Same shape, non-noise values differ -> SIMILAR
 * - Shape differs (key added/removed, type changed) -> DIFFERENT
 *
 * Value-level noise is masked, and the injected payload is masked too. Two exceptions keep an
 * *accepted* payload from looking like a structural break: a path that was JSON `null` in the
 * baseline has no concrete type (a `null -> value` change is a value-level event, not a type
 * change), and a path echoing the payload is the "input accepted" signal. Both are handled at the
 * value stage instead of short-circuiting to DIFFERENT.
 */
object StructuralComparator {

    fun compare(
        baseline: ResponseFingerprint,
        mutated: ResponseFingerprint,
        noiseMap: NoiseMap,
        payload: String,
    ): CompareResult {
        val baseShape = baseline.jsonShape ?: return CompareResult.DIFFERENT
        val mutShape = mutated.jsonShape ?: return CompareResult.DIFFERENT

        val baseValues = baseline.jsonValues ?: emptyMap()
        val mutValues = mutated.jsonValues ?: emptyMap()

        // key set differs -> shape differs
        if (baseShape.keys != mutShape.keys) return CompareResult.DIFFERENT
        // type changed at a path -> shape differs, EXCEPT when a JSON null is involved (no concrete
        // type) or the path echoes the payload (an accepted value, not a real shape change). Those
        // fall through to the value stage below, where reflection/noise masking applies.
        for (path in baseShape.keys) {
            val bk = baseShape[path]
            val mk = mutShape[path]
            if (bk == mk) continue
            if (bk == JsonKind.NULL || mk == JsonKind.NULL) continue
            val mv = mutValues[path]
            if (payload.isNotEmpty() && mv != null && mv.contains(payload)) continue
            return CompareResult.DIFFERENT
        }

        var anyNonNoiseDiff = false
        for (path in baseShape.keys) {
            if (noiseMap.isNoisePath(path)) continue
            val bv = baseValues[path]
            val mv = mutValues[path]
            if (bv != mv) {
                // reflected payload at this path is not a real difference
                if (payload.isNotEmpty() && mv != null && mv.contains(payload)) continue
                anyNonNoiseDiff = true
                break
            }
        }
        return if (anyNonNoiseDiff) CompareResult.SIMILAR else CompareResult.SAME
    }
}
