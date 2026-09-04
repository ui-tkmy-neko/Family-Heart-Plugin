from pathlib import Path
import re, sys
try:
    import yaml
except Exception:
    yaml = None

ROOT = Path(__file__).resolve().parents[1]
FAIL = []

# 1) Kotlin lexical delimiter balance (best-effort, ignores comments/strings).
for p in (ROOT / 'src/main/kotlin').rglob('*.kt'):
    s = p.read_text(encoding='utf-8')
    pairs = {'(': ')', '{': '}', '[': ']'}
    stack=[]; i=0; state='code'; esc=False
    while i < len(s):
        c=s[i]; n=s[i+1] if i+1 < len(s) else ''
        if state=='line':
            if c=='\n': state='code'
            i+=1; continue
        if state=='block':
            if c=='*' and n=='/': state='code'; i+=2; continue
            i+=1; continue
        if state=='string':
            if esc: esc=False
            elif c=='\\': esc=True
            elif c=='"': state='code'
            i+=1; continue
        if c=='/' and n=='/': state='line'; i+=2; continue
        if c=='/' and n=='*': state='block'; i+=2; continue
        if c=='"': state='string'; i+=1; continue
        if c in pairs: stack.append(c)
        elif c in ')}]':
            if not stack or pairs[stack.pop()] != c:
                FAIL.append(f'delimiter mismatch: {p}')
                break
        i+=1
    if stack or state in ('block','string'):
        FAIL.append(f'unclosed lexical construct: {p}')

# 2) Removed custom-item feature must not exist in active source/config.
for p in list((ROOT/'src/main/kotlin').rglob('*.kt')) + list((ROOT/'src/main/resources').glob('*')):
    text=p.read_text(encoding='utf-8', errors='ignore').lower()
    if any(x in text for x in ('hdb726yb','customitem','custom-item')):
        # Only the database migration may mention the legacy enum name.
        if p.name != 'DatabaseManager.kt' or 'delete from requests where type=\'custom_item\'' not in text:
            FAIL.append(f'removed-feature reference: {p}')

# 3) MySQL must be absent from active source/config.
for p in list((ROOT/'src/main/kotlin').rglob('*.kt')) + list((ROOT/'src/main/resources').glob('*')):
    if re.search(r'(?i)mysql|mariadb', p.read_text(encoding='utf-8', errors='ignore')):
        FAIL.append(f'legacy database reference: {p}')

# 4) Java 25 and requested soft dependencies.
pom=(ROOT/'pom.xml').read_text(encoding='utf-8')
if '<java.version>25</java.version>' not in pom: FAIL.append('java.version is not 25')
if '<maven.compiler.release>${java.version}</maven.compiler.release>' not in pom: FAIL.append('Maven compiler release is not linked to Java 25')
if '<kotlin.compiler.jvmTarget>${java.version}</kotlin.compiler.jvmTarget>' not in pom: FAIL.append('Kotlin jvmTarget is not linked to Java 25')
if 'org.xerial' not in pom or 'sqlite-jdbc' not in pom: FAIL.append('SQLite JDBC dependency missing')

if yaml:
    try:
        plugin=yaml.safe_load((ROOT/'src/main/resources/plugin.yml').read_text(encoding='utf-8'))
        if set(plugin.get('softdepend',[])) != {'LuckPerms','Vault','Geyser-Spigot','floodgate'}:
            FAIL.append('softdepend mismatch')
    except Exception as e: FAIL.append(f'plugin.yml parse failure: {e}')
else:
    FAIL.append('PyYAML unavailable; YAML check skipped')

# 5) Acceptance contract checks.
cmd=(ROOT/'src/main/kotlin/nekouidaga/net/familyheartplugin/command/FamilyHeartCommand.kt').read_text(encoding='utf-8')
req=(ROOT/'src/main/kotlin/nekouidaga/net/familyheartplugin/request/RequestService.kt').read_text(encoding='utf-8')
gui=(ROOT/'src/main/kotlin/nekouidaga/net/familyheartplugin/gui/GuiManager.kt').read_text(encoding='utf-8')
for marker in ['"accept" ->', 'RequestType.MARRY && role == null', 'req.findLatestPendingMarriage(s.uniqueId)', 'request.type != RequestType.MARRY && role != null']:
    if marker not in cmd: FAIL.append(f'accept command marker missing: {marker}')
for marker in ['acceptedSpouseRole', 'marriage.same-role', 'RequestType.SKINSHIP']:
    if marker not in req: FAIL.append(f'request decision marker missing: {marker}')
if 'MenuType.MARRIAGE_ROLE' not in gui: FAIL.append('GUI marriage role selector missing')

# 6) README must agree with same-role rejection.
readme=(ROOT/'README.md').read_text(encoding='utf-8')
if 'wife×wife / husband×husband' in readme and '拒否' not in readme:
    FAIL.append('README still describes same-role marriage as accepted')

# 7) No stale build output in source package.
if (ROOT/'target').exists() and any((ROOT/'target').glob('*.jar')):
    FAIL.append('stale target JAR present')

if FAIL:
    print('FAIL')
    for x in FAIL: print(x)
    sys.exit(1)
print('PASS: self-repair audit found no deterministic violations')
