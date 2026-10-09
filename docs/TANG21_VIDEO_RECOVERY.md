# Video backlog recovery contribution

Adaptation author and vehicle tester: 寒叙 (@Hanxu4131). Source: the independent BYD CarPlay adaptation and DiPlay issue #159.

This patch changes recovery already present in DiPlay. A single delayed frame no longer forces a decoder rebuild. Growing backlog is observed for 750 ms, with a 1.5-second hard bound. Loss of a reference chain discards only its queued frames and preserves the next configuration or Surface change. A keyframe made obsolete by slow codec startup is discarded while keeping the newly created codec and requesting a fresh keyframe. Copied PCM releases its MediaCodec output slot before a potentially blocking AudioTrack write.

The source implementation was installed and observed on a 2023 BYD Tang DM-i Champion Edition / controller-platform 21, including simultaneous navigation, music, instrument projection, Dudu, L1Mini and driving recording. The remaining receive stalls were not solved: the observed comparison used different driving scenes and loads. Do not describe this as a complete stutter fix, or as a Smart Guardian fix. This port to current upstream has not had a separate vehicle acceptance test.

No network configuration, Wi-Fi credentials, vehicle identifiers, runtime authentication assets, third-party packages or private logs are included. DiPlay branding and application ID are unchanged.
