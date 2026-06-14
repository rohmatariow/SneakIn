package com.sneakin.core

import burp.api.montoya.MontoyaApi
import burp.api.montoya.http.RequestOptions
import burp.api.montoya.http.message.requests.HttpRequest
import java.io.IOException

/** Tunables for the live sender, mirrored from the engine config. */
class SenderSettings(
    var timeoutMs: Long,
    var retryCount: Int,
    var interRequestDelayMs: Long,
    var backoff429Ms: Long,
)

/**
 * Sends requests **through Burp** (`api.http().sendRequest`) so they are subject to Burp's
 * processing and Session Handling Rules. Applies a per-request timeout, retries transient failures,
 * and on HTTP 429 backs off and retries without treating it as a verdict signal (Section 6.5).
 */
class RequestSender(
    private val api: MontoyaApi,
    private val settings: SenderSettings,
    private val log: (String) -> Unit = {},
) {
    private val max429Retries = 5

    fun send(request: HttpRequest): SentResponse {
        var failures = 0
        var backoffs = 0
        while (true) {
            val options = RequestOptions.requestOptions().withResponseTimeout(settings.timeoutMs)
            val start = System.nanoTime()
            val rr = try {
                api.http().sendRequest(request, options)
            } catch (e: Exception) {
                failures++
                if (failures > settings.retryCount) {
                    throw IOException("send failed after ${settings.retryCount} retry(ies): ${e.message}", e)
                }
                continue
            }

            if (!rr.hasResponse()) {
                failures++
                if (failures > settings.retryCount) {
                    throw IOException("no response after ${settings.retryCount} retry(ies)")
                }
                continue
            }

            val response = rr.response()
            val elapsedMs = (System.nanoTime() - start) / 1_000_000

            if (response.statusCode().toInt() == 429 && backoffs < max429Retries) {
                backoffs++
                log("429 received; backing off ${settings.backoff429Ms} ms (attempt $backoffs).")
                sleep(settings.backoff429Ms)
                continue
            }

            sleep(settings.interRequestDelayMs)
            return SentResponse(response, elapsedMs)
        }
    }

    private fun sleep(ms: Long) {
        if (ms <= 0) return
        try {
            Thread.sleep(ms)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }
}
