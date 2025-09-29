package com.dimadesu.mediasrvr

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.BufferedReader
import java.io.File
import java.io.FileReader
import java.util.LinkedList

class LogViewModel(application: Application) : AndroidViewModel(application) {
    private val _lines = MutableLiveData<List<String>>(emptyList())
    val lines: LiveData<List<String>> = _lines

    private var pollJobRunning = false

    fun startPolling(intervalMs: Long = 1000L, maxLines: Int = 10) {
        if (pollJobRunning) return
        pollJobRunning = true
        viewModelScope.launch(Dispatchers.IO) {
            while (isActive) {
                try {
                    val logDir = getApplication<Application>().filesDir.absolutePath + "/nodejs-project"
                    val logFile = File("$logDir/nms.log")
                    val resultLines = if (!logFile.exists()) {
                        listOf("(no nms.log found at ${logFile.absolutePath})")
                    } else {
                        val tail = LinkedList<String>()
                        val br = BufferedReader(FileReader(logFile))
                        var line: String?
                        while (br.readLine().also { line = it } != null) {
                            if (line == null) continue
                            val trimmed = line!!.trim()
                            if (trimmed.isEmpty()) continue
                            val singleLine = trimmed.replace(Regex("\\s+"), " ")
                            tail.add(singleLine)
                            if (tail.size > maxLines) tail.removeFirst()
                        }
                        br.close()
                        ArrayList(tail)
                    }
                    _lines.postValue(resultLines)
                } catch (e: Exception) {
                    _lines.postValue(listOf(e.toString()))
                }
                delay(intervalMs)
            }
        }
    }

    fun stopPolling() {
        pollJobRunning = false
        // viewModelScope cancellation will stop the loop when activity is cleared
    }
}
