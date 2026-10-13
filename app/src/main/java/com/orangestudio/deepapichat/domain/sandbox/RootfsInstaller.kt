package com.orangestudio.deepapichat.domain.sandbox

import android.content.Context
import android.net.ConnectivityManager
import com.orangestudio.deepapichat.data.store.SandboxStore
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Puts the Alpine rootfs on disk the first time the sandbox is used.
 *
 * The tarball ships inside the APK, so nothing is downloaded, and extraction uses
 * the platform's own `toybox tar` rather than a runtime we would have to bootstrap
 * first. The version marker means shipping a newer rootfs later re-installs instead
 * of silently keeping the old one.
 */
class RootfsInstaller(
    private val context: Context,
    private val store: SandboxStore,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : SandboxInstaller {

    fun isInstalled(): Boolean =
        readMarker() == VERSION && File(store.rootfs, "bin/busybox").isFile

    override suspend fun ensureInstalled(): Boolean = withContext(io) {
        if (isInstalled()) {
            // Written on every call rather than once at install: the resolvers a
            // device uses change with the network, and a stale file is the usual
            // reason a package install fails with "temporary failure in name
            // resolution" long after it worked on another wifi.
            writeResolvConf()
            return@withContext true
        }

        val staging = File(store.root, "rootfs.tar")
        try {
            store.root.mkdirs()
            store.tmp.mkdirs()
            context.assets.open(ASSET).use { input ->
                staging.outputStream().use { input.copyTo(it) }
            }
            store.rootfs.mkdirs()

            val extract = ProcessBuilder(
                "/system/bin/toybox",
                "tar",
                "-xf",
                staging.absolutePath,
                "-C",
                store.rootfs.absolutePath,
            ).redirectErrorStream(true).start()
            extract.inputStream.bufferedReader().use { it.readText() }
            val finished = extract.waitFor(EXTRACT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            if (!finished) extract.destroyForcibly()

            val ok = finished && extract.exitValue() == 0 && File(store.rootfs, "bin/busybox").isFile
            if (ok) {
                writeResolvConf()
                marker().writeText(VERSION)
                true
            } else {
                store.rootfs.deleteRecursively()
                false
            }
        } catch (_: Throwable) {
            // A half-extracted rootfs is worse than none, so never leave one behind.
            store.rootfs.deleteRecursively()
            false
        } finally {
            staging.delete()
        }
    }

    /**
     * Gives the guest its own `/etc/resolv.conf`. proot passes the app's files
     * through, not its DNS settings, so without this the package manager resolves
     * nothing at all. Alpine ships resolv.conf as a symlink into /tmp, so the link
     * is removed before a real file is written - writing through it would land the
     * content wherever the link pointed.
     */
    private fun writeResolvConf() {
        runCatching {
            val target = File(store.rootfs, "etc/resolv.conf")
            target.delete()
            target.parentFile?.mkdirs()
            target.writeText(dnsServers().joinToString("") { "nameserver $it\n" })
        }
    }

    /** The running network's own resolvers, or public ones when it reports none. */
    private fun dnsServers(): List<String> {
        val reported = runCatching {
            val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            val network = manager?.activeNetwork ?: return@runCatching emptyList()
            manager.getLinkProperties(network)?.dnsServers?.mapNotNull { it.hostAddress } ?: emptyList()
        }.getOrDefault(emptyList())
        return reported.ifEmpty { FALLBACK_DNS }
    }

    private fun marker(): File = File(store.root, MARKER_FILE)

    private fun readMarker(): String? =
        runCatching { marker().readText().trim() }.getOrNull()?.takeIf { it.isNotEmpty() }

    companion object {
        const val ASSET = "rootfs/alpine-minirootfs-3.20.9-aarch64.tar"
        const val VERSION = "3.20.9"
        private const val MARKER_FILE = "rootfs.version"

        /** Used only when the running network reports no resolvers at all. */
        private val FALLBACK_DNS = listOf("1.1.1.1", "8.8.8.8")
        private const val EXTRACT_TIMEOUT_SECONDS = 300L
    }
}
