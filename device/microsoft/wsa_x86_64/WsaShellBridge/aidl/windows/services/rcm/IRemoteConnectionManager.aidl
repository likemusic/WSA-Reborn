// Recovered from the windows.services.rcm.IRemoteConnectionManager$Stub stub compiled into
// SystemUIApp.apk. Stable AIDL, VERSION = 1, HASH = 3d32e9ac06b42f2072c4eae5b628f45050ce07ba.
// Service: ServiceManager.waitForDeclaredService("windows.services.rcm.IRemoteConnectionManager/default")
// — implemented by /vendor/bin/hw/windows.services.rcm@1.0-service (rcmsvc), see docs/wsa-shell-protocol.md.
package windows.services.rcm;

interface IRemoteConnectionManager {
    // Open a named channel to the host (WsaClient.exe). Returns an fd (vsock) carrying
    // ApiMessageHeader frames (4-byte LE length + protobuf). Channel names — ChannelNames:
    // "Shell", "Apps", "Clipboard", "FileSystem", "Intents", "Notification", "Settings", ...
    ParcelFileDescriptor createChannel(String channelName);  // TRANSACTION 1
}
