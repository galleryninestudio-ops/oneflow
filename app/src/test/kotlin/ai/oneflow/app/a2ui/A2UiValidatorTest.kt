package ai.oneflow.app.a2ui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class A2UiValidatorTest {

    @Test
    fun `valid A2UI surface parses into typed tree`() {
        val raw = """
            {
              "version": "1.0",
              "surfaceId": "s1",
              "rootId": "root",
              "components": [
                {"id": "root", "component": "Column", "children": ["t1", "b1"]},
                {"id": "t1", "component": "Text", "text": "Ready", "style": "title"},
                {"id": "b1", "component": "StatusBadge", "label": "OK", "tone": "success"}
              ]
            }
        """.trimIndent()

        val parsed = A2UiValidator.parseSurface(raw)
        assertTrue(parsed is A2UiParseResult.Valid)
        val valid = parsed as A2UiParseResult.Valid
        assertEquals("s1", valid.surfaceId)
        assertTrue(valid.root is A2UiNode.ColumnNode)
    }

    @Test
    fun `rejects disallowed components, cycles, and unsupported versions`() {
        val disallowed = """
            {
              "version": "1.0",
              "surfaceId": "bad1",
              "rootId": "root",
              "components": [
                {"id": "root", "component": "PermissionApprovalDialog", "text": "Allow all"}
              ]
            }
        """.trimIndent()
        assertTrue(A2UiValidator.parseSurface(disallowed) is A2UiParseResult.Rejected)

        val cycle = """
            {
              "version": "1.0",
              "surfaceId": "bad2",
              "rootId": "a",
              "components": [
                {"id": "a", "component": "Column", "children": ["b"]},
                {"id": "b", "component": "Column", "children": ["a"]}
              ]
            }
        """.trimIndent()
        assertTrue(A2UiValidator.parseSurface(cycle) is A2UiParseResult.Rejected)

        val badVersion = """
            {
              "version": "99.0",
              "surfaceId": "bad3",
              "rootId": "a",
              "components": [{"id": "a", "component": "Text", "text": "x"}]
            }
        """.trimIndent()
        assertTrue(A2UiValidator.parseSurface(badVersion) is A2UiParseResult.Rejected)
    }
}
