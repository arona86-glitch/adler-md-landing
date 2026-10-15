# Architecture overview (for Pinhas)

Written from what is actually in this repository (`arona86-glitch/adler-md-landing`)
as of 2026-10-15. Anything marked **[UNKNOWN – Aron to fill in]** is not visible from
this repo and must be confirmed in the dashboards (Vercel / Neon / Cloudflare / Clerk)
or in the CRM's own repo.

> **Most important thing to know:** this repo does **not** contain the CRM's web
> app or its API/database code. It contains (1) the public landing page and
> (2) a native Android app that is a thin WebView around the live CRM. The CRM
> itself lives in another repository (or deployment) that we need to locate and
> document the same way. See "Open questions".

---

## 1. The big picture

```
                         ┌────────────────────────────┐
  Browser / phone  ───►  │ adler-md.com  (this repo)  │  static landing page,
                         │ Cloudflare Workers assets  │  links only, no JS
                         └─────────────┬──────────────┘
                                       │ links out to
        ┌──────────────────┬───────────┴───────┬────────────────────┐
        ▼                  ▼                   ▼                    ▼
 er.adler-md.com   notes.adler-md.com  app.hatzolahair.org.il   scheduler.adler-md.com
 ER Chart          Doctor Notes        Hatzolah Air CRM          ER Scheduler
 [UNKNOWN]         [UNKNOWN]           [UNKNOWN – separate repo]  [UNKNOWN]
                                              ▲
                                              │ HTTPS, loaded in a WebView
                                  ┌───────────┴───────────┐
                                  │ Android app (this repo)│
                                  │ il.org.hatzolahair.crm │
                                  └───────────────────────┘
```

| Piece | Where it lives | Evidence in this repo |
|---|---|---|
| Landing page (`adler-md.com`) | This repo, root: `index.html`, `style.css`, `_headers` | `_headers` header comment: "Cloudflare response headers for the static assets Worker serving this site" |
| Hatzolah Air CRM (web) | `app.hatzolahair.org.il` | Links in `index.html`, `Config.kt` |
| Android app | This repo, `android/` | Kotlin project, GitHub Actions workflow |
| Authentication for the CRM | **Clerk** (Google / Apple / Microsoft sign-in through Clerk) | `Config.kt` allow-list of auth domains |
| Database | Neon (Postgres) per Aron | **[UNKNOWN]** not referenced in this repo |
| App hosting | Vercel per Aron | **[UNKNOWN]** not referenced in this repo; the landing page is on Cloudflare, not Vercel |
| DNS / domain | Cloudflare per Aron | **[UNKNOWN]** which zones: `adler-md.com`, `hatzolahair.org.il`? who is the registrar? |

Note the two domains: `adler-md.com` (Aaron Adler's portal) and
`hatzolahair.org.il` (the organisation's CRM). The CRM link was moved between them
in commits `39e8b58` → `9f68100`. The Android app still trusts `*.adler-md.com`
"because Clerk may still be configured on it" (`Config.kt`).

---

## 2. The landing page (`/`)

- Pure static HTML/CSS, **no JavaScript at all**. 4 cards: ER Chart, Doctor Notes,
  Hatzolah Air CRM, ER Scheduler. Each is just an `<a href>` to a subdomain.
- Only external resource: Google Fonts (Inter).
- Deployed by Cloudflare Workers static assets. `.assetsignore` keeps `android/` and
  `.github/` (and now `docs/`) **out** of the public site, otherwise they would be
  downloadable by anyone at `adler-md.com/android/...`.
- `_headers` sets a strict security posture: `Content-Security-Policy` with
  `script-src 'none'`, `frame-ancestors 'none'`, `X-Frame-Options: DENY`,
  `Referrer-Policy: no-referrer`, `X-Robots-Tag: noindex`. This is good; **if you
  ever add JS, tighten/adjust CSP deliberately**, don't just loosen it.
- How deploys happen: **[UNKNOWN]** presumably Cloudflare's Git integration on this
  repo (push to `main` → deploy). Confirm in Cloudflare → Workers & Pages.

## 3. The Hatzolah Air CRM (what we know)

From the Android README and the landing-page card text. Everything here is
secondhand until we see the source:

- Medical mission intake, patient **case management**, inquiries, missions and
  pricing, generated documents (MEDIF, Fit-to-Fly, agreements, PDFs/CSVs), staff,
  equipment, logistics, "Flight Day", tools, settings and admin.
- Public-ish **portals** reached through emailed links: family, escort, logistics
  and document-signing portals (no full login, presumably token links).
- Routes seen: `/dashboard` (start page). Native tabs map to Dashboard, Cases,
  Inquiries, Intake, More.
- Auth: Clerk; the app contains an allow-list for `*.accounts.dev` /
  `*.clerk.accounts.dev`, which is what **Clerk development instances** use.
  Either dev-mode Clerk is still in use or that entry is a leftover. **Verify: a
  production CRM holding patient data should be on a Clerk *production* instance.**

Data handled is medical/personal (patients, families, documents). Treat the whole
system as regulated-grade data (Israeli Privacy Protection Law; HIPAA/GDPR
expectations may apply depending on patients).

**[UNKNOWN – Aron to fill in]** Framework (Next.js?), ORM/migrations, API shape,
file storage for documents/attachments (Neon doesn't store files — where are
PDFs/photos?), email sender, cron jobs, env var list (names only), roles/permissions
model, backups.

## 4. The Android app (`android/`)

A single-Activity Kotlin app (~1,600 lines) that hosts the CRM in a `WebView`.
Package `il.org.hatzolahair.crm`, minSdk 26, target/compile 35.

| File | Responsibility |
|---|---|
| `Config.kt` | `HOST = app.hatzolahair.org.il`, start path, lock grace (30 s), and `isInternal()`: which URLs stay in the WebView vs. open in a Custom Tab |
| `MainActivity.kt` (832 lines) | WebView setup, link routing, native bottom tab bar kept in sync with the page URL, pull-to-refresh, error/offline screen, file chooser + camera, downloads, biometric lock, `FLAG_SECURE` |
| `MoreSheet.kt` | "More" bottom sheet (New case, Staff, Equipment, Logistics, Flight Day, Tools, Settings, Admin) |
| `FileTransfer.kt` | Downloads (uses the WebView's cookies), base64 blob saving, camera photo downsizing to 2200 px; files kept in private cache, wiped each launch |
| `Prefs.kt` | Small settings store (lock on/off, hide-in-screenshots) |

Key design decision: **nothing from the CRM is re-implemented**. All features come
from the website, so the app can't drift from the web app.

Security-relevant behaviours already built in: cleartext traffic off, backups off,
`allowFileAccess=false`, mixed content blocked, WebView debugging only in debug
builds, unknown URL schemes (`intent:`, `file:`) refused, screenshots/recents
hidden, optional biometric lock, cache wipe, sign-out clears cookies.

### Build and release

`.github/workflows/android-apk.yml` runs on every push touching `android/**` (or
manually): JDK 17 → Android SDK → `lintDebug` → `assembleDebug assembleRelease` →
uploads APKs as a GitHub Actions artifact named `hatzolah-air-crm-apk`.
Signing uses repo secrets `HAI_KEYSTORE_B64`, `HAI_KEYSTORE_PASSWORD`,
`HAI_KEY_ALIAS`, `HAI_KEY_PASSWORD`. **If those are not set, the release APK is
signed with the debug key.** Distribution is sideloading (not Play Store).

---

## 5. Environments, accounts, secrets (checklist to complete)

| Service | Role | Owner today | Pinhas access needed |
|---|---|---|---|
| GitHub | Source, CI, Android build secrets | Aron (personal account) | Collaborator → better: org + Owner |
| Cloudflare | DNS, landing-page Worker, maybe more | Aron | Super Administrator |
| Vercel | CRM hosting (per Aron) | Aron, plan: Hobby | Needs Pro + team to add members |
| Neon | Postgres | Aron | Org Admin |
| Clerk | Authentication | **[UNKNOWN]** | Admin on the Clerk app |
| Registrar | Domain ownership | **[UNKNOWN]** | Account access or transfer |
| Email provider (CRM emails) | **[UNKNOWN]** | | |
| File storage for documents | **[UNKNOWN]** | | |

Secrets that exist (names only, values never go in the repo or in chat):
the four `HAI_KEYSTORE_*`/`HAI_KEY_*` GitHub secrets; Neon connection string,
Clerk keys, email/API keys **[UNKNOWN – list the Vercel env var names]**.

## 6. Observations and first fixes Pinhas should look at

These are from reading this repo only; severity is my judgement, not a pentest.

1. **WebView trusts broad wildcard domains** (`Config.isInternal`):
   `*.accounts.dev`, `*.google.com`, `*.youtube.com`, `*.live.com`, `*.apple.com`,
   `*.adler-md.com`, etc. stay inside the WebView, and the WebView exposes a
   JavaScript bridge (`DownloadBridge.save()`) to every page it loads. The bridge
   only acts when the app just asked for a blob (`awaitingBlob`), which limits it,
   but `*.accounts.dev` is a multi-tenant domain anyone can register under. Narrow
   it to the exact Clerk hostnames in use and consider restricting the bridge by
   origin (`WebViewCompat.addWebMessageListener` with an allowed-origin set).
2. **Third-party cookies accepted** (`setAcceptThirdPartyCookies(web, true)`) and
   the user agent / `X-Requested-With` header are altered to get past Google's
   embedded-WebView block. Google's policy discourages OAuth in embedded WebViews;
   a Custom Tab / Credential Manager flow would be the proper long-term fix.
3. **Release APK may be signed with the debug key** if the secrets aren't set. Debug
   keys are public knowledge; anyone could produce an update that installs over it.
   Create and safely back up a real keystore; verify the secrets exist.
4. **Clerk development instance** possibly in use (see §3). Verify.
5. **CI hygiene:** GitHub Actions are pinned by tag (`@v4`), not commit SHA; no
   `CODEOWNERS`, no branch protection documented, no Dependabot config, no secret
   scanning evidence; lint is `abortOnError = false` so lint errors never fail the
   build; dependencies are pinned but unmonitored.
6. **Deep-link verification** needs `/.well-known/assetlinks.json` on
   `app.hatzolahair.org.il` (README says so). Check it exists and matches the real
   signing certificate fingerprint, otherwise app links silently fall back to the
   browser.
7. **No tests anywhere** in this repo.
8. The real security work (authorization on every API route, tenant/case-level
   access, portal-token entropy and expiry, document storage ACLs, audit logs,
   rate limiting, backups, database roles, PII retention) is in the **CRM repo,
   which I have not seen**.

## 7. Working agreement (suggested)

- `main` is protected: changes go through pull requests; Pinhas reviews Aron's PRs
  and vice-versa. Nobody pushes to `main` directly.
- No secrets in git, ever. Use platform env vars; rotate anything that was ever
  pasted somewhere it shouldn't have been.
- Each service has at least two Owners (Aron + Pinhas) so nothing depends on one
  person.
- Keep this document updated; every **[UNKNOWN]** above should be resolved first.

## 8. Open questions (answer these first)

1. Which repo/deployment holds the CRM code, and is it in a GitHub repo Pinhas can
   be added to?
2. Framework, ORM and where migrations live? How is the Neon DB reached
   (pooled/unpooled URLs), and are there separate dev/prod databases?
3. Where are uploaded documents/photos stored?
4. Is Clerk on a production instance? Which sign-in methods and who can sign up?
5. How are the family/escort/logistics/signing portal links generated and secured?
6. Who owns the domains at the registrar? Is anything on the Cloudflare account that
   isn't known to either of you?
7. Is there any backup/restore procedure for the database, and has it been tested?
