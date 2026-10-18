# DeepAPI Chat — v1.0 RELEASE

An Android chat client for the DeepSeek API, with a per-conversation Linux sandbox
the assistant can run commands in.

## What it does

- **Streaming chat** against DeepSeek's chat completions API, with the model's
  reasoning shown separately from its answer. The model list is fetched from the
  API rather than hard-coded, so new models appear without an app update.
- **Markdown rendering** of replies: headings, lists, quotes, pipe tables, and
  code blocks with syntax highlighting, the language name the model supplied, and
  a copy button. A ` ```chart ` fence containing JSON is drawn as a bar, line or
  pie chart instead of being shown as code.
- **Tool calls you approve one at a time.** The assistant asks before it does
  anything, and the confirmation shows exactly what it wants to do.
- **A sandbox per conversation.** Each chat gets its own workspace and its own
  Alpine Linux root filesystem, run under proot. The assistant can ask to run a
  shell command in it; the first use unpacks the root filesystem, and every
  command is confirmed by you.
- **Attachments.** Images are inlined into the request so the model can look at
  them. Other files are copied into that chat's sandbox workspace and the model
  is told where they are.
- **A system prompt held for the life of a chat.** Editing it does not silently
  change the conversation mid-flight — the chat keeps the prompt it started with,
  and the edit takes effect when you tap regenerate on a reply.
- **Chat names suggested by the model**, which you can accept, or type your own
  and the app stops offering.
- **Memories** as a list of individual entries, which the assistant can ask to
  add and you can edit or remove.
- **Themes:** light, dark, true-black surfaces for OLED screens, and an accent
  colour.
- **Export and import** every conversation as a backup file.

## Requirements

- Android 9 (API 28) or newer.
- An `arm64-v8a` device — the sandbox runtime is built for that ABI only.
- A DeepSeek API key.

## Building

The Gradle wrapper is included; no local Gradle install is needed.

```sh
export JAVA_HOME="/path/to/jdk-17-or-newer"
./gradlew :app:assembleDebug
```

The debug APK lands in `app/build/outputs/apk/debug/app-debug.apk`. To build and
install on a connected device:

```sh
./gradlew :app:installDebug
```

Unit tests:

```sh
./gradlew :app:testDebugUnitTest
```

`app/src/androidTest` holds tests that exercise the sandbox on a real device and
need a connected phone:

```sh
./gradlew :app:connectedDebugAndroidTest
```

## About the sandbox

The sandbox confines **the filesystem** — a command can only see the root
filesystem and that chat's workspace. It does **not** confine the network, and it
is not a security boundary against a determined attacker; proot rewrites paths, it
does not isolate the kernel. The Android device nodes (`/dev/binder`, the ADB
gadget) and `/proc` and `/sys` are deliberately not exposed to the guest. Treat
commands the model proposes as you would any code you did not write, and read what
you are approving.

## License

The app's own source code is released under the MIT License — see `LICENSE`.

It also redistributes third-party binaries under their own licenses: proot
(GPL-2.0-or-later), talloc (LGPL-3.0-or-later), an `android-shmem` shim, and the
Alpine Linux minirootfs. Those are **not** covered by the MIT license. See
`THIRD_PARTY_NOTICES.md`, and the Licenses screen in the app.
