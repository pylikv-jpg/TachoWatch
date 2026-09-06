from pathlib import Path

p = Path("app/src/main/java/com/pylikv/tachowatch/TargetCorrelatorDiagnostic.kt")
if not p.exists():
    raise SystemExit(0)

s = p.read_text(encoding="utf-8")
old = 'trendHits.takeLast(20).joinToString("; ")'
new = 'trendHits.toList().takeLast(20).joinToString("; ")'

if old in s:
    p.write_text(s.replace(old, new), encoding="utf-8")
    print("Patched TargetCorrelatorDiagnostic.kt takeLast() for LinkedHashSet")
else:
    print("Target correlator takeLast patch not needed")
