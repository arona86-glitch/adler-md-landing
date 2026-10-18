(function () {
  'use strict';
  var C = window.DischargeCore;
  var $ = function (id) { return document.getElementById(id); };
  var data, templates = [], current = null, working = null, editing = false;

  var LATIN = /[A-Za-z][A-Za-z0-9\-+\/.% ]*[A-Za-z0-9%]|[A-Za-z]/g;

  /* Text node builder; isolates Latin runs (COVID-19, UTI, SPF 50) so bidi stays correct. */
  function appendText(el, text) {
    var last = 0, m;
    LATIN.lastIndex = 0;
    while ((m = LATIN.exec(text))) {
      if (m.index > last) el.appendChild(document.createTextNode(text.slice(last, m.index)));
      var b = document.createElement('bdi');
      b.dir = 'ltr';
      b.textContent = m[0];
      el.appendChild(b);
      last = m.index + m[0].length;
    }
    if (last < text.length) el.appendChild(document.createTextNode(text.slice(last)));
    return el;
  }

  function el(tag, cls, text) {
    var e = document.createElement(tag);
    if (cls) e.className = cls;
    if (text != null) appendText(e, text);
    return e;
  }

  function sectionView(w, s) {
    var l = C.lines(w[s.key]);
    if (!l.length) return null;
    var sec = el('section', 'dc-section' + (s.warn ? ' dc-warn' : ''));
    sec.appendChild(el('h3', null, s.label));
    var ul = el('ul', 'dc-bullets');
    l.forEach(function (x) { ul.appendChild(el('li', null, x)); });
    sec.appendChild(ul);
    return sec;
  }

  function renderList(items) {
    var ul = $('dc-list');
    ul.textContent = '';
    items.forEach(function (t) {
      var li = el('li');
      var b = el('button', 'dc-result');
      b.type = 'button';
      b.setAttribute('aria-pressed', current && current.id === t.id ? 'true' : 'false');
      b.dataset.id = t.id;
      b.appendChild(el('span', 'dc-res-name', t.diagnosis));
      b.appendChild(el('span', 'dc-res-info', t.category));
      b.addEventListener('click', function () { select(t.id, true); });
      li.appendChild(b);
      ul.appendChild(li);
    });
    $('dc-empty').hidden = items.length > 0;
    $('dc-count').textContent = 'נמצאו ' + items.length + ' תבניות מתוך ' + templates.length;
  }

  function refresh() {
    renderList(C.search(templates, $('dc-search').value, $('dc-category').value));
  }

  function select(id, focus) {
    current = templates.filter(function (t) { return t.id === id; })[0];
    working = C.workingCopy(current);
    editing = false;
    refresh();
    renderDetail();
    renderPrint();
    if (focus) $('dc-detail').focus();
  }

  function btn(label, cls, fn) {
    var b = el('button', 'dc-btn ' + cls, label);
    b.type = 'button';
    b.addEventListener('click', fn);
    return b;
  }

  function renderDetail() {
    var box = $('dc-detail');
    box.textContent = '';
    if (!current) { box.appendChild(el('p', 'dc-placeholder', 'בחרו תבנית מהרשימה.')); return; }

    var head = el('div', 'dc-detail-head');
    var titles = el('div');
    titles.appendChild(el('span', 'dc-tag', current.category));
    titles.appendChild(el('h2', null, working.diagnosis));
    head.appendChild(titles);
    var actions = el('div', 'dc-actions');
    actions.appendChild(btn('העתקת כל ההנחיות', 'dc-btn-primary', copyAll));
    actions.appendChild(btn('הדפסה / PDF', 'dc-btn-ghost', function () { renderPrint(); window.print(); }));
    actions.appendChild(btn(editing ? 'סיום עריכה' : 'עריכת ההנחיות', 'dc-btn-ghost', toggleEdit));
    if (editing) actions.appendChild(btn('איפוס למקור', 'dc-btn-ghost', resetOriginal));
    head.appendChild(actions);
    box.appendChild(head);

    var status = el('p', 'dc-copy-status');
    status.id = 'dc-copy-status';
    status.setAttribute('role', 'status');
    box.appendChild(status);

    if (editing) {
      box.appendChild(el('p', 'dc-caution', 'מצב עריכה: השינויים נשמרים בזיכרון הדף בלבד ואינם נשלחים או נשמרים. אין להזין פרטים מזהים של מטופלים; הם יימחקו ברענון הדף.'));
      C.SECTIONS.forEach(function (s) {
        var wrap = el('div', 'dc-edit-field');
        var lab = el('label', null, s.label);
        lab.htmlFor = 'dc-edit-' + s.key;
        var ta = document.createElement('textarea');
        ta.id = 'dc-edit-' + s.key;
        ta.dir = 'rtl';
        ta.rows = Math.max(3, C.lines(working[s.key]).length + 1);
        ta.value = working[s.key];
        ta.addEventListener('input', function () { working[s.key] = ta.value; renderPrint(); });
        wrap.appendChild(lab);
        wrap.appendChild(ta);
        box.appendChild(wrap);
      });
    } else {
      C.SECTIONS.forEach(function (s) {
        var v = sectionView(working, s);
        if (v) box.appendChild(v);
      });
    }
  }

  function renderPrint() {
    var p = $('dc-print');
    p.textContent = '';
    if (!current) return;
    p.appendChild(el('h1', null, working.diagnosis));
    C.SECTIONS.forEach(function (s) {
      var v = sectionView(working, s);
      if (v) p.appendChild(v);
    });
  }

  function toggleEdit() { editing = !editing; renderDetail(); }
  function resetOriginal() { working = C.workingCopy(current); renderDetail(); renderPrint(); }

  function setStatus(msg, bad) {
    var s = $('dc-copy-status');
    if (!s) return;
    s.textContent = msg;
    s.className = 'dc-copy-status' + (bad ? ' is-bad' : ' is-ok');
  }

  function fallbackCopy(text) {
    var ta = document.createElement('textarea');
    ta.value = text;
    ta.setAttribute('readonly', '');
    ta.style.position = 'fixed';
    ta.style.opacity = '0';
    document.body.appendChild(ta);
    ta.select();
    var ok = false;
    try { ok = document.execCommand('copy'); } catch (e) { ok = false; }
    document.body.removeChild(ta);
    return ok;
  }

  function copyAll() {
    var text = C.buildCopyText(working);
    var done = function () { setStatus('ההנחיות הועתקו ללוח.'); };
    var fail = function () { setStatus('ההעתקה נכשלה. סמנו את הטקסט והעתיקו ידנית.', true); };
    if (navigator.clipboard && navigator.clipboard.writeText) {
      navigator.clipboard.writeText(text).then(done, function () { fallbackCopy(text) ? done() : fail(); });
    } else {
      fallbackCopy(text) ? done() : fail();
    }
  }

  function showError(msg) {
    var e = $('dc-error');
    e.textContent = msg;
    e.hidden = false;
  }

  function init(d) {
    var v = C.validate(d);
    if (!v.ok) { showError('שגיאת נתונים בתבניות: ' + v.errors.slice(0, 3).join('; ')); return; }
    data = d;
    templates = d.templates;
    var sel = $('dc-category');
    var cats = [];
    templates.forEach(function (t) { if (cats.indexOf(t.category) === -1) cats.push(t.category); });
    cats.sort(function (a, b) { return a.localeCompare(b, 'he'); });
    sel.appendChild(new Option('כל הקטגוריות (' + cats.length + ')', ''));
    cats.forEach(function (c) { sel.appendChild(new Option(c, c)); });

    var ol = $('dc-sources-list');
    (d.sources || []).forEach(function (s) {
      var li = el('li');
      var a = document.createElement('a');
      a.href = s.url;
      a.target = '_blank';
      a.rel = 'noopener noreferrer';
      a.dir = 'ltr';
      a.textContent = s.title;
      li.appendChild(a);
      ol.appendChild(li);
    });

    $('dc-search').addEventListener('input', refresh);
    sel.addEventListener('change', refresh);
    $('dc-clear').addEventListener('click', function () {
      $('dc-search').value = '';
      sel.value = '';
      refresh();
      $('dc-search').focus();
    });
    refresh();
  }

  fetch('templates.json', { credentials: 'same-origin' })
    .then(function (r) { if (!r.ok) throw new Error(r.status); return r.json(); })
    .then(init)
    .catch(function () { showError('טעינת התבניות נכשלה. רעננו את הדף ונסו שוב.'); });
})();
