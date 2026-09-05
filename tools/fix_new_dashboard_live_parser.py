from pathlib import Path

p = Path('app/src/main/java/com/pylikv/tachowatch/TachoWatchActivity.kt')
s = p.read_text(encoding='utf-8')
old = '    private fun lastDecoded(log: String, did: String): String? = log.lines().lastOrNull { it.startsWith("$did=") }?.substringAfter("|")?.trim()\n'
new = '''    private fun lastDecoded(log: String, did: String): String? {\n        val marker = "$did="\n        return log.lines()\n            .asReversed()\n            .firstOrNull { it.contains(marker) }\n            ?.substringAfter(marker)\n            ?.substringAfter("|")\n            ?.trim()\n    }\n'''
if old not in s:
    raise SystemExit('lastDecoded anchor not found')
s = s.replace(old, new, 1)
p.write_text(s, encoding='utf-8')
print('Fixed new dashboard live DID parser: accepts timestamp/prefix before DID')
