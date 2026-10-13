# Third-party notices

DeepAPI Chat's own source code is under the MIT License (see `LICENSE`). The app
also redistributes the following third-party components inside the APK. These are
separate works, under their own licenses, and the MIT license above does not
apply to them.

## proot

- Files: `app/src/main/jniLibs/arm64-v8a/libproot.so`,
  `app/src/main/jniLibs/arm64-v8a/libproot-loader.so`
- Copyright (C) 2015 STMicroelectronics
- License: GNU General Public License, version 2 or later (GPL-2.0-or-later)

proot is the process-launcher the sandbox runs on. The copyright and license
statement above is reproduced from proot's own banner, which it prints on
startup.

Because proot is under the GPL, anyone redistributing an APK containing these
binaries must comply with the GPL for them: pass on the license text, and make
the corresponding source available. Upstream is <https://github.com/proot-me/PRoot>.

## talloc

- File: `app/src/main/jniLibs/arm64-v8a/libtalloc.so`
- Part of the Samba project
- License: GNU Lesser General Public License, version 3 or later (LGPL-3.0-or-later)

`libproot.so` links against talloc, which is why it is bundled. Upstream is
<https://talloc.samba.org/>.

## android-shmem

- File: `app/src/main/jniLibs/arm64-v8a/libandroid-shmem.so`
- License: to be confirmed against the exact upstream source it was built from

This shim lets the guest use shared memory through `ashmem`. The license has not
been verified here; confirm it against the source used for the build before
publishing a release.

## Alpine Linux minirootfs

- File: `app/src/main/assets/rootfs/alpine-minirootfs-3.20.9-aarch64.tar`
- Alpine Linux 3.20.9, aarch64
- Upstream: <https://alpinelinux.org/>, <https://gitlab.alpinelinux.org/alpine/aports>

The root filesystem the sandbox chroots into. It bundles a large number of
packages, each under its own license — notably busybox (GPL-2.0) and musl
(MIT). The distributed tarball carries no per-package license files, so the
license texts for the contents are not bundled here. Alpine publishes the license
for every package in the APKBUILD for that package; if you redistribute this app,
satisfy yourself that the licenses of the packages you actually ship are met.

## AndroidX, Jetpack Compose, Kotlin, OkHttp, kotlinx, highlights

- Declared in `gradle/libs.versions.toml`, resolved from Maven Central and Google's
  Maven repository at build time. These are linked into the APK and remain under
  their own licenses — predominantly Apache-2.0 for the AndroidX and Kotlin
  components, Apache-2.0 for OkHttp, Apache-2.0 for kotlinx, and MIT for
  `dev.snipme:highlights`.
