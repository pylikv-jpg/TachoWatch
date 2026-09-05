from pathlib import Path

p = Path('app/src/main/java/com/pylikv/tachowatch/FullDtcoScannerActivity.kt')
text = p.read_text(encoding='utf-8')
text2 = text.replace('\\"', '"')
if text2 != text:
    p.write_text(text2, encoding='utf-8')
    print('Fixed FullDtcoScannerActivity Kotlin quoting')
else:
    print('FullDtcoScannerActivity quoting already clean')
