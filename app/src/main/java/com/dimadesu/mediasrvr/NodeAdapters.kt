package com.dimadesu.mediasrvr

import android.content.Context
import android.util.Log

object NodeAdapters {
    private const val TAG = "NodeAdapters"

    // Keep references to listeners in case callers want to unregister later.
    private var postPublishListener: ((Array<Any?>) -> Unit)? = null
    private var donePublishListener: ((Array<Any?>) -> Unit)? = null

    fun init(ctx: Context) {
        // id, streamPath, args
        postPublishListener = { args ->
            try {
                val id = args.getOrNull(0)
                val streamPath = args.getOrNull(1)
                val extra = args.getOrNull(2)
                Log.i(TAG, "[postPublish] id=$id streamPath=$streamPath args=$extra")
                // Example: in Node, trans server starts FFmpeg jobs here. We only log.
            } catch (e: Exception) {
                Log.e(TAG, "postPublish listener error", e)
            }
        }

        donePublishListener = { args ->
            try {
                val id = args.getOrNull(0)
                val streamPath = args.getOrNull(1)
                val extra = args.getOrNull(2)
                Log.i(TAG, "[donePublish] id=$id streamPath=$streamPath args=$extra")
            } catch (e: Exception) {
                Log.e(TAG, "donePublish listener error", e)
            }
        }

        NodeEventBus.on("postPublish", postPublishListener!!)
        NodeEventBus.on("donePublish", donePublishListener!!)
        Log.i(TAG, "NodeAdapters initialized: postPublish/donePublish listeners registered")
    }

    fun shutdown() {
        postPublishListener?.let { NodeEventBus.off("postPublish", it) }
        donePublishListener?.let { NodeEventBus.off("donePublish", it) }
        postPublishListener = null
        donePublishListener = null
        Log.i(TAG, "NodeAdapters shutdown: listeners removed")
    }
}
