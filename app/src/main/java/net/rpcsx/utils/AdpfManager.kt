package net.rpcsx.utils

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.PerformanceHintManager
import android.os.PowerManager
import android.util.Log
import net.rpcsx.RPCSX

/**
 * ADPF (Android Dynamic Performance Framework) hint feed. OFF by default.
 *
 * The reactive [ThermalManager] only caps fps AFTER the SoC is already throttling
 * (fan already loud). ADPF works the other way round: it tells the OS scheduler the
 * presenting thread's real per-frame CPU work via PerformanceHintManager, so the
 * scheduler can pick the LOWEST CPU clock that still hits the frame target - same
 * fps, less heat, BEFORE any throttle. Purely advisory: if the device or scheduler
 * ignores the hint, behaviour is identical to off. Requires API 31 (S).
 *
 * The work figure comes from the core's RSX flip loop (wall interval minus the
 * frame-limiter sleep), polled here and forwarded once per ~frame. We also log
 * PowerManager.getThermalHeadroom() once a second so an on-device A/B run (hint on
 * vs off, same scene) can be PROVEN from the logs instead of guessed.
 *
 * Default OFF until that A/B proves it helps - per the project rule never to
 * default-on an unproven feature.
 */
object AdpfManager {
    private const val TAG = "AdpfManager"
    private const val KEY = "adpf_hints"

    // 60fps frame budget. We report actual WORK (busy time, idle excluded), so a
    // lighter game reports work below this target and the scheduler ramps the clock
    // DOWN (the heat win); a struggling game reports above it and asks for more.
    private const val TARGET_NANOS = 16_666_666L
    private const val POLL_MS = 16L

    var enabled: Boolean
        get() = GeneralSettings[KEY] as? Boolean ?: false
        set(value) { GeneralSettings[KEY] = value }

    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var session: PerformanceHintManager.Session? = null
    private var pm: PowerManager? = null
    @Volatile private var running = false
    private var headroomTick = 0

    fun register(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        if (!enabled) return
        val phm = context.getSystemService(PerformanceHintManager::class.java) ?: return
        pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager

        val t = HandlerThread("adpf-hint").also { it.start() }
        val h = Handler(t.looper)
        thread = t
        handler = h
        running = true
        headroomTick = 0

        // The RSX thread may not have flipped yet when the game is still booting, so
        // the tid is 0 until the first frame. Poll until it appears, then create the
        // session with that tid and start reporting.
        h.post(object : Runnable {
            override fun run() {
                if (!running) return
                val sess = session
                if (sess == null) {
                    val tid = runCatching { RPCSX.instance.getRsxThreadTid() }.getOrDefault(0)
                    if (tid != 0) {
                        session = runCatching {
                            phm.createHintSession(intArrayOf(tid), TARGET_NANOS)
                        }.getOrNull()
                        if (session != null) {
                            Log.i(TAG, "ADPF hint session created (rsx tid=$tid, target=${TARGET_NANOS}ns)")
                        } else {
                            // Device/driver does not support hint sessions - stop quietly.
                            Log.i(TAG, "ADPF hint session unsupported on this device; disabling feed")
                            running = false
                            return
                        }
                    }
                } else {
                    val work = runCatching { RPCSX.instance.getFrameWorkNanos() }.getOrDefault(0L)
                    if (work > 0L) {
                        runCatching { sess.reportActualWorkDuration(work) }
                    }
                }

                // A/B telemetry: thermal headroom (0 = none, ~1 = at throttle).
                if (++headroomTick >= (1000L / POLL_MS).toInt()) {
                    headroomTick = 0
                    val hr = runCatching { pm?.getThermalHeadroom(10) }.getOrNull()
                    if (hr != null && !hr.isNaN()) {
                        Log.i(TAG, "thermal headroom=%.3f work=%.2fms".format(hr,
                            (runCatching { RPCSX.instance.getFrameWorkNanos() }.getOrDefault(0L)) / 1_000_000.0))
                    }
                }

                h.postDelayed(this, POLL_MS)
            }
        })
    }

    fun unregister() {
        running = false
        handler?.removeCallbacksAndMessages(null)
        runCatching { session?.close() }
        session = null
        handler = null
        thread?.quitSafely()
        thread = null
        pm = null
    }
}
