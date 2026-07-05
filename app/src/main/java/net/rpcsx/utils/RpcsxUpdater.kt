package net.rpcsx.utils

import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.rpcsx.R
import net.rpcsx.RPCSX
import net.rpcsx.dialogs.AlertDialogQueue
import java.io.File
import kotlin.system.exitProcess


object RpcsxUpdater {
    // The core embeds build decorations after the CalVer date-time: " Draft"/" RC"
    // for untagged/pre-release builds (with an optional tag number) and a trailing
    // "+" when built from a dirty tree, e.g. "2026.06.06-1436 Draft+". Strip them so
    // an installed build compares equal to its clean release tag. The old code used
    // removeSuffix(" Draft"), which could not strip "Draft+" (the string ends in '+',
    // not " Draft"), so the updater kept re-offering the version already installed.
    private fun normalizeVersion(raw: String): String =
        raw.trim()
            .replace(Regex("\\s*(Draft|RC|Release)\\s*\\d*"), "")
            .replace("+", "")
            .trim()

    // Extract the CalVer "YYYY.MM.DD-HHMM" sort key from a version string like
    // "v2026.06.07-1950-armv8.2-a". The fixed-width, zero-padded form sorts
    // chronologically under plain string comparison, and dropping the arch suffix
    // keeps the comparison about build age rather than which variant was picked.
    private fun versionSortKey(v: String?): String {
        if (v == null) return ""
        return Regex("\\d{4}\\.\\d{2}\\.\\d{2}-\\d{4}").find(v)?.value ?: v
    }

    // True when [candidate] is a strictly newer build than [installed].
    private fun isNewer(candidate: String, installed: String?): Boolean =
        versionSortKey(candidate) > versionSortKey(installed)

    fun getCurrentVersion(): String? {
        if (RPCSX.activeLibrary.value == null) {
            return null
        }

        // Fall back to the device arch when the installed arch is unknown
        // (e.g. a side-loaded library whose filename doesn't encode it),
        // otherwise the version would read "...-null".
        val arch = GeneralSettings["rpcsx_installed_arch"] as? String ?: getArch()
        return "v" + normalizeVersion(RPCSX.instance.getVersion()) + "-" + arch
    }

    fun getFileArch(file: File): String? {
        val parts = file.name.removeSuffix(".so").split("_")
        if (parts.size != 3) {
            return null
        }

        return parts[1]
    }
    
    fun getFileVersion(file: File): String? {
        val parts = file.name.removeSuffix(".so").split("_")
        if (parts.size != 3) {
            return null
        }
        val arch = parts[1]
        val version = parts[2]
        return "$version-$arch"
    }

    fun getAbi(): String = Build.SUPPORTED_64_BIT_ABIS[0]

    // arm64 -march variants we may ship, ordered highest -> lowest capability.
    private val ARCH_ORDER = listOf(
        "armv9.1-a", "armv9-a", "armv8.5-a", "armv8.4-a", "armv8.2-a", "armv8.1-a", "armv8-a"
    )

    // Highest arch the device can safely run, inferred from /proc/cpuinfo HWCAP
    // features. Conservative on purpose: an unknown device falls back to armv8-a,
    // and we never auto-select armv9/SVE codegen (SIGILL risk if SVE is off at EL0).
    //   atomics = LSE (8.1), asimddp = dotprod (8.2), uscat = LSE2 + flagm = FlagM (8.4)
    fun detectMaxArch(): String {
        val feats = runCatching {
            File("/proc/cpuinfo").readLines()
                .filter { it.trimStart().startsWith("Features") }
                .joinToString(" ")
        }.getOrDefault("")
        fun has(f: String) = Regex("(^|\\s)$f(\\s|\$)").containsMatchIn(feats)
        return when {
            has("uscat") && has("flagm") -> "armv8.4-a"
            has("asimddp") && has("atomics") -> "armv8.2-a"
            has("atomics") -> "armv8.1-a"
            else -> "armv8-a"
        }
    }

    fun getArch(): String {
        return when (getAbi()) {
            "x86_64" -> "x86-64"
            // Explicit user choice (manual picker) wins; otherwise auto-detect.
            else -> GeneralSettings["rpcsx_arch"] as? String ?: detectMaxArch()
        }
    }

    fun setArch(arch: String) {
        GeneralSettings["rpcsx_arch"] = arch
    }

    private fun assetNameFor(arch: String) = "librpcsx-android-${getAbi()}-$arch.so"

    // Pick the best release asset for this device: an explicit user-selected arch if
    // that variant exists in the release, otherwise the highest shipped variant the
    // device can run (<= detectMaxArch()), falling back down the chain. This lets us
    // ship any subset of variants and still give every device the fastest one it can
    // safely execute. Returns (arch, downloadUrl).
    private fun selectArchAsset(release: GitHub.Release): Pair<String, String>? {
        fun urlFor(arch: String): String? =
            release.assets.find { it.name == assetNameFor(arch) }?.browser_download_url

        if (getAbi() != "arm64-v8a") {
            // Non-arm64 (x86-64): single-arch behavior.
            val arch = getArch()
            return urlFor(arch)?.let { arch to it }
        }

        // Explicit override from the manual picker, if that variant was shipped.
        (GeneralSettings["rpcsx_arch"] as? String)?.let { userArch ->
            urlFor(userArch)?.let { return userArch to it }
        }

        val maxIdx = ARCH_ORDER.indexOf(detectMaxArch()).let { if (it < 0) ARCH_ORDER.lastIndex else it }
        for (i in maxIdx until ARCH_ORDER.size) {
            urlFor(ARCH_ORDER[i])?.let { return ARCH_ORDER[i] to it }
        }
        return null
    }

    suspend fun checkForUpdate(): String? {
        val url = "https://github.com/Snap888/rpcsx-build"

        when (val fetchResult = GitHub.fetchLatestRelease(url)) {
            is GitHub.FetchResult.Success<*> -> {
                val release = fetchResult.content as GitHub.Release
                // Best variant this device can run that the release actually ships.
                val (arch, _) = selectArchAsset(release) ?: return null
                // Normalize the tag the same way as the installed version so a clean
                // release tag and a "Draft+"-decorated local build compare equal.
                val releaseVersion = "v" + normalizeVersion(release.name.removePrefix("v")) + "-" + arch

                if (RPCSX.activeLibrary.value == null) {
                    return releaseVersion
                }

                val current = getCurrentVersion()

                // A deliberately side-loaded custom core (e.g. a local dev build)
                // should not be nagged to "update" to a same-age or OLDER channel
                // build, but we DO still surface a genuinely NEWER one so the user
                // never silently misses a real release. Previously any custom core
                // suppressed the check entirely, so release notifications stopped.
                if (GeneralSettings["rpcsx_custom_library"] as? Boolean == true) {
                    return if (isNewer(releaseVersion, current) &&
                        releaseVersion != GeneralSettings["rpcsx_bad_version"]
                    ) releaseVersion else null
                }

                if (current != releaseVersion && releaseVersion != GeneralSettings["rpcsx_bad_version"]) {
                    return releaseVersion
                }
            }
            is GitHub.FetchResult.Error -> {
//                AlertDialogQueue.showDialog("Check For RPCSX Updates Error", fetchResult.message)
            }
        }

        return null
    }

    suspend fun downloadUpdate(destinationDir: File, progressCallback: (Long, Long) -> Unit): File? {
        val url = "https://github.com/Snap888/rpcsx-build"

        when (val fetchResult = GitHub.fetchLatestRelease(url)) {
            is GitHub.FetchResult.Success<*> -> {
                val release = fetchResult.content as GitHub.Release
                // Same variant the update check offered.
                val (arch, downloadUrl) = selectArchAsset(release) ?: return null
                val releaseVersion = "v" + normalizeVersion(release.name.removePrefix("v")) + "-" + arch

                if (releaseVersion != getCurrentVersion()) {
                    val target = File(destinationDir, "librpcsx-android_${arch}_${release.name}.so")

                    if (target.exists()) {
                        return target
                    }

                    val tmp = File(destinationDir, "librpcsx.so.tmp")
                    if (tmp.exists()) {
                        withContext(Dispatchers.IO) {
                            tmp.delete()
                        }
                    }

                    withContext(Dispatchers.IO) {
                        tmp.createNewFile()
                    }

                    tmp.deleteOnExit()

                    when (val downloadStatus = GitHub.downloadAsset(downloadUrl, tmp, progressCallback)) {
                        is GitHub.DownloadStatus.Success -> {
                            withContext(Dispatchers.IO) {
                                tmp.renameTo(target)
                            }
                            return target
                        }
                        is GitHub.DownloadStatus.Error ->
                            AlertDialogQueue.showDialog("RPCSX Download Error", downloadStatus.message ?: "Unexpected error")
                    }
                }
            }
            is GitHub.FetchResult.Error -> {
                AlertDialogQueue.showDialog("RPCSX Download Error", fetchResult.message)
            }
        }

        return null
    }

    fun installUpdate(context: Context, updateFile: File, isCustom: Boolean = false): Boolean {
        // Remember whether this core was hand-installed by the user (side-loaded) vs
        // pulled from the release channel, so the updater doesn't keep offering to
        // "update" (downgrade) a deliberately side-loaded custom build.
        GeneralSettings["rpcsx_custom_library"] = isCustom

        val restart = {
            val packageManager = context.packageManager
            val intent = packageManager.getLaunchIntentForPackage(context.packageName)
            val mainIntent = Intent.makeRestartActivityTask(intent!!.component)
            mainIntent.setPackage(context.packageName)
            context.startActivity(mainIntent)
            GeneralSettings.sync()
            exitProcess(0)
        }

        val prevLibrary = GeneralSettings["rpcsx_library"] as? String
        val prevArch = GeneralSettings["rpcsx_installed_arch"] as? String
        GeneralSettings["rpcsx_library"] = updateFile.toString()
        GeneralSettings["rpcsx_update_status"] = null
        GeneralSettings["rpcsx_installed_arch"] = getFileArch(updateFile) ?: getArch()

        Log.e("RPCSX-UI", "registered update file ${GeneralSettings["rpcsx_library"]}")

        if (prevLibrary == null) {
            restart()
        }

        GeneralSettings["rpcsx_prev_library"] = prevLibrary
        GeneralSettings["rpcsx_prev_installed_arch"] = prevArch
        AlertDialogQueue.showDialog(
            title = context.getString(R.string.rpcsx_update_available),
            message = context.getString(R.string.restart_ui_to_apply_change),
            onConfirm = { restart() }
        )
        return true
    }
}