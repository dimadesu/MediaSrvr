package com.dimadesu.mediasrvr

data class RtmpSessionInfo(val id: Int, val remote: String, val isPublishing: Boolean, val publishName: String?)

object RtmpServerState {
    private val sessions = mutableMapOf<Int, RtmpSessionInfo>()
    private val streams = mutableMapOf<String, Int>() // stream -> publisher session id

    @Synchronized
    fun registerSession(info: RtmpSessionInfo) {
        sessions[info.id] = info
    }

    @Synchronized
    fun updateSession(id: Int, isPublishing: Boolean, publishName: String?) {
        val prev = sessions[id]
        if (prev != null) {
            sessions[id] = prev.copy(isPublishing = isPublishing, publishName = publishName)
        }
    }

    @Synchronized
    fun unregisterSession(id: Int) {
        sessions.remove(id)
        // remove any streams published by this session
        val toRemove = streams.filterValues { it == id }.keys.toList()
        for (k in toRemove) streams.remove(k)
    }

    @Synchronized
    fun registerStream(name: String, publisherId: Int) {
        streams[name] = publisherId
    }

    @Synchronized
    fun unregisterStream(name: String) {
        streams.remove(name)
    }

    @Synchronized
    fun snapshot(): Pair<List<RtmpSessionInfo>, Map<String, Int>> {
        return Pair(sessions.values.toList(), streams.toMap())
    }
}
