#!/usr/bin/env python3
"""
Layout guard. Runs in the GitHub build (see .github/workflows/deploy.yml) and fails the
build when a screen is written in a way that breaks on phones with big fonts or a big
"Display size". Run it yourself with:  python3 tools/check_layouts.py

Rules (why each exists):
 1. Text widgets never get a fixed height in dp. Use wrap_content + minHeight, so the
    widget grows with the text instead of clipping it.
 2. Layouts never hard-code android:textSize. Use android:textAppearance with a
    TextAppearance.VG.* style, so every size and weight comes from the one type scale.
 3. A TextView that shortens text with "..." (ellipsize) must also shrink to fit
    (app:autoSizeTextType="uniform"), so a phone number is never cut to "0803...".
 4. sp is only for text. Never for margins, paddings, widths or heights.
 5. Every screen extends BaseActivity (not AppCompatActivity), so ScaleGuard covers it.

To deliberately break a rule for one element (for example a decorative badge), add its
id to ALLOW below with a short reason.
"""
import glob, os, re, sys

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', 'app', 'src', 'main')
LAYOUTS = os.path.join(ROOT, 'res', 'layout', '*.xml')
KOTLIN = os.path.join(ROOT, 'java', '**', '*.kt')

# element id -> reason. Rule number in the key: (rule, id)
ALLOW = {
    (2, 'pendingViewers'): 'hero number, intentionally outside the type scale',
}

TEXT_TAGS = {'TextView', 'Button', 'MaterialButton', 'EditText', 'TextInputEditText',
             'AutoCompleteTextView', 'CheckBox', 'RadioButton', 'Switch'}
errors = []

def line_of(text, pos):
    return text.count('\n', 0, pos) + 1

for path in sorted(glob.glob(LAYOUTS)):
    name = os.path.basename(path)
    text = open(path, encoding='utf-8').read()
    for m in re.finditer(r'<([\w\.]+)\s[^<>]*?>', text, re.S):
        tag = m.group(1).split('.')[-1]
        blk = m.group(0)
        idm = re.search(r'android:id="@\+id/(\w+)"', blk)
        eid = idm.group(1) if idm else None
        where = f'{name}:{line_of(text, m.start())} <{tag}{" id=" + eid if eid else ""}>'

        def allowed(rule):
            return eid is not None and (rule, eid) in ALLOW

        if tag in TEXT_TAGS:
            h = re.search(r'android:layout_height="(\d+(?:\.\d+)?)dp"', blk)
            if h and float(h.group(1)) >= 20 and not allowed(1):
                errors.append(f'RULE 1  {where}: fixed height {h.group(1)}dp on a text widget. '
                              f'Use layout_height="wrap_content" with android:minHeight="{h.group(1)}dp".')
        if re.search(r'android:textSize=', blk) and not allowed(2):
            errors.append(f'RULE 2  {where}: hard-coded android:textSize. '
                          f'Use android:textAppearance="@style/TextAppearance.VG.<Role>".')
        if tag == 'TextView' and 'android:ellipsize=' in blk and 'autoSizeTextType' not in blk and not allowed(3):
            errors.append(f'RULE 3  {where}: ellipsize without shrink-to-fit. Add '
                          f'app:autoSizeTextType="uniform", app:autoSizeMinTextSize, app:autoSizeMaxTextSize.')
        for a in re.finditer(r'android:(layout_\w+|padding\w*|margin\w*|minHeight|minWidth)="[\d\.]+sp"', blk):
            if not allowed(4):
                errors.append(f'RULE 4  {where}: sp used for {a.group(1)}. Use dp.')

for path in sorted(glob.glob(KOTLIN, recursive=True)):
    name = os.path.basename(path)
    if name == 'BaseActivity.kt':
        continue
    text = open(path, encoding='utf-8').read()
    for m in re.finditer(r'class\s+(\w+)\s*(?:\([^)]*\))?\s*:\s*[^{\n]*\b(AppCompatActivity|ComponentActivity|FragmentActivity|Activity)\s*\(', text):
        errors.append(f'RULE 5  {name}:{line_of(text, m.start())} class {m.group(1)} extends '
                      f'{m.group(2)}. Extend BaseActivity so the font/display scale policy applies.')

if errors:
    print('LAYOUT CHECK FAILED: %d problem(s)\n' % len(errors))
    print('\n'.join(errors))
    print('\nWhy: these break on phones with a big font or display size. See tools/check_layouts.py.')
    sys.exit(1)
print('Layout check passed.')
