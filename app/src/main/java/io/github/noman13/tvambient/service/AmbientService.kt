package io.github.noman13.tvambient.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import io.github.noman13.tvambient.Native
import io.github.noman13.tvambient.NativeConfig
import io.github.noman13.tvambient.R
import io.github.noman13.tvambient.adb.Shell
import io.github.noman13.tvambient.audio.AudioInput
import io.github.noman13.tvambient.ui.HomeActivity
import io.github.noman13.tvambient.ui.SettingsSchema as S
import java.nio.ByteBuffer
import java.util.concurrent.locks.LockSupport

/**
 * The foreground service (TECHNICAL.md §4, §13): one output thread at the LED rate, the USB port, and per mode only
 * what that mode needs: the capture helper in Screen mode, audio in Music mode, nothing else for Effect and Single
 * colour (the helper runs stats-only there while the stats panel is shown). Nothing runs while it's stopped.
 *
 * Threads: main (lifecycle, settings, mode changes), the output thread, the helper link's reader, and the
 * Visualizer's callback. Everything native goes through [lock].
 */
class AmbientService : Service(), SharedPreferences.OnSharedPreferenceChangeListener {
    private lateinit var sp: SharedPreferences
    private lateinit var modes: ModeController
    private val main = Handler(Looper.getMainLooper())
    private val lock = Object()
    private var pipeline = 0L // under lock
    private var audio = 0L // under lock: the Rust music engine
    private var leds = 0
    private var layoutKey = ""
    private lateinit var port: LedPort
    private var helper: HelperLink? = null
    private var helperRunning = HELPER_OFF // main thread
    private var audioIn: AudioInput? = null // main thread
    private var projection: io.github.noman13.tvambient.capture.ProjectionCapture? = null // main thread
    @Volatile private var running = false
    @Volatile private var screenOff = false
    @Volatile private var mode = ModeController.SCREEN
    @Volatile private var frozen = false // "Keep last colours" during silence: only keep-alives go out
    private var output: Thread? = null

    // silence handling (TECHNICAL.md §14.2 "After 5 s of silence"), output thread
    @Volatile private var loudAt = 0L
    @Volatile private var silenceActed = false
    private val snapshot = ByteBuffer.allocateDirect(16)
    private var shownBuf = ByteBuffer.allocateDirect(0) // output thread: the colours last sent, for the UI previews
    private var lastShownAt = 0L

    // output-thread buffers, allocated per pipeline (never per frame)
    private var packet = ByteBuffer.allocateDirect(0)
    private var packetBytes = ByteArray(0)

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            screenOff = i.action == Intent.ACTION_SCREEN_OFF
            Log.i(TAG, "screen ${if (screenOff) "off" else "on"}")
            if (screenOff) {
                stopHelper()
                stopAudio()
                if (S.get(sp, S.SCREEN_OFF) == 0) setSource(Native.SOURCE_OFF)
            } else {
                applyMode()
            }
        }
    }

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            if (i.action == UsbManager.ACTION_USB_DEVICE_DETACHED) port.requestClose() else port.resetPermissionAsk()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Native.load()
        sp = S.prefs(this)
        modes = ModeController(sp)
        port = LedPort(this)
        rebuildPipeline()
        sp.registerOnSharedPreferenceChangeListener(this)
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.app_name), NotificationManager.IMPORTANCE_LOW),
        )
        registerReceiver(screenReceiver, IntentFilter(Intent.ACTION_SCREEN_OFF).apply { addAction(Intent.ACTION_SCREEN_ON) })
        val uf = IntentFilter(LedPort.ACTION_USB_PERMISSION).apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        // not exported: only our own USB permission PendingIntent and the system's protected USB broadcasts reach it
        // (below API 33, a signature permission does the same)
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(usbReceiver, uf, Context.RECEIVER_NOT_EXPORTED)
        else registerReceiver(usbReceiver, uf, "$packageName.permission.INTERNAL", null)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        // the media-projection service type only with a granted projection: starting it after a refusal throws on
        // API 34; a refusal still reaches startProjection, which reports it
        val consent = intent?.takeIf {
            it.action == ACTION_PROJECTION && it.getIntExtra(EXTRA_CODE, 0) == android.app.Activity.RESULT_OK && it.hasExtra(EXTRA_DATA)
        }
        val n = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.app_name))
            .setContentText("Lights on")
            .setContentIntent(PendingIntent.getActivity(this, 0, Intent(this, HomeActivity::class.java), PendingIntent.FLAG_IMMUTABLE))
            .setOngoing(true)
            .build()
        // with screen-recording consent the service also becomes a media-projection one (required before the projection)
        val type = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or
            if (consent != null) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION else 0
        if (Build.VERSION.SDK_INT >= 29) startForeground(1, n, type) else startForeground(1, n)
        if (!running) {
            running = true
            sRunning = true
            sInstance = this
            if (S.get(sp, S.START_IN) == 1 && modes.mode != ModeController.SCREEN) {
                sp.edit().putInt(S.MODE.key, ModeController.SCREEN).apply() // "Start in: Screen" (DESIGN.md §11.1)
            }
            sAutoSwitched = false
            applyMode()
            output = Thread(::outputLoop, "tvambient-output").apply { start() }
        }
        if (consent != null) {
            @Suppress("DEPRECATION")
            startProjection(consent.getIntExtra(EXTRA_CODE, 0), consent.getParcelableExtra(EXTRA_DATA))
        } else if (intent?.action == ACTION_PROJECTION) {
            startProjection(0, null) // refused: says so in the status, nothing else
        }
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        sRunning = false
        sInstance = null
        sStatus = "Stopped"
        main.removeCallbacksAndMessages(null) // also the automatic source probe
        // The output thread darkens the strip, closes the port and frees the pipeline and the music engine as it
        // exits: no USB I/O or waiting on the main thread.
        output?.interrupt()
        output = null
        helper?.stop()
        stopAudio()
        stopProjection()
        sp.unregisterOnSharedPreferenceChangeListener(this)
        runCatching { unregisterReceiver(screenReceiver) }
        runCatching { unregisterReceiver(usbReceiver) }
        super.onDestroy()
    }

    override fun onSharedPreferenceChanged(s: SharedPreferences, key: String?) {
        when {
            key == S.BAUD.key -> port.requestClose() // reopened at the new speed by the output loop
            key in PICTURE_KEYS -> synchronized(lock) {
                if (pipeline != 0L) Native.pipelineSetPicture(pipeline, NativeConfig.picture(S.picture(sp)), S.get(sp, S.SMOOTHING))
            }
            key in LAYOUT_KEYS || key == S.COLOR_ORDER.key -> {
                rebuildPipeline()
                if (running) {
                    stopHelper()
                    // a new pipeline starts dark: put the Settings test pattern back (the strip editor shows one)
                    if (overrideActive) override(overrideKind, overrideArg, overrideParams) else applyMode()
                }
            }
            key == S.CAPTURE_METHOD.key -> if (running) { shellFailed = false; stopHelper(); applyMode() }
            key == S.CAPTURE_RATE.key || key == S.CAPTURE_ADAPTIVE.key || key == S.BARS.key || key == S.DARK_REACH.key -> if (running && helperRunning != HELPER_OFF) {
                stopHelper(); applyMode()
            }
            key == S.MODE.key -> if (running) {
                if (!sAutoSwitching) sAutoSwitched = false // any manual mode change (§13)
                applyMode()
            }
            key in MODE_KEYS -> if (running) applyMode()
            key != null && key.startsWith(S.FX_PREFIX) -> if (running && mode == ModeController.EFFECT) applyMode()
            key == S.AUDIO_SOURCE.key -> if (running) { sp.edit().remove(FOUND_KEY).apply(); applyMode() }
            key == S.MUSIC_SENS.key || key == S.MUSIC_RANGE.key -> synchronized(lock) {
                if (audio != 0L) { Native.audioFree(audio); audio = Native.audioNew(S.audio(sp)) }
            }
        }
    }

    // ---------------------------------------------------------------- modes

    /** Puts the pipeline and the helper/audio into the current mode (main thread). Mode changes cross-fade in Rust. */
    private fun applyMode() {
        if (!running || screenOff) return
        if (overrideActive) return // a Settings test pattern is on the strip; the mode comes back with clearOverride
        val m = modes.mode
        mode = m
        frozen = false
        silenceActed = false
        loudAt = System.nanoTime()
        val now = System.nanoTime()
        val wantAudio = m == ModeController.MUSIC || (m == ModeController.SCREEN && sAutoSwitched)
        synchronized(lock) {
            if (pipeline == 0L) return
            when (m) {
                ModeController.SCREEN -> Native.pipelineSetSource(pipeline, Native.SOURCE_SCREEN, 0, null, now)
                ModeController.MUSIC -> Native.pipelineSetSource(pipeline, Native.SOURCE_MUSIC, 0, S.music(sp), now)
                ModeController.EFFECT -> S.get(sp, S.EFFECT).let { id -> Native.pipelineSetSource(pipeline, Native.SOURCE_EFFECT, id, S.effectParams(sp, id), now) }
                else -> Native.pipelineSetSource(pipeline, Native.SOURCE_SINGLE, 0, S.single(sp), now)
            }
            if (wantAudio && audio == 0L) audio = Native.audioNew(S.audio(sp))
        }
        val source = if (wantAudio) audioSource() else 0
        val capture = m == ModeController.SCREEN
        val recording = capture && useRecording()
        if (!recording) stopProjection()
        when {
            recording -> {
                // screen recording instead of the helper (TECHNICAL.md §6 fallback); the helper only for source B audio
                if (source == SOURCE_B) startHelper(HELPER_PCM) else if (sStatsOn) startHelper(HELPER_STATS) else stopHelper()
                if (projection == null) {
                    sNeedProjection = true
                    sStatus = getString(io.github.noman13.tvambient.R.string.recording_needs_ok)
                }
            }
            capture -> startHelper(HELPER_CAPTURE)
            source == SOURCE_B -> startHelper(HELPER_PCM)
            sStatsOn -> startHelper(HELPER_STATS)
            else -> stopHelper()
        }
        if (m == ModeController.SCREEN && wantAudio && audioSource() == SOURCE_B) {
            // waiting for music to come back after an automatic switch: B would need a second helper, so listen with A
            startAudio()
        } else if (source == SOURCE_A) startAudio() else stopAudio()
        if (source == SOURCE_B) sAudioSource = "playback capture"
        if (source == SOURCE_NONE) sAudioSource = "unavailable (remote submix isn't in this build)"
        if (source == SOURCE_A && S.get(sp, S.AUDIO_SOURCE) == 0 && sp.getInt(FOUND_KEY, 0) == 0 && Build.VERSION.SDK_INT >= 33) {
            main.removeCallbacks(autoProbe)
            main.postDelayed(autoProbe, 3000) // "Automatic": is A hearing what plays? (§14.1)
        }
        sMode = modes.name(m)
        Log.i(TAG, "mode: $sMode")
    }

    /** Screen recording for captures: chosen in Screen mode, or Automatic after the shell failed (no USB debugging). */
    private fun useRecording(): Boolean = when (S.get(sp, S.CAPTURE_METHOD)) {
        2 -> true
        1 -> false
        else -> shellFailed && !sShellOk
    }

    @Volatile private var shellFailed = false
    @Volatile private var shellFailures = 0

    private fun startProjection(code: Int, data: Intent?) {
        sNeedProjection = false
        if (data == null || code != android.app.Activity.RESULT_OK) {
            sStatus = getString(io.github.noman13.tvambient.R.string.recording_refused)
            return
        }
        stopProjection()
        val cfg = NativeConfig.unhex(layoutKey)
        val p = io.github.noman13.tvambient.capture.ProjectionCapture(this, cfg, leds, RECORDING_MAX_RATE,
            onFrame = { buf, len -> synchronized(lock) { if (pipeline != 0L && Native.pipelineFeed(pipeline, buf, len, System.nanoTime()) == 0) framesIn++ } },
            onStop = { main.post { stopProjection(); if (running && useRecording()) sNeedProjection = true } },
        )
        if (p.start(code, data)) {
            projection = p
            sCapturePath = "Screen recording"
            sStatus = getString(io.github.noman13.tvambient.R.string.recording_notice)
            Log.i(TAG, "capture: screen recording, up to $RECORDING_MAX_RATE/s")
        } else {
            sStatus = getString(io.github.noman13.tvambient.R.string.recording_refused)
        }
    }

    private fun stopProjection() {
        projection?.stop()
        projection = null
        sCapturePath = "Shell screenshots"
    }

    /** The audio source for Music mode: the setting, or for Automatic the one found to work (A until proven silent). */
    private fun audioSource(): Int = when (S.get(sp, S.AUDIO_SOURCE)) {
        1 -> SOURCE_A
        2 -> if (Build.VERSION.SDK_INT >= 33) SOURCE_B else SOURCE_NONE
        3 -> SOURCE_NONE
        else -> sp.getInt(FOUND_KEY, 0).takeIf { it == SOURCE_A || it == SOURCE_B } ?: SOURCE_A
    }

    /**
     * Automatic, 3 s after A started: if something plays (AudioManager says music is active) but A hears silence,
     * switch to B and remember it; if A hears sound, remember A. Nothing is decided while nothing plays.
     */
    private val autoProbe: Runnable = Runnable {
        if (!running || mode != ModeController.MUSIC || S.get(sp, S.AUDIO_SOURCE) != 0) return@Runnable
        val playing = getSystemService(android.media.AudioManager::class.java).isMusicActive
        when {
            !playing -> main.postDelayed(autoProbeAgain, 3000)
            Stats.musicDb > -70 -> { sp.edit().putInt(FOUND_KEY, SOURCE_A).apply(); Log.i(TAG, "audio: automatic chose the output mix") }
            else -> {
                Log.i(TAG, "audio: output mix is silent while music plays; trying playback capture")
                sp.edit().putInt(FOUND_KEY, SOURCE_B).apply()
                applyMode()
            }
        }
    }
    private val autoProbeAgain: Runnable = Runnable { autoProbe.run() }

    // ---------------------------------------------------------------- Settings test patterns (LED strip, white balance)

    @Volatile private var overrideActive = false

    private var overrideKind = 0
    private var overrideArg = 0
    private var overrideParams: ByteArray? = null

    private fun override(kind: Int, arg: Int, params: ByteArray?) {
        overrideActive = true
        overrideKind = kind; overrideArg = arg; overrideParams = params
        stopAudio()
        synchronized(lock) { if (pipeline != 0L) Native.pipelineSetSource(pipeline, kind, arg, params, System.nanoTime()) }
    }

    private fun clearOverride() {
        if (!overrideActive) return
        overrideActive = false
        applyMode()
    }

    private fun setSource(kind: Int) = synchronized(lock) {
        if (pipeline != 0L) Native.pipelineSetSource(pipeline, kind, 0, null, System.nanoTime())
    }

    /** The stats panel was shown or hidden: outside Screen mode the helper runs stats-only just for it. */
    private fun statsWanted(on: Boolean) {
        helper?.setStats(on, sStatsIntervalMs)
        if (running && !screenOff && (helperRunning == HELPER_OFF || helperRunning == HELPER_STATS)) {
            if (on) startHelper(HELPER_STATS) else stopHelper()
        }
    }

    // ---------------------------------------------------------------- audio (Music mode, §14)

    private fun startAudio() {
        if (audioIn?.running == true) return
        val input = audioIn ?: AudioInput { fft, tMs ->
            synchronized(lock) { if (audio != 0L) Native.audioFeedVisualizer(audio, fft, tMs) }
        }.also { audioIn = it }
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            sAudioSource = "starting"
            input.start { err -> if (audioIn === input) sAudioSource = if (err == null) "output mix" else "unavailable" }
            return
        }
        // Self-grant over the app's own ADB (TECHNICAL.md §14.1), only for our package; off the main thread.
        sAudioSource = "asking for audio permission"
        val app = applicationContext
        Thread({
            val ok = runCatching { Shell.run(app, "pm grant ${app.packageName} android.permission.RECORD_AUDIO") }.isSuccess &&
                app.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
            main.post {
                if (!running || audioIn !== input) return@post
                if (!ok) { sAudioSource = "unavailable (no audio permission)"; return@post }
                input.start { err -> if (audioIn === input) sAudioSource = if (err == null) "output mix" else "unavailable" }
            }
        }, "tvambient-grant").start()
    }

    private fun stopAudio() {
        audioIn?.stop()
        audioIn = null
        sAudioSource = "-"
    }

    /** Output thread, 4 times a second in Music mode (or while waiting to switch back): silence and the stats. */
    private fun watchMusic(now: Long) {
        val db: Int
        val bpm: Int
        synchronized(lock) {
            if (audio == 0L || Native.audioSnapshot(audio, snapshot) != 16) return
            db = snapshot.get(12).toInt()
            bpm = snapshot.get(13).toInt() and 0xff
            for (k in 0 until 12) Stats.musicBands[k] = snapshot.get(k)
        }
        Stats.musicDb = db
        Stats.musicBpm = bpm
        if (db > SILENCE_DB) {
            loudAt = now
            if (silenceActed) {
                silenceActed = false
                Log.i(TAG, "music: sound again")
                main.post { onSoundAgain() }
            }
        } else if (!silenceActed && now - loudAt > SILENCE_NS && mode == ModeController.MUSIC) {
            silenceActed = true
            Log.i(TAG, "music: 5 s of silence")
            main.post { onSilence() }
        }
    }

    private fun onSilence() {
        if (!running || mode != ModeController.MUSIC) return
        when (S.get(sp, S.MUSIC_SILENCE)) {
            1 -> setSource(Native.SOURCE_OFF) // lights off
            2 -> { // switch to Screen; audio keeps listening so the music can bring it back
                sAutoSwitched = true
                sAutoSwitching = true
                sp.edit().putInt(S.MODE.key, ModeController.SCREEN).apply() // apply() notifies listeners at once on the main thread
                sAutoSwitching = false
                silenceActed = true
            }
            3 -> frozen = true // keep last colours
            // 0 dim glow: the music effect already keeps the minimum brightness
        }
    }

    private fun onSoundAgain() {
        if (!running) return
        when {
            mode == ModeController.SCREEN && sAutoSwitched -> {
                sAutoSwitching = true
                sp.edit().putInt(S.MODE.key, ModeController.MUSIC).apply()
                sAutoSwitching = false
                sAutoSwitched = false
            }
            mode == ModeController.MUSIC -> {
                frozen = false
                synchronized(lock) { if (pipeline != 0L) Native.pipelineSetSource(pipeline, Native.SOURCE_MUSIC, 0, S.music(sp), System.nanoTime()) }
            }
        }
    }

    // ---------------------------------------------------------------- pipeline and helper

    private var framesIn = 0 // under lock
    private var feedErrors = 0 // under lock
    private var captureUsSum = 0L // under lock
    private var captureStartedAt = 0L // under lock: when a capture helper was asked for, until its first frame

    private fun rebuildPipeline() {
        val layout = S.layout(sp)
        val cfg = NativeConfig.pipeline(layout, S.picture(sp), S.get(sp, S.SMOOTHING), S.get(sp, S.COLOR_ORDER))
        synchronized(lock) {
            if (pipeline != 0L) Native.pipelineFree(pipeline)
            pipeline = Native.pipelineNew(cfg)
            leds = layout.count
            packet = ByteBuffer.allocateDirect(6 + 3 * leds)
            Stats.packetBytes = 6 + 3 * leds
            packetBytes = ByteArray(6 + 3 * leds)
            shownBuf = ByteBuffer.allocateDirect(3 * leds)
        }
        layoutKey = NativeConfig.hex(NativeConfig.analyzer(layout, S.analyzer(sp)))
    }

    @Volatile private var shortcutsTried = false

    private fun startHelper(kind: Int) {
        if (helperRunning == kind) return
        val h = helper ?: HelperLink(noBackupFilesDir, filesDir, applicationInfo.sourceDir,
            onFrame = { buf, len ->
                synchronized(lock) {
                    if (pipeline != 0L) {
                        val r = Native.pipelineFeed(pipeline, buf, len, System.nanoTime())
                        if (r == 0) {
                            if (captureStartedAt != 0L) {
                                // §13: how long a switch back to Screen waits for the helper (decides "keep warm")
                                Log.i(TAG, "first capture ${(System.nanoTime() - captureStartedAt) / 1_000_000} ms after the helper started")
                                captureStartedAt = 0L
                            }
                            framesIn++
                            // TVA2 header bytes 20..23: the capture's duration in µs, little-endian
                            captureUsSum += (buf.get(20).toLong() and 0xff) or ((buf.get(21).toLong() and 0xff) shl 8) or
                                ((buf.get(22).toLong() and 0xff) shl 16) or ((buf.get(23).toLong() and 0xff) shl 24)
                        } else feedErrors++
                    }
                }
            },
            onStats = { ids, values, n -> Stats.update(ids, values, n) },
            onPcm = { pcm, samples ->
                // the low 32 bits of the millisecond clock the pipeline renders with
                synchronized(lock) { if (audio != 0L) Native.audioFeedPcm(audio, pcm, samples, (System.nanoTime() / 1_000_000).toInt()) }
            },
            onStatus = { st ->
                sStatus = st
                // Automatic: no shell (USB debugging off or refused) means screen recording instead (decision B)
                // three failures in a row (about 10 s), so a TV still booting its ADB doesn't fall back by mistake
                if (st.startsWith("Shell capture unavailable")) shellFailures++ else if (st.startsWith("Capturing")) shellFailures = 0
                if (sShellOk && !shortcutsTried) { // ADB works: Remote shortcuts on by default, once (Shell.enableShortcutsOnce)
                    shortcutsTried = true
                    val app = applicationContext
                    Thread({ runCatching { io.github.noman13.tvambient.adb.Shell.enableShortcutsOnce(app) } }, "tvambient-shortcuts").start()
                }
                if (shellFailures >= 3 && !sShellOk && S.get(sp, S.CAPTURE_METHOD) == 0 && !shellFailed) {
                    shellFailed = true
                    main.post { if (running) { stopHelper(); applyMode() } }
                }
            },
        ).also { helper = it }
        h.setStats(sStatsOn, sStatsIntervalMs)
        h.setMask(sMask)
        val captures = kind == HELPER_CAPTURE
        if (captures) synchronized(lock) { captureStartedAt = System.nanoTime() }
        // Adaptive (the default): up to 30/s, as the pressure allows; Fixed: the chosen rate
        val adaptive = S.get(sp, S.CAPTURE_ADAPTIVE) == 1
        h.start(layoutKey, if (adaptive) S.CAPTURE_RATE.max else S.get(sp, S.CAPTURE_RATE), statsOnly = !captures,
            pcm = kind == HELPER_PCM, adaptive = adaptive && captures)
        helperRunning = kind
    }

    private fun stopHelper() {
        helper?.stop()
        helperRunning = HELPER_OFF
        if (running) sStatus = sMode
    }

    private fun outputLoop() {
        var lastSend = 0L
        var lastConnectTry = 0L
        var lastMusicCheck = 0L
        var frames = 0
        var statsFrom = System.nanoTime()
        while (running) {
            val now = System.nanoTime()
            port.closeIfRequested()
            if (!port.isOpen && now - lastConnectTry > 2_000_000_000L) {
                lastConnectTry = now
                val st = port.open(S.BAUDS[S.get(sp, S.BAUD)])
                if (!port.isOpen) sStatus = st
            }
            var len: Int
            synchronized(lock) {
                if (pipeline == 0L) { len = 0 } else {
                    len = if (frozen) 0 else Native.pipelineRender(pipeline, now, if (mode == ModeController.MUSIC) audio else 0L, packet)
                    if (len == 0 && now - lastSend > 500_000_000L) len = Native.pipelineLastPacket(pipeline, packet) // keep-alive
                    if (len > 0) { packet.position(0); packet.get(packetBytes, 0, len) }
                }
            }
            sUsbState = if (port.isOpen) "USB" else "USB not connected"
            // the UI previews (Home, Settings) read the colours last sent, at most 15 times a second, only while shown
            if (sPreviewWanted && now - lastShownAt > 66_000_000L) {
                lastShownAt = now
                synchronized(lock) {
                    if (pipeline != 0L && Native.pipelineShown(pipeline, shownBuf) > 0) Shown.publish(shownBuf, leds)
                }
            }
            if (len > 0 && port.isOpen) {
                port.write(packetBytes)
                lastSend = now
                frames++
            }
            if (now - lastMusicCheck > 250_000_000L) {
                lastMusicCheck = now
                if (mode == ModeController.MUSIC || sAutoSwitched) watchMusic(now)
            }
            if (now - statsFrom >= 5_000_000_000L) {
                Stats.ledRateX10 = (frames * 10_000_000_000L / (now - statsFrom)).toInt()
                val inX10: Int
                val errors: Int
                val capMsX10: Int
                synchronized(lock) {
                    inX10 = (framesIn * 10_000_000_000L / (now - statsFrom)).toInt()
                    errors = feedErrors
                    capMsX10 = if (framesIn > 0) (captureUsSum / framesIn / 100).toInt() else 0
                    framesIn = 0; feedErrors = 0; captureUsSum = 0
                }
                Stats.framesInX10 = inX10
                // a stable, parseable line for measuring: "LEDs x/s", "capture y/s", "z ms per capture"
                Log.i(TAG, "LEDs ${Stats.ledRateX10 / 10.0}/s, capture ${inX10 / 10.0}/s, ${capMsX10 / 10.0} ms per capture" +
                    (if (errors > 0) " ($errors rejected)" else "") +
                    ", USB ${if (port.isOpen) "connected" else "not connected"}, $sMode" +
                    (if (mode == ModeController.MUSIC) ", audio $sAudioSource ${Stats.musicDb} dB" else ""))
                frames = 0
                statsFrom = now
            }
            val period = 1_000_000_000L / S.get(sp, S.LED_RATE)
            val sleep = period - (System.nanoTime() - now)
            if (sleep > 0) LockSupport.parkNanos(sleep)
        }
        // stopped: leave the strip dark, then release everything this thread owned
        if (port.isOpen && leds > 0) port.write(ByteArray(6 + 3 * leds).also { darkPacket(it) })
        port.close()
        synchronized(lock) {
            if (pipeline != 0L) Native.pipelineFree(pipeline)
            pipeline = 0
            if (audio != 0L) Native.audioFree(audio)
            audio = 0
        }
    }

    private fun darkPacket(b: ByteArray) {
        val n = leds - 1
        b[0] = 'A'.code.toByte(); b[1] = 'd'.code.toByte(); b[2] = 'a'.code.toByte()
        b[3] = (n shr 8).toByte(); b[4] = n.toByte(); b[5] = ((n shr 8) xor n xor 0x55).toByte()
    }

    companion object {
        const val TAG = "TvAmbient"
        const val ACTION_START = "io.github.noman13.tvambient.START"
        const val ACTION_STOP = "io.github.noman13.tvambient.STOP"
        const val ACTION_PROJECTION = "io.github.noman13.tvambient.PROJECTION"
        private const val EXTRA_CODE = "code"
        private const val EXTRA_DATA = "data"
        /** Screen recording's ceiling: it measured costly on the reference TV, so fewer captures than the helper. */
        private const val RECORDING_MAX_RATE = 10

        /** Screen mode wants screen recording but has no consent yet: Home asks (Android requires it each start). */
        @Volatile var sNeedProjection = false

        /** The consent dialog's answer ([io.github.noman13.tvambient.ui.ProjectionRequestActivity]). */
        fun projectionResult(c: Context, code: Int, data: Intent?) {
            val i = Intent(c, AmbientService::class.java).setAction(ACTION_PROJECTION).putExtra(EXTRA_CODE, code).putExtra(EXTRA_DATA, data)
            try { c.startForegroundService(i) } catch (e: IllegalStateException) { Log.w(TAG, "projection start refused: ${e.message}") }
        }
        private const val CHANNEL = "lights"
        private const val HELPER_OFF = 0
        private const val HELPER_CAPTURE = 1
        private const val HELPER_STATS = 2
        private const val HELPER_PCM = 3 // source B: playback PCM (+ stats when the panel asks)
        private const val SOURCE_NONE = 0
        private const val SOURCE_A = 1
        private const val SOURCE_B = 2
        /** What "Automatic" found to work on this TV (§14.1); 0 = not decided yet. */
        private const val FOUND_KEY = "audio_source_found"
        private const val SILENCE_DB = -55
        private const val SILENCE_NS = 5_000_000_000L
        private val PICTURE_KEYS = setOf(S.BRIGHTNESS.key, S.SATURATION.key, S.GAMMA.key, S.BAL_R.key, S.BAL_G.key, S.BAL_B.key, S.BLACK.key, S.SMOOTHING.key)
        private val LAYOUT_KEYS = setOf(S.TOP.key, S.RIGHT.key, S.BOTTOM.key, S.LEFT.key, S.GAP.key, S.START_CORNER.key, S.DIRECTION.key, S.OFFSET.key, S.DEPTH_TB.key, S.DEPTH_LR.key)
        private val MODE_KEYS = setOf(
            S.EFFECT.key, S.SINGLE_HUE.key, S.SINGLE_SAT.key, S.SINGLE_BRIGHT.key, S.MUSIC_EFFECT.key, S.MUSIC_PALETTE.key, S.MUSIC_FOLLOW.key,
            S.MUSIC_MIN.key, S.MUSIC_BEAT.key, S.MUSIC_FADE.key, S.MUSIC_DELAY.key,
        )

        @Volatile var sRunning = false
        /** Set once the app's own ADB connection has worked (the capture helper started). */
        @Volatile var sShellOk = false
        /** The last colour-button press and what it did, for the Remote buttons test line ("Green → Open settings"). */
        @Volatile var sLastKey = ""
        /** A Home or Settings preview is visible: publish the shown colours ([Shown]). */
        @Volatile var sPreviewWanted = false

        /** Shows a test pattern on the strip from Settings: an effect ([Native.SOURCE_EFFECT], id) or a single colour. */
        fun showTest(kind: Int, arg: Int, params: ByteArray?) { sInstance?.override(kind, arg, params) }

        /** Back to the current mode after a Settings test pattern. */
        fun endTest() { sInstance?.clearOverride() }
        @Volatile var sStatus = "Stopped"
        @Volatile var sUsbState = "-"
        @Volatile var sMode = "-"
        /** "Shell screenshots" or "Screen recording", for Home's status line and the stats panel. */
        @Volatile var sCapturePath = "Shell screenshots"
        @Volatile var sAudioSource = "-"
        @Volatile private var sInstance: AmbientService? = null
        @Volatile private var sStatsOn = false
        @Volatile private var sStatsIntervalMs = 1000
        @Volatile private var sMask = ByteArray(8)
        @Volatile private var sAutoSwitched = false // Music → Screen after silence; music coming back returns
        @Volatile private var sAutoSwitching = false // the mode change in progress is the automatic one

        /** The overlay wants helper stats (TVS1) at this interval, or none. Kept across helper restarts. Main thread. */
        fun setStats(on: Boolean, intervalMs: Int) {
            sStatsOn = on
            sStatsIntervalMs = intervalMs
            sInstance?.statsWanted(on)
        }

        /** Pixels the analyser ignores (the overlay, TECHNICAL.md §12.3), in capture pixels; all zero clears it. */
        fun setMask(l: Int, t: Int, r: Int, b: Int) {
            val p = ByteArray(8)
            intArrayOf(l, t, r, b).forEachIndexed { k, v -> p[2 * k] = v.toByte(); p[2 * k + 1] = (v shr 8).toByte() }
            sMask = p
            sInstance?.helper?.setMask(p)
            sInstance?.projection?.setMask(l, t, r, b)
        }

        /**
         * Starts the service. Android 12+ refuses a foreground-service start while the app is in the background
         * (an ADB broadcast with no activity showing); boot, the activity and the accessibility service are exempt.
         * Returns false when refused, instead of crashing the caller.
         */
        fun start(c: Context): Boolean = try {
            c.startForegroundService(Intent(c, AmbientService::class.java).setAction(ACTION_START)); true
        } catch (e: IllegalStateException) { // ForegroundServiceStartNotAllowedException is a subclass (API 31)
            Log.w(TAG, "start refused: ${e.message}"); sStatus = "Can't start from the background: open TV Ambient"; false
        }

        fun stop(c: Context) {
            if (!sRunning) return
            try { c.startService(Intent(c, AmbientService::class.java).setAction(ACTION_STOP)) }
            catch (e: IllegalStateException) { Log.w(TAG, "stop refused: ${e.message}") }
        }
    }
}

/** The colours last sent to the strip (RGB per LED, strip order, after the picture settings), for the UI previews. */
object Shown {
    private val lock = Any()
    private var data = ByteArray(0)
    @Volatile var leds = 0
        private set
    @Volatile var seq = 0
        private set

    fun publish(buf: ByteBuffer, n: Int) = synchronized(lock) {
        if (data.size != 3 * n) data = ByteArray(3 * n)
        buf.position(0)
        buf.get(data, 0, 3 * n)
        leds = n
        seq++
    }

    /** Copies the latest colours into [dst] (resized by the caller to 3 × [leds]); returns the LED count copied. */
    fun copy(dst: ByteArray): Int = synchronized(lock) {
        val n = minOf(dst.size, data.size)
        System.arraycopy(data, 0, dst, 0, n)
        n / 3
    }
}

/** The latest stats from the helper (`TVS1`), the output thread and the music engine, for the overlay and logs. */
object Stats {
    private val values = IntArray(32)
    @Volatile var ledRateX10 = 0
    @Volatile var framesInX10 = 0
    @Volatile var packetBytes = 0
    @Volatile var updatedAt = 0L
    @Volatile var musicDb = -96
    @Volatile var musicBpm = 0
    /** The 12 band levels (0..255) for Music mode's status card; written by the output thread. */
    val musicBands = ByteArray(12)

    fun update(ids: IntArray, v: IntArray, n: Int) {
        for (i in 0 until n) if (ids[i] in values.indices) values[ids[i]] = v[i]
        updatedAt = System.nanoTime()
    }

    fun value(id: Int) = if (id in values.indices) values[id] else 0
}
