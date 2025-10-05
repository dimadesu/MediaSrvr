# MediaSrvr - RTMP Media Server Android App

Run RTMP media server as an Android app.

Intended usage is mainly with [LifeStreamer](https://github.com/dimadesu/LifeStreamer) app, but can be used for anything you want really.

![MediaSrvr Screenshot](docs/MediaSrvr-screenshot.png)

## Apps that can work together

- [MediaSrvr](https://github.com/dimadesu/MediaSrvr) - Runs RTMP server on Android phone. You can publish RTMP stream to it from an action camera, for example.
- [LifeStreamer](https://github.com/dimadesu/LifeStreamer) - Can use RTMP as source: playback RTMP stream from server and restream it as SRT with great dynamic bitrate.
- [Bond Bunny](https://github.com/dimadesu/bond-bunny) - You can use LifeStreamer to publish SRT stream into Bond Bunny app. Bond Bunny accepts SRT as input and forwards packets to SRTLA server like Belabox Cloud. Uses multiple networks to improve stream quality.

Discord server: https://discord.gg/2UzEkU2AJW

Uses:

- [Node Media Server v2](https://github.com/illuspas/Node-Media-Server/tree/v2).
- Runs NMS using [Node.js mobile](https://github.com/nodejs-mobile/nodejs-mobile).
- Project is based on [Node.js mobile sample app](https://github.com/nodejs-mobile/nodejs-mobile-samples/tree/master/android/native-gradle-node-folder) - needed a lot of updates.

## How to Install

I am releasing APK files via [GitHub Releases](https://github.com/dimadesu/MediaSrvr/releases).

- Open [GitHub Releases page](https://github.com/dimadesu/MediaSrvr/releases) on your Android device.
- Download APK file.
- Install.
- Enjoy!

## How to Use

### Stop/start

RTMP server starts automatically when the app is opened.

To stop the server tap the button in the notification.

### Publish and play streams on the same same device

To publish from a streaming app on this device to the server on the same device use this URL:

```
rtmp://localhost:1935/publish/live
```

Play streams on this device using the same URL. Use an app like VLC player.

### Publish stream from the other device to this device

For example, to publish stream from an action camera to the server on your phone:

- Phone and action camera have to be on the same Wi-Fi.
- You can create Wi-Fi hotspot with your phone.
- This app builds RTMP URLs for each network that this device is on.
- Configure action camera to connect to the Wi-Fi hotspot and publish to the RTMP URL that is not `localhost`.
