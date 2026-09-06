# DTCO DID research map

Persistent reverse-engineering notes for TachoWatch. This file is the source of truth for discovered DTCO BLE/UDS channels and control observations. Do not promote hypotheses to confirmed without a physical DTCO/Volvo display correlation or a controlled state-change test.

## Confirmed / high-confidence DIDs

| DID | Raw/example | Decode | Meaning | Confidence / evidence |
|---|---|---|---|---|
| F923 | `00 3A` | u16 BE minutes = 58 min | Driver 1 continuous driving time | CONFIRMED: physical DTCO showed `0h58` |
| F925 | e.g. `04 23` = 1059 min; later 18:15→18:26 | u16 BE minutes | Driver 1 cumulative break/rest time | HIGH: scanner label `Driver1CumulativeBreakTime`; tracks physical rest timer |
| F927 | tracks 17:39→18:26 | u16 BE minutes | Driver 1 current duration of selected activity | HIGH: scanner label `Driver1CurrentDurationOfSelectedActivity`; matched current rest during test |
| F938 | `12 F0` | u16 BE minutes = 4848 min = 80:48 | Driver 1 cumulative driving, previous + current week | CONFIRMED: physical Volvo/DTCO showed `80:48 / 90:00` |
| F902 | `00` while parked | enum/value | Tachograph vehicle speed | HIGH / named DID |
| F903 | `00` during rest | enum/flags TBD | Driver 1 current working state | HIGH / named DID, exact enum mapping pending |
| F921 | ASCII | text | Tire size `315/70R22.5` | CONFIRMED decode |
| F916 | ASCII | text | Card/driver identifier observed `LV V100000197677000` | CONFIRMED decode |

## Important unresolved candidates

| DID | Observation | Working hypothesis / next test |
|---|---|---|
| F906 | `06` | `Driver1TimeRelatedStates`; likely bitfield/flags. Correlate across warnings/activity/rest transitions. |
| F928 | initially `FF FF`, later rest-like timer; examples 18:12→18:21 | Secondary/cached rest-related counter. Exact semantics unknown. |
| F90B | 8 bytes dynamic; e.g. `E0 2D 08 09 16 29 7D 7F` | First 3 bytes strongly decode UTC clock: byte0≈seconds×4, byte1=minute, byte2=UTC hour. Remaining bytes unknown. |
| F9D7 | 14 bytes dynamic; first 4 bytes e.g. `6A 9D 28 42` | First 4 bytes CONFIRMED Unix UTC timestamp. Remaining bytes unknown; byte4 observed changing 07/08/09. |
| F92C | `5A 00` | 0x5A=90; possible speed-limit/calibration field, NOT confirmed. |
| F912/F913 | `03 4F 08 C1` | Calibration/static candidate. |
| F918/F91D | `11 2F` = 4399 | Calibration/static candidate. |
| F91C | `63 38` = 25400 | Calibration/static candidate. |
| F91E | `17 70` = 6000 | Calibration/static candidate. |
| F9D0/F9D1 | varies/unknown | Unknown; retain for correlation. |

## Physical display control values

Observed Volvo/DTCO values used for correlation:

- Continuous driving: `0:58` → matched F923.
- Two-week cumulative driving: `80:48 / 90:00` → matched F938.
- Current rest around `18:30` → matched progression of F925/F927.
- Current-week used: `40:50 / 56:00`.
- Two-week remaining: `9:12`.
- Weekly-rest screen: `5:30` means remaining time until the CURRENT weekly rest reaches `24:00` and qualifies as a reduced weekly rest. It is NOT time until weekly rest must start.
- Allowance screen: `9h 1` = one reduced 9-hour daily rest remaining; `10h 2` = two 10-hour daily-driving extensions remaining.
- `VDO24h +15h00` observed; exact semantics unresolved.

Daily history observed approximately:

| Day | Driving | Span/other display |
|---|---:|---:|
| 1 | 6:51 | 9:56 |
| 2 | 8:12 | 13:12 |
| 3 | 6:14 | 9:53 |
| 4 | 5:02 | 17:31 |
| 5 | 5:40 | 9:33 |
| 6 | 8:51 | -- |

Bottom totals observed: `40:50 / 56:00` and `80:48 / 90:00`.

## Derived values / formulas

These values were NOT found as simple direct u16-BE values in the F900–F9FF v7 scan and may be calculated by the tachograph/display:

- `twoWeekRemaining = max(0, 5400 - F938_minutes)` → with 80:48 used gives `9:12`.
- Previous week used = two-week used − current-week used = `80:48 - 40:50 = 39:58`.
- `maxCurrentWeek = min(56:00, 90:00 - previousWeekUsed)` → `50:02` in the observed case.
- Current-week remaining = `50:02 - 40:50 = 9:12`.
- During a qualifying weekly rest: `weeklyRest24Remaining = max(0, 24:00 - currentRest)`; at 18:30 this gives `5:30`. Only use this when state/history confirms that the ongoing rest can qualify as weekly rest.

Exact simple-value searches in v7 found no direct F900–F9FF u16-BE match for:

- `9:12` = 552 = `02 28`
- `5:30` = 330 = `01 4A`
- current week `40:50` = 2450 = `09 92`
- current-week maximum `50:02` = 3002 = `0B BA`
- `18:00` = 1080 = `04 38`
- `9:00` = 540 = `02 1C`

## Regulatory/state-transition hypotheses to test

Keep these as TEST HYPOTHESES until observed on the tachograph:

- The allowance for 10-hour daily driving extensions is weekly/calendar-week based; expected reset is at the new week boundary, not simply after a weekly rest.
- The allowance for reduced 9-hour daily rests is tied to the interval between weekly rests; after a qualifying weekly rest, the display may return from `9h 1` to `9h 3`.
- At the current 24-hour weekly-rest qualification point, expected useful signature is therefore potentially `9h: 1 → 3` while `10h: 2` remains `2`.
- The DTCO may update some counters immediately at 24:00 rest, or defer display/state refresh until the next shift/opening event. Test both transition points.

## Planned controlled observations

1. Capture v8 report before current weekly rest reaches 24:00.
2. Capture physical DTCO/Volvo values close to the transition.
3. Scan immediately after 24:00 qualifies as reduced weekly rest.
4. Compare all positive DIDs byte-for-byte, especially small 1–4 byte payloads and F906/F928.
5. If allowances do not refresh at 24:00, scan again at next shift opening.
6. Separately identify 10-hour-extension counter via a `2 → 1` use event or calendar-week boundary.

## Scanner v8 purpose

`TargetCorrelatorDiagnostic` scans F900–F9FF read-only with UDS 0x22, then monitors positive DIDs over 12 cycles. It searches for exact/near target values, small count fields, and ±1/±2/±3 trends. Reports should be retained with physical-display timestamps so correlations remain reproducible.

---
Last consolidated from v7 discovery and physical DTCO/Volvo observations on 2026-09-06.
