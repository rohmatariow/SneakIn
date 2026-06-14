package com.sneakin.oracle

import com.sneakin.core.BaselineResult
import com.sneakin.model.ComparisonMode
import com.sneakin.model.Payload
import com.sneakin.model.Preset
import com.sneakin.model.ScanConfig

/**
 * Combines the comparators, reflection detector and success profile into a [Verdict], implementing
 * the REJECT_EXPECTED classification flow of Section 7.2:
 *
 * 1. 5xx -> REJECTED (server errored on the illegal input — never accepted as a valid value).
 * 2. Proper rejection (negative-match present, 4xx, or positive-match absent) -> REJECTED.
 * 3. Accepted like a success (status in success set, markers ok, structurally same/similar) -> VULNERABLE.
 * 4. Otherwise -> REVIEW.
 */
class Classifier(private val baseline: BaselineResult, private val config: ScanConfig) {

    private val representative = baseline.representative
    private val noiseMap = baseline.noiseMap
    private val baselineStatuses = noiseMap.baselineStatuses

    fun classify(mutated: ResponseFingerprint, payload: Payload): Classification {
        val reasons = ArrayList<String>()
        val signals = ArrayList<String>()
        if (ReflectionDetector.isReflected(mutated.body, payload.value)) signals.add("reflected")

        val rep = representative
            ?: return Classification(
                Verdict.REVIEW, "", 0.5, null,
                listOf("No baseline captured — cannot classify."), signals
            )

        val compare = compareResult(rep, mutated, payload.value)

        val statusSuccess = statusIsSuccess(mutated)
        val positiveConfigured = config.usePositive && config.positiveMatch.isNotBlank()
        val positivePresent = matches(mutated.body, config.positiveMatch, config.positiveMatchIsRegex)
        val negativeConfigured = config.useNegative && config.negativeMatch.isNotBlank()
        val negativePresent = matches(mutated.body, config.negativeMatch, config.negativeMatchIsRegex)
        val lengthOk = withinLengthBand(mutated.bodyLength)

        val score = acceptanceScore(
            statusSuccess, compare, lengthOk,
            positiveConfigured, positivePresent, negativeConfigured, negativePresent
        )

        // Step 1: 5xx -> REJECTED (server errored on the illegal input; not accepted as valid)
        if (mutated.statusClass == 5) {
            signals.add("5xx")
            reasons.add("Server error (status ${mutated.statusCode}) — input not accepted (rejected).")
            return Classification(Verdict.REJECTED, "", score, compare, reasons, signals)
        }

        // Step 2: proper rejection?
        if (negativeConfigured && negativePresent) {
            reasons.add("Validation-error marker present (negative-match).")
            return Classification(Verdict.REJECTED, "", score, compare, reasons, signals)
        }
        if (config.useStatus && mutated.statusClass == 4) {
            reasons.add("Client-error status ${mutated.statusCode} — input rejected.")
            return Classification(Verdict.REJECTED, "", score, compare, reasons, signals)
        }
        if (positiveConfigured && !positivePresent) {
            reasons.add("Success marker absent (positive-match) — input rejected.")
            return Classification(Verdict.REJECTED, "", score, compare, reasons, signals)
        }

        // Step 3: accepted like a normal success?
        val structuralOk = compare in acceptedResults()
        val positiveOk = !positiveConfigured || positivePresent
        val negativeOk = !negativeConfigured || !negativePresent
        if (statusSuccess && positiveOk && negativeOk && structuralOk) {
            reasons.add(
                "Accepted: status ${mutated.statusCode} in success set; response " +
                    "${compare.name.lowercase()} to baseline."
            )
            if (positiveConfigured) reasons.add("Success marker present.")
            return Classification(
                Verdict.VULNERABLE, payload.category.acceptedSubLabel, score, compare, reasons, signals
            )
        }

        // Step 4: diverged but ambiguous
        reasons.add(
            "Diverged from baseline (compare=${compare.name.lowercase()}, status ${mutated.statusCode}) — " +
                "neither a clean accept nor a clean reject."
        )
        return Classification(Verdict.REVIEW, "", score, compare, reasons, signals)
    }

    private fun compareResult(
        baselineFp: ResponseFingerprint,
        mutated: ResponseFingerprint,
        payload: String,
    ): CompareResult {
        fun structural() = StructuralComparator.compare(baselineFp, mutated, noiseMap, payload)
        fun similarity(): CompareResult {
            val (same, similar) = similarityThresholds()
            return SimilarityComparator(same, similar, config.lengthTolerancePercent)
                .compare(baselineFp, mutated, noiseMap, payload)
        }
        return when (config.comparisonMode) {
            ComparisonMode.STRUCTURAL -> if (baselineFp.isJson) structural() else similarity()
            ComparisonMode.SIMILARITY -> similarity()
            ComparisonMode.AUTO -> if (baselineFp.isJson) structural() else similarity()
        }
    }

    private fun statusIsSuccess(mutated: ResponseFingerprint): Boolean {
        if (config.useStatusList && config.successStatuses.isNotEmpty()) {
            return mutated.statusCode in config.successStatuses
        }
        if (mutated.statusCode in baselineStatuses) return true
        return config.matchStatusClass2xx && mutated.statusClass == 2
    }

    private fun acceptedResults(): Set<CompareResult> = when (config.preset) {
        Preset.STRICT -> setOf(CompareResult.SAME)
        Preset.BALANCED, Preset.SENSITIVE -> setOf(CompareResult.SAME, CompareResult.SIMILAR)
    }

    private fun similarityThresholds(): Pair<Double, Double> = when (config.preset) {
        Preset.STRICT -> 0.97 to 0.80
        Preset.BALANCED -> 0.95 to 0.60
        Preset.SENSITIVE -> 0.90 to 0.45
    }

    private fun withinLengthBand(length: Int): Boolean {
        val tol = config.lengthTolerancePercent / 100.0
        val low = (noiseMap.lengthMin * (1.0 - tol)).toInt()
        val high = (noiseMap.lengthMax * (1.0 + tol)).toInt()
        return length in low..high
    }

    private fun acceptanceScore(
        statusSuccess: Boolean,
        compare: CompareResult,
        lengthOk: Boolean,
        positiveConfigured: Boolean,
        positivePresent: Boolean,
        negativeConfigured: Boolean,
        negativePresent: Boolean,
    ): Double {
        var weighted = 0.0
        var total = 0.0
        if (config.useStatus) {
            weighted += (if (statusSuccess) 1.0 else 0.0) * config.weightStatus; total += config.weightStatus
        }
        if (config.useStructure) {
            val s = when (compare) {
                CompareResult.SAME -> 1.0
                CompareResult.SIMILAR -> 0.7
                CompareResult.DIFFERENT -> 0.0
            }
            weighted += s * config.weightStructure; total += config.weightStructure
        }
        if (config.useLength) {
            weighted += (if (lengthOk) 1.0 else 0.0) * config.weightLength; total += config.weightLength
        }
        if (positiveConfigured) {
            weighted += (if (positivePresent) 1.0 else 0.0) * config.weightPositive; total += config.weightPositive
        }
        if (negativeConfigured) {
            weighted += (if (negativePresent) 0.0 else 1.0) * config.weightNegative; total += config.weightNegative
        }
        return if (total == 0.0) 0.5 else weighted / total
    }

    private fun matches(text: String, pattern: String, isRegex: Boolean): Boolean {
        if (pattern.isBlank()) return false
        return if (isRegex) {
            runCatching { Regex(pattern).containsMatchIn(text) }.getOrDefault(false)
        } else {
            text.contains(pattern)
        }
    }
}
