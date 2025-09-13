package com.pichs.download.core

import com.pichs.download.model.DownloadTask
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.io.File

internal object DownloadEventBus {
    
    // 任务事件
    private val _taskEvents = MutableSharedFlow<TaskEvent>(replay = 0, extraBufferCapacity = 64)
    val taskEvents: SharedFlow<TaskEvent> = _taskEvents.asSharedFlow()
    
    // 进度事件
    private val _progressEvents = MutableSharedFlow<ProgressEvent>(replay = 0, extraBufferCapacity = 64)
    val progressEvents: SharedFlow<ProgressEvent> = _progressEvents.asSharedFlow()
    
    // 错误事件
    private val _errorEvents = MutableSharedFlow<ErrorEvent>(replay = 0, extraBufferCapacity = 64)
    val errorEvents: SharedFlow<ErrorEvent> = _errorEvents.asSharedFlow()
    
    // 系统事件
    private val _systemEvents = MutableSharedFlow<SystemEvent>(replay = 0, extraBufferCapacity = 64)
    val systemEvents: SharedFlow<SystemEvent> = _systemEvents.asSharedFlow()
    
    fun emitTaskEvent(event: TaskEvent) {
        _taskEvents.tryEmit(event)
    }
    
    fun emitProgressEvent(event: ProgressEvent) {
        _progressEvents.tryEmit(event)
    }
    
    fun emitErrorEvent(event: ErrorEvent) {
        _errorEvents.tryEmit(event)
    }
    
    fun emitSystemEvent(event: SystemEvent) {
        _systemEvents.tryEmit(event)
    }
}

sealed class TaskEvent {
    data class TaskCreated(val task: DownloadTask) : TaskEvent()
    data class TaskStarted(val task: DownloadTask) : TaskEvent()
    data class TaskPaused(val task: DownloadTask) : TaskEvent()
    data class TaskResumed(val task: DownloadTask) : TaskEvent()
    data class TaskCompleted(val task: DownloadTask, val file: File) : TaskEvent()
    data class TaskCancelled(val task: DownloadTask) : TaskEvent()
    data class TaskRemoved(val taskId: String) : TaskEvent()
}

sealed class ProgressEvent {
    data class ProgressUpdated(val taskId: String, val progress: Int, val speed: Long) : ProgressEvent()
    data class ChunkProgressUpdated(val taskId: String, val chunkIndex: Int, val progress: Int) : ProgressEvent()
}

sealed class ErrorEvent {
    data class TaskError(val context: ErrorContext) : ErrorEvent()
    data class ChunkError(val context: ErrorContext) : ErrorEvent()
    data class SystemError(val context: ErrorContext) : ErrorEvent()
}

sealed class SystemEvent {
    data class NetworkChanged(val networkType: NetworkType) : SystemEvent()
    data class BatteryChanged(val isLowBattery: Boolean, val level: Int) : SystemEvent()
    data class StorageChanged(val availableSpace: Long) : SystemEvent()
    data class SchedulerConfigChanged(val config: SchedulerConfig) : SystemEvent()
}
