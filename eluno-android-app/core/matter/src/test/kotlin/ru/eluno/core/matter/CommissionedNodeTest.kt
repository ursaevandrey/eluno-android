package ru.eluno.core.matter

import org.junit.Assert.*
import org.junit.Test

class CommissionedNodeTest {
    @Test fun `successful new commissioning keeps the requested node`() {
        assertEquals(5L, commissionedNode(5L, 0, 0))
    }
    @Test fun `fabric conflict can reuse only a matched existing node`() {
        assertEquals(7L, commissionedNode(5L, 126, 7L))
        assertNull(commissionedNode(5L, 126, 0))
    }
    @Test fun `a matched fabric never overrides other commissioning errors`() {
        assertNull(commissionedNode(5L, 50, 7L))
        assertNull(commissionedNode(5L, 51, 7L))
    }
}
