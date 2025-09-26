package com.dimadesu.mediasrvr

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class RtmpSessionInfo(
    val id: Int,
    val remote: String,
    val isPublishing: Boolean,
    val publishName: String?,
    val bytesTransferred: Long = 0L,
    val connectedAt: Long = System.currentTimeMillis()
)

object RtmpServerState {
    private val sessions = mutableMapOf<Int, RtmpSessionInfo>()
    private val streams = mutableMapOf<String, Int>() // stream -> publisher session id

    private val _sessionsFlow = MutableStateFlow<List<RtmpSessionInfo>>(emptyList())
    val sessionsFlow: StateFlow<List<RtmpSessionInfo>> = _sessionsFlow

    private val _streamsFlow = MutableStateFlow<Map<String, Int>>(emptyMap())
    val streamsFlow: StateFlow<Map<String, Int>> = _streamsFlow

    @Synchronized
    fun registerSession(info: RtmpSessionInfo) {
        sessions[info.id] = info
        emit()
    }

    @Synchronized
    fun updateSession(id: Int, isPublishing: Boolean, publishName: String?) {
        val prev = sessions[id]
        if (prev != null) {
            sessions[id] = prev.copy(isPublishing = isPublishing, publishName = publishName)
            emit()
        }
    }

    @Synchronized
    fun updateSessionStats(id: Int, bytesTransferred: Long) {
        val prev = sessions[id]
        if (prev != null) {
            sessions[id] = prev.copy(bytesTransferred = bytesTransferred)
            emit()
        }
    }

    @Synchronized
    fun unregisterSession(id: Int) {
        sessions.remove(id)
        // remove any streams published by this session
        val toRemove = streams.filterValues { it == id }.keys.toList()
        for (k in toRemove) streams.remove(k)
        emit()
    }

    @Synchronized
    fun registerStream(name: String, publisherId: Int) {
        streams[name] = publisherId
        emit()
    }

    @Synchronized
    fun unregisterStream(name: String) {
        streams.remove(name)
        emit()
    }

    private fun emit() {
        _sessionsFlow.value = sessions.values.toList()
        _streamsFlow.value = streams.toMap()
    }

    @Synchronized
    fun snapshot(): Pair<List<RtmpSessionInfo>, Map<String, Int>> {
        return Pair(sessions.values.toList(), streams.toMap())
    }
}
