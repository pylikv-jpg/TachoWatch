# TachoWatch regression checklist

This file defines behaviour that must remain intact when fixing unrelated bugs. The current `main` branch is always the implementation baseline. Do not restore whole files or large code blocks from older builds; old revisions may only be consulted to understand a specific behaviour.

## Mandatory pre-build checks

- History: decode and show the complete card activity ring buffer from oldest to newest. Do not impose 21-day, 56-day, or other artificial display/decoder caps.
- Weekly-rest compensation: a reduced weekly rest remains permanently visible in history. After compensation is paid, keep the original debt and due date and show the payment as completed with its payment date; never erase the historical debt record.
- Continuous driving: keep the currently validated F923 behaviour and 45-minute reset semantics unless the task explicitly concerns this counter.
- Shift driving: sum every DRIVING segment in the confirmed current shift. There is no two-segment limit. A 45-minute break must not reset shift driving. A confirmed new daily-rest boundary must prevent any previous-shift segment from entering the new shift.
- Continuous work: DRIVING + OTHER WORK only. AVAILABILITY is excluded. Activity changes must not reset the counter. Apply only the approved qualifying-break reset logic.
- Continuous-work sources: use F923 for the driving contribution and F927 only for OTHER WORK. Never add F927 as a driving delta on an activity transition; this prevents false jumps when returning from OTHER WORK to DRIVING.
- Daily rest: display only uninterrupted current REST duration from F927. Never reuse F925 cumulative break credit (including a retained 15-minute break) as daily-rest time.
- Other work and availability: values from a previous confirmed shift must never leak into a new shift. A fresh card read performed during a confirmed >=9h daily rest must seed the next shift at zero even when the rest crosses midnight.
- Source reconciliation: card history establishes completed historical segments and shift boundaries; live DTCO supplies the current unfinished segment. Persisted preferences are recovery cache only and must not override fresher card/live state. Every Bluetooth reconnect starts a new live-cycle epoch; only DID values present in the same completed cycle may update counters, and stale values from an earlier cycle/session must never be reused.
- Card presence: never infer card insertion/removal from daily activity record boundaries or midnight 23:59/00:00 transitions. Record removal only from the confirmed live `F923/F925/F927/F938 = FF FF` sentinel pattern; record insertion only when valid driver timers return after that state. `FF FE`/`FF FF` timer values are unavailable sentinels and must never enter counters.
- Alerts: each threshold warning fires once per driving/work cycle. Completing the qualifying break closes the old cycle and cancels its pending/repeating alerts before a new cycle starts.
- Background operation: preserve foreground monitoring, automatic Bluetooth reconnect, state persistence, process-death recovery, and reboot recovery.
- UI: do not change the approved main-screen layout or history presentation unless the requested task explicitly requires it.
- Keep-screen-on control must remain available.

## Change discipline

Before a build, compare the final diff with the previous `main`. Every changed file and every behavioural change must be attributable to the current task. If an unrelated feature changes, stop and correct the diff before building.

For counter fixes, verify at minimum: app restart, Android process death/restart, card read/reconciliation, new shift after daily rest, multiple driving segments, 15+30/45-minute break, and Bluetooth disconnect/reconnect.


### Split daily rest 3+9
- [ ] After a REST segment reaches at least 3:00 but less than 9:00, switching to OTHER WORK or DRIVING keeps the first 3:00 part credited on the dashboard.
- [ ] Reading the driver card after that first 3:00 part restores the credit instead of clearing it.
- [ ] While the second REST part is running, the dashboard keeps the first 3:00 part and shows progress toward the required 9:00 second part.
- [ ] At 9:00 of the second REST part, the dashboard identifies the result as a regular split daily rest (3:00 + 9:00), not a reduced 9:00 rest.
- [ ] A continuous 9:00 REST with no earlier completed 3:00 part remains a reduced daily rest.


### Stability / lifecycle
- [ ] Card-read recovery resume: after a successful DDD download, live BLE resumes only after reconciled counters have been reloaded from preferences.
- [ ] Background UI: leaving the main Activity does not keep live-log UI callbacks registered.
- [ ] BLE reconnect: callbacks from a superseded GATT connection cannot replace the current session.
- [ ] Android 8/9 startup: recovery FileObserver uses the API-26-compatible path constructor.
- [ ] Card download logging: full-log UI updates are throttled; final SUCCESS/FAILED status remains immediate.


### Counter edge cases
- [ ] F923 transient reset guard: unsupported F9AF/F9A6 with 200 → 0 → 200 must not double shift driving.
- [ ] Missed F923 reset recovery: a small post-break F923 value that keeps growing is accepted only after confirmation and then catches up.
- [ ] Observed 45-minute break anchors the fallback F923 cycle immediately.
- [ ] Split daily rest history: a first 3-hour part split across midnight is carried into the same shift; a continuous 9-hour rest clears that credit.


### Reproducible source build
- [ ] CI does not run Python scripts that rewrite tracked Kotlin source before compilation.
- [ ] RTO 45-minute continuous-work reset rules are checked into RtoCore.kt.
- [ ] A fresh RtoRuntime card seed resets synthesized legacy runtime state.
- [ ] DriverDashboardActivityV2 is the only registered driver dashboard.
- [ ] CI runs git diff --exit-code after the APK build so hidden source mutation fails the build.


### Logging performance
- [ ] Card-download and target-monitor logs use bounded deque buffers instead of CopyOnWriteArrayList.
- [ ] Frequent log writes do not copy the whole log buffer on every line.
- [ ] UI log snapshots are throttled while final card-download status remains immediate.


### History tab responsiveness
- [ ] Opening History does not rebuild the full history view on every tap.
- [ ] History is rebuilt only when fresh card/history data is loaded.
- [ ] Both top tabs give immediate visual touch feedback before navigation completes.
