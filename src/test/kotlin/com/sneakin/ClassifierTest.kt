package com.sneakin

import com.sneakin.core.BaselineResult
import com.sneakin.core.JsonLeafWalker
import com.sneakin.core.NoiseMap
import com.sneakin.model.GlobalConfig
import com.sneakin.model.Payload
import com.sneakin.model.PayloadCategory
import com.sneakin.model.display
import com.sneakin.oracle.Classifier
import com.sneakin.oracle.ResponseFingerprint
import com.sneakin.oracle.Verdict
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ClassifierTest {

    private fun jsonFp(status: Int, body: String, time: Long = 10): ResponseFingerprint {
        val leaves = JsonLeafWalker.leaves(body)!!
        val shape = leaves.associate { it.path.display() to it.kind }
        val values = leaves.associate { it.path.display() to it.value }
        return ResponseFingerprint(status, status / 100, "application/json", true, body.length, time, body, shape, values)
    }

    private fun textFp(status: Int, body: String, time: Long = 10): ResponseFingerprint =
        ResponseFingerprint(status, status / 100, "text/html", false, body.length, time, body, null, null)

    private fun baselineOf(vararg fps: ResponseFingerprint): BaselineResult {
        val list = fps.toList()
        val nm = NoiseMap.build(list, emptyList())
        return BaselineResult(list, nm, list[list.size / 2])
    }

    private val negative = Payload("-1", PayloadCategory.NEGATIVE)
    private fun newConfig() = GlobalConfig().newScanConfig()

    @Test
    fun weakEndpointAcceptingNegativeIsVulnerable() {
        val base = jsonFp(200, """{"ok":true,"value":100}""")
        val classifier = Classifier(baselineOf(base, base, base), newConfig())
        // server accepted -1 and returned the same success shape
        val result = classifier.classify(jsonFp(200, """{"ok":true,"value":100}"""), negative)
        assertEquals(Verdict.VULNERABLE, result.verdict)
        assertEquals("Negative Accepted", result.subLabel)
    }

    @Test
    fun strictEndpointRejectingWith4xxIsRejected() {
        val base = jsonFp(200, """{"ok":true,"value":100}""")
        val classifier = Classifier(baselineOf(base, base, base), newConfig())
        val result = classifier.classify(jsonFp(400, """{"error":"invalid value"}"""), negative)
        assertEquals(Verdict.REJECTED, result.verdict)
    }

    @Test
    fun serverErrorIsRejectedNeverVulnerable() {
        val base = jsonFp(200, """{"ok":true}""")
        val classifier = Classifier(baselineOf(base, base, base), newConfig())
        val result = classifier.classify(jsonFp(500, """{"stack":"trace"}"""), negative)
        assertEquals(Verdict.REJECTED, result.verdict)
        assertTrue(result.signals.contains("5xx"))
    }

    @Test
    fun twoHundredButDifferentShapeIsReview() {
        val base = jsonFp(200, """{"ok":true,"value":100}""")
        val classifier = Classifier(baselineOf(base, base, base), newConfig())
        val result = classifier.classify(jsonFp(200, """{"message":"weird"}"""), negative)
        assertEquals(Verdict.REVIEW, result.verdict)
    }

    @Test
    fun positiveMatchAbsentForcesRejected() {
        val base = jsonFp(200, """{"ok":true,"value":100}""")
        val config = newConfig().apply { positiveMatch = "\"ok\":true" }
        val classifier = Classifier(baselineOf(base, base, base), config)
        val result = classifier.classify(jsonFp(200, """{"ok":false,"value":100}"""), negative)
        assertEquals(Verdict.REJECTED, result.verdict)
    }

    @Test
    fun negativeMatchPresentForcesRejectedEvenOn200() {
        val base = jsonFp(200, """{"ok":true,"value":100}""")
        val config = newConfig().apply { negativeMatch = "invalid" }
        val classifier = Classifier(baselineOf(base, base, base), config)
        val result = classifier.classify(jsonFp(200, """{"ok":true,"value":100,"note":"invalid"}"""), negative)
        assertEquals(Verdict.REJECTED, result.verdict)
    }

    @Test
    fun nonJsonIdenticalBodyIsAcceptedViaSimilarity() {
        val base = textFp(200, "Welcome user, your order is confirmed. Thank you.")
        val classifier = Classifier(baselineOf(base, base, base), newConfig())
        val result = classifier.classify(textFp(200, "Welcome user, your order is confirmed. Thank you."), negative)
        assertEquals(Verdict.VULNERABLE, result.verdict)
    }

    @Test
    fun nonJsonVeryDifferentBodyIsReview() {
        val base = textFp(200, "Welcome user, your order is confirmed. Thank you for shopping.")
        val classifier = Classifier(baselineOf(base, base, base), newConfig())
        val result = classifier.classify(textFp(200, "zzz qqq 9381 boom"), negative)
        assertEquals(Verdict.REVIEW, result.verdict)
    }

    @Test
    fun similarNotIdenticalWithNoMarkerIsReviewNotVulnerable() {
        // Same JSON shape, one non-noise value changed -> structural SIMILAR (not SAME). With no
        // positive/negative marker configured this is ambiguous, so it must be REVIEW, not VULNERABLE.
        val base = jsonFp(200, """{"ok":true,"value":100}""")
        val classifier = Classifier(baselineOf(base, base, base), newConfig())
        val result = classifier.classify(jsonFp(200, """{"ok":true,"value":999}"""), negative)
        assertEquals(Verdict.REVIEW, result.verdict)
    }

    @Test
    fun similarWithPositiveMarkerPresentIsVulnerable() {
        // Same SIMILAR match, but now a success marker is configured AND present -> strong enough.
        val base = jsonFp(200, """{"ok":true,"value":100}""")
        val config = newConfig().apply { positiveMatch = "\"ok\":true" }
        val classifier = Classifier(baselineOf(base, base, base), config)
        val result = classifier.classify(jsonFp(200, """{"ok":true,"value":999}"""), negative)
        assertEquals(Verdict.VULNERABLE, result.verdict)
    }

    @Test
    fun similarWithNegativeMarkerConfiguredButAbsentStaysVulnerable() {
        // A configured negative marker that does NOT fire still counts as "a marker to lean on",
        // so the legacy SIMILAR-accept behaviour is preserved when the user opted into markers.
        val base = jsonFp(200, """{"ok":true,"value":100}""")
        val config = newConfig().apply { negativeMatch = "invalid" }
        val classifier = Classifier(baselineOf(base, base, base), config)
        val result = classifier.classify(jsonFp(200, """{"ok":true,"value":999}"""), negative)
        assertEquals(Verdict.VULNERABLE, result.verdict)
    }

    @Test
    fun reflectionRecordedAsSignalButDoesNotSetVerdict() {
        val base = textFp(200, "result ok here")
        val classifier = Classifier(baselineOf(base, base, base), newConfig())
        val symbol = Payload("<>", PayloadCategory.SYMBOL)
        val result = classifier.classify(textFp(200, "result ok here <>"), symbol)
        assertTrue(result.signals.contains("reflected"))
        // masking the reflected payload keeps it similar -> accepted, but reflection alone didn't decide it
        assertEquals(Verdict.VULNERABLE, result.verdict)
    }
}
