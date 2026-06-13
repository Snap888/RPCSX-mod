package net.rpcsx.utils

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import android.util.Log
import net.rpcsx.dialogs.AlertDialogQueue

/**
 * Surfaces WHY the process last died. The core's RPCSX.log cannot capture an
 * uncatchable SIGKILL (the Low Memory Killer) or a native tombstone - those
 * deaths look like "the log just stops". Android records the real reason and we
 * can read it on the next launch with no adb, turning a guess ("did it OOM, or
 * is it a different bug?") into a fact.
 *
 * Distinguishes the OOM family (LOW_MEMORY / SIGNALED-by-SIGKILL) from a real
 * code defect (CRASH_NATIVE / CRASH / ANR), and reports the memory footprint at
 * death so we can see if it died holding multiple GB.
 */
object ExitReasonReporter {
    private const val TAG = "RPCSX-ExitReason"

    /** Reads the most recent process death; null for a normal/expected exit. */
    fun lastAbnormalExit(context: Context): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            ?: return null
        val info = runCatching { am.getHistoricalProcessExitReasons(context.packageName, 0, 1) }
            .getOrNull()?.firstOrNull() ?: return null

        val label = when (info.reason) {
            ApplicationExitInfo.REASON_LOW_MEMORY -> "Low memory - the system killed it for using too much RAM (OOM)"
            ApplicationExitInfo.REASON_SIGNALED ->
                if (info.status == 9) "Killed by SIGKILL (status 9) - almost always the Low Memory Killer (OOM)"
                else "Killed by signal ${info.status}"
            ApplicationExitInfo.REASON_CRASH_NATIVE -> "Native crash - a bug in the emulator core, NOT memory"
            ApplicationExitInfo.REASON_CRASH -> "App (Java/Kotlin) crash"
            ApplicationExitInfo.REASON_ANR -> "ANR - the app stopped responding"
            ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "Killed for excessive resource usage"
            ApplicationExitInfo.REASON_OTHER -> "Other system kill (often the Low Memory Killer)"
            // EXIT_SELF / USER_REQUESTED / USER_STOPPED / DEPENDENCY_DIED: normal, ignore.
            else -> return null
        }

        val mb = { bytes: Long -> bytes / (1024 * 1024) }
        return buildString {
            append(label)
            append("\n\nMemory held at death: RSS ${mb(info.rss)} MB, PSS ${mb(info.pss)} MB")
            info.description?.takeIf { it.isNotBlank() }?.let { append("\nSystem note: $it") }
        }
    }

    /** Logs the last abnormal exit and shows it once so it can be screenshotted. */
    fun reportLastAbnormalExit(context: Context) {
        val message = lastAbnormalExit(context) ?: return
        Log.w(TAG, message)
        AlertDialogQueue.showDialog("Previous session ended unexpectedly", message)
    }
}
