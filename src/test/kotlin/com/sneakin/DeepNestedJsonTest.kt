package com.sneakin

import com.sneakin.core.BaselineResult
import com.sneakin.core.JsonLeafWalker
import com.sneakin.core.JsonMutate
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

/**
 * Regression for branching JSON: a null leaf buried in object -> array -> object -> object must be
 * enumerated, mutated, and (when the server accepts/echoes it) classified VULNERABLE — so it reaches
 * the Combine tab. The earlier "tool missed merchantDtls.field1" report was a null-baseline *verdict*
 * miss (now fixed), not a nesting/enumeration miss: enumeration and mutation already handle any depth.
 */
class DeepNestedJsonTest {

    private val reqBody =
        """{"groupingFuncCd":"v","accountList":[{"id":"x","version":null,"merchantDtls":{"field1":null}}],"serviceChannel":"c"}"""

    private fun fp(body: String): ResponseFingerprint {
        val leaves = JsonLeafWalker.leaves(body)!!
        return ResponseFingerprint(
            200, 2, "application/json", true, body.length, 10, body,
            leaves.associate { it.path.display() to it.kind },
            leaves.associate { it.path.display() to it.value },
        )
    }

    @Test
    fun deepNullLeafIsEnumeratedAndMutated() {
        val leaves = JsonLeafWalker.leaves(reqBody)!!
        val paths = leaves.map { it.path.display() }
        assertTrue("accountList[0].merchantDtls.field1" in paths, "deep null leaf not enumerated: $paths")

        val target = leaves.first { it.path.display() == "accountList[0].merchantDtls.field1" }
        val out = JsonMutate.set(reqBody, target.path, "<{!@#}>", target.kind)!!
        assertTrue(out.contains("\"merchantDtls\":{\"field1\":\"<{!@#}>\"}"), "deep null leaf not filled: $out")
    }

    @Test
    fun deepNullLeafEchoedByServerClassifiesVulnerable() {
        // baseline echoes the request (field1 null), createdDt varies across samples -> noise.
        val baseA = fp("""{"code":"00","account":{"id":"x","field1":null,"createdDt":"T1"}}""")
        val baseB = fp("""{"code":"00","account":{"id":"x","field1":null,"createdDt":"T9"}}""")
        val nm = NoiseMap.build(listOf(baseA, baseB), emptyList())
        val classifier = Classifier(BaselineResult(listOf(baseA, baseB), nm, baseA), GlobalConfig().newScanConfig())

        // server accepted the illegal symbol into the previously-null field and echoed it back.
        val mutated = fp("""{"code":"00","account":{"id":"x","field1":"<{!@#}>","createdDt":"T2"}}""")
        val result = classifier.classify(mutated, Payload("<{!@#}>", PayloadCategory.SYMBOL))
        assertEquals(Verdict.VULNERABLE, result.verdict)
    }
}
