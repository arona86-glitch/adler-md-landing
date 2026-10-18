# Hebrew RTL Documents — verified export correction (2026-10-18)

> **Apply this section to** `.claude/skills/rtl-hebrew-docs/SKILL.md` and add the technical details to `references/rtl-rendering.md`. This supplement was derived from an actual tested 52-page Hebrew medical discharge PDF and is meant to complement, not replace, the existing skill.

## Permanent guardrail: bidi metadata is not proof of visual RTL

For ALL Hebrew PDF, DOCX, XLSX and HTML outputs, validate **four separate properties**:

1. Hebrew characters are shaped and ordered naturally; Latin/numbers preserve their own order.
2. Text starts at the **physical right margin**, including each wrapped line.
3. Bullet glyphs sit at the **right**, then Hebrew text proceeds leftward; list hanging indentation follows the same direction.
4. Tables/list order and Excel sheet columns are right-to-left, with the first Hebrew header at the right edge.

**DO NOT sign off from the OOXML `<w:bidi/>`, `<w:jc w:val="right"/>`, or text extraction alone.** Render the actual output and inspect opening, middle, long wrapped bullets, tables, mixed Hebrew/Latin/number sequences, and last pages. A short smoke-test is mandatory before converting a 50-page document.

## Proven fix for DOCX → PDF when using LibreOffice

**Observed bug**: on this LibreOffice conversion environment, `w:bidi=1` + `w:jc=right` resulted in visually **left-aligned** Hebrew, including bullets and wrapped lines. A temporary *PDF-export-only* DOCX whose `w:bidi=1` paragraphs used `w:jc=left` visually rendered **right-aligned** in the LibreOffice-generated PDF. The converted PDF was checked and approved by the user on 2026-10-18.

**Scope caveat:** This is **not** a general Microsoft Word rule. A normal editable Word DOCX should retain correct Word-native `w:bidi=1`, `w:rtl=1` for Hebrew runs, and `w:jc=right`. The left/right inversion is only an empirically tested workaround for a particular LibreOffice converter. Never modify the master Word file to obtain a converter-specific PDF.

**Preferred PDF path:** semantic HTML `<html lang="he" dir="rtl">` with CSS `direction: rtl; text-align: right/start;`, printed through headless Chromium. Use that for PDF when possible. Preserve source Word separately for editing.

**Fallback if PDF must come from DOCX using LibreOffice**:

1. Make a separate `*_PDF_export_working.docx` copy; do not overwrite the source.
2. For paragraphs (including paragraphs inside table cells) with `w:bidi=1`, swap explicit RIGHT and LEFT alignments **only in the temporary export copy**, if the test page proves this converter exhibits the inversion. Do not change centered paragraphs.
3. For RTL index tables, set `w:bidiVisual=1` on table properties when necessary. Review each cell's visual position.
4. Convert the temporary DOCX to PDF, render pages and verify right-side bullets and line wraps before delivering.
5. If a different LibreOffice/Word version behaves differently, **do not blindly swap** — determine the correct rendering via an 8-case visual test (`bidi/no-bidi × left/right × bullet/plain`).

Minimal *conditional* export-only adjustment:

```python
from docx import Document
from docx.enum.text import WD_ALIGN_PARAGRAPH
from docx.oxml.ns import qn

def converter_workaround(doc):
    def patch(p):
        pPr = p._p.pPr
        if pPr is None:
            return
        bidi = pPr.find(qn('w:bidi'))
        if bidi is None or bidi.get(qn('w:val')) == '0':
            return
        if p.alignment == WD_ALIGN_PARAGRAPH.RIGHT:
            p.alignment = WD_ALIGN_PARAGRAPH.LEFT
        elif p.alignment == WD_ALIGN_PARAGRAPH.LEFT:
            p.alignment = WD_ALIGN_PARAGRAPH.RIGHT

    for p in doc.paragraphs:
        patch(p)
    for t in doc.tables:
        for row in t.rows:
            for cell in row.cells:
                for p in cell.paragraphs:
                    patch(p)
    return doc

# ONLY after an empirical render test for the affected LibreOffice environment:
# src = Document('master.docx')
# converter_workaround(src).save('temp_for_libreoffice_pdf.docx')
# Convert temporary copy, render pages, inspect, never ship it as Word master.
```

## Editable Microsoft Word DOCX (separate track)

- Use Hebrew-capable font, preferably David (when installed/licensed) or Noto Sans Hebrew/Arial.
- Set section, paragraph and run RTL OOXML properly: `w:bidi=1`, `w:rtl=1`, `w:cs=1`, `w:lang` he-IL, `w:jc=right` (or justified if requested).
- Use true RTL numbered/bulleted lists. On any RTL discrepancy, prioritize actual rendering in **Microsoft Word** over converter appearance.
- Always preserve editable text; never substitute screenshots to mask a direction issue.

## Microsoft Excel XLSX — actual sheet direction, not just align-right

For **every Hebrew worksheet**:
- Set `xl/worksheets/sheetN.xml`: `<sheetView rightToLeft="1" ...>`; this places column **A on the right**.
- For Hebrew text styles, `xl/styles.xml` `alignment readingOrder="2"` plus `horizontal="right"`, `vertical="top"` and wrapping as needed.
- Header, filter arrows, active/frozen panes, tables, merged titles, and screen-reader reading order must all be checked visually.
- It is not sufficient to set only `horizontal_alignment="right"` on cells: the source problem had right-aligned Hebrew cells but LTR *worksheet* order.
- Prefer spreadsheet-tool native settings where available; if no RTL setting exists, perform a narrowly scoped OOXML metadata repair, then re-open and verify (avoid rewriting medical text, formulas, IDs, filtering).

## HTML / app integration

Use `<html lang="he" dir="rtl">`; `body{direction:rtl;text-align:right}`; `text-align:start`; CSS logical properties (`padding-inline-start`, `border-inline-start`, etc.), with list markers physically on right. Isolate English abbreviations, phone numbers and other LTR fragments using `<bdi dir="ltr">` or CSS `direction:ltr;unicode-bidi:isolate`. Provide a robust print view and mobile breakpoint, verify no horizontal scrolling.

For all Hebrew UI flows, visually check **copy-to-clipboard output**, RTL list markers, short and long lines, mixed Hebrew and Latin, print/PDF, and desktop/mobile.

## Minimum QA checklist to run before delivery

- [ ] PDF first/middle/last pages visually show headings and bullets right-aligned.
- [ ] At least one long multiline bullet wraps to the correct right margin.
- [ ] Mixed `SPF 50`, `COVID-19`, dose, dates and punctuation remain ordered.
- [ ] Table of contents/index direction and column order correct.
- [ ] DOCX rendered in the target editor, not merely assumed from PDF output.
- [ ] All XLSX sheets have `rightToLeft="1"` and readingOrder=2 on Hebrew text styles; filters & frozen rows preserved.
- [ ] HTML `dir="rtl"`, search, copy button, and mobile layout verified.

**Incident reference:** Hebrew clinic discharge instruction set, 100 templates, October 2026. User verified corrected PDF on Oct 18. The original defect was a PDF converter alignment issue, *not* reversed Hebrew strings. Do not reverse characters as a fix.
