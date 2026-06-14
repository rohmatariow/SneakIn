package com.sneakin.core

import burp.api.montoya.MontoyaApi
import burp.api.montoya.http.message.HttpRequestResponse
import burp.api.montoya.http.message.requests.HttpRequest
import com.sneakin.model.Finding
import com.sneakin.model.InjectionPoint
import com.sneakin.model.Payload
import com.sneakin.model.ScanConfig
import com.sneakin.oracle.Classification
import com.sneakin.oracle.Classifier
import com.sneakin.oracle.ResponseFingerprint
import com.sneakin.oracle.Verdict
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/** Events streamed from the engine. Implementations marshal to the Swing EDT. */
interface ScanCallbacks {
    fun onStarted(totalPayloads: Int)
    fun onLog(message: String)
    fun onProgress(sent: Int, total: Int)
    fun onCounters(vulnerable: Int, review: Int, rejected: Int, requests: Int)
    fun onFinding(finding: Finding)
    fun onFinished(stopped: Boolean)
}

/**
 * Orchestrates a scan (Section 6.3): baseline + noise map, then sniper mutation of each selected
 * point with early-exit on the first VULNERABLE. Points are tested in parallel on a fixed pool;
 * each point's payloads run sequentially in order (symbol -> bad-format -> negative). Never blocks
 * the EDT — runs on a background coordinator thread.
 */
class ScanEngine(
    private val api: MontoyaApi,
    private val seed: HttpRequestResponse,
    private val config: ScanConfig,
    private val payloadLibrary: PayloadLibrary,
    private val points: List<InjectionPoint>,
    private val uniquePoints: List<InjectionPoint>,
    private val callbacks: ScanCallbacks,
) {
    private val cancelled = AtomicBoolean(false)
    private val requestsSent = AtomicInteger(0)
    private val mutationsSent = AtomicInteger(0)
    private val vulnerable = AtomicInteger(0)
    private val review = AtomicInteger(0)
    private val rejected = AtomicInteger(0)

    /** Monotonic counter for unique-value rotation, time-seeded to avoid collisions across runs. */
    private val uniqueSeq = AtomicLong((System.currentTimeMillis() / 1000L) % 1_000_000L)

    @Volatile private var coordinator: Thread? = null

    fun start() {
        val thread = Thread({ run() }, "sneakin-scan")
        thread.isDaemon = true
        coordinator = thread
        thread.start()
    }

    fun stop() {
        cancelled.set(true)
    }

    private fun run() {
        val totalPayloads = points.sumOf { payloadLibrary.payloadsFor(it).size }
        callbacks.onStarted(totalPayloads)
        callbacks.onLog("Scan starting: ${points.size} point(s), ~$totalPayloads payload(s).")

        if (!runCatching { seed.request().isInScope }.getOrDefault(true)) {
            callbacks.onLog("WARNING: seed host is out of Burp scope — proceeding anyway.")
        }

        val sender = RequestSender(api, senderSettings(), callbacks::onLog)
        val mutator = RequestMutator(callbacks::onLog)
        if (uniquePoints.isNotEmpty()) {
            callbacks.onLog(
                "Unique rotation on for: ${uniquePoints.joinToString(", ") { it.name }} " +
                    "— fresh value on every request."
            )
        }
        val sampler = BaselineSampler({ sender.send(it) }, callbacks::onLog)
        val baseline = sampler.sample(
            seed.request(), config.baselineSamples, config.warmUp, config.manualIgnore,
        ) { req -> withRotatedUnique(req, mutator, exclude = null) }
        requestsSent.addAndGet(baseline.fingerprints.size)
        publishCounters()

        if (!baseline.ok) {
            callbacks.onLog("No baseline captured — aborting scan.")
            callbacks.onFinished(cancelled.get())
            return
        }

        val classifier = Classifier(baseline, config)
        val repLength = baseline.representative?.bodyLength ?: 0
        val repTime = baseline.representative?.responseTimeMs ?: 0

        val threads = config.threadCount.coerceIn(1, 32)
        val pool = Executors.newFixedThreadPool(threads)
        for (point in points) {
            pool.submit { processPoint(point, sender, mutator, classifier, repLength, repTime, totalPayloads) }
        }
        pool.shutdown()
        try {
            while (!pool.awaitTermination(200, TimeUnit.MILLISECONDS)) {
                if (cancelled.get()) {
                    pool.shutdownNow()
                    break
                }
            }
        } catch (e: InterruptedException) {
            pool.shutdownNow()
            Thread.currentThread().interrupt()
        }

        val stopped = cancelled.get()
        callbacks.onProgress(totalPayloads, totalPayloads)
        callbacks.onLog(
            if (stopped) "Scan stopped. ${vulnerable.get()} vulnerable, ${review.get()} review."
            else "Scan complete. ${vulnerable.get()} vulnerable, ${review.get()} review, ${rejected.get()} rejected."
        )
        callbacks.onFinished(stopped)
    }

    private fun processPoint(
        point: InjectionPoint,
        sender: RequestSender,
        mutator: RequestMutator,
        classifier: Classifier,
        repLength: Int,
        repTime: Long,
        totalPayloads: Int,
    ) {
        if (cancelled.get()) return
        val payloads = payloadLibrary.payloadsFor(point)
        if (payloads.isEmpty()) {
            callbacks.onLog("No applicable payloads for $point — skipped.")
            return
        }
        callbacks.onLog("Testing $point: ${payloads.size} payload(s).")

        for (payload in payloads) {
            if (cancelled.get()) return
            if (capReached()) {
                callbacks.onLog("Max-requests cap reached — stopping further sends.")
                cancelled.set(true)
                return
            }

            // Rotate every other unique-flagged field to a fresh value so duplicate-checks don't
            // reject this request; the field under test (point) gets the payload instead.
            val base = withRotatedUnique(seed.request(), mutator, exclude = point)
            val mutated = mutator.mutate(base, point, payload.value)
            if (mutated == null) {
                callbacks.onLog("Could not mutate $point with '${payload.value}' — skipped.")
                continue
            }

            val finding = try {
                val sent = sender.send(mutated)
                requestsSent.incrementAndGet()
                mutationsSent.incrementAndGet()
                val fp = ResponseFingerprint.of(sent.response, sent.timeMs)
                val classification = classifier.classify(fp, payload)
                Finding(
                    point, payload, classification, mutated, sent.response,
                    seed.request(), seed.response(),
                    fp.statusCode, fp.bodyLength - repLength, sent.timeMs - repTime
                )
            } catch (e: Exception) {
                requestsSent.incrementAndGet()
                mutationsSent.incrementAndGet()
                callbacks.onLog("Send failed for $point '${payload.value}': ${e.message}")
                noResponseFinding(point, payload, mutated)
            }

            tally(finding.verdict)
            callbacks.onFinding(finding)
            callbacks.onProgress(mutationsSent.get(), totalPayloads)
            publishCounters()

            if (finding.verdict == Verdict.VULNERABLE) {
                callbacks.onLog("VULNERABLE: $point accepted ${payload.category.label} payload '${payload.value}'.")
                return // EARLY EXIT — stop this point on first vulnerable
            }
        }
    }

    private fun noResponseFinding(point: InjectionPoint, payload: Payload, mutated: burp.api.montoya.http.message.requests.HttpRequest): Finding {
        val classification = Classification(
            Verdict.REVIEW, "", 0.5, null,
            listOf("No response received (network error/timeout)."), listOf("no-response")
        )
        return Finding(
            point, payload, classification, mutated, null,
            seed.request(), seed.response(), 0, 0, 0
        )
    }

    /**
     * Rotate every unique-flagged field on [base] to a fresh value (original + an incrementing
     * counter), skipping [exclude] — the field currently under test, which gets the payload instead.
     * No-op when nothing is flagged unique. Thread-safe via the atomic [uniqueSeq].
     */
    private fun withRotatedUnique(base: HttpRequest, mutator: RequestMutator, exclude: InjectionPoint?): HttpRequest {
        if (uniquePoints.isEmpty()) return base
        val mutations = uniquePoints
            .filter { it !== exclude }
            .map { it to UniqueValue.next(it.originalValue, it.valueType, uniqueSeq.incrementAndGet()) }
        return mutator.mutateAll(base, mutations)
    }

    private fun capReached(): Boolean = config.maxRequests > 0 && mutationsSent.get() >= config.maxRequests

    private fun tally(verdict: Verdict) {
        when (verdict) {
            Verdict.VULNERABLE -> vulnerable.incrementAndGet()
            Verdict.REVIEW -> review.incrementAndGet()
            Verdict.REJECTED -> rejected.incrementAndGet()
        }
    }

    private fun publishCounters() {
        callbacks.onCounters(vulnerable.get(), review.get(), rejected.get(), requestsSent.get())
    }

    private fun senderSettings() = SenderSettings(
        timeoutMs = config.requestTimeoutMs,
        retryCount = config.retryCount,
        interRequestDelayMs = config.interRequestDelayMs,
        backoff429Ms = config.backoff429Ms,
    )
}
