# הנחיות שחרור (Discharge Instructions)

Static, client-side module served at `/discharge-instructions/`. No backend, no storage, no analytics.

- `templates.json` — canonical clinical data (100 templates, 27 categories, 52 sources). Single source of truth; edit only via a reviewed data change.
- `discharge-core.js` — pure logic (validation, search, copy text); unit-tested.
- `discharge.js` / `discharge.css` — UI, edit-before-copy (in-memory only), print stylesheet.
- `RTL_NOTES.md` — Hebrew RTL engineering notes from the handoff.

Run tests: `node --test tests/discharge.test.js`

`_headers` gives this path its own CSP (`script-src 'self'`, `connect-src 'self'`); the rest of the site keeps its strict no-script policy.
