# Approved TachoWatch design — 9 September 2026

The user approved the three-panel dark mockup in chat: upper main screen,
continuation of the same scrolling screen, and history. These are two pages,
not three separate main-screen tabs.

- Background #0B1118, cards #17212C, light text, emerald/amber/red status accents.
- Large HH:MM numbers, monochrome pictograms, rounded cards, thin progress bars.
- Header: TachoWatch, Bluetooth selection, settings; connection status and data age.
- Bottom navigation: Сейчас, История, Сканер.
- Main order: Непрерывное вождение, Вождение за смену, Непрерывная работа,
  Другая работа, Ожидание, Готовность, Отдых / Пауза, Рабочая неделя,
  Текущая неделя, Две недели.
- Continuous driving: remaining time large, driven time and limit below.
- Other work, waiting and availability use compact rows.
- Rest: actual duration large; credited duration and next milestone in a contrasting inset.
- History: compact consecutive-week summary, used/remaining allowances, shifts
  with rest separators; tap shift header to expand activity details.
- No fabricated demo values in production. Separate waiting is unavailable and
  displays a dash; the availability row uses the existing shared DTCO mode.
- Transport reconnect/watchdog remains owned by LiveDidDiagnostic.

The illustration's numbers and bar lengths are placeholders, not calculation
specifications. Preserve existing calculation sources while changing presentation.

Validation: CI unit tests and APK compilation. Real-device Bluetooth and exact
visual rendering still need device verification; there is no local Android SDK.
