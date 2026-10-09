# Platform 21 independent cluster task (experimental)

Adaptation author and source vehicle tester: 寒叙 (@Hanxu4131),
https://github.com/Hanxu4131/BYD-CarPlay. The source adaptation was field-tested on a
2023 BYD Tang DM-i Champion Edition / platform-controller 21. This smaller upstream
integration has not been installed or tested on that vehicle; model year alone is
not an enablement condition or a compatibility guarantee.

Enable **Platform 21 cluster task (experimental)** in the instrument-map settings
while parked, after authorizing local ADB with the existing explicit check. It is
off by default and mutually exclusive with the DiLink 4 direct-task switch.
Turning either direct-task switch on closes the previous task and reconnects the
cluster stream. The general dashboard-map switch must remain enabled.

The route accepts only a unique nonzero logical display whose base record names
`fission_bg_xdjaVirtualSurface`, reports 1920 × 720 and is owned by
`com.xdja.containerservice` (uid 1000). It does not assume display 1, accept an
override/layer-stack number, create a missing display or fall back to the centre
screen. Known DiLink 5/5.1 presentations keep priority. Each independent Activity
checks its task placement through read-only AMS history before admitting a surface;
UUID admission and the existing generation checks reject stale launches.

The route reuses the existing manifest-declared `AdbClusterActivity`, independent
task flags, stream 111 decoder owner and stream safe-area/turn-card settings. It
adds no Activity or permission. The default video/waiting region uses the source
adaptation's centred, half-width 8:3 layout on a transparent canvas. No proprietary
artwork, third-party camera code or runtime authentication files are included.

Failed launches retry quietly every five seconds while the connection owner exists.
A checked target task alone is not output confirmation: the legacy retry stops only
after the current valid texture receives a frame during the active cluster stream.
Disabling the route or disconnecting invalidates pending work. Once a frame was
presented, destruction does not repeatedly reclaim another instrument window.
The explicit **Open cluster window** button starts a fresh recovery attempt when
the user wants that window back.
Texture delivery does not prove physical visibility or preservation of every native
vehicle readout; inspect these while parked on the target firmware.

This route neither acquires the DiLink 4 stock-map hold nor issues DiLink 3 projection
mode commands. Existing recovery journals still retain their normal restoration
behavior. It cannot establish a projection mode that the firmware has not already
made available. No claim is made for cold-start recovery on every firmware.

The source adaptation's separate map/guidance-region editors, L1Mini ordering and
wake recovery, camera coexistence, OEM song publication and vehicle-data addresses
are not part of this patch. The reused upstream safe-area editor is not the complete
source region editor. Those modules require separate scoped review and vehicle tests.

## Local validation

Initial port validation checked 1,489 shared/common unit cases: 1,488 passed, zero
failures/errors and one existing macOS wildcard-bind assumption skip. Six new
cases cover opt-in/mutual exclusion, existing-display priority, transparent-region
geometry, and current-owner/active-output admission plus stop invalidation.

After tightening the disappeared-task guard and preserving explicit manual reopen,
the final source passed all 21 focused routing/selection/output/texture/layout cases,
shared/common/mobile compilation and mobile debug lint (zero errors/fatal findings,
18 existing warnings). A fresh in-process compiler avoided an environment failure
reading the SDK's memory-mapped lambda-stubs JAR. APK packaging was not completed.
These checks do not represent an installation or physical vehicle test.
