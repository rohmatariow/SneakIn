package com.sneakin.core

import burp.api.montoya.http.message.requests.HttpRequest
import com.sneakin.oracle.ResponseFingerprint

/** Baseline samples + the noise map + a representative success profile derived from them. */
class BaselineResult(
    val fingerprints: List<ResponseFingerprint>,
    val noiseMap: NoiseMap,
    val representative: ResponseFingerprint?,
) {
    val ok: Boolean get() = fingerprints.isNotEmpty()
}

/**
 * Sends the original, unmutated request N times to learn the baseline and build the noise map
 * (Section 6.2). Sending is injected as a lambda so this stays decoupled from the live sender.
 */
class BaselineSampler(
    private val send: (HttpRequest) -> SentResponse,
    private val log: (String) -> Unit,
) {
    fun sample(
        request: HttpRequest,
        samples: Int,
        warmUp: Boolean,
        manualIgnore: List<String>,
        prepare: (HttpRequest) -> HttpRequest = { it },
    ): BaselineResult {
        if (warmUp) {
            runCatching { send(prepare(request)) }
                .onSuccess { log("Warm-up request sent (discarded).") }
                .onFailure { log("Warm-up request failed: ${it.message}") }
        }

        val fingerprints = ArrayList<ResponseFingerprint>()
        val n = samples.coerceAtLeast(1)
        for (i in 1..n) {
            try {
                val sent = send(prepare(request))
                val fp = ResponseFingerprint.of(sent.response, sent.timeMs)
                fingerprints.add(fp)
                log("Baseline #$i: status ${fp.statusCode}, ${fp.bodyLength} bytes, ${fp.responseTimeMs} ms" +
                    if (fp.isJson) " (JSON)" else "")
            } catch (e: Exception) {
                log("Baseline #$i failed: ${e.message}")
            }
        }

        val noiseMap = NoiseMap.build(fingerprints, manualIgnore)
        warnIfUnstable(fingerprints, noiseMap)

        val representative = chooseRepresentative(fingerprints)
        return BaselineResult(fingerprints, noiseMap, representative)
    }

    private fun warnIfUnstable(fingerprints: List<ResponseFingerprint>, noiseMap: NoiseMap) {
        if (fingerprints.isEmpty()) {
            log("WARNING: no baseline responses captured — the engine cannot classify reliably.")
            return
        }
        if (!noiseMap.statusStable) {
            log("WARNING: baseline status codes vary across samples ${noiseMap.baselineStatuses} — " +
                "baseline is unstable. You may proceed, but verdicts may be noisy.")
        }
        val first = fingerprints.first()
        if (first.statusClass != 2) {
            log("WARNING: baseline already returns a non-2xx response (status ${first.statusCode}). " +
                "The success profile may not represent a real success.")
        }
        if (noiseMap.isJson && noiseMap.noisePaths.isNotEmpty()) {
            log("Noise paths (value-masked): ${noiseMap.noisePaths.sorted().joinToString(", ")}")
        }
        if (noiseMap.lengthMin != noiseMap.lengthMax) {
            log("Baseline length band: ${noiseMap.lengthMin}..${noiseMap.lengthMax} bytes.")
        }
    }

    /** Pick the sample whose body length is closest to the median as the success profile. */
    private fun chooseRepresentative(fingerprints: List<ResponseFingerprint>): ResponseFingerprint? {
        if (fingerprints.isEmpty()) return null
        val sorted = fingerprints.sortedBy { it.bodyLength }
        return sorted[sorted.size / 2]
    }
}
