package com.dimadesu.mediasrvr

object NodeEventBus {
    private val listeners = mutableMapOf<String, MutableList<(Array<Any?>) -> Unit>>()

    fun on(event: String, listener: (Array<Any?>) -> Unit) {
        val l = listeners.getOrPut(event) { mutableListOf() }
        l.add(listener)
    }

    fun off(event: String, listener: (Array<Any?>) -> Unit) {
        listeners[event]?.remove(listener)
    }

    fun emit(event: String, vararg args: Any?) {
        val l = listeners[event]
        if (l == null) return
        for (fn in l.toList()) {
            try {
                fn(args as Array<Any?>)
            } catch (e: Exception) {
                android.util.Log.i("NodeEventBus", "Listener for $event threw: ${e.message}")
            }
        }
    }
}
