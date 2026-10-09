# DiPlay 0.2.16 — 2026-10-09

Public preview for compatible BYD Android head units. Android 7.1+ (API 25) remains the minimum; Android 7.1–8.1 still needs vehicle testing. This update focuses on calls and Siri on older head units, wired and wireless connection recovery, lower-latency video options, Settings and dashboard placement. USB and wireless remain available, subject to the head unit's firmware and hardware.

## Highlights

- Siri and wireless calls now send the microphone on head units whose Android has no Opus encoder (Android 7.1–9). DiPlay falls back to a bundled software Opus encoder ([Concentus](https://github.com/lostromb/concentus)) and only offers Opus when it can encode it. Accepted on a BOS Mini A1 head unit (Android 9, MediaTek) with an iPhone 12 on iOS 27. Related: [#415](https://github.com/shihabal3amri/DiPlay/issues/415). [#468](https://github.com/shihabal3amri/DiPlay/pull/468), [#483](https://github.com/shihabal3amri/DiPlay/pull/483)
- Wired CarPlay fixes for NCM receive framing and Android 8 USB reads, with smaller-read retries when a head unit rejects a large USB read. [#478](https://github.com/shihabal3amri/DiPlay/pull/478), [#495](https://github.com/shihabal3amri/DiPlay/pull/495)
- Wireless recovery refreshes hotspot addresses after a first-connection timeout, DiPlay requests Android 17's local-network permission, and missing VPN authorization screens no longer crash DiPlay. Car-hotspot setup adds WPA3 and WPA3 transition security. [#474](https://github.com/shihabal3amri/DiPlay/pull/474), [#465](https://github.com/shihabal3amri/DiPlay/pull/465), [#517](https://github.com/shihabal3amri/DiPlay/pull/517)
- New experimental video options: Low-latency decoding and Direct video output, plus an FPS counter in Diagnostics. All are off by default. [#496](https://github.com/shihabal3amri/DiPlay/pull/496)
- Settings search is an inline box, the in-session menu asks before discarding staged changes, and the CarPlay swipe-down gesture can be turned off. [#450](https://github.com/shihabal3amri/DiPlay/pull/450), [#500](https://github.com/shihabal3amri/DiPlay/pull/500), [#487](https://github.com/shihabal3amri/DiPlay/pull/487)
- Dashboard placement uses 1% sliders for the car marker, including DiLink 4 and the ADB cluster route, and the custom turn card gets its own small-window placement. [#494](https://github.com/shihabal3amri/DiPlay/pull/494), [#493](https://github.com/shihabal3amri/DiPlay/pull/493)

## Siri, calls and audio

- The diagnostic report names the microphone encoder for each microphone stream. [#468](https://github.com/shihabal3amri/DiPlay/pull/468)
- The experimental call echo canceller keeps its playback reference contiguous and suppresses noise after cancelling instead of before it. It is off by default and not yet accepted in a car. [#421](https://github.com/shihabal3amri/DiPlay/pull/421)
- Experimental Buffered music paces its intake after the initial fill to reduce contention with realtime audio and video. [#484](https://github.com/shihabal3amri/DiPlay/pull/484)
- Diagnostic exports include communication-mode transitions and dropped audio-focus callbacks, with failure-safe reporting. [#499](https://github.com/shihabal3amri/DiPlay/pull/499)
- The reconnect bar appears when Auto yield changes, since the change applies at the next connection. [#482](https://github.com/shihabal3amri/DiPlay/pull/482)

## Connection setup and recovery

- Wired NCM receive framing accepts an optional short-packet pad when a transfer block ends on a USB packet boundary; Android 8 read requests are capped before Android rejects an oversized queue. Rejected large USB reads retry at smaller sizes down to 2 KiB; malformed framing stays fatal. [#478](https://github.com/shihabal3amri/DiPlay/pull/478), [#495](https://github.com/shihabal3amri/DiPlay/pull/495)
- After a first AirPlay connection timeout, the next attempt tries the hotspot's other address family and waits briefly for a preferred IPv6 address. [#474](https://github.com/shihabal3amri/DiPlay/pull/474)
- Wireless CarPlay requests Android 17's local-network permission. [#465](https://github.com/shihabal3amri/DiPlay/pull/465)
- Missing or blocked VPN authorization screens are handled without crashing; firmware that cannot grant VPN authorization still needs a compatible connection path. Car-hotspot setup offers WPA2, WPA3 transition and WPA3, keeps the chosen security when credentials are edited, and explains that hotspot details are sent over Bluetooth, so the iPhone does not need to join manually. Identification-rejection reports include unsupported message IDs for older-iPhone investigation; this is not a confirmed compatibility repair. [#517](https://github.com/shihabal3amri/DiPlay/pull/517)
- A healthy session stays connected when returning to an unchanged window or cancelling unchanged in-session display settings. [#501](https://github.com/shihabal3amri/DiPlay/pull/501)
- The DiLink 3 Wi-Fi scan pause can no longer outlive a disconnect that races wireless startup.

## Video and display

- **Low-latency decoding (experimental)** asks Qualcomm decoders to output each frame as soon as it is decoded and releases frames from a dedicated thread; **Direct video output (experimental)** sends the CarPlay picture straight to the head unit's compositor instead of drawing it inside the DiPlay window; picture adjustments do not apply. Both are under Advanced and off by default; turn them off if the picture shows artifacts or freezes. The **FPS counter** in Diagnostics shows shown and received frames per second and the decode time. [#496](https://github.com/shihabal3amri/DiPlay/pull/496)
- With Smooth video, the main-screen H.264 decoder tries a hardware low-latency decoder first and asks Qualcomm decoders for decode-order output. [#456](https://github.com/shihabal3amri/DiPlay/pull/456)
- Video backlog recovery keeps short delayed bursts instead of rebuilding the decoder after 250 ms: a backlog that keeps growing recovers after a short grace period, and one 1.5 s behind recovers at once. Decoded audio is copied out of the decoder and its buffer returned before the blocking audio write. This changes the default for every head unit and needs wider in-car feedback. [#347](https://github.com/shihabal3amri/DiPlay/pull/347)
- Each display declares automatic UI and map appearance when CarPlay connects, before the runtime day/night updates. The contributor's original version was tested on a 2023 Tang DM-i; this port awaits in-car acceptance. [#346](https://github.com/shihabal3amri/DiPlay/pull/346)
- The side panel has three sizes set by dragging its edge, closes when dragged past five sixths of the screen, and opens from a pull tab on CarPlay's passenger edge; taps, scrolls and long presses on the tab still reach CarPlay. On Android 12 and later it blurs while resizing, and on BYD it shows tyre pressure and temperature through read-only ADB. [#480](https://github.com/shihabal3amri/DiPlay/pull/480)

## Settings

- The Settings header has an inline search box: matches drop down as you type, Enter opens the top match, and Back clears an open search. [#450](https://github.com/shihabal3amri/DiPlay/pull/450)
- The Back gesture and the full-settings link in the in-session menu ask before discarding staged changes. [#500](https://github.com/shihabal3amri/DiPlay/pull/500)
- The CarPlay swipe-down quick-menu gesture can be set to Off under **Settings → Vehicle → CarPlay controls** or in the quick menu. [#487](https://github.com/shihabal3amri/DiPlay/pull/487)
- Settings spacing, button alignment and the in-session menu width are refined; Diagnostics and Advanced appear as Overview category rows. [#486](https://github.com/shihabal3amri/DiPlay/pull/486)

## Dashboard, navigation and vehicle

- The car marker uses 1% sliders in full-screen and small-window layouts, including DiLink 4 and the ADB cluster route. Earlier step positions migrate, and changes apply at the next connection. [#494](https://github.com/shihabal3amri/DiPlay/pull/494)
- The custom turn card has its own small-window size, position, opacity and day/night choice, with a placement sketch. A full-screen card position the driver already set keeps applying in the small window until the small-window card is adjusted. [#493](https://github.com/shihabal3amri/DiPlay/pull/493)
- The cluster's waiting screen follows DiPlay's theme with a small spinner. [#481](https://github.com/shihabal3amri/DiPlay/pull/481)
- The DiLink 3 simple-navigation cluster receives the arrival time in AMap's form so the whole time can show. Not yet confirmed in a car. [#458](https://github.com/shihabal3amri/DiPlay/pull/458)
- An experimental Platform 21 instrument task route for the 2023 Tang DM-i, off by default. [#348](https://github.com/shihabal3amri/DiPlay/pull/348)
- Experimental wheel-key volume for spoken navigation guidance, off by default. [#344](https://github.com/shihabal3amri/DiPlay/pull/344)
- An optional delayed pause of the car Bluetooth during CarPlay (needs ADB, off by default). While it is paused, CarPlay calls use the cabin speaker and microphone. Bluetooth turns back on when CarPlay ends or disconnects, when DiPlay reopens after an interruption, and before the next wireless handshake. Pending in-car acceptance. [#307](https://github.com/shihabal3amri/DiPlay/pull/307)
- Experimental music-following ambient lighting for compatible BYD interior lamps (needs ADB, off by default). Turning it off restores the lamp settings it changed. Pending in-car acceptance. [#345](https://github.com/shihabal3amri/DiPlay/pull/345)

## Development

- Settings layout rules and their test guards. [#490](https://github.com/shihabal3amri/DiPlay/pull/490), [#491](https://github.com/shihabal3amri/DiPlay/pull/491)
- Connection reports now use a form that requires diagnostic logs. [#459](https://github.com/shihabal3amri/DiPlay/pull/459)

## Updating and reporting problems

Install the official APK over the previous public release to preserve settings and pairing records. The package remains `com.shihab.diplay`; release packaging verifies the existing signing certificate. Source builds omit runtime authentication assets by default; see [building from source](BUILD.md).

Reproduce remaining problems on **0.2.16**, then open **Settings → Diagnostics → Save diagnostic report**. Android 10+ normally saves to **Downloads/DiPlay**; Android 7.1–9 uses a document picker. If it is unavailable, follow the save confirmation and use **View report** or **Share**. Review the `.txt` and attach it to a matching [existing issue](https://github.com/shihabal3amri/DiPlay/issues) or [new issue](https://github.com/shihabal3amri/DiPlay/issues/new/choose). Connection reports require the diagnostic log. Reports are not uploaded automatically.

Include car/head unit, Android/DiLink and full firmware, iPhone/iOS, USB or wireless mode, relevant settings, reproduction steps and failure time.

See [validation](VALIDATION.md) for automated checks. No new maintainer vehicle test of the complete release is claimed. The video backlog policy change and the experimental vehicle options above need current in-car feedback; test optional features while parked. The background update check (#488) is not included in this release.

Thanks to the contributors linked in the 32 pull requests above, and to the users supplying diagnostic reports and vehicle feedback.
