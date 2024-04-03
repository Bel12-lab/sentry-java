package io.sentry.android.replay

import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import io.sentry.DateUtils
import io.sentry.Hint
import io.sentry.IHub
import io.sentry.Integration
import io.sentry.ReplayRecording
import io.sentry.SentryLevel.DEBUG
import io.sentry.SentryLevel.INFO
import io.sentry.SentryOptions
import io.sentry.SentryReplayEvent
import io.sentry.protocol.SentryId
import io.sentry.rrweb.RRWebMetaEvent
import io.sentry.rrweb.RRWebVideoEvent
import io.sentry.transport.ICurrentDateProvider
import io.sentry.util.FileUtils
import java.io.Closeable
import java.io.File
import java.util.Date
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit.MILLISECONDS
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.LazyThreadSafetyMode.NONE

class ReplayIntegration(
    private val context: Context,
    private val dateProvider: ICurrentDateProvider
) : Integration, Closeable, ScreenshotRecorderCallback {

    companion object {
        const val VIDEO_SEGMENT_DURATION = 5_000L
        const val VIDEO_BUFFER_DURATION = 30_000L
    }

    private lateinit var options: SentryOptions
    private var hub: IHub? = null
    private var recorder: WindowRecorder? = null
    private var cache: ReplayCache? = null

    // TODO: probably not everything has to be thread-safe here
    private val isEnabled = AtomicBoolean(false)
    private val isRecording = AtomicBoolean(false)
    private val currentReplayId = AtomicReference(SentryId.EMPTY_ID)
    private val segmentTimestamp = AtomicReference<Date>()
    private val currentSegment = AtomicInteger(0)
    private val saver =
        Executors.newSingleThreadScheduledExecutor(ReplayExecutorServiceThreadFactory())

    private val recorderConfig by lazy(NONE) {
        ScreenshotRecorderConfig.from(
            context,
            targetHeight = 720,
            options.experimental.sessionReplayOptions
        )
    }

    override fun register(hub: IHub, options: SentryOptions) {
        this.options = options

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            options.logger.log(INFO, "Session replay is only supported on API 26 and above")
            return
        }

        // TODO: check for replaysSessionSampleRate and replaysOnErrorSampleRate

        this.hub = hub
        recorder = WindowRecorder(options, recorderConfig, this)
        isEnabled.set(true)
    }

    fun isRecording() = isRecording.get()

    fun start() {
        // TODO: add lifecycle state instead and manage it in start/pause/resume/stop
        if (!isEnabled.get()) {
            options.logger.log(
                DEBUG,
                "Session replay is disabled due to conditions not met in Integration.register"
            )
            return
        }

        if (isRecording.getAndSet(true)) {
            options.logger.log(
                DEBUG,
                "Session replay is already being recorded, not starting a new one"
            )
            return
        }

        currentSegment.set(0)
        currentReplayId.set(SentryId())
        hub?.configureScope { it.replayId = currentReplayId.get() }
        cache = ReplayCache(options, currentReplayId.get(), recorderConfig)

        recorder?.startRecording()
        // TODO: replace it with dateProvider.currentTimeMillis to also test it
        segmentTimestamp.set(DateUtils.getCurrentDateTime())
        // TODO: finalize old recording if there's some left on disk and send it using the replayId from persisted scope (e.g. for ANRs)
    }

    fun resume() {
        // TODO: replace it with dateProvider.currentTimeMillis to also test it
        segmentTimestamp.set(DateUtils.getCurrentDateTime())
        recorder?.resume()
    }

    fun pause() {
        val now = dateProvider.currentTimeMillis
        recorder?.pause()

        val currentSegmentTimestamp = segmentTimestamp.get()
        val segmentId = currentSegment.get()
        val duration = now - currentSegmentTimestamp.time
        val replayId = currentReplayId.get()
        saver.submit {
            val videoDuration =
                createAndCaptureSegment(duration, currentSegmentTimestamp, replayId, segmentId)
            if (videoDuration != null) {
                currentSegment.getAndIncrement()
            }
        }
    }

    fun stop() {
        if (!isEnabled.get()) {
            options.logger.log(
                DEBUG,
                "Session replay is disabled due to conditions not met in Integration.register"
            )
            return
        }

        val now = dateProvider.currentTimeMillis
        val currentSegmentTimestamp = segmentTimestamp.get()
        val segmentId = currentSegment.get()
        val duration = now - currentSegmentTimestamp.time
        val replayId = currentReplayId.get()
        val replayCacheDir = cache?.replayCacheDir
        saver.submit {
            createAndCaptureSegment(duration, currentSegmentTimestamp, replayId, segmentId)
            FileUtils.deleteRecursively(replayCacheDir)
        }

        recorder?.stopRecording()
        cache?.close()
        currentSegment.set(0)
        segmentTimestamp.set(null)
        currentReplayId.set(SentryId.EMPTY_ID)
        hub?.configureScope { it.replayId = SentryId.EMPTY_ID }
        isRecording.set(false)
    }

    override fun onScreenshotRecorded(bitmap: Bitmap) {
        // have to do it before submitting, otherwise if the queue is busy, the timestamp won't be
        // reflecting the exact time of when it was captured
        val frameTimestamp = dateProvider.currentTimeMillis
        saver.submit {
            cache?.addFrame(bitmap, frameTimestamp)

            val now = dateProvider.currentTimeMillis
            if (now - segmentTimestamp.get().time >= VIDEO_SEGMENT_DURATION) {
                val currentSegmentTimestamp = segmentTimestamp.get()
                val segmentId = currentSegment.get()
                val replayId = currentReplayId.get()

                val videoDuration =
                    createAndCaptureSegment(
                        VIDEO_SEGMENT_DURATION,
                        currentSegmentTimestamp,
                        replayId,
                        segmentId
                    )
                if (videoDuration != null) {
                    currentSegment.getAndIncrement()
                    // set next segment timestamp as close to the previous one as possible to avoid gaps
                    segmentTimestamp.set(DateUtils.getDateTime(currentSegmentTimestamp.time + videoDuration))
                }
            }
        }
    }

    private fun createAndCaptureSegment(
        duration: Long,
        currentSegmentTimestamp: Date,
        replayId: SentryId,
        segmentId: Int
    ): Long? {
        val generatedVideo = cache?.createVideoOf(
            duration,
            currentSegmentTimestamp.time,
            segmentId
        ) ?: return null

        val (video, frameCount, videoDuration) = generatedVideo
        captureReplay(
            video,
            replayId,
            currentSegmentTimestamp,
            segmentId,
            frameCount,
            videoDuration
        )
        return videoDuration
    }

    private fun captureReplay(
        video: File,
        currentReplayId: SentryId,
        segmentTimestamp: Date,
        segmentId: Int,
        frameCount: Int,
        duration: Long
    ) {
        val replay = SentryReplayEvent().apply {
            eventId = currentReplayId
            replayId = currentReplayId
            this.segmentId = segmentId
            this.timestamp = DateUtils.getDateTime(segmentTimestamp.time + duration)
            if (segmentId == 0) {
                replayStartTimestamp = segmentTimestamp
            }
            videoFile = video
        }

        val recording = ReplayRecording().apply {
            this.segmentId = segmentId
            payload = listOf(
                RRWebMetaEvent().apply {
                    this.timestamp = segmentTimestamp.time
                    height = recorderConfig.recordingHeight
                    width = recorderConfig.recordingWidth
                },
                RRWebVideoEvent().apply {
                    this.timestamp = segmentTimestamp.time
                    this.segmentId = segmentId
                    this.durationMs = duration
                    this.frameCount = frameCount
                    size = video.length()
                    frameRate = recorderConfig.frameRate
                    height = recorderConfig.recordingHeight
                    width = recorderConfig.recordingWidth
                    // TODO: support non-fullscreen windows later
                    left = 0
                    top = 0
                }
            )
        }

        val hint = Hint().apply { replayRecording = recording }
        hub?.captureReplay(replay, hint)
    }

    override fun close() {
        stop()
        saver.gracefullyShutdown(options)
    }

    private class ReplayExecutorServiceThreadFactory : ThreadFactory {
        private var cnt = 0
        override fun newThread(r: Runnable): Thread {
            val ret = Thread(r, "SentryReplayIntegration-" + cnt++)
            ret.setDaemon(true)
            return ret
        }
    }
}

/**
 * Retrieves the [ReplayIntegration] from the list of integrations in [SentryOptions]
 */
fun IHub.getReplayIntegration(): ReplayIntegration? =
    options.integrations.find { it is ReplayIntegration } as? ReplayIntegration

fun ExecutorService.gracefullyShutdown(options: SentryOptions) {
    synchronized(this) {
        if (!isShutdown) {
            shutdown()
        }
        try {
            if (!awaitTermination(options.shutdownTimeoutMillis, MILLISECONDS)) {
                shutdownNow()
            }
        } catch (e: InterruptedException) {
            shutdownNow()
            Thread.currentThread().interrupt()
        }
    }
}
