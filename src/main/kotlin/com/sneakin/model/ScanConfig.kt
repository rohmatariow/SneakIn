package com.sneakin.model

/** Auto = structural for JSON, similarity otherwise. */
enum class ComparisonMode { AUTO, STRUCTURAL, SIMILARITY }

/** Threshold presets that shift the score that maps to a verdict bucket. */
enum class Preset { STRICT, BALANCED, SENSITIVE }

/** Global defaults shared across scan tabs. A new scan tab copies these into its [ScanConfig]. */
class GlobalConfig {
    // Baseline
    var baselineSamples: Int = 3
    var warmUp: Boolean = true

    // Engine
    var threadCount: Int = 4
    var interRequestDelayMs: Long = 0
    var requestTimeoutMs: Long = 20_000
    var retryCount: Int = 1
    var maxRequests: Int = 0 // 0 = unlimited (practical bound, not a safety gate)

    // Rate limiting
    var backoff429Ms: Long = 2_000

    // Threshold preset
    var preset: Preset = Preset.BALANCED

    fun newScanConfig(): ScanConfig = ScanConfig(this)
}

/**
 * Per-tab configuration. Seeded from [GlobalConfig] and independently editable per scan tab.
 * Holds the "definition of normal/success", comparison knobs, and criterion weights.
 */
class ScanConfig(global: GlobalConfig) {
    // Engine (seeded from global, overridable per tab)
    var baselineSamples = global.baselineSamples
    var warmUp = global.warmUp
    var threadCount = global.threadCount
    var interRequestDelayMs = global.interRequestDelayMs
    var requestTimeoutMs = global.requestTimeoutMs
    var retryCount = global.retryCount
    var maxRequests = global.maxRequests
    var backoff429Ms = global.backoff429Ms
    var preset = global.preset

    // Definition of "normal/success"
    var matchStatusClass2xx = true            // treat any 2xx as success
    var useStatusList = false                 // if true use [successStatuses] instead of the 2xx class
    val successStatuses = linkedSetOf<Int>()  // exact/list; empty => derived from baseline
    var positiveMatch: String = ""            // must be PRESENT to count as success
    var positiveMatchIsRegex = false
    var negativeMatch: String = ""            // must be ABSENT; presence marks a validation rejection
    var negativeMatchIsRegex = false
    var lengthTolerancePercent = 30.0
    var timingToleranceMs = 0L
    var lockContentType = true

    // Criterion on/off toggles + weights
    var useStatus = true;    var weightStatus = 1.0
    var usePositive = true;  var weightPositive = 1.0
    var useNegative = true;  var weightNegative = 1.0
    var useStructure = true; var weightStructure = 1.0
    var useLength = true;    var weightLength = 1.0

    // Comparison
    var comparisonMode = ComparisonMode.AUTO

    // Manual force-masked field paths / regexes, applied on top of the auto noise map.
    val manualIgnore = mutableListOf<String>()

    /** Restore per-tab fields to the supplied global defaults. */
    fun resetTo(global: GlobalConfig) {
        baselineSamples = global.baselineSamples
        warmUp = global.warmUp
        threadCount = global.threadCount
        interRequestDelayMs = global.interRequestDelayMs
        requestTimeoutMs = global.requestTimeoutMs
        retryCount = global.retryCount
        maxRequests = global.maxRequests
        backoff429Ms = global.backoff429Ms
        preset = global.preset
        matchStatusClass2xx = true
        useStatusList = false
        successStatuses.clear()
        positiveMatch = ""; positiveMatchIsRegex = false
        negativeMatch = ""; negativeMatchIsRegex = false
        lengthTolerancePercent = 30.0
        timingToleranceMs = 0L
        lockContentType = true
        useStatus = true; weightStatus = 1.0
        usePositive = true; weightPositive = 1.0
        useNegative = true; weightNegative = 1.0
        useStructure = true; weightStructure = 1.0
        useLength = true; weightLength = 1.0
        comparisonMode = ComparisonMode.AUTO
        manualIgnore.clear()
    }
}
