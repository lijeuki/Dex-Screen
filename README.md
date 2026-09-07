# DexScreen Receiver

<img src="media/icon.png" alt="DexScreen Receiver icon" width="120">

An experimental, **non-root** Wi-Fi Display (Miracast) sink for Android, built to let a spare Android
tablet act as a wireless display for Samsung's **Wireless DeX**. No Magisk module, no hidden/non-SDK
APIs, no platform signing keys — just [Shizuku](https://github.com/RikkaApps/Shizuku) brokering a
shell-UID `WifiP2pManager`/RTSP/RTP session, and public `MediaCodec` doing the AVC decode.

## Status

Tested and verified working, on real hardware:

- **Sink**: Redmi Pad SE (Android 15 / HyperOS)
- **Source**: Samsung Galaxy S26 (Wireless DeX)

P2P pairing, RTSP capability negotiation, and live AVC video (up to 1920×1080p30) all confirmed
end-to-end — a real Samsung DeX desktop renders on the tablet, not a mirrored phone screen. This is
still experimental: audio is explicitly unsupported (advertised as `none`), and there's no packet-loss
recovery beyond a best-effort IDR-refresh request, so lossy Wi-Fi can still show brief visual
corruption during fast motion.

## Why non-root

Most Android-as-a-Miracast-sink projects (including the one this one owes its start to, below) use
root or a Magisk module to reach the privileged APIs a Wi-Fi Display sink normally needs. This project
takes the harder, more constrained path instead: a [Shizuku](https://github.com/RikkaApps/Shizuku)
`UserService` running at shell UID (2000), with all Wi-Fi P2P and RTSP/RTP/decode work done through
regular public Android APIs from there. No hidden API exemptions, no root, nothing that requires
unlocking the bootloader.

## Acknowledgments

Shout out to **[FoxLost/universal-miracast-sink](https://github.com/FoxLost/universal-miracast-sink)**
— a system-level, Magisk-based Miracast sink for Android 10+, and the project that inspired this one.
No code from it was copied here (this repo's receiver/negotiation code is an original implementation),
but its write-up of the WFD/RTSP/P2P plumbing was a valuable reference while exploring whether the same
result was reachable without root.

Also built on [Shizuku](https://github.com/RikkaApps/Shizuku) (MIT-licensed) for the non-root
shell-privilege bridge.

## How it works

1. A Shizuku `UserService` (shell UID 2000) configures `WifiP2pManager` as a Wi-Fi Display primary sink
   and starts P2P discovery/listening — standard Android pairing dialogs stay in control throughout.
2. Once a phone connects and forms a P2P group, the app opens an RTSP control connection to the source
   and negotiates capabilities (video-only, AVC baseline, up to 1080p30).
3. RTP/MPEG-TS video arrives over UDP, gets demuxed into AVC access units, and is decoded with the
   public `MediaCodec` API straight to a `Surface`.

See `probe/app/src/main/java/local/dexprobe/` for the actual implementation — `P2pSession` for the P2P
side, `receiver/ReceiverEngine` for RTSP/RTP/decode.

## Building

Requires Android SDK 35, build-tools 35.0.0, and JDK 17.

```powershell
probe/build.ps1
```

This builds, signs, and verifies a debug APK. Install it with `adb install`, authorize it with Shizuku
(via `adb` wireless debugging or the Shizuku app), then open it and tap **Start receiving**.

## License

MIT — see [LICENSE](LICENSE).
