# Integration scope and review

carlito | Upstream 6ebb5821 and the user-verified KX11 branch 3c96bff6 are retained as ancestors.

Speech detection uses decoded compatibility/phone/assistant/ringtone streams, with a per-track playback tail, not SETUP or silence thresholds. Navigation remains an overlay. The music renderer is suppressed immediately and the matching controller sends explicit pause/play. Manual commands, metadata changes, detach and session replacement cancel resumption. This cannot identify WeChat when iOS sends it mixed into the same music PCM stream; application-specific behavior still requires vehicle/iPhone feedback.

Passive log/broadcast media bindings never claim OEM consumption. Genuine media-session UI commands remain available. FX11 requires the separately signed closed bridge with XUI preference when available; DiPlay alone cannot block an OEM dispatcher which ignores OneOS interception.

Upstream microphone encoder selection, software codec sources, ambient sink lifecycle, video recovery, dashboard and settings updates are preserved. The older binary Concentus dependency is removed to avoid duplicate classes with upstream's source copy. The media output route does not determine the microphone input.

No unit tests were added or run during this integration. Compilation and self-review are recorded with the release artifact. Hardware validation of the new speech and FX11 changes remains pending.

The newly supplied FX11_LOW report (DiPlay-20261009-082521-463.txt, app 0.2.14) states that disabling the stock music application restores wheel behavior. It already shows ECARX_XUI and callbacks for 200085/200087/200088, so XUI preference alone cannot establish a fix for that device. Later audio sessions have focus=false. The report has no stock-app dispatch trace and no independent compatibility voice stream; neither OEM key consumption nor WeChat interruption can be verified from it. Keep the stock application enabled during validation; compare audio-focus enabled and disabled without altering the connection path. Do not present callback receipt as proof that every OEM consumer was blocked.
