package net.rpcsx.utils

import android.app.ActivityManager
import android.content.Context
import net.rpcsx.RPCSX
import net.rpcsx.utils.GeneralSettings.boolean

/**
 * App-side, device-adaptive default for the core's "Max LLVM Compile Threads".
 *
 * The PPU/SPU LLVM compiler runs one worker per core by default (the RPCSX/RPCS3
 * setting 0 = auto = all cores). Each concurrent module compile holds a lot of
 * RAM, so on low-memory devices "all cores" drives the device into the Android
 * Low Memory Killer mid-compile (the game appears to "not start"). The fix the
 * emulator expects is simply a lower thread count - this picks a memory-safe one
 * automatically so testers never have to find the buried setting.
 *
 * IMPORTANT: this does NOT change any emulator (RPCSX core) code or its throttle
 * - it only writes a saner value into the existing "Max LLVM Compile Threads"
 * config on constrained devices, which is exactly what config defaults are for.
 * High-memory devices get 0 (auto/all cores) - identical to stock behaviour.
 */
object CompileThreadPolicy {
    private const val KEY = "auto_compile_threads"
    private const val CONFIG_PATH = "Core@@Max LLVM Compile Threads"

    /** Master switch (Clanker Settings). Default on: harmless on high-RAM. */
    var enabled: Boolean
        get() = GeneralSettings[KEY].boolean(true)
        set(value) { GeneralSettings[KEY] = value }

    /** Total physical RAM the kernel reports (matches the core's get_total_memory). */
    private fun totalRamGib(context: Context): Double {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            ?: return 0.0
        val info = ActivityManager.MemoryInfo()
        am.getMemoryInfo(info)
        return info.totalMem / (1024.0 * 1024.0 * 1024.0)
    }

    /**
     * Memory-safe "Max LLVM Compile Threads" for this device. 0 = auto (all
     * cores). The caps bound concurrent module compiles so peak compiler memory
     * stays well under the device budget (e.g. ~3.8 GiB devices OOM'd at the
     * 8-core default but compile fine at 2).
     */
    fun safeThreads(context: Context): Int {
        val gib = totalRamGib(context)
        return when {
            gib <= 0.0 -> 0       // couldn't read RAM: don't interfere
            gib < 4.5 -> 2
            gib < 6.5 -> 4
            else -> 0             // plenty of RAM: stock auto (all cores)
        }
    }

    /**
     * Push the effective thread count into the core config. Call once after the
     * core is initialized and whenever the toggle changes - before any game boots.
     * When disabled, restores 0 (auto) so "off" == stock RPCSX behaviour.
     */
    fun apply(context: Context) {
        val threads = if (enabled) safeThreads(context) else 0
        runCatching { RPCSX.instance.settingsSet(CONFIG_PATH, threads.toString()) }
    }
}
