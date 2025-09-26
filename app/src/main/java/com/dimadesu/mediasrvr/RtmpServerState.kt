package com.dimadesu.mediasrvr

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay

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

    // Debounce emissions to at most once per throttleMillis to avoid UI churn under heavy load
    private const val throttleMillis = 200L
    private val emitScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    @Volatile
    private var emitJob: kotlinx.coroutines.Job? = null

    @Synchronized
    fun registerSession(info: RtmpSessionInfo) {
        sessions[info.id] = info
        android.util.Log.i("RtmpServerState", "registerSession id=${info.id} remote=${info.remote} isPublishing=${info.isPublishing} publishName=${info.publishName}")
        scheduleEmit()
    }

    @Synchronized
    fun updateSession(id: Int, isPublishing: Boolean, publishName: String?) {
        val prev = sessions[id]
        if (prev != null) {
            sessions[id] = prev.copy(isPublishing = isPublishing, publishName = publishName)
            scheduleEmit()
        }
    }

    @Synchronized
    fun updateSessionStats(id: Int, bytesTransferred: Long) {
        val prev = sessions[id]
        if (prev != null) {
            sessions[id] = prev.copy(bytesTransferred = bytesTransferred)
            scheduleEmit()
        }
    }

    @Synchronized
    fun unregisterSession(id: Int) {
        sessions.remove(id)
        android.util.Log.i("RtmpServerState", "unregisterSession id=$id")
        // remove any streams published by this session
        val toRemove = streams.filterValues { it == id }.keys.toList()
        for (k in toRemove) streams.remove(k)
        scheduleEmit()
    }

    @Synchronized
    fun registerStream(name: String, publisherId: Int) {
        streams[name] = publisherId
        android.util.Log.i("RtmpServerState", "registerStream name=$name publisherId=$publisherId")
        scheduleEmit()
    }

    @Synchronized
    fun unregisterStream(name: String) {
        streams.remove(name)
        android.util.Log.i("RtmpServerState", "unregisterStream name=$name")
        scheduleEmit()
    }

    private fun scheduleEmit() {
        // cancel previous scheduled emit and schedule a new one after throttleMillis
        emitJob?.cancel()
        emitJob = emitScope.launch {
            kotlinx.coroutines.delay(throttleMillis)
            val sess = synchronized(this@RtmpServerState) { sessions.values.toList() }
            val strs = synchronized(this@RtmpServerState) { streams.toMap() }
            // debug log to help trace updates
            android.util.Log.i("RtmpServerState", "Emitting sessions=${sess.size} streams=${strs.size}")
            _sessionsFlow.value = sess
            _streamsFlow.value = strs
        }
    }

    @Synchronized
    fun snapshot(): Pair<List<RtmpSessionInfo>, Map<String, Int>> {
        return Pair(sessions.values.toList(), streams.toMap())
    }
}
