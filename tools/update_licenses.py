#!/usr/bin/env python3
"""Rebuilds the third-party notices from the real dependencies of the two apps.

It asks Gradle for the release runtime classpath of :app and :phone, reads the licence each library declares in its
POM, groups the libraries into families and writes
  - THIRD_PARTY_NOTICES.md                       (for the repository)
  - phone/src/main/assets/licenses.txt           (shown by the phone app: Settings -> Open-source licenses)
Run it again after changing the dependencies (needs a Gradle cache with the libraries, i.e. a build was done).
The texts of the licences come from the libraries themselves, from the system (Apache 2.0) and from LICENSE."""
import collections, glob, os, re, subprocess, sys, zipfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
CACHE = os.path.expanduser('~/.gradle/caches/modules-2/files-2.1')

# family -> (display name, copyright line, licence key); the licence a POM declares is checked against it
FAMILIES = [
    ('androidx.wear.protolayout:protolayout-external-protobuf', 'Protocol Buffers (bundled in AndroidX ProtoLayout)', 'Copyright Google Inc.', 'BSD-3-Clause'),
    ('androidx.', 'AndroidX (Jetpack)', 'Copyright (C) The Android Open Source Project', 'Apache-2.0'),
    ('org.jetbrains', 'Kotlin and kotlinx.coroutines', 'Copyright JetBrains s.r.o. and Kotlin Programming Language contributors', 'Apache-2.0'),
    ('com.google.guava:', 'Guava', 'Copyright The Guava Authors', 'Apache-2.0'),
    ('com.google.android.gms:', 'Google Play services (Wearable, Base, Basement, Tasks)', 'Copyright Google LLC', 'Android-SDK-License'),
    ('org.checkerframework:', 'Checker Framework annotations', 'Copyright 2004-present by the Checker Framework developers', 'MIT'),
    ('com.google.code.findbugs:', 'JSR 305 annotations', 'Copyright the JSR 305 authors', 'Apache-2.0'),
    ('com.google.errorprone:', 'Error Prone annotations', 'Copyright The Error Prone Authors', 'Apache-2.0'),
    ('com.google.j2objc:', 'J2ObjC annotations', 'Copyright Google Inc.', 'Apache-2.0'),
]
SCHEDULES = ('The schedules come from the website of the Poltava power company, https://www.poe.pl.ua, and from '
             'https://bezsvitla.com.ua (all the other regions); the app links to both. Wattlo is not an official source.')
LICENCE_NAMES = {'Apache-2.0': 'Apache License 2.0', 'MIT': 'MIT License', 'BSD-3-Clause': 'BSD 3-Clause License',
                 'Android-SDK-License': 'Android Software Development Kit License'}
# what the POMs call the licences
ALIASES = [('apache', 'Apache-2.0'), ('mit', 'MIT'), ('bsd', 'BSD-3-Clause'), ('android software development kit', 'Android-SDK-License')]
ICONS = ('Material Icons (Google)', 'Copyright Google LLC', 'Apache-2.0',
         'the icons of the settings and of the tab bar; https://github.com/google/material-design-icons')


def classpath():
    """The libraries that end up in the apps: the release runtime classpath of each module, nothing of the build tools."""
    found = {}
    for module in (':app', ':phone'):
        out = subprocess.run([os.path.join(ROOT, 'gradlew'), f'{module}:dependencies', '--configuration',
                              'releaseRuntimeClasspath', '-q'], cwd=ROOT, capture_output=True, text=True).stdout
        inside = False
        for line in out.splitlines():
            if line.startswith('releaseRuntimeClasspath'):
                inside = True
                continue
            if inside and not line.strip():
                break  # the blank line ends the section
            if not inside:
                continue
            s = line.strip().lstrip('+\\| -')
            m = re.match(r'([\w.\-]+):([\w.\-]+):([\w.\-]+)(?: -> ([\w.\-]+))?', s)
            if m and not s.startswith('project'):
                g, a, v, up = m.groups()
                found[(g, a)] = up or v
    return found


def declared(g, a, v):
    for pom in glob.glob(f'{CACHE}/{g}/{a}/{v}/*/{a}-{v}.pom'):
        names = re.findall(r'<license>.*?<name>(.*?)</name>', open(pom, encoding='utf-8', errors='ignore').read(), re.S)
        for n in names:
            for key, lic in ALIASES:
                if key in n.lower():
                    return lic
    return None


def jar_text(path_glob, member_re):
    for p in glob.glob(path_glob):
        with zipfile.ZipFile(p) as z:
            for n in z.namelist():
                if re.search(member_re, n):
                    return z.read(n).decode('utf-8', 'replace').strip()
    return None


def reflow(text):
    """The licence texts are wrapped at ~78 columns; on a phone the paragraphs are joined and the screen wraps them."""
    paragraphs = re.split(r'\n\s*\n', text.strip())
    return '\n\n'.join(' '.join(line.strip() for line in p.splitlines()) for p in paragraphs)


def main():
    libs = classpath()
    fams = collections.OrderedDict((f[1], {'copyright': f[2], 'licence': f[3], 'libs': []}) for f in FAMILIES)
    unknown = []
    for (g, a), v in sorted(libs.items()):
        key = f'{g}:{a}'
        fam = next((f for f in FAMILIES if key.startswith(f[0]) or g.startswith(f[0])), None)
        if fam is None:
            unknown.append(key)
            continue
        fams[fam[1]]['libs'].append(a)
        d = declared(g, a, v)
        if d and d != fam[3] and not (fam[3] == 'Android-SDK-License'):
            print(f'warning: {key} declares {d}, the family {fam[1]} says {fam[3]}', file=sys.stderr)
    if unknown:
        print('warning: libraries without a family (add them to FAMILIES):', ', '.join(unknown), file=sys.stderr)
    fams = collections.OrderedDict((k, v) for k, v in fams.items() if v['libs'])

    apache = open('/usr/share/common-licenses/Apache-2.0', encoding='utf-8').read().strip()
    mit_orig = open(os.path.join(ROOT, 'LICENSE'), encoding='utf-8').read().strip()
    mit_checker = jar_text(f'{CACHE}/org.checkerframework/checker-qual/*/*/checker-qual-*-javadoc.jar', r'META-INF/LICENSE\.txt$') \
        or jar_text(f'{CACHE}/org.checkerframework/checker-qual/*/*/checker-qual-*.jar', r'LICENSE')
    bsd = jar_text(f'{CACHE}/androidx.wear.protolayout/protolayout-external-protobuf/*/*/*.jar', r'LICENSE\.txt$')
    if not (mit_checker and bsd):
        sys.exit('could not read the licence texts of Checker Framework / protobuf from the Gradle cache (build first)')

    used = {f['licence'] for f in fams.values()} | {ICONS[2]}
    texts = collections.OrderedDict()
    if 'Apache-2.0' in used: texts['Apache License 2.0'] = apache
    if 'MIT' in used: texts['MIT License (Checker Framework)'] = mit_checker
    if 'BSD-3-Clause' in used: texts['BSD 3-Clause License (Protocol Buffers)'] = bsd
    sdk = ('The Google Play services libraries are provided under the Android Software Development Kit License '
           'Agreement: https://developer.android.com/studio/terms')

    head = ('Wattlo is open source. It is built on the work of other people: thank you to the authors.\n\n'
            'Wattlo itself\n-------------\n'
            'Wattlo grew out of the app "Svitlo" by Yehor Shamanskyi (https://github.com/sudoRM-rSLASH/Watch4Classic-Svitlo). '
            'That code is used under the MIT License; its copyright notice is kept:\n\n' + reflow(mit_orig) + '\n\n'
            'Schedules\n---------\n' + SCHEDULES + '\n')
    libs_txt = 'Libraries\n---------\n' + '\n'.join(
        f'* {n}: {LICENCE_NAMES[f["licence"]]}\n  {f["copyright"]}' for n, f in fams.items()) + \
        f'\n* {ICONS[0]}: {LICENCE_NAMES[ICONS[2]]}\n  {ICONS[1]}; {ICONS[3]}\n'
    plain = head + '\n' + libs_txt + '\nLicense texts\n-------------\n\n' + sdk + '\n\n' + \
        '\n\n'.join(f'=== {n} ===\n\n{reflow(t)}' for n, t in texts.items()) + '\n'
    asset = os.path.join(ROOT, 'phone/src/main/assets/licenses.txt')
    os.makedirs(os.path.dirname(asset), exist_ok=True)
    open(asset, 'w', encoding='utf-8').write(plain)

    md = ['# Third-party notices\n', 'Generated by `tools/update_licenses.py` from the dependencies of the two apps; '
          'the phone app shows the same text (Settings → Open-source licenses).\n', '## Wattlo itself\n',
          'Wattlo grew out of the app "Svitlo" by Yehor Shamanskyi, '
          '[sudoRM-rSLASH/Watch4Classic-Svitlo](https://github.com/sudoRM-rSLASH/Watch4Classic-Svitlo). That code is used '
          'under the MIT License; its copyright notice is kept in [LICENSE](LICENSE).\n', '## Schedules\n',
          'The schedules come from the website of the Poltava power company, [poe.pl.ua](https://www.poe.pl.ua), and from '
          '[bezsvitla.com.ua](https://bezsvitla.com.ua) (all the other regions); the app links to both. Wattlo is not an '
          'official source.\n', '## Libraries\n',
          '| Library | License | Copyright |', '|---|---|---|']
    for n, f in fams.items():
        md.append(f'| {n} ({len(f["libs"])}) | {LICENCE_NAMES[f["licence"]]} | {f["copyright"]} |')
    md.append(f'| {ICONS[0]} | {LICENCE_NAMES[ICONS[2]]} | {ICONS[1]} |')
    md += ['', sdk, '', '## License texts\n']
    for n, t in texts.items():
        md += [f'### {n}\n', '```', t, '```', '']
    open(os.path.join(ROOT, 'THIRD_PARTY_NOTICES.md'), 'w', encoding='utf-8').write('\n'.join(md))
    print(f'{len(libs)} libraries in {len(fams)} families; wrote THIRD_PARTY_NOTICES.md and {os.path.relpath(asset, ROOT)}')


if __name__ == '__main__':
    main()
