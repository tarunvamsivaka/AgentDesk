package com.agentdesk.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Unit verification test for the ART Ahead-of-Time (AOT) Baseline Profile.
 *
 * Verifies:
 * 1. `baseline-prof.txt` exists in `:app/src/main/baseline-prof.txt`.
 * 2. File is non-empty and contains valid profile rule declarations.
 * 3. All critical hot execution paths are explicitly covered:
 *    - RuleEngine
 *    - Redactor
 *    - FastVectorMath
 *    - HybridRetriever
 *    - CommandGateway
 *    - ChatScreen composition
 */
class BaselineProfileValidationTest {

    private fun findBaselineProfileFile(): File {
        var current: File? = File(".").canonicalFile
        while (current != null) {
            val candidate = File(current, "app/src/main/baseline-prof.txt")
            if (candidate.exists()) {
                return candidate
            }
            current = current.parentFile
        }
        return File("c:/AgentDesk/app/src/main/baseline-prof.txt")
    }

    @Test
    fun testBaselineProfileExistsAndIsNotEmpty() {
        val file = findBaselineProfileFile()
        assertTrue("baseline-prof.txt must exist at ${file.absolutePath}", file.exists())
        assertTrue("baseline-prof.txt must not be empty", file.length() > 0)

        val lines = file.readLines().filter { it.isNotBlank() && !it.startsWith("#") }
        assertTrue("baseline-prof.txt must contain at least 10 rule lines", lines.size >= 10)
    }

    @Test
    fun testCriticalExecutionPathsAreIncludedInBaselineProfile() {
        val file = findBaselineProfileFile()
        assertTrue(file.exists())

        val content = file.readText()

        val requiredRules = listOf(
            "Lcom/agentdesk/agent/rule/RuleEngine;",
            "Lcom/agentdesk/core/security/Redactor;",
            "Lcom/agentdesk/knowledge/vector/FastVectorMath;",
            "Lcom/agentdesk/knowledge/retrieval/HybridRetriever;",
            "Lcom/agentdesk/agent/command/CommandGateway;",
            "Lcom/agentdesk/feature/chat/ChatScreenKt;",
            "Lcom/agentdesk/feature/chat/ChatViewModel;"
        )

        for (rule in requiredRules) {
            assertTrue(
                "Baseline profile must include critical execution rule: $rule",
                content.contains(rule)
            )
        }
    }
}
