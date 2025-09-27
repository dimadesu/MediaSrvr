package com.dimadesu.mediasrvr

import org.junit.Test
import org.junit.Assert.*

class NodeEventBusTest {
    @Test
    fun testEventOrdering() {
        val calls = mutableListOf<String>()
        val l1: (Array<Any?>) -> Unit = { args -> calls.add("a:${args.joinToString()}") }
        val l2: (Array<Any?>) -> Unit = { args -> calls.add("b:${args.joinToString()}") }
        NodeEventBus.on("x", l1)
        NodeEventBus.on("x", l2)
        NodeEventBus.emit("x", 1, "two")
        assertEquals(2, calls.size)
        assertTrue(calls[0].startsWith("a:"))
        assertTrue(calls[1].startsWith("b:"))
        NodeEventBus.off("x", l1)
        calls.clear()
        NodeEventBus.emit("x", 3)
        assertEquals(1, calls.size)
    }
}
