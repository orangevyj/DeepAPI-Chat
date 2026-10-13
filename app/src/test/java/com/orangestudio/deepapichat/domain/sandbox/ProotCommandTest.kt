package com.orangestudio.deepapichat.domain.sandbox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ProotCommandTest {

    private val libs = "/data/app/~~abc==/com.orangestudio.deepapichat-xyz==/lib/arm64"
    private val sandbox = "/data/user/0/com.orangestudio.deepapichat/files/sandbox"
    private val workspace = File("$sandbox/workspaces/chat-1")

    private val command = ProotCommand(
        nativeLibDir = libs,
        rootfs = File("$sandbox/rootfs"),
        tmpDir = File("$sandbox/tmp"),
    )

    @Test
    fun theLinkerIsPointedAtTheLibrariesWeShip() {
        val env = command.environment()

        // The one requirement with no fallback: bionic does not look next to the
        // executable, so this must be exactly the directory the libs ship in. The
        // on-device spike proves the real paths; File.absolutePath is
        // platform-dependent, so here the rest is asserted structurally.
        assertEquals(libs, env["LD_LIBRARY_PATH"])

        assertEquals("libproot.so", File(command.executable).name)
        assertEquals(File(libs).absolutePath, File(command.executable).parentFile?.absolutePath)

        assertEquals("libproot-loader.so", File(env.getValue("PROOT_LOADER")).name)
        assertEquals(File(libs).absolutePath, File(env.getValue("PROOT_LOADER")).parentFile?.absolutePath)

        assertEquals(File(sandbox, "tmp").absolutePath, env["PROOT_TMP_DIR"])
    }

    @Test
    fun theWorkspaceIsMountedAndBecomesTheWorkingDirectory() {
        val argv = command.argv(workspace, "echo hi")

        assertTrue(argv.containsAll(listOf("-w", ProotCommand.GUEST_WORKSPACE)))
        assertTrue(argv.contains("${workspace.absolutePath}:${ProotCommand.GUEST_WORKSPACE}"))
        assertEquals(listOf("/bin/sh", "-c", "echo hi"), argv.takeLast(3))
    }

    @Test
    fun theRootfsIsTheOnlyThingTheGuestSeesBeyondItsWorkspace() {
        val argv = command.argv(workspace, "ls")
        val binds = argv.zipWithNext().filter { it.first == "-b" }.map { it.second }

        // Device nodes by name, and never a directory: a bind of /dev, /proc or /sys
        // passes through everything inside it, which is how /dev/binder and the ADB
        // gadget would reach the guest.
        assertEquals(
            ProotCommand.MINIMAL_DEVICES + "${workspace.absolutePath}:/workspace",
            binds,
        )
        assertFalse(binds.any { it == "/dev" || it == "/proc" || it == "/sys" })
        assertFalse(binds.any { it.contains("/data/user/0") && !it.contains("/workspaces/") })
    }

    @Test
    fun fakeRootIsOptional() {
        assertTrue(command.argv(workspace, "id").contains("-0"))
        assertFalse(command.argv(workspace, "id", fakeRoot = false).contains("-0"))
    }
}
