package dev.aichallenge.rag

import dev.aichallenge.rag.grounding.adapters.IsolatedClaimSupportValidator
import dev.aichallenge.rag.grounding.enums.*
import dev.aichallenge.rag.grounding.models.*
import dev.aichallenge.rag.grounding.ports.ClaimSupportValidator
import dev.aichallenge.rag.grounding.services.ClaimSupportPromptAssembler
import dev.aichallenge.rag.indexing.models.Chunk
import dev.aichallenge.rag.retrieval.models.SearchHit
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import tools.jackson.module.kotlin.jacksonObjectMapper

class IsolatedClaimSupportTest {
    private val hits = (0..2).map { n -> SearchHit(1, .8, Chunk("c$n", "d$n", "source$n", "title", "section", emptyList(), 0, 0, 30, 1, 1, "Уникальный текст источника $n.", "hash")) }
    private val claims = hits.map { GroundedClaim("Утверждение ${it.chunk.chunkId}", listOf(VerifiedCitation(EvidenceSource(it.chunk.chunkId, it.chunk.documentId, it.chunk.source, "title", "section"), it.chunk.text, 0, it.chunk.text.length, 0, it.chunk.text.length))) }
    private val trace = GroundingGeneration("fixture", "stop", 1, null, null, emptyList(), "{}")
    @Test fun `each call sees one claim and only its own chunks while outer indices are restored`() {
        var calls = 0
        val delegate = object : ClaimSupportValidator {
            override fun validate(input: List<GroundedClaim>, included: List<SearchHit>): ClaimSupportCheck {
                assertEquals(listOf(claims[calls]), input)
                assertEquals(listOf(hits[calls]), included)
                val rejected = calls++ == 1
                return ClaimSupportCheck(if (rejected) SupportCheckStatus.REJECTED else SupportCheckStatus.PASSED,
                    listOf(ClaimSupportAssessment(0, if (rejected) ClaimSupportVerdict.UNSUPPORTED else ClaimSupportVerdict.SUPPORTED, "reason")),
                    if (rejected) listOf(EvidenceIssue("unsupported", "reason", 0)) else emptyList(), trace)
            }
        }
        val checked = IsolatedClaimSupportValidator(delegate, ClaimSupportPromptAssembler(jacksonObjectMapper()), 1).validate(claims, hits)
        assertEquals(3, calls); assertEquals(3, checked.generations().size)
        assertEquals(listOf(0, 1, 2), checked.claims.map { it.claimIndex })
        assertEquals(1, checked.issues.single().claimIndex)
        assertEquals(SupportCheckStatus.REJECTED, checked.status)
    }
    @Test fun `transport failure keeps preceding calls and failed attempt without retry or pretending zero usage`() {
        var calls = 0
        val delegate = object : ClaimSupportValidator {
            override fun validate(input: List<GroundedClaim>, included: List<SearchHit>): ClaimSupportCheck {
                if (++calls == 2) throw IllegalStateException("sensitive transport body")
                return ClaimSupportCheck(SupportCheckStatus.PASSED, listOf(ClaimSupportAssessment(0, ClaimSupportVerdict.SUPPORTED, "reason")), emptyList(), trace)
            }
        }
        val checked = IsolatedClaimSupportValidator(delegate, ClaimSupportPromptAssembler(jacksonObjectMapper()), 1).validate(claims, hits)
        assertEquals(SupportCheckStatus.INVALID_RESPONSE, checked.status)
        assertEquals(3, calls); assertEquals(3, checked.generations().size)
        assertNull(checked.additionalGenerations.first().usage)
        assertEquals("error", checked.additionalGenerations.first().finishReason)
        assertFalse(checked.toString().contains("sensitive"))
    }
    @Test fun `parallel checks are bounded and results retain original claim order`() {
        val active = java.util.concurrent.atomic.AtomicInteger()
        val peak = java.util.concurrent.atomic.AtomicInteger()
        val delegate = object : ClaimSupportValidator {
            override fun validate(claims: List<GroundedClaim>, included: List<SearchHit>): ClaimSupportCheck {
                val now = active.incrementAndGet(); peak.accumulateAndGet(now, ::maxOf)
                try {
                    Thread.sleep(30)
                    return ClaimSupportCheck(SupportCheckStatus.PASSED, listOf(ClaimSupportAssessment(0, ClaimSupportVerdict.SUPPORTED, claims.single().text)), emptyList(), trace)
                } finally { active.decrementAndGet() }
            }
        }
        val checker = IsolatedClaimSupportValidator(delegate, ClaimSupportPromptAssembler(jacksonObjectMapper()), 3)
        try {
            val input = claims + claims
            val result = checker.validate(input, hits)
            assertTrue(peak.get() in 2..3)
            assertEquals(input.map { it.text }, result.claims.map { it.reason })
            assertEquals((0..5).toList(), result.claims.map { it.claimIndex })
            assertEquals(6, result.generations().size)
        } finally { checker.close() }
    }
}
