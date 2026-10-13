package com.orangestudio.deepapichat

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.orangestudio.deepapichat.core.JsonStore
import com.orangestudio.deepapichat.data.store.SandboxStore
import com.orangestudio.deepapichat.domain.sandbox.ProotCommand
import com.orangestudio.deepapichat.domain.sandbox.RootfsInstaller
import com.orangestudio.deepapichat.domain.sandbox.ShellRunner
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Spike for the sandbox runtime: proves the bundled proot can be executed at all
 * under this app's targetSdk (Android blocks exec of app-writable files), and that
 * it can enter the Alpine rootfs. Every diagnostic is collected into one report so
 * a failure names the real cause instead of just "expected true".
 */
@RunWith(AndroidJUnit4::class)
class SandboxRuntimeTest {

    private class Result(val code: Int, val out: String)

    private fun run(cmd: List<String>, env: Map<String, String>, timeoutSec: Long = 60): Result {
        val pb = ProcessBuilder(cmd)
        pb.environment().putAll(env)
        pb.redirectErrorStream(true)
        val p = pb.start()
        val text = p.inputStream.bufferedReader().use { it.readText() }
        if (!p.waitFor(timeoutSec, TimeUnit.SECONDS)) {
            p.destroyForcibly()
            return Result(-999, "TIMEOUT\n$text")
        }
        return Result(p.exitValue(), text)
    }

    @Test
    fun prootRunsAndEntersTheRootfs() {
        val log = StringBuilder()
        var sandboxOk = false
        try {
            val ctx = InstrumentationRegistry.getInstrumentation().targetContext
            val lib = ctx.applicationInfo.nativeLibraryDir
            val home = File(ctx.filesDir, "sandbox")
            val tmp = File(home, "tmp").apply { mkdirs() }
            val root = File(home, "rootfs")

            log.append("nativeLibraryDir=").append(lib).append('\n')
            log.append("extracted libs=").append(File(lib).list()?.sorted()?.joinToString(",")).append('\n')
            log.append("assets/rootfs=").append(ctx.assets.list("rootfs")?.joinToString(",")).append('\n')

            val proot = "$lib/libproot.so"
            val baseEnv = mapOf(
                "PROOT_LOADER" to "$lib/libproot-loader.so",
                "PROOT_TMP_DIR" to tmp.absolutePath,
                "PROOT_NO_SECCOMP" to "1",
                "HOME" to "/root",
                "PATH" to "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
                "TERM" to "dumb",
                "LANG" to "C",
            )

            val plain = run(listOf(proot, "--version"), baseEnv)
            log.append("A1: no LD_LIBRARY_PATH = ").append(plain.code).append(" | ").append(plain.out.trim()).append('\n')

            val withPath = baseEnv + mapOf("LD_LIBRARY_PATH" to lib)
            val linked = run(listOf(proot, "--version"), withPath)
            log.append("A2: LD_LIBRARY_PATH=$lib = ").append(linked.code).append(" | ").append(linked.out.trim()).append('\n')

            val env = if (linked.code == 0) withPath else baseEnv

            val tarball = File(ctx.filesDir, "alpine.tar")
            if (!tarball.exists()) {
                ctx.assets.open("rootfs/alpine-minirootfs-3.20.9-aarch64.tar").use { input ->
                    tarball.outputStream().use { input.copyTo(it) }
                }
            }
            log.append("tarball bytes=").append(tarball.length()).append('\n')

            if (!File(root, "bin/busybox").exists()) {
                root.mkdirs()
                val tar = run(
                    listOf("/system/bin/toybox", "tar", "-xf", tarball.absolutePath, "-C", root.absolutePath),
                    env = emptyMap(),
                    timeoutSec = 300,
                )
                log.append("B: extract = ").append(tar.code).append(" | ").append(tar.out.trim().take(800)).append('\n')
            }
            log.append("B: rootfs bin/busybox=").append(File(root, "bin/busybox").exists()).append('\n')

            val probe = run(
                listOf(
                    proot, "-r", root.absolutePath, "/bin/sh", "-c",
                    "echo sandbox-ok; uname -m; cat /etc/alpine-release; id",
                ),
                env,
            )
            log.append("C: guest = ").append(probe.code).append(" | ").append(probe.out.trim()).append('\n')
            sandboxOk = probe.code == 0 && probe.out.contains("sandbox-ok")
        } catch (t: Throwable) {
            log.append("EXCEPTION: ").append(t).append('\n')
        }
        android.util.Log.i("SandboxRuntimeSpike", log.toString())
        assertTrue(log.toString(), sandboxOk)
    }

    /**
     * The path a real install takes, through the app's own classes rather than this
     * file's private harness: the installer lays down the rootfs and its
     * resolv.conf, then the guest fetches a package over the network and runs it.
     *
     * This is the test that justifies writing resolv.conf at all. proot passes the
     * device's filesystem through, not its DNS settings, so without that file `apk`
     * fails with a name-resolution error even though the phone has working internet.
     */
    @Test
    fun guestCanInstallAPackage() = runBlocking {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val store = SandboxStore(JsonStore(File(ctx.filesDir, "data")))
        val log = StringBuilder()
        try {
            val installed = RootfsInstaller(ctx, store).ensureInstalled()
            log.append("A: installer = ").append(installed).append('\n')

            val resolv = File(store.rootfs, "etc/resolv.conf")
            log.append("B: resolv.conf = ")
                .append(if (resolv.isFile) resolv.readText().trim().replace('\n', ';') else "MISSING")
                .append('\n')

            val proot = ProotCommand(ctx.applicationInfo.nativeLibraryDir, store.rootfs, store.tmp)
            val install = ShellRunner().run(
                proot,
                store.prepare("apk-test"),
                "apk add --no-cache tree && tree --version",
                timeoutSeconds = 300,
            )
            log.append("C: apk add exit=").append(install.exitCode)
                .append(" timedOut=").append(install.timedOut)
                .append(" | ").append(install.text.trim().takeLast(600)).append('\n')
        } catch (t: Throwable) {
            log.append("EXCEPTION: ").append(t).append('\n')
        }
        android.util.Log.i("SandboxRuntimeSpike", log.toString())
        assertTrue(log.toString(), log.contains("tree v"))
    }

    /**
     * What the guest can actually reach, and whether the narrow bind set is still
     * enough to work. Both halves matter: closing a leak by breaking the sandbox is
     * not a fix.
     *
     * The list of things checked is the host's, not a guess: /dev/binder and
     * /dev/binderfs are Android's IPC driver, /dev/usb-ffs/adb is the ADB gadget, and
     * the host's /proc would hand over every process's command line and exe link -
     * including the app that started the sandbox.
     */
    @Test
    fun guestCannotSeeAndroidNodesButCanStillWork() = runBlocking {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val store = SandboxStore(JsonStore(File(ctx.filesDir, "data")))
        val log = StringBuilder()
        try {
            assertTrue("rootfs not installed", RootfsInstaller(ctx, store).ensureInstalled())
            val proot = ProotCommand(ctx.applicationInfo.nativeLibraryDir, store.rootfs, store.tmp)
            val workspace = store.prepare("hardening")
            val runner = ShellRunner()

            suspend fun probe(label: String, cmd: String): ShellRunner.Output {
                val out = runner.run(proot, workspace, cmd, timeoutSeconds = 300)
                log.append(label).append(" exit=").append(out.exitCode).append('\n')
                    .append(out.text.trim().take(600)).append("\n---\n")
                return out
            }

            val leaks = probe(
                "leaks",
                "for p in /dev/binder /dev/binderfs /dev/usb-ffs /dev/kgsl-3d0 /dev/ashmem " +
                    "/dev/socket /proc/1; do [ -e \$p ] && echo REACHABLE:\$p; done; " +
                    "echo pids=$(ls /proc 2>/dev/null | grep -c .); echo probes-done",
            )

            probe("devices", "ls -l /dev 2>&1 | head -20")
            val su = probe("su", "ls -l /bin/su 2>&1; readlink -f /bin/su 2>&1; su -c id 2>&1 | head -2")
            val essentials = probe(
                "essentials",
                "cat /etc/alpine-release; echo hi > /dev/null && echo null-ok; " +
                    "head -c 4 /dev/urandom | wc -c; id",
            )
            val install = probe("apk", "apk add --no-cache tree >/dev/null 2>&1 && tree --version | head -1")

            // The work still runs.
            assertTrue(log.toString(), essentials.text.contains("null-ok"))
            assertTrue(log.toString(), install.exitCode == 0 && install.text.contains("tree v"))
            // The Android-specific nodes are not reachable.
            assertTrue(log.toString(), !leaks.text.contains("REACHABLE:/dev/binder"))
            assertTrue(log.toString(), !leaks.text.contains("REACHABLE:/dev/binderfs"))
            assertTrue(log.toString(), !leaks.text.contains("REACHABLE:/dev/usb-ffs"))
            // And host ancestry is not on show.
            assertTrue(log.toString(), !leaks.text.contains("REACHABLE:/proc/1"))
            assertTrue(log.toString(), leaks.text.contains("pids=0"))
            // /bin/su is either absent or busybox's applet, which under proot cannot
            // setuid anything on the host.
            assertTrue(log.toString(), su.text.contains("busybox") || su.text.contains("No such file"))
        } catch (t: Throwable) {
            log.append("EXCEPTION: ").append(t).append('\n')
        }
        android.util.Log.i("SandboxRuntimeSpike", log.toString())
        assertTrue(log.toString(), log.contains("probes-done"))
        assertTrue(log.toString(), !log.contains("EXCEPTION"))
    }
}
