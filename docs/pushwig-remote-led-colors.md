# Push 3 remote-slot button LEDs

Controller-only proof authorized and physically verified September 10, 2026. This changes the **physical upper display buttons immediately beneath the encoders**, not the on-screen parameter slots. The maintainer confirmed recognizable remote-color correspondence and normal operation in the exercised contexts. The change remains unmerged, for review.

## Behavior

Only on Push 3 and only while the active mode is `DEVICE_PARAMS`, `DeviceParamsMode.getButtonColor` supplies the existing upper-row LEDs with remote-slot colors. Device/page selection on the lower row is unchanged. Button actions are unchanged: this deliberately replaces the upper row's previous function-state color feedback in this mode.

| Encoder / remote slot | Color family | Existing Push palette index |
| --- | --- | --- |
| 1 | Red | 5 |
| 2 | Orange | 9 |
| 3 | Yellow | 13 |
| 4 | Lime | 17 |
| 5 | Green | 25 |
| 6 | Blue | 37 |
| 7 | Purple | 48 |
| 8 | Pink | 56 |

An unassigned remote or missing device gives an off LED. A reassigned remote keeps its encoder-slot color; it is not a permanent color for a parameter named Speed or Pitch. This corresponds to the existing hardware group indices 0–7 set by Push setup. It is an explicit fixed presentation palette, **not a readback of an editable Bitwig palette or an exact RGB/perceptual equality claim**.

The active mode manager continues to own all light routing. Track, Volume, Device Chains and temporary Master keep their existing colors; returning to Device Parameters restores the remote palette. Push 1/2 retain their existing behavior. There is no Sampler-only identity gate: the proof applies to the current device's remote page in Device Parameters mode, not to every mode merely because a device is selected.

The existing remote-bank `doesExist` state controls assignment presence. No new native-parameter identity or atomic hardware-target snapshot is claimed. Manual hardware-binding overrides beyond the current remote page have not been qualified.

No changes to encoder binding, button actions, conductive touch, screen rendering, MIDI routing, display USB, ingress, capability files or audio. No new setting, observer, sender, queue or thread. The eight-entry integer palette is initialized once; the added color-return path allocates no per-query objects and performs no I/O.

## Verification

Base: accepted `pushwig/main` commit `997158b0a4ddd932a0a985c8b74ffff1e631120f`, tree `dbf3dc8d4e6d6b95a654088f85b8a183584063b7`.

The focused test runs production `DeviceParamsMode`, `BankParameterProvider`, `ModeManager`, `VolumeMode`, `TrackMode`, `DeviceChainsMode` and `MasterMode` with fake DAW/hardware endpoints. It tests all eight colors, valid palette entries, empty/missing assignments, page-observer rebinding, existing button actions, unchanged lower-row device/page colors, repeated mode round trips, temporary Master restoration, and Push 1/2 regressions. The 190 passing assertions include repeated stability checks; they are not 190 distinct physical test cases. The same test against the unmodified accepted package fails at the new eight-color expectation, as expected.

Targeted iteration, using an existing accepted or current package:

```sh
sh scripts/test-pushwig-remote-led-colors.sh /path/to/DrivenByMoss-26.4.1.jar
```

One final package build plus the existing settings, rendezvous and receiver/display-lifecycle suites:

```sh
env JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home \
  PATH=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home/bin:/opt/homebrew/bin:/usr/bin:/bin:/usr/sbin:/sbin \
  sh scripts/test-pushwig-external-ingress-activation.sh
sh scripts/test-pushwig-remote-led-colors.sh
```

September 10 results: OpenJDK/Javac 21.0.11, Maven 3.9.16; final package and all four suites pass. `git diff --check` passes. The package `target/DrivenByMoss-26.4.1.jar` is 14,402,949 bytes, SHA-256 `c2e1355b0f10772ad5ab246cbd1fd6c546c41fc2df71a7885a34ab026b76af40`; it is the candidate extension archive, not a committed artifact.

An extracted full payload comparison with the retained accepted V5A package (SHA-256 `ea69daa18a41011105c8228035dd377964ca05f5cf37196f0629fc70824050e6`, 14,402,726 bytes) finds exactly one different entry: `DeviceParamsMode.class`. No entries were added or removed. All other classes/resources, including the manifest, are byte-identical. `PushUsbDisplay.class` remains SHA-256 `288b576b3f2ed064f8d9a0c6f6d384fb3516a0858cc22e7879bee896df83dec3`. Source diff confirms the screen and action method bodies are unchanged within the one changed class. Comparison commands: `unzip -qq`, `diff -qr`, `shasum -a 256`; bytecode inspected with Java 21 `javap -c -p`.

These deterministic checks do not establish physical LED hue/brightness, human control/audio acceptance, or native Bitwig behavior for every reassignment route. The separate focused physical observations follow.

## Focused physical result and official recovery

The tested source head was `6e8e15f463977ad980befe1bd5fb0d3e12ba2d13`, tree `6d366ced9c09654a3d2845f49a4a5641a9e19cd4`, with the parent above. The final evidence amendment changes only this document relative to that tested head; production source, test, runner and packaged artifact are unchanged. No rebuild or source change occurred during the physical session.

After normal maintainer quit, checks found no Bitwig application/audio-engine or helper process, no ingress listener, and no current manifest/capability; only dormant `owner.lock` remained. Exactly one scanned DrivenByMoss extension had the official SHA-256 below. That original file was moved intact outside all scan paths and verified before installing the exact candidate archive as `DrivenByMoss.bwextension`. The installed hash matched the candidate above. Unrelated extensions were untouched.

Candidate and official recovery launches both used ordinary `open -a 'Bitwig Studio'`, with `JAVA_TOOL_OPTIONS`, `JDK_JAVA_OPTIONS` and `_JAVA_OPTIONS` absent. No producer, capture helper, permission change, new observer or preference change was involved. The ingress remained inactive. Open-file readback confirmed Bitwig loaded the expected archive on each launch; this supports artifact custody, not physical acceptance.

Direct maintainer observations:

- On Sampler's Device Parameters page, the eight upper physical LEDs corresponded recognizably to Bitwig's red/orange/yellow/lime/green/blue/purple/pink remote slots. The maintainer reported that it worked and was especially helpful. The ordinary parameter screen remained unchanged.
- Track/Mix to Device Parameters round trips restored the appropriate contextual LED colors.
- Conductive Master touch/release showed the existing temporary mixer feedback and then restored the remote palette.
- The grouped check of knobs, button actions, pads/pressure, transport and Push headphone audio passed. The maintainer confirmed all requested groups and appropriate colors in the other exercised contexts.
- Bitwig quit normally. The candidate was moved intact outside scan paths, and the original official artifact was moved back to the canonical filename. Exactly one DrivenByMoss extension remained scanned.
- Ordinary official relaunch restored the usual display/button colors, controls and Push headphone audio. The maintainer confirmed recovery and normal quit. Final independent readback found no Bitwig/Pushwig process, no TCP 45291 listener, no current manifest/capability, and only the intentional dormant lock.

Restored official SHA-256: `98dc3195ad8d911526e18b1005f09f69a1aedcb965b080565474104654345c5a`, recomputed after the final quit. Tested candidate SHA-256 remained `c2e1355b0f10772ad5ab246cbd1fd6c546c41fc2df71a7885a34ab026b76af40`. Custody/process commands included `shasum -a 256`, `ps`, `lsof`, and directory enumeration; no raw logs, screenshots, user projects or extension binaries are committed.

This qualifies the focused Push 3 LED change and exercised mode/control/audio paths, not every controller mode or mapping route. Empty/reassigned slots and Push 1/2 retain deterministic coverage only. No calibrated color equality, live palette readback, new screen behavior or device localization is claimed.
