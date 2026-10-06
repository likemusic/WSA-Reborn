# RCM channel protocol

This is an interoperability reference for the protocol between the WSA host client (`WsaClient.exe`) and the Android guest. It describes what WSA-Reborn's guest bridge (`WsaShellBridge`) implements and what was learned while implementing it. For where the components live and how the system boots, see [architecture.md](architecture.md).

Everything here was observed against WSA `2407.40000.4.0`. The host side is Microsoft's and was not modified; all behaviour attributed to the host is what the host was seen to do.

## Transport

### Opening a channel

Microsoft's vendor image provides a Binder service, `windows.services.rcm.IRemoteConnectionManager/default`, implemented by `/vendor/bin/hw/windows.services.rcm@1.0-service` (SELinux domain `rcmsvc`). It is a stable AIDL interface (version 1) with a single method:

```aidl
package windows.services.rcm;
interface IRemoteConnectionManager {
    ParcelFileDescriptor createChannel(String channelName);   // transaction 1
}
```

`createChannel(name)` returns the file descriptor of a connected vsock stream whose other end is `WsaClient.exe`. Our AIDL definition is in `WsaShellBridge/aidl/windows/services/rcm/`. A client gets the service with `ServiceManager.waitForDeclaredService` and calls `createChannel` once per channel; each channel is an independent socket.

Under the hood (vendor `librcm.so`), the guest listens on an ephemeral vsock port, tells the host about it over a control connection to CID 2, and the host connects back; the resulting socket is what `createChannel` returns. Clients never see this; they only see a stream socket.

SELinux needs nothing extra for clients in the usual app domains: Microsoft's untouched vendor policy puts `platform_app`, `priv_app`, `system_app`, `system_server` and `sysprep_service` into `hal_rcm_client`, allows them to find `hal_rcm_service`, call into the server, and read/write its vsock sockets.

### Framing

Both directions use the same frame:

```
uint32, little-endian    N
N bytes                  ApiMessageHeader (protobuf)
```

```proto
message ApiMessageHeader {
  required uint64     callId     = 1;  // each side keeps its own counter, starting at 0
  optional bool       isReply    = 2;
  optional ResultCode returnCode = 3;  // absent = Success; otherwise a Windows HRESULT
  optional bytes      payload    = 4;  // the serialized channel message
}
```

The host's schema has one more field (`performance_message = 5`) that no implemented channel uses. `ResultCode` values are signed HRESULTs (`E_FAIL`, `E_POINTER`, `E_UNEXPECTED`, `ERROR_NOT_READY`, `WAIT_TIMEOUT`, …); the list is in `result_codes.proto`.

Call semantics, as implemented in `ApiChannel.java`:

- a request is a frame with `isReply=false` and a fresh `callId` from the sender's counter; the receiver must answer with a frame carrying the same `callId` and `isReply=true`;
- replies are matched to outstanding requests by `callId` (a map of `callId → future`); a reply with a non-success `returnCode` completes the request with an error;
- **the conventional reply to a host request is an echo**: the reply payload is the request message itself, possibly with a result sub-message filled in. Some channels instead expect an empty reply and send the actual result as a separate request (noted per channel below);
- if handling fails, the reply has no payload and carries a `returnCode`;
- a request may be sent synchronously (wait for the reply) or asynchronously (the reply still arrives and is simply dropped from the pending map). The bridge waits with a timeout (15 s for window creation) so a dead host cannot hang it;
- frames are written under a lock and read on one receive thread per channel; the bridge rejects frames over 16 MiB. The host has its own frame limit, so large payloads (images, audio, files) are size-capped or chunked by the guest.

When a channel's socket reaches EOF, all pending requests fail and the handler is notified. Microsoft's guest code does not reconnect; the bridge reconnects with a 1→10 s backoff and re-announces its state, because the host client may be restarted at any time.

### Schemas

Microsoft's guest apps use protobuf-javalite, which strips descriptors, but the host is C++ with full protobuf and embeds the complete serialized `FileDescriptorProto` of every channel schema (26 files, package `LatteApi.<Channel>`). `bin/proto-from-wsaclient.py` lists and prints them from your own copy of `WsaClient.exe`, for checking. The extracted schemas are not part of this repository.

The `.proto` files in `WsaShellBridge/proto/` were written for interoperability: message structure, field numbers, field types, `required`/`optional`/`repeated` and enum values follow the host; names are adapted to our style (names do not go on the wire), and messages no implemented channel needs are omitted. Each channel lives in its own protobuf package because several channels define a message called `Result`.

Every channel message has the same shape: a `messageType` enum plus one optional sub-message per type. Dispatch is by `messageType`, not by which sub-message is present.

## Channel inventory

Channel names are passed to `createChannel` verbatim (case-sensitive).

| channel | purpose | guest side in WSA-Reborn |
| --- | --- | --- |
| `Shell` | one Windows window per Android task | bridge (`ShellBridge`) |
| `Intents` | launch apps from Windows; open Android links in Windows | bridge (`IntentsBridge`, `OpenInWindowsActivity`) |
| `UserInput` | mouse, touch and keyboard for app windows | bridge (`UserInputBridge`) |
| `Clipboard` | shared clipboard | bridge (`ClipboardBridge`) |
| `Notification` | notifications and `Toast`s in the Windows action center | bridge (`NotificationBridge`) + SystemUI patch |
| `Apps` | Android apps in the Start menu and "Installed apps" | bridge (`AppsBridge`); Microsoft's `SystemApp` also opens it |
| `FileSystem` | files both ways, shared user folder, install-time scanning | bridge (`FileSystemBridge`), `WsaPackageVerifier`, `wsa_mount_shared_folder` |
| `TSF` | text input through Windows text services | bridge (`TsfBridge`, `WindowsInputMethod`) |
| `SystemMediaControlsChannel` | Windows media flyout for Android media sessions | bridge (`MediaControlsBridge`) |
| `TextToSpeech` | Windows voices as an Android TTS engine | bridge (`TtsBridge`, `WindowsTtsService`) |
| `UIABridge` | Android accessibility tree for UI Automation | bridge (`UiaBridge`, `WindowsAccessibilityService`) |
| `Settings` | settings sync from Windows (screen reader switch, UI Automation flag, …) | Microsoft `SystemApp` (BYOB) |
| `Lifetime` | guest wake state, heartbeat | Microsoft `SystemApp` |
| `Telemetry` | DropBox entries, app usage | Microsoft `SystemApp` |
| `PermissionUsage` | which app uses location / camera / microphone, shown in the Windows privacy indicator | Microsoft `SystemApp` |
| `Geocode` | geocoding through Azure Maps with a host-supplied key | Microsoft `SystemApp`; dead (key revoked), superseded by the bridge's OpenStreetMap `Geocoder` provider |
| `SysPrep` (`SysPrepChannel`) | boot-phase handshake | vendor `sysprep_service` |
| `Composition` | layers and buffers for host composition | vendor `hwcomposer.windows` |
| `Camera` | host cameras | vendor `camera-service.windows` |
| `AudioControl`, `AudioNotificationChannel`, `AudioOut`, `AudioIn` | sound | vendor `audio.primary.windows` |
| `Geolocation` | location from Windows | vendor `gnss-service.windows` |
| `UserInputNative` | virtual touchscreen | vendor `virtualinputservice` |
| logcat channel | guest logcat to `LocalState/diagnostics/logcat` | Microsoft `logcat_forwarder` (BYOB) |
| `Wns` | Windows push notifications for Amazon Device Messaging | not needed: Amazon Appstore is discontinued |
| `Amazon` | Amazon Appstore install events | not needed, same reason |
| `CoffeeTest` | a name present in Microsoft's channel list, apparently a test channel | not used |

Graphics command streams (GLES/Vulkan via gfxstream) do not use RCM: `windows.services.graphics` talks to the host over its own vsock ports, and large buffers go through `/mnt/shared_mem` and virtio-gpu resources, with only descriptors on the `Composition` channel.

On Microsoft's Android 13 image, `Shell` and `Notification` are handled in `SystemUIApp`; `Intents`, `Clipboard`, `FileSystem`, `TSF`, `TextToSpeech`, `UIABridge`, `UserInput`, `Wns` and `SystemMediaControlsChannel` in `UserApp`; `Apps`, `Settings`, `Telemetry`, `Geocode`, `Lifetime`, `PermissionUsage` and `Amazon` in `SystemApp`. The host does not care which process opens a channel (it only sees the socket), which is what makes the bridge possible.

## Channels implemented by the bridge

### `Shell`

The host never creates a window by itself when an Android activity starts (`am start` is purely a guest operation); a window appears only in response to the guest's `CreateTopLevelWindow`.

| message | direction | meaning |
| --- | --- | --- |
| `CreateTopLevelWindow{WindowInfo}` | guest → host, synchronous | a new task should get a window |
| `UpdateClientWindow{WindowInfo}` | guest → host | task changed (title, bounds, mode, visibility) |
| `SetFocusedWindow{taskId, setForeground}` | both | guest: task moved to front; host: user activated the window |
| `RemoveWindow{taskId, force}` | both | guest: task vanished; host: user closed the window (`force` → force-stop the package) |
| `MinimizeClientWindow{taskId}` | guest → host | task moved to back |
| `UpdateAndroidWindow{WindowInfo}` | host → guest | user moved/resized the window or changed its mode |
| `ClearFocusedWindow{taskId}` | host → guest | window lost focus |
| `OnWindowMinimized{taskId, minimized}` | host → guest | window minimized/restored |
| `UpdateAndroidDisplayInfo{[DisplayParameters]}` | host → guest | monitor density, stable bounds (work area) and maximized bounds |
| `RestorePipWindow{taskId}` | host → guest | leave picture-in-picture |

`WindowInfo` carries the task id, package name, bounds in guest pixels, `wsaDisplayId`, label, minimum size, visibility, PiP aspect ratio, a host-set `userResize` flag and a window mode (`Freeform`, `PictureInPicture`, `Fullscreen`). The host composites a window from the guest layers tagged with that task id (layer metadata set by SurfaceFlinger; see [patches.md](patches.md)).

Which tasks get a window: those with a base activity, activity type `STANDARD`, on the internal display. HOME tasks never get one; in WSA the Android home screen is not shown.

At start the guest prepares each internal display: clears any forced size, enables system decorations, sets the IME policy to local, sets density from the physical DPI, and switches the display to freeform windowing so every task is its own freeform window. The screen must be awake: the host does not create windows for a display that is off, so the guest wakes it when a task is created.

Implementation notes:

- the bridge listens with `TaskStackListener` and `IActivityTaskManager.getTasks()` instead of hooking WM Shell internals, and applies host commands with `WindowOrganizer.applyTransaction` (needs `MANAGE_ACTIVITY_TASKS`, granted to uid `system`). It does not touch task leashes: the window manager already tags each task surface with its task id;
- `wsaDisplayId` is the number in the display's `uniqueId` (`local:100` → 100) on Android 14–16. On 17, SurfaceFlinger builds the id from EDID and the host answers `E_UNEXPECTED` to every `CreateTopLevelWindow`; the bridge uses the display address port instead, which is 100 on all versions. `UserInput` uses the same mapping;
- **new windows are fitted to the host work area** before they are announced: a task covering ≥ 90 % of it is maximized into `maximizedBounds` (if resizeable), otherwise moved inside `stableBounds` or shrunk. `stableBounds` rather than `maximizedBounds`, because the host draws the title bar above the task and `stable.top` leaves room for it;
- **PiP**: `TaskStackListener` does not report mode changes, so the bridge listens to `onActivityPinned`/`onActivityUnpinned`. The host creates the PiP window from the first message about a pinned task and never resizes it, so that message waits until WM Shell has set the PiP bounds. The host then sends `UpdateAndroidWindow` with origin `0,0`; applying it would move the task's configuration but not its surface (owned by the PiP organizer) and misroute clicks, so it is not applied;
- the bridge's own invisible activities (e.g. the link forwarder) must never be announced, or the host flashes an empty window;
- **after a channel drop** the host closes all windows and does not reopen them on its own. A repeated `CreateTopLevelWindow` creates a window, but it is only shown for a task brought to the front; the bridge therefore re-announces visible tasks and reorders them, focused task last. The host also sends `LaunchPackage{MainLaunch}` (on `Intents`) for the focused app; when that package already has a visible task, the bridge only brings it to front rather than clearing the task.

### `Intents`

The host turns `wsa://<package>`, Start menu shortcuts and drag-and-drop into `IntentMessage{LaunchPackage}`. Without a handler the host shows its splash window and waits forever.

`LaunchPackage` has an intent type: `MainLaunch` (launcher intent of the package), `Activity` (explicit component), `Implicit` (action + data, optionally an App Link), `Shortcut`, `Install`, `FileShare` (`ACTION_SEND`/`SEND_MULTIPLE` with files the host provides through `FileSystem`). The guest replies with the same message plus `Result{succeeded, errorMessage}`. The bridge implements all but `Shortcut` and `Install`, which are answered with `succeeded=false`. Like the reference, it starts activities with `NEW_TASK | CLEAR_TASK`.

In the other direction, `LaunchUri` opens a URI in Windows; the host replies with an empty frame. The bridge's `OpenInWindowsActivity` handles `VIEW` for `http`/`https`, `mailto`/`SENDTO` (recipients, subject and body merged from extras), `tel`/`sip`/`voicemail`, `geo` (translated to `bingmaps:` since Windows has no `geo` handler) and web search (to a Bing search URL). Differences from the reference, all deliberate: only an allow-list of schemes is forwarded (the reference had an exported activity that forwarded any URI, including `file:` and `ms-settings:`); there is no in-app `WebView` for OAuth flows (not allowed in a uid-`system` process); mail attachments are not sent.

### `UserInput`

Host → guest only; the reply is an empty frame. Without this channel clicks on app windows never reach the guest.

- `MouseMessage{displayId, emulateTouch, mouseAction (Down/Up/DragMove/HoverMove/Scroll), x, y, actionButton, buttonState, modifierKeys, scroll deltas, targetTaskId}` → `MotionEvent` from `SOURCE_MOUSE` (or touchscreen when `emulateTouch`), with `BUTTON_PRESS`/`BUTTON_RELEASE` around down/up; a right click is delivered as a long press; on down the target task is moved to front;
- `TouchMessage{…, touchPointers[], actionPointerIndex, targetTaskId}` → multi-pointer `MotionEvent`, stylus button as `BUTTON_STYLUS_PRIMARY`;
- `KeyboardMessage{virtualKeyCode (Windows VK_*), keyDown, modifierKeys, repeatCount, scanCode, targetTaskId}` → `KeyEvent` via a VK → `KEYCODE_*` table.

Events are injected with `InputManager.injectInputEvent(…, ASYNC)` (`INJECT_EVENTS`). Keys arrive as VK codes and map through the US layout, which is why text entry goes through `TSF` instead.

### `Clipboard`

Clipboard contents never travel over the channel; only an inventory does. The bytes live in a named Windows shared-memory section that the guest sees as the file `/mnt/shared_mem/<sectionName>` (virtiofs) and maps with `FileChannel.map`. The host always creates the sections.

| message | direction | meaning |
| --- | --- | --- |
| `SetServerClipboardContent{SharedMemory, ClipboardData}` | host → guest | something was copied in Windows; read the items and `setPrimaryClip` |
| `RequestSharedMemory{}` | guest → host, synchronous | give me a section to write into; the reply carries its name and size |
| `SetClientClipboardContent{SharedMemory, ClipboardData}` | guest → host | something was copied in Android; items are already written |

`ClipboardData` is a list of `{size, offset, contentItemType}` with types `Text` and `Html` (UTF-8), `Uri`, `Bitmap` (PNG), `File` (a GUID with extension; the bytes are fetched through `FileSystem`). Replies are echoes with `Result`. The host sends its current clipboard as soon as the channel opens.

Gotchas:

- **echo loop**: clips set from host data carry a label the guest recognizes, so its own `setPrimaryClip` is not sent back;
- **background access**: Android 14+ lets only the focused app or a holder of `READ_CLIPBOARD_IN_BACKGROUND` read the clipboard; the bridge has no windows, so it holds that permission (signature-level). Writing needs no focus;
- **URI grants from uid `system` are impossible**: `UriGrantsManagerService` refuses to issue grants on behalf of uid 1000, so pasting apps get a `SecurityException`. The bridge's image provider is therefore exported, read-only, serves only the last few images under random UUID names that appear only in the clip;
- **`/mnt/shared_mem` looks absent**: `File.isDirectory()` returns false for `system_app` (policy grants `search` but not `getattr` on the directory), while opening files inside works. Check `/proc/self/mounts` instead;
- **HTML is decoded by the host in the Windows ANSI code page**, not UTF-8; non-ASCII text in the `Html` item must be sent as numeric character references. `Text` is taken as UTF-8;
- the reference never sends `Html` (it checks `getText()` first) and writes items at the wrong offset (passes the offset as the source-array offset of `put(byte[], int, int)`), which breaks any clip with more than one item. The bridge writes both `Html` and `Text`, at the declared offsets, with bounds checks;
- `ClipboardService` notifies listeners twice per clip (again after the text classifier annotates it); the bridge sends a clip only once;
- every `setPrimaryClip` from host data would make SystemUI show its clipboard preview, which the host turns into a brief stray window on the Windows desktop. The bridge sets the clip as `com.android.shell` with the `SUPPRESS_CLIPBOARD_OVERLAY` extra (`setPrimaryClipAsPackage`, `SET_CLIP_SOURCE`), which SystemUI honours.

### `Notification`

One message type, `NotificationMessage`:

| type | direction | meaning |
| --- | --- | --- |
| `SendNotification` | guest → host | post or update; everything (texts, progress, actions, icon and picture as PNG) is in the message |
| `CancelNotification` | guest → host | removed in Android |
| `Activation{action, replyKey, replyData}` | host → guest | user clicked the toast body (`action="Launch"`) or a button, possibly with typed text |
| `Dismissal` | host → guest | user dismissed it in Windows |
| `Toast` | guest → host | an Android `Toast` |
| `ReportNotificationSeenEvent` | host → guest | Windows showed it |

A notification is identified on both sides by `packageName` + `id` + `tag` (empty string if none). Replies to host requests are echoes with `Result`; errors go into `Result`, not the frame `returnCode`. Images travel inline (`Common.PNG`), unlike the clipboard.

The bridge sees notifications by registering a `NotificationListenerService` directly with `registerAsSystemService(…, USER_ALL)`, which `NotificationManagerService` allows for uid `system` without user consent. Media-style notifications (shown through the media flyout instead) and group summaries are not forwarded, as on stock.

Gotchas and differences from the reference:

- titles/texts are read as `CharSequence` (styled text returns `null` from `getString`);
- `BigPictureStyle` pictures set as an `Icon` (API 33+) live under `android.pictureIcon`;
- the original `PendingIntent` is sent, with `setPendingIntentBackgroundActivityStartMode` opted in (required on 14+); the reference re-created intents as immutable, which loses the app's identity and drops inline replies;
- pictures are capped (4 MB) so a frame stays under the host's limit; listener callbacks are moved off the main thread;
- **action buttons**: the host shows a button only for actions with a `RemoteInput`; plain actions are not rendered, and clicking anywhere on the card yields `Launch`. The bridge matches a reply first by the `RemoteInput` result key (`replyKey`), then by title;
- **non-ASCII button titles break replies in the host**: it builds the toast arguments as `action=<title>&package=…&id=…&tag=…` without escaping and later fails to parse them, so the activation is silently lost. Stock WSA behaves identically. Percent-encoding the title would fix the reply but show the encoded text on the button, so titles are sent unchanged;
- posting a new notification with the same id right after cancelling the old one can lose it: a late cancel at the host removes the new toast.

`Toast`s do not pass through `NotificationManager`; for apps targeting API 30+ they are drawn by SystemUI. A SystemUI patch ([patches.md](patches.md)) makes `ToastUI` send an explicit broadcast to the bridge (receivable only from a holder of `STATUS_BAR_SERVICE`, i.e. SystemUI) instead of drawing (only if the bridge is installed) and acknowledge the toast to the app. The bridge sends `NotificationMessage{Toast}` (one id for all toasts, the toast token as tag, 4 s or 7 s duration) and `CancelNotification` on hide. Toasts with custom views and those of apps targeting API < 30 are drawn by the app process and are not covered, as on stock.

### `Apps`

| type | direction | meaning |
| --- | --- | --- |
| `GetList` | host → guest | enumerate apps: label, publisher, icon, version, size, URI schemes, verified links, shortcuts |
| `Status` | guest → host, synchronous | app `ADDED`, `REPLACED` or `REMOVED`; the reply carries compatibility shims |
| `UninstallRequest` / `UninstallResult` | host → guest / guest → host | uninstall from Windows |
| `SyncCompatShims` | host → guest | shims for listed packages |
| `JumpListChanged` | guest → host | app shortcuts changed |
| `FileShare` | host → guest | would the app accept files with these extensions |
| `ForceStopPackage` | host → guest | stop the app |

The host creates a Start menu shortcut (`WsaClient.exe /launch wsa://<package>`) only for launchable apps. A few stock packages that have host equivalents are excluded from the list, as on stock. The publisher is derived from the package name reversed (Android has no such field). The only known shim is `ForceNonResizeableApp` (platform change `FORCE_NON_RESIZE_APP`), applied through `CompatChanges.putPackageOverrides` (`OVERRIDE_COMPAT_CHANGE_CONFIG_ON_RELEASE_BUILD`, since builds are not `eng`); the guest reports its own resize heuristics and the host decides.

Gotchas:

- `GetList` is not sent on every connection; the host appears to rebuild the Start menu only when the WSA version or language list changes, and otherwise relies on `Status` events;
- **the host keeps only the most recent connection of a channel**. Microsoft's `SystemApp` also opens `Apps`; whichever connects last wins, and the other side gets EOF. `SystemApp` does not reconnect, so the bridge reconnects after it and keeps the channel; events in the first seconds of boot may be lost;
- `FileShare` is the host's drag-and-drop path: when a file is dropped onto an app window the host asks whether the app accepts it and then sends `Intents` `LaunchPackage{FileShare}`. Android apps do not appear in the Windows Share sheet or "Open with";
- the privileged permissions used here (`PACKAGE_USAGE_STATS`, `DELETE_PACKAGES`, the compat override) must be in the bridge's privapp allowlist, which is installed under the module name; a missing entry makes `system_server` abort while parsing the package;
- robustness fixes over the reference: one package query per app instead of four, icons without intrinsic size or missing icons no longer abort the whole list, icons capped at 192 px (all icons travel in one `GetList` frame), and the package-change receiver is registered with an explicit export flag (required on 14+).

### `FileSystem`

| type | direction | meaning |
| --- | --- | --- |
| `FileTransferRequest` / `FileTransferResult` | guest → host / host → guest | send a file to Windows in chunks (last chunk empty with `transferComplete`); the next chunk goes only after the host accepts the previous one (same `cookie`); `launch` asks the host to open it |
| `FolderSharingTestRequest` / `Result` | host → guest / guest → host | can the guest see a probe file in the shared folder |
| `MediaScanRequest` | host → guest | a shared-folder file changed on the Windows side; rescan it |
| `ExternalStorageMediaScanFinished` | guest → host | external storage scan done |
| `OpenSharedFile` | guest → host | open in Windows a file that is already in the shared folder |
| `CreateSharedTempFile` | guest → host, synchronous | create a temp file under `/mnt/shared_tmp/<subdir>` |
| `PackageVerificationRequest` / `Result` | guest → host / host → guest | scan an APK being installed; allow or deny |
| `ProvideContentFile` / `Result` | guest → host, synchronous | put the Windows file with this GUID into `/mnt/shared_tmp/content/` |
| `ProvideContentFileDetails` | guest → host, synchronous | name and size of a Windows file by GUID |

Host requests are answered with an empty frame; results (such as `FolderSharingTestResult`) are sent as separate messages. Files from Windows do not travel over the channel: the host drops them into `/mnt/shared_tmp/content/<GUID>.<ext>` (virtiofs, owned `cache:cache` 0770; the process gets the `cache` group through `ACCESS_CACHE_FILESYSTEM`). The bridge exposes them as `content://…windowsfiles/<GUID>.<ext>` through its own provider; the clipboard `File` type and `LaunchPackage{FileShare}` both resolve through it. Files sent to Windows land in the user's `Downloads`; the host itself refuses executable types.

Security: the "send to Windows" activity is exported and the bridge can read nearly any `content://` URI as uid `system`, so it only sends a file the launching app could read itself (explicit URI grant, provider owner, or the provider's read permission), using the launcher's uid rather than `checkUriPermission`. Windows-executable extensions are never sent with `launch=true`.

Shared user folder: the host offers a `windows_user` virtiofs share only if "share user folders" is enabled in WSA Settings, decided at VM start. Our `wsa_mount_shared_folder` mounts it at `/sdcard/Windows` after boot with the SELinux context of the sibling `Documents` directory and sets `external_storage.windows_user.mounted=1`, which the channel handler checks. Two pitfalls: the context contains commas (`…:s0:c82,c256,…`) and must be quoted in the mount options, or the kernel splits it; and property names are limited to 32 bytes in the older `PROP_NAME_MAX` buffers.

Install-time scanning (AMSI), on by default as on stock: the required verifier is `WsaPackageVerifier`, a separate platform-signed package in the `platform_app` domain, because only that domain may read APKs in the installer session directory (`apk_tmp_file`; a rule for `system_app` violates a `neverallow`). On `PACKAGE_NEEDS_VERIFICATION` it extends the timeout (default `REJECT`), streams each APK through a pipe to a bridge provider call, the bridge copies it into a host temp file (`CreateSharedTempFile` in `quarantine`), sends `PackageVerificationRequest` and waits for the result, and the verifier calls `verifyPendingInstall`. Streaming installs do not provide the root-hash extra the reference relied on, so APKs are taken from the session directory. Disable with `settings put global verifier_verify_adb_installs 0` (adb only) or `package_verifier_enable 0` (everything).

Updating the bridge inside an image: the package parser cache is keyed on the package directory's mtime; replacing the APK with a filesystem tool that does not touch the directory makes Android use the stale parsed manifest. `bin/patch-system-file.sh` bumps the directory time.

### `TSF`

Keys from `UserInput` arrive as VK codes and map through the US layout, so non-Latin layouts, IME composition, the emoji panel and dictation need this channel: the guest mirrors the focused text field to the host, the host attaches a Windows TSF text store to it and sends back edits.

- guest → host: `SetClientFocus` (enter a field: new control id, Windows `InputScope`, multiline flag, text and selection; leave: same id without text), `SetClientText`, `SetClientSelection`, `SetClientCursorLocation` (for the candidate window and emoji panel position);
- host → guest: `SetRemoteText` (replace a range), `SetRemoteSelection`, `TagRemoteText` (mark composition), `SetRemoteCompositionState`, `RemoteKeyboard`; the guest replies with an empty frame.

The guest side is an Android input method (`WindowsInputMethod`, no on-screen keyboard), which the bridge makes the default at start, plus the channel handler. Line breaks are `\r` on Windows and `\n` on Android (same length, ranges stay aligned).

The main pitfall: for ordinary typing the host sends `SetRemoteText` **without** a replacement range ("insert at the current selection") and does not apply it to its own store; it expects the guest to confirm with `SetClientText` carrying the range and inserted text. Without that the host thinks the field is empty and later insertions land at position 0. Other choices: all field work on one thread, own edits filtered by comparing with the mirror, every focus change closes and opens a session, cursor position only from `CursorAnchorInfo`, backspace queued behind pending host edits.

### `SystemMediaControlsChannel`

Note the channel name (not `SystemMediaControls`). All messages are `MediaTransportControlsMessage` with a package name; the host distinguishes sessions only by package.

- guest → host: `SetSmtcEnabled`, `SetPlaybackState`, `SetEnabledButtons` (bit mask), `SetMetadata` (repeated key/value: `Title`, `Artist`, `AlbumArtist`), `SetArtwork` (encoded image, PNG);
- host → guest: `MediaKeyPress`; reply is the same message with `Result`.

The bridge watches active sessions via `MediaSessionManager` (`MEDIA_CONTENT_CONTROL`) and forwards keys to the session's transport controls.

Host behaviour to know:

- the media flyout is tied to the package's Windows **window**: `SetSmtcEnabled` for a package without a window fails with `E_POINTER`; closing the window makes the host send `KeyStop` and drop the session; when a new window opens the host neither restores the session nor accepts a repeated `SetSmtcEnabled=true`. The bridge, notified by `Shell` after window creation, sends `SetSmtcEnabled=false` and then re-announces the whole session;
- an empty `SetArtwork` clears the thumbnail but the host does not commit the change; it is committed with the next metadata change, so the bridge sends the artwork clear before the new track's metadata;
- Windows displays the album artist in preference to the track artist when both are set;
- the bridge always sends all three metadata keys (empty when absent), forwards state and buttons only on change, picks artwork from `ALBUM_ART`, `ART`, `DISPLAY_ICON` scaled to 512 px, and follows sessions re-created by the app.

### `TextToSpeech`

Guest-initiated only; the host replies with the same `TextToSpeechMessage` with `result` and the answer filled in.

- `GetVoices` → list of voices (name, BCP 47 tag);
- `SynthesizeText{text, voice_name, pitch, speech_rate, volume}` → `{audio_buffer, sample_rate_hz}`: raw 16-bit mono PCM in one buffer. `pitch` and `speech_rate` are in Android units (100 = normal).

The bridge's `WindowsTtsService` is a regular `TextToSpeechService` and becomes the default engine if none is selected. Long text is split at sentence boundaries into chunks of up to 500 characters, so the first audio comes quickly and no reply approaches the frame limit (the reference sent up to 4000 characters in one request, minutes of audio in a single frame); `onStop` stops between chunks; volume is applied by Android only; the voice list is cached for a minute; a WAV header, if the host ever sends one, is parsed rather than played.

### `UIABridge`

Host-initiated; the guest answers with the same `UIABridgeMessage` and `result`.

- `GetAccessibilityWindowInfo` → active window: id, display, title, type, layer, bounds;
- `GetAccessibilityNodesInfo` → the visible tree of the active window, breadth-first, each node with id, class, text, description, state flags, screen bounds and `parent`/`first_child`/`next_sibling` links (-1 = none);
- `PerformAction{node_id, CLICK|COLLAPSE|EXPAND}` → success flag;
- guest → host: `DirtyBit` (no content), "the tree changed"; the bridge rate-limits it to once per 100 ms.

The host enables the channel only when a hidden per-user registry value is set; it then sends `UiaBridge` over `Settings`, and Microsoft's `SystemApp` writes Microsoft's (absent) accessibility service into `enabled_accessibility_services`. Android removes unknown services from that setting on its next recalculation, so the bridge treats the appearance as a one-shot signal: it removes the entry, enables its own `WindowsAccessibilityService`, and remembers the boot count so the service stays on only for that boot.

In practice the host fetches the tree but never passes it on to Narrator, on stock WSA as well; Narrator reads only the window title, and the host itself suggests the in-guest screen reader. The working path for screen-reader users is TalkBack, which the WSA "Screen reader" switch and `Win+Ctrl+T` enable through `Settings`/`SystemApp`, speaking with Windows voices through `TextToSpeech`.

## Channels served by Microsoft components

These are handled by components WSA-Reborn keeps; the notes are what was observed with them on Android 14+.

- **`Settings`, `Telemetry`, `Lifetime`** (`SystemApp`): host messages (`SyncAll`, `DeviceMetadata`, `DateTime`; `DropBox`, `AppUsage`; `AndroidWakeState`) are accepted without errors. `SystemApp` finishes initialization only if it can register its `IWsaNotifications` service, which needs a SELinux service type in our policy; without it the geocoder and the `Lifetime` heartbeat wait forever.
- **`PermissionUsage`** (`SystemApp`): subscribes to AppOps active/noted callbacks for location, camera and microphone; the host names the Android app in the Windows privacy indicator. On 14+ it requires the AppOps compatibility patch ([patches.md](patches.md)).
- **`Geocode`** (`SystemApp`): the host hands out an Azure Maps key that the service now rejects. The bridge's `OsmGeocodeService` is a separate `GeocodeProvider` with a higher service version, so the platform picks it over Microsoft's; with GApps, Google's provider wins.
- **`SysPrep`** (`sysprep_service`): the host sends `StartPhase1`, the service sets `vendor.sysprep.start_phase=1`, vendor init proceeds and the service replies `Phase1Started`. The guest must not set that property itself: doing so made the service answer before its channel existed and crash every five seconds while the host reconnected.
- **`Composition`, `Camera`, audio channels, `Geolocation`, `UserInputNative`** (vendor HALs): untouched and working as on stock.

## Not implemented

- **`Wns`**: carries Windows push notifications exclusively for Amazon Device Messaging (`com.amazon.device.messaging`). With Amazon Appstore discontinued there is nothing to deliver to.
- **`Amazon`**: Appstore install/boot events; same reason.
- **`Intents`** `Shortcut`, `Install`, `AddCalendarEvent`, `AddWifiNetwork`.

## Debugging a channel

- guest side: `adb logcat | grep WsaShellBridge` (each channel logs under its own `WsaShellBridge.<Channel>` tag), or better the host-side logcat file for anything during boot ([architecture.md](architecture.md#diagnostics));
- host side: the `WsaClient-*.etl` ETW traces record what the host did with each message (window creation HRESULTs, media flyout errors, notification activations); decode with `tracerpt.exe`;
- schemas: `bin/proto-from-wsaclient.py <WsaClient.exe> <Channel>.proto` prints the host's schema for comparison with `WsaShellBridge/proto/`;
- test apps for each channel are in `testapps/` (`ClipTest`, `NotifTest`, `FileTest`, `MediaTest`, `TtsTest`, `GeoTest`, …).
