# MediaTest — a test app for the "SystemMediaControls" channel

A silent "player": a media session with three tracks (title, artist, cover art on two of the three), the state changes honestly on commands. Every command that reaches the session goes to `logcat` as a `SESSION COMMAND:` line; this shows that a Windows media panel button or a media key reached the app.

```bash
bin/build-testapp.sh testapps/MediaTest --install
adb logcat -s MediaTest WsaShellBridge.Media

adb shell am start-foreground-service -n org.wsalineage.mediatest/.PlayerService              # start, track 1, playing
adb shell am start-foreground-service -n org.wsalineage.mediatest/.PlayerService -e cmd next  # next track
# also: -e cmd pause | play | prev | stop | noart | release
```

`noart` removes the cover art from the current track, `release` closes the session — the player should disappear from the Windows media panel. The cover is deliberately 800×800: the bridge scales it down to 512.
