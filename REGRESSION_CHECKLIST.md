# TachoWatch regression checklist

This file defines behaviour that must remain intact when fixing unrelated bugs. The current `main` branch is always the implementation baseline. Do not restore whole files or large code blocks from older builds; old revisions may only be consulted to understand a specific behaviour.

## Mandatory pre-build checks

- History: never reduce the visible history window as a side effect of another change. Minimum accepted window is 56 days; prefer all history available from the card/local archive.
- Weekly-rest compensation: a reduced weekly rest remains permanently visible in history. After compensation is paid, keep the original debt and due date and show the payment as completed with its payment date; never erase the historical debt record.
- Continuous driving: keep the currently validated F923 behaviour and 45-minute reset semantics unless the task explicitly concerns this counter.
- Shift driving: sum every DRIVING segment in the confirmed current shift. There is no two-segment limit. A 45-minute break must not reset shift driving. A confirmed new daily-rest boundary must prevent any previous-shift segment from entering the new shift.
- Continuous work: DRIVING + OTHER WORK only. AVAILABILITY is excluded. Activity changes must not reset the counter. Apply only the approved qualifying-break reset logic.
- Other work and availability: values from a previous confirmed shift must never leak into a new shift.
- Source reconciliation: card history establishes completed historical segments and shift boundaries; live DTCO supplies the current unfinished segment. Persisted preferences are recovery cache only and must not override fresher card/live state.
- Alerts: each threshold warning fires once per driving/work cycle. Completing the qualifying break closes the old cycle and cancels its pending/repeating alerts before a new cycle starts.
- Background operation: preserve foreground monitoring, automatic Bluetooth reconnect, state persistence, process-death recovery, and reboot recovery.
- UI: do not change the approved main-screen layout or history presentation unless the requested task explicitly requires it.
- Keep-screen-on control must remain available.

## Change discipline

Before a build, compare the final diff with the previous `main`. Every changed file and every behavioural change must be attributable to the current task. If an unrelated feature changes, stop and correct the diff before building.

For counter fixes, verify at minimum: app restart, Android process death/restart, card read/reconciliation, new shift after daily rest, multiple driving segments, 15+30/45-minute break, and Bluetooth disconnect/reconnect.
