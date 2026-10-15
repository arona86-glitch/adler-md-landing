# Hatzolah Air CRM — system overview for Pinhas

Audience: a professional engineer joining the project. Goal: understand the whole
system, know where the risks are, and be able to contribute safely from day one.

Basis: I read the code of both repositories on 2026-10-15
(`arona86-glitch/hatzolah-air-crm` at commit `7f174f2`, and
`arona86-glitch/adler-md-landing`). I did **not** run the app, and I have no access
to the Vercel, Neon, Clerk, Cloudflare or Google dashboards. Statements are tagged:

- **[code]**: verified in the source today.
- **[docs]**: stated in the repo's own docs (`ARCHITECTURE.md`, `PROJECT_STATE.md`,
  `docs/*`), which may be stale (PROJECT_STATE was last verified 2026-08-30).
- **[verify]**: cannot be known from code; check in the dashboard.

The CRM repo already has good documentation. Read these first, in this order:
`README.md` → `ARCHITECTURE.md` → `PROJECT_STATE.md` → `docs/*.md`. This file is the
cross-repo map plus an independent security read; it does not replace them.

---

## 1. The system at a glance

```
                       ┌────────────────────────┐
 Public ─────────────► │ adler-md.com           │  Static landing page (repo: adler-md-landing)
                       │ Cloudflare Workers     │  Links only, no JS, strict CSP
                       └───────────┬────────────┘
                                   │ links to
   ┌───────────────┬───────────────┼───────────────────────┐
   ▼               ▼               ▼                       ▼
 er.adler-md.com  notes.adler-md.com  app.hatzolahair.org.il   scheduler.adler-md.com
 (other apps,     (other apps,        THE CRM (repo:            (other apps,
  not in scope)    not in scope)       hatzolah-air-crm)         not in scope)
                                           ▲
                          ┌────────────────┴───────────────┐
                          │ Android app (in adler-md-landing/android)
                          │ WebView wrapper of the CRM       │
                          └──────────────────────────────────┘

 The CRM (Next.js 15 on Vercel)
   ├── Clerk ............ sign-in for staff (Google/Apple/Microsoft etc.)
   ├── Neon Postgres .... all application data (Prisma ORM)
   ├── Google Drive ..... every document, attachment, receipt, passport (service account)
   ├── Gmail SMTP/IMAP .. outbound notifications; inbound email intake polling
   ├── WhatsApp (Meta) .. inbound webhook intake
   ├── Gemini API ....... medical summaries, intake matching, receipt reading
   └── Vercel cron ...... 3 daily jobs
```

| Layer | Provider | Evidence |
|---|---|---|
| CRM hosting | Vercel (project `hatzolah-air-crm`) | `apps/web/vercel.json`, README "Domain setup", CI comments |
| CRM domain | `app.hatzolahair.org.il`, A record `76.76.21.21` at the org's DNS registrar | README [docs] |
| Old domain | `app.adler-md.com` retired; middleware 308-redirects pages, leaves `/api/*` alone | `middleware.ts` [code] |
| Database | Neon Postgres, `eu-central-1`, one database, pooled + direct URLs | `docs/database-defense-in-depth.md` [docs] |
| Auth | Clerk | `packages/auth` [code] |
| Landing page | Cloudflare Workers static assets | `_headers` in landing repo [code] |
| Mail | Gmail SMTP using `CRM@hatzolahair.org.il` app password; Resend fallback | `.env.example` [code] |

Note: Cloudflare hosts the landing page only. Whether the *CRM's* DNS is also in
Cloudflare is **[verify]**; the README only says "the registrar managing
hatzolahair.org.il's DNS".

## 2. CRM repo layout [code]

Turborepo + pnpm 10 workspaces, Node ≥ 20, TypeScript.

```
apps/web        ← the ONLY deployed app: Next.js 15 App Router, 110 API route files
apps/family|escort|landing   ← empty stubs, never deployed (ignore)
packages/db     ← Prisma schema (45 models), migrations (SQL), audit log, ID generation, durable jobs
packages/auth   ← Clerk session helpers + role/capability functions (+ unit tests)
packages/config ← shared TS/ESLint config
scripts/        ← one-off Node/TS scripts (several touch the production DB, see §5)
eval/           ← scaffold for AI regression evaluation (not populated)
docs/           ← hardening docs written by earlier Claude Code sessions
```

Route groups in `apps/web/src/app`:

| Group | Auth | Purpose |
|---|---|---|
| `(auth)` | public | Clerk sign-in/up |
| `(internal)` | Clerk session + role | Dashboard, cases, inquiries, intake, equipment, logistics, staff, finance, settings, admin, tools |
| `(flight-day-staff)` | Clerk, incl. `FLIGHT_DAY` partner role | Flight Day booking board |
| `/portal/[token]` | token only | Family consent + attachments |
| `/escort/[token]` | token **and** staff session | Escort PCR, checklist, expenses, equipment |
| `/logistics/[token]` | token only | Equipment stock editing |
| `/sign/[token]` | token only | Document signing |
| `/request`, `/flight-day`, `/onboarding`, `/flight-day-access` | public / sign-up | Public inquiry form, donor signup, staff onboarding, partner access request |

## 3. Core domain model

- **Inquiry** (public form) → staff convert → **Case** (+ `Patient`, `Contact`,
  `Task`, `CaseAttachment`, `Document`, `Expense`, `CommunicationLog`).
- **Mission planning**: `MissionScenario` → `MissionLeg`/`MissionPayload`/
  `CostLineItem` → `MissionCalculation`; fleet in `Aircraft` + effective-dated
  configuration/cost profile; `Airport` (~10k rows), `AirportServiceQuote`,
  `AirspaceRestriction`. Economics are gated by `canViewMissionEconomics`.
- **People/access**: `User` (role + positions + `flightDayManager`),
  `MedicalCredential` (review status), `UserSignature`, `PortalToken`,
  `SigningRequest`.
- **Ops**: equipment catalog/units/stock/checklists, escort mission checklist, PCR.
- **Finance** (admin only): `FinanceEntry`/`FinanceAttachment`, Excel import/export,
  AI receipt scanning.
- **Flight Day**: `FlightDayBooking`/`FlightDayPassenger` (public donor signups with
  passport uploads).
- **Governance**: `AuditLog`, `ExternalJob` (retry queue), `Setting`,
  `UserDeletionRequest`.

Roles (hierarchy in `packages/auth/src/server.ts`): `ADMIN 100 > MEDICAL_DIRECTOR 80
> COORDINATOR 60 > ESCORT 40 > READ_ONLY 10 > FLIGHT_DAY 5`. New sign-ups are created
`READ_ONLY` + `isActive:false` until an Admin approves. An Admin can "view as"
another role via a cookie, honored only when the stored role is ADMIN, so it can only
narrow access [code].

## 4. Key flows

1. **Public inquiry**: `POST /api/inquiries`: honeypot, 5/hr/IP (DB-counted),
   validation + magic-byte file checks → `Inquiry` + Drive upload → staff email.
2. **Case folder**: created via `ExternalJob` with exponential backoff, daily retry
   cron, human attention after 5 failures.
3. **Documents**: generators (MEDIF fillable PDFs for El Al/Emirates/Condor, Fit-to-Fly,
   agreements, quote, medical summary) → HTML/PDF → Drive. Medical-summary Markdown is
   rendered with `marked` then allowlist-sanitized (`sanitize-html`) before saving.
4. **Signing**: staff send a `SigningRequest`; signer opens `/sign/[token]`; a
   signature is only recorded if the signed copy was stored.
5. **Intake**: IMAP poll (daily cron) and WhatsApp webhook (HMAC signature verified)
   → `IntakeItem`; Gemini proposes the matching case.
6. **Portal tokens**: 256-bit random, only the SHA-256 hash is stored (`PortalToken.token`),
   with expiry, revocation, usage counters, and audit entries. Escort links also
   require a case-relevant staff session (`canAccessEscortPortal`).

## 5. Where this system is fragile or risky

Severity is my judgement from reading the code; no penetration test has been done.
Ordered by what I'd fix first.

### Critical: operational foot-guns for a new contributor
1. **There is no development database.** Local dev, `pnpm db:migrate`
   (`prisma migrate dev`), `pnpm db:seed` and one-off scripts all use whatever
   `DATABASE_URL` is in `.env.local`, which in practice is **production** [docs +
   code]. `scripts/reset-and-seed-demo-data.ts` **deletes every case, patient,
   inquiry, expense, audit log and all staff except one admin account** (its
   `--dry-run` flag is opt-in, not the default). `prisma migrate dev` can also
   prompt to reset a drifted database. **Do not run any DB command until a separate
   Neon branch exists for development.** Recommended: a Neon branch per developer;
   guard scripts so they refuse to run unless an explicit `ALLOW_DESTRUCTIVE=1`
   is set and the host isn't the prod endpoint; make dry-run the default.
2. **The runtime app role is the database owner** (`neondb_owner` for both
   `DATABASE_URL` and `DATABASE_DIRECT_URL`) [docs]. Any leaked env var, SQL injection
   or dependency compromise gives full DDL/drop rights. Create a restricted runtime
   role (DML only) and keep the owner role for migrations only. Plan: Neon branch →
   create role + grants → run test suite and smoke test → cut over in Vercel.
3. **Clerk was a development instance in production** (`pk_test_…`,
   `clerk.accounts.dev`) as of 2026-08-24 [docs], and the Android app still
   whitelists `*.accounts.dev` [code], which suggests it still is. Migration notes
   are in `docs/clerk-production-migration.md`. The key trap: `syncClerkUser()`
   matches on `clerkId` only, so after switching instances every existing staff member
   would get a new `READ_ONLY`, inactive row and be locked out of their real one.
   Ship the email-fallback fix (or pre-map IDs) **before** switching keys. No MFA is
   enforced for any role [docs]. Require it at least for ADMIN and MEDICAL_DIRECTOR.
   Also: deactivating a user in the CRM does not revoke their Clerk sessions;
   `getSessionUser()` re-checks `isActive` on each request, which mitigates this for
   API/page access [code].
4. **No enforced review gate.** Branch protection is unavailable on a private repo
   under the free plan [docs]. CI runs lint/typecheck/test/build but nothing forces
   it to pass before merge, and Vercel likely deploys `main` on push regardless
   **[verify in Vercel → Git settings]**. Fix: GitHub organization or Pro, then require
   PRs + the `ci` check on `main`. The repo has had effectively one human author and
   AI-authored PRs; a second human reviewer is the single biggest control you can add.

### High: data protection and compliance
5. **Patient data goes to Google Gemini** (medical records, discharge summaries,
   imaging, receipts, intake messages) [code]. Confirm the API key is on a paid tier /
   terms that exclude training on prompts, that this is covered in the privacy policy
   and consent text, and that this is compliant with the Israeli Privacy Protection
   Law and its data-security regulations. The model alias is `gemini-flash-latest`, so
   behavior can change without a deploy.
6. **Google Drive is the document store** [code]. Everything (records, passports,
   signed documents) lives in Drive, referenced by URL in the DB. Review the service
   account's scope, folder sharing settings, who in the Workspace can see these
   folders, and retention (Flight Day passports have no retention policy [docs]).
7. **Backups**: Neon PITR window and a tested restore are unconfirmed [docs]. Do one
   restore drill into a new branch. Drive content is not backed up by Neon at all.
8. **Family portal and signing links are bearer tokens with no second factor**
   [code]: anyone holding the link sees the case data it exposes until it expires.
   Reasonable for the use case, but confirm expiry defaults, that links are not
   logged by email gateways/WhatsApp previews, and that the data returned is
   minimized (PROJECT_STATE lists "data minimization" as not done).
9. **Logistics link** lasts 365 days, edits equipment stock, old links are not retired
   when new ones are issued, and the stored `scope` is not enforced [docs + code].

### Medium: application security
10. **Authorization is per-route and manual**, not centralized: middleware only checks
    "has a Clerk session" (and a public allowlist); each of 110 route files must call
    `getSessionUser()`/`requireRole()`/a capability helper itself [code]. A forgotten
    check silently becomes an IDOR. My heuristic scan found only intended exceptions
    (health, onboarding, flight-day-access, flight-day/slots; finance routes use
    `requireFinanceUser`). Add a test that enumerates every `route.ts` and fails
    unless it either calls an approved guard or is on an explicit public allowlist,
    and add case-level ownership checks tests (can a COORDINATOR open another
    coordinator's case?). Intended behavior needs a product decision.
11. **No Content-Security-Policy on the CRM** (other headers and HSTS preload are
    set) [code]; PROJECT_STATE lists this as pending. The landing page has a strict
    one. Start with Report-Only.
12. **Rate limiting is ad hoc**: DB-counted per-IP limits on `/api/inquiries` and Flight
    Day creation; nothing evident on `/api/onboarding`, `/flight-day-access`, token
    endpoints, WhatsApp webhook, or login. Use Vercel Firewall/WAF rules or an edge
    limiter; Cloudflare in front would also help.
13. **Secret comparison**: cron `Bearer` secret and WhatsApp verify token use plain
    `===`/`!==` [code]. Low practical risk, trivial fix: `crypto.timingSafeEqual`.
    The cron routes fail closed when the secret is unset, which is good.
14. **Vercel limits**: the 4.5 MB request-body limit is worked around by one-file-per-
    request uploads; base64 data URLs are still used in `/api/onboarding`. Function
    timeouts on the Hobby plan (10 s default) are a risk for Gemini/PDF work
    **[verify]**.
15. **`dangerouslySetInnerHTML`** appears once (`EscortPCRClient.tsx:957`) but only
    receives hard-coded section titles. Benign today; replace with JSX so a future
    dynamic title can't become an XSS sink.
16. **Dependencies and supply chain**: no Dependabot, `npm audit`/OSV step, secret
    scanning or SAST in CI; GitHub Actions pinned by tag not SHA. I grepped the working
    tree for obvious secret patterns (private keys, Neon URLs, Clerk/Google keys) and
    found none; git history was not scanned (the clone is shallow). Run a full-history
    scan (e.g. gitleaks/trufflehog) as part of onboarding.
17. **Testing**: Vitest unit tests exist for validation/helpers; no integration or e2e
    tests, so no regression net around authorization or the DB layer. AI evaluation
    set is not built.

### Android app (adler-md-landing/android) [code]
- WebView wrapper with a download JS bridge exposed to every page it loads, and an
  allowlist that trusts broad wildcard domains (`*.accounts.dev`, `*.google.com`,
  `*.youtube.com`, `*.live.com`, `*.apple.com`, `*.adler-md.com`). Once Clerk is on a
  production instance, narrow this to exact hostnames.
- It spoofs the user agent and drops `X-Requested-With` to get past Google's block on
  embedded sign-in, and accepts third-party cookies.
- Release builds are signed with the debug key unless the four `HAI_KEYSTORE_*`
  secrets are set. Distribution is by sideloading APKs from CI artifacts.
- Needs `/.well-known/assetlinks.json` on the CRM domain for verified app links
  **[verify]**.

### What is already good [code]
Hashed, expiring, revocable portal tokens with audit logging; server-side audit log;
Zod validation across routes; magic-byte upload checks and filename sanitization;
HTML-escaping in emails and AI output sanitization; serializable transactions for seat
booking; fail-closed cron auth; HMAC-verified WhatsApp webhook; HSTS and other
headers; no raw SQL except three fixed queries; `.env*` ignored; durable retry queue;
CI on every push; honest documentation of known gaps.

## 6. Day-one checklist for Pinhas

1. Get access (GitHub, Vercel, Neon, Clerk, Cloudflare, Google Workspace/Drive): see
   the separate access guide. Enable 2FA everywhere.
2. **Before touching any data**: create a Neon branch (or separate project) for
   development and put *its* URLs in `apps/web/.env.local`. Never use the production
   URLs locally.
3. `pnpm install`; `pnpm --filter @hai/db run generate`; `pnpm typecheck`; `pnpm test`.
4. Run a full-history secret scan on both repos. Rotate anything found.
5. Review Vercel project settings: Git branch deploy rules, env var list (names only),
   team plan, regions, function limits, firewall; Clerk instance type, sign-up rules
   and MFA; Neon backup window and roles; Google service account permissions.
6. Agree the PR workflow (§7) and turn on branch protection.

## 7. Working agreement (proposal)

- Everyone, including Aron, works on branches and opens PRs against `main`; the other
  person reviews. CI must pass. Nobody pushes directly to `main`.
- Schema changes: write the migration SQL, apply it to a Neon branch first, then run
  `prisma migrate deploy` on production **before** deploying code that needs it (the
  repo has hit this ordering already).
- No secrets in git, chats or screenshots. Production credentials only live in Vercel
  env vars and the people's password managers.
- Two Owners on every service. Keep `docs/` and `PROJECT_STATE.md` current.

## 8. Open questions for Aron (please answer so this doc can be finalized)

1. Is the Clerk production instance live, or still the development one?
2. Which Vercel plan, and does `main` auto-deploy? Who else can access Vercel?
3. Neon: plan, PITR window, any branches, who has access?
4. Is the CRM's DNS in Cloudflare or at another registrar? Who owns each domain?
5. Is the Gemini key on a paid tier? Is there a privacy policy / consent text that
   mentions AI processing?
6. Which Google account owns the Drive root folders and the service account?
7. Does anything else (Make/Zapier, Meta WhatsApp app, other webhooks) call the CRM?
