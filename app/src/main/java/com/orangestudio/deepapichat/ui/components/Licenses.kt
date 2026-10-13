package com.orangestudio.deepapichat.ui.components

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp

/**
 * The license texts this app is distributed under.
 *
 * The app's own code is MIT. proot, talloc, the shared-memory shim and the Alpine
 * root filesystem are separate works under their own licenses that are
 * redistributed inside the APK, so their notices are shown here and not only in
 * the repository's THIRD_PARTY_NOTICES.md.
 */
const val MIT_LICENSE = """MIT License

Copyright (c) 2026 Orange Studio

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE."""

private class Notice(val name: String, val detail: String)

private val THIRD_PARTY = listOf(
    Notice(
        "proot",
        "Copyright (C) 2015 STMicroelectronics. GNU General Public License v2 or later. " +
            "Bundled as libproot.so and libproot-loader.so; it is what the sandbox runs on.",
    ),
    Notice(
        "talloc",
        "Samba project. GNU Lesser General Public License v3 or later. Bundled as " +
            "libtalloc.so, which libproot.so links against.",
    ),
    Notice(
        "android-shmem",
        "Shared-memory shim so the guest can use ashmem. Bundled as libandroid-shmem.so.",
    ),
    Notice(
        "Alpine Linux 3.20.9 (aarch64)",
        "The sandbox root filesystem, including busybox (GPL-2.0) and musl (MIT). " +
            "Redistributed as alpine-minirootfs-3.20.9-aarch64.tar.",
    ),
    Notice(
        "AndroidX, Jetpack Compose, Kotlin, OkHttp, kotlinx.serialization, dev.snipme:highlights",
        "AndroidX, Compose, Kotlin, OkHttp and kotlinx are Apache-2.0; highlights is MIT.",
    ),
)

@Composable
fun LicensesDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val version = remember { appVersion(context) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Licenses") },
        text = {
            Column(modifier = Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                Text(
                    text = "DeepAPI Chat v$version",
                    style = MaterialTheme.typography.titleSmall,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "This app's own source code is MIT licensed.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(14.dp))
                Text(
                    text = MIT_LICENSE,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                )
                Spacer(Modifier.height(20.dp))
                Text(
                    text = "Third-party components redistributed in this app",
                    style = MaterialTheme.typography.labelLarge,
                )
                Spacer(Modifier.height(6.dp))
                for (notice in THIRD_PARTY) {
                    Text(text = notice.name, style = MaterialTheme.typography.bodySmall)
                    Text(
                        text = notice.detail,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(10.dp))
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

@Suppress("DEPRECATION")
private fun appVersion(context: Context): String =
    runCatching { context.packageManager.getPackageInfo(context.packageName, 0) }
        .getOrNull()
        ?.versionName
        .orEmpty()
        .ifBlank { "1.0" }
