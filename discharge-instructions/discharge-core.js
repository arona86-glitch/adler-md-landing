/* Pure helpers for the discharge-instructions module (no DOM access). */
(function (root) {
  'use strict';

  var SECTIONS = [
    { key: 'summary', label: 'אבחנה / סיכום' },
    { key: 'instructions', label: 'הנחיות בשחרור' },
    { key: 'follow_up', label: 'מעקב / ביקורת' },
    { key: 'red_flags', label: 'סימני אזהרה – פנייה דחופה', warn: true },
    { key: 'fields', label: 'שדות להתאמה לפני הדבקה' }
  ];

  function validate(data) {
    var errors = [];
    var t = (data && data.templates) || [];
    var ids = {}, cats = {};
    t.forEach(function (x, i) {
      if (typeof x.id !== 'number') errors.push('template #' + i + ': missing id');
      if (ids[x.id]) errors.push('duplicate id ' + x.id);
      ids[x.id] = true;
      cats[x.category] = true;
      ['category', 'diagnosis', 'summary', 'instructions', 'follow_up', 'red_flags'].forEach(function (k) {
        if (!x[k] || !String(x[k]).trim()) errors.push('template ' + x.id + ': empty ' + k);
      });
    });
    return {
      ok: errors.length === 0,
      errors: errors,
      templates: t.length,
      categories: Object.keys(cats).length,
      sources: ((data && data.sources) || []).length
    };
  }

  function normalize(s) {
    return String(s == null ? '' : s)
      .normalize('NFKC')
      .replace(/[֑-ׇ]/g, '')          // niqqud / cantillation
      .replace(/[׳״'"`״׳]/g, '')       // geresh / gershayim / quotes
      .toLowerCase()
      .replace(/[\-‐-―_.,;:!?()\[\]{}\/\\|•]+/g, ' ')
      .replace(/\s+/g, ' ')
      .trim();
  }

  function haystack(t) {
    return normalize([t.id, t.diagnosis, t.category, t.keywords].join(' '));
  }

  /* Light Hebrew inflection tolerance: a token also matches via its stem
     (e.g. כאבי -> כאב, הדבקה -> דבק). Applies to Hebrew tokens only. */
  function variants(tok) {
    var out = [tok];
    if (!/^[א-ת]+$/.test(tok)) return out;
    var bases = [tok];
    if (tok.length > 3 && /^[הובלמש]/.test(tok)) bases.push(tok.slice(1));
    bases.forEach(function (b) {
      if (out.indexOf(b) === -1) out.push(b);
      ['ים', 'ות', 'ה', 'י'].forEach(function (suf) {
        if (b.length - suf.length >= 3 && b.slice(-suf.length) === suf) {
          var s = b.slice(0, -suf.length);
          if (out.indexOf(s) === -1) out.push(s);
        }
      });
    });
    return out;
  }

  function search(templates, query, category) {
    var tokens = normalize(query).split(' ').filter(Boolean).map(variants);
    return templates.filter(function (t) {
      if (category && t.category !== category) return false;
      if (!tokens.length) return true;
      var h = t._h || (t._h = haystack(t));
      return tokens.every(function (vs) {
        return vs.some(function (v) { return h.indexOf(v) !== -1; });
      });
    });
  }

  /* Source strings already carry a leading "•"; strip it for <li> rendering. */
  function lines(text) {
    return String(text || '').split(/\r?\n/)
      .map(function (l) { return l.replace(/^\s*[•·\-–*]\s*/, '').trim(); })
      .filter(Boolean);
  }

  function toPlain(l) { return l.map(function (x) { return '• ' + x; }).join('\n'); }

  function workingCopy(t) {
    var w = { id: t.id, diagnosis: t.diagnosis, category: t.category };
    SECTIONS.forEach(function (s) { w[s.key] = toPlain(lines(t[s.key])); });
    return w;
  }

  /* sel: optional { sectionKey: [bool per line] }; missing => everything included. */
  function isOn(sel, key, i) { return !sel || !sel[key] || sel[key][i] !== false; }

  function selectAll(w, on) {
    var sel = {};
    SECTIONS.forEach(function (s) { sel[s.key] = lines(w[s.key]).map(function () { return on; }); });
    return sel;
  }

  /* Clean clipboard text: diagnosis, then each section that has selected lines, single-bullet lines. */
  function buildCopyText(w, sel) {
    var out = [w.diagnosis];
    SECTIONS.forEach(function (s) {
      var l = lines(w[s.key]).filter(function (x, i) { return isOn(sel, s.key, i); });
      if (!l.length) return;
      out.push('', s.label, l.map(function (x) { return '• ' + x; }).join('\n'));
    });
    return out.join('\n');
  }

  var api = { SECTIONS: SECTIONS, validate: validate, normalize: normalize, search: search,
              lines: lines, workingCopy: workingCopy, buildCopyText: buildCopyText, selectAll: selectAll, isOn: isOn };
  if (typeof module !== 'undefined' && module.exports) module.exports = api;
  else root.DischargeCore = api;
})(typeof self !== 'undefined' ? self : this);
