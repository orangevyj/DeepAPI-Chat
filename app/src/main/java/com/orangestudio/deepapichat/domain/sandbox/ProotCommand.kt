package com.orangestudio.deepapichat.domain.sandbox

import java.io.File

/**
 * Turns a guest shell command into the argv and environment that run it inside the
 * bundled Alpine rootfs.
 *
 * Deliberately pure - no Context, no I/O - so the exact invocation can be asserted
 * in a unit test. Two details are load-bearing and were confirmed on a real device:
 *
 *  - the runtime is exec'd from `nativeLibraryDir`, the only place Android lets an
 *    app run code from, and bionic does *not* search the executable's own directory,
 *    so `LD_LIBRARY_PATH` has to name it or proot fails to link at exec time;
 *  - `PROOT_LOADER` has to point at the loader extracted alongside it, for the same
 *    reason.
 */
class ProotCommand(
    private val nativeLibDir: String,
    private val rootfs: File,
    private val tmpDir: File,
) {
    val executable: String get() = File(nativeLibDir, PROOT_LIBRARY).absolutePath

    val loader: String get() = File(nativeLibDir, LOADER_LIBRARY).absolutePath

    fun environment(): Map<String, String> = mapOf(
        "LD_LIBRARY_PATH" to nativeLibDir,
        "PROOT_LOADER" to loader,
        "PROOT_TMP_DIR" to tmpDir.absolutePath,
        "PROOT_NO_SECCOMP" to "1",
        "HOME" to "/root",
        "LANG" to "C",
        "TERM" to "dumb",
        "PATH" to "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
    )

    /**
     * [fakeRoot] maps the app's uid to root inside the guest, which is what `apk add`
     * and friends expect. It grants nothing on the host: proot never runs the guest
     * as anything but the app's own uid.
     */
    fun argv(
        workspace: File,
        command: String,
        fakeRoot: Boolean = true,
        devices: List<String> = MINIMAL_DEVICES,
    ): List<String> = buildList {
        add(executable)
        add("--kill-on-exit")
        if (fakeRoot) add("-0")
        add("-r"); add(rootfs.absolutePath)
        add("-w"); add(GUEST_WORKSPACE)
        // Named nodes, never the host's /dev directory. A proot bind of a directory
        // passes every entry inside it straight through - there is no way to filter
        // one - and the host's /dev carries /dev/binder, /dev/binderfs and
        // /dev/usb-ffs/adb: Android's IPC driver and the ADB gadget, neither of which
        // a filesystem sandbox has any business reaching.
        devices.forEach { add("-b"); add(it) }
        // The guest gets no view of the app's storage beyond its own workspace.
        add("-b"); add("${workspace.absolutePath}:$GUEST_WORKSPACE")
        add("/bin/sh"); add("-c"); add(command)
    }

    companion object {
        const val PROOT_LIBRARY = "libproot.so"
        const val LOADER_LIBRARY = "libproot-loader.so"

        /** Where the chat's own workspace appears inside the guest. */
        const val GUEST_WORKSPACE = "/workspace"

        /**
         * Every device node the guest is given. A non-interactive shell needs a bin
         * to discard output into and a source of randomness; that is the whole list.
         *
         * `/proc` and `/sys` are deliberately absent. proot cannot hide individual
         * entries inside a bound directory, so binding either one at all is exactly
         * what leaks host process and device information into the guest - there is no
         * narrower version of that bind to reach for.
         */
        val MINIMAL_DEVICES = listOf("/dev/null", "/dev/zero", "/dev/random", "/dev/urandom")
    }
}
