'use strict';
const test = require('node:test');
const assert = require('node:assert');
const fs = require('node:fs');
const path = require('node:path');
const C = require('../discharge-instructions/discharge-core.js');
const data = JSON.parse(fs.readFileSync(path.join(__dirname, '../discharge-instructions/templates.json'), 'utf8'));
const T = data.templates;

test('integrity: 100 templates, 27 categories, 52 sources', () => {
  const v = C.validate(data);
  assert.deepStrictEqual(v.errors, []);
  assert.strictEqual(v.templates, 100);
  assert.strictEqual(v.categories, 27);
  assert.strictEqual(v.sources, 52);
});

test('validate fails loudly on duplicate ids / missing sections', () => {
  const bad = { templates: [{ id: 1, category: 'a', diagnosis: 'd', summary: 's', instructions: 'i', follow_up: 'f', red_flags: '' }, { id: 1, category: 'a', diagnosis: 'd', summary: 's', instructions: 'i', follow_up: 'f', red_flags: 'r' }] };
  const v = C.validate(bad);
  assert.ok(!v.ok);
  assert.ok(v.errors.some(e => /duplicate/.test(e)));
  assert.ok(v.errors.some(e => /red_flags/.test(e)));
});

for (const q of ['סטרפ', 'כאבי בטן', 'תפרים', 'תפירה', 'דבק', 'הדבקה', 'UTI', 'uti', 'COVID-19', 'covid 19']) {
  test('search finds results for ' + q, () => {
    assert.ok(C.search(T, q, '').length > 0, q);
  });
}

test('category filter and empty query', () => {
  assert.strictEqual(C.search(T, '', '').length, 100);
  const cat = T[0].category;
  const r = C.search(T, '', cat);
  assert.ok(r.length > 0 && r.every(t => t.category === cat));
  assert.strictEqual(C.search(T, 'zzzzqqq', '').length, 0);
});

test('normalize strips niqqud and punctuation', () => {
  assert.strictEqual(C.normalize('סְטְרֶפּ'), 'סטרפ');
  assert.strictEqual(C.normalize('COVID-19'), 'covid 19');
});

test('copy text: every populated section, one bullet per line, no double bullets', () => {
  for (const t of T) {
    const text = C.buildCopyText(C.workingCopy(t));
    assert.ok(!/•\s*•/.test(text), 'double bullet in ' + t.id);
    assert.ok(text.startsWith(t.diagnosis));
    for (const s of C.SECTIONS) {
      const src = C.lines(t[s.key]);
      if (!src.length) { assert.ok(!text.includes(s.label), t.id + ' ' + s.key); continue; }
      assert.ok(text.includes(s.label));
      for (const l of src) assert.ok(text.includes('• ' + l), t.id + ' missing line');
    }
    const body = text.split('\n').filter(l => l.trim() && !C.SECTIONS.some(s => s.label === l) && l !== t.diagnosis);
    assert.ok(body.every(l => l.startsWith('• ')));
  }
});

test('edited working copy is reflected and original untouched', () => {
  const w = C.workingCopy(T[0]);
  w.instructions = '• שורה ערוכה';
  assert.ok(C.buildCopyText(w).includes('• שורה ערוכה'));
  assert.ok(!C.buildCopyText(C.workingCopy(T[0])).includes('שורה ערוכה'));
});
