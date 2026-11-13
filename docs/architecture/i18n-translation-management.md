# I18N-01 — Multilingual UI and Translation Management (Loco-style)

Status: implemented in slices (see "Rollout and coverage" at the end).
Scope: English (`en-LK`, source), Sinhala (`si-LK`), Tamil (`ta-LK`).

> The request that started this work named MariaDB and "React". This repository is
> PostgreSQL + Flyway (root `CLAUDE.md`) with a Next.js frontend, so everything below
> is PostgreSQL/Next.js. No second database and no new i18n framework were added: the
> repo had no i18n library, so the runtime is a small in-repo formatter (one TypeScript
> implementation, one Java implementation, one shared test-vector file).

## 1. Locales

`i18n/locales.json` is the single registry (copied into backend and frontend by the
generator).

| Tag     | Autonym | Dir | Aliases accepted                | Plural categories (integers) |
|---------|---------|-----|---------------------------------|------------------------------|
| `en-LK` | English | ltr | `en`, `en-US`, `en-GB`, `en-*`  | one (n=1), other             |
| `si-LK` | සිංහල   | ltr | `si`, `si-*`                    | one (n=0,1), other           |
| `ta-LK` | தமிழ்    | ltr | `ta`, `ta-*`                    | one (n=1), other             |

Aliases are only used to *match* input (Accept-Language, `?lang=`); responses and
storage always use the canonical tag.

Tenant settings (existing tenant-config framework, `ConfigDomain.GENERAL`, no migration):

- `enabled_locales` — list, default **every supported locale** (changed 2026-11-13 so the
  switcher is offered everywhere); must contain `default_locale`.
- `default_locale` — default `en-LK`; must be enabled.
- `browser_language_detection` — boolean, default `false`.

Existing tenants have no rows for these keys, so they get all three languages offered in
the switcher while `default_locale` keeps them in English unless a visitor chooses
otherwise. A tenant admin can switch languages off.

## 2. Locale resolution (same order on server and client)

1. Explicit valid selection for this request: `?lang=` on a page URL, or the
   `X-LMS-Locale` header / `locale` query param on an API call.
2. Signed-in user's saved preference for the active tenant (`user_locale_preference`).
3. Anonymous preference for this tenant: cookie `lms_locale` (host-scoped, and the host
   *is* the tenant).
4. Only when 3 is absent and the tenant enabled `browser_language_detection`:
   best match of `Accept-Language` against **enabled** locales.
5. Tenant `default_locale`.
6. `en-LK`.

Any value that is unknown, malformed or not enabled for the tenant is ignored and the
chain continues. Only an explicit switcher action persists a choice
(`PUT /api/v1/me/locale` when signed in, the `lms_locale` cookie otherwise); background
requests, `?lang=` deep links and the `X-LMS-Locale` header never write anything.

Recipient locale (emails, queued notifications): saved preference → tenant default →
`en-LK`. Interactive exports use the requester's resolved locale; machine-oriented
exports (CSV) keep canonical values and English headers.

## 3. Messages

### 3.1 Keys and ownership

- Keys are stable, dot-namespaced, lower camel segments: `teacher.courses.title`.
  The first segment is the namespace (`common`, `public`, `auth`, `teacher`, `admin`,
  `errors`, `email`, `i18nAdmin`, ...).
- **Registry (source of truth for English):** `i18n/source/<namespace>.json`,
  `{ "<key>": { "source": "English text", "context": "translator note" } }`.
  Every key used in code must be registered there; dynamic keys (e.g. `errors.<CODE>`)
  are registered explicitly, one entry per value.
- **Platform translations:** `i18n/po/<locale>.po`, version-controlled, edited through
  pull requests (the platform release workflow). Tenants never edit these.
- **Tenant overrides:** PostgreSQL (`translation_draft`, `translation_release`,
  `translation_release_entry`), edited in Settings → Translations.
- **Generated, never edited:** `i18n/messages.pot`,
  `backend/src/main/resources/i18n/platform-catalog.json`,
  `frontend/src/i18n/generated/*.json`, optional `.mo` files under `i18n/build/`.

### 3.2 Message format (shared by TypeScript and Java)

An ICU MessageFormat **subset**:

- literal text;
- `{name}` — named placeholder, `name` matches `[A-Za-z][A-Za-z0-9_]*`;
- `{name, plural, =0 {...} one {...} other {...}}` — `other` is mandatory, `#` inside a
  branch is the locale-formatted count, branches may contain placeholders and nested
  plurals;
- quoting: `''` is a literal apostrophe; `'{`…`'` quotes braces/`#` (ICU
  apostrophe mode DOUBLE_OPTIONAL); any other apostrophe is literal.

No `select`, `date`, `number` or HTML tags. Output is always plain text, rendered as a
React text node or HTML-escaped by Java mail templates — translation text is never
evaluated or rendered as HTML.

Validation (on draft save, PO import and publish): must parse; at most 5000 chars; the
set of placeholder and plural-argument names must equal the English source's; each
plural has `other` and only categories valid for the target locale (`one`, `other`) or
`=N`. Implementations: `frontend/src/i18n/icu.ts` (also used by the Node tooling) and
`com.lms.localizationmanagement.format.MessageFormatter`. Both are tested against
`i18n/spec/format-vectors.json`.

### 3.3 PO mapping

```
#. <translator context from the registry>
#: i18n/source/teacher.json
#, icu-format[, fuzzy]
#| msgid "<previous English source>"      (only when the source changed)
msgctxt "teacher.courses.count"
msgid "{count, plural, one {# course} other {# courses}}"
msgstr "{count, plural, one {පාඨමාලා #} other {පාඨමාලා #}}"
```

- `msgctxt` = key, `msgid` = English source, `msgstr` = translation in the same ICU
  subset. Plurals live **inside** the ICU string; `msgid_plural`/`msgstr[n]` are not
  used and an import containing them is rejected as invalid.
- Review status: empty `msgstr` = missing; `fuzzy` = needs review (not shipped);
  non-fuzzy `msgstr` = reviewed/translated (committing it through a reviewed PR is the
  reviewer's sign-off).
- Merge (`extract`): new keys are added untranslated; when English source changes the
  entry is marked `fuzzy` with `#| msgid` holding the old source; removed keys become
  `#~` obsolete entries. Existing work is never dropped. Output is sorted by key and
  byte-stable.
- `.mo` (optional export): standard gettext MO, entries keyed `msgctxt\u0004msgid`.
  External gettext clients receive the ICU strings verbatim: plain placeholders work
  with simple substitution; plural messages need an ICU-capable formatter. The MO
  header declares `Plural-Forms: nplurals=1; plural=0;` because gettext plural
  selection is not used.

### 3.4 Tooling

`node scripts/i18n/catalog.mjs <command>` (Node 24, no dependencies):

- `extract` — scan frontend/backend code for literal `t("…")`/`msg("…")` keys, verify
  each is registered, write `i18n/messages.pot`, merge into every `i18n/po/*.po`.
- `generate` — validate and write the generated JSON for backend and frontend.
- `check` — CI: fail on unregistered keys, invalid messages, or generated files that
  differ from what `extract` + `generate` would write.
- `mo <locale>` — write `i18n/build/<locale>.mo`.

## 4. Fallback chain for a UI string

1. Published tenant override for the resolved locale.
2. Reviewed platform translation for that locale.
3. English source.

Blank or invalid values count as missing at every step. Drafts are never in this
chain; they are visible only in the authorized preview catalog.

## 5. Tenant overrides — draft, publish, rollback

- `translation_draft` — one working row per (tenant, locale, key): `SET` with a value,
  or `RESET` ("reset to platform translation": the key is dropped from the next
  release). Each row has a `version` for optimistic concurrency.
- `translation_release` — immutable published versions per (tenant, locale),
  `version_no` 1, 2, 3, … with editor, timestamp, summary, `rollback_of_release_id`.
- `translation_release_entry` — the full override snapshot of that release
  (key, value, English source hash at publish time).
- Current published overrides = entries of the highest `version_no`.
- **Publish** validates every draft, builds the next snapshot (previous snapshot +
  SETs − RESETs), inserts it as `version_no = expected + 1` (unique constraint
  = concurrency guard; `409 TRANSLATION_RELEASE_STALE`), deletes the consumed drafts
  and writes an audit entry.
- **Rollback** creates a new release whose snapshot copies an older release. Nothing
  is deleted.
- **Import** (PO, dry run first) writes drafts only. Limits: 1 MiB, UTF-8, the PO
  `Language` header (if present) must equal the selected locale, only registered keys,
  every message valid. A non-dry run with any invalid message is rejected with no
  writes; unknown keys are ignored and reported.
- **Export**: explicit `locale` and `scope` — `OVERRIDES` (published tenant overrides
  only) or `EFFECTIVE` (every key with the value the tenant actually serves).
- Override entries remember the English source hash; when the platform source changes
  the override shows "needs review" in the editor (it is still served).

## 6. API (all tenant-scoped from the host/authenticated context; no tenant id accepted)

Public (anonymous allowed, principal used when present):

- `GET /api/v1/public/i18n/settings` → `{ supportedLocales[{tag,autonym,dir}],
  enabledLocales[], defaultLocale, browserLanguageDetection }`
- `GET /api/v1/public/i18n/catalog?locale=&ns=common,teacher` →
  `{ locale, resolvedFrom: EXPLICIT|USER|COOKIE|BROWSER|TENANT_DEFAULT|PLATFORM,
  version, namespaces[], messages{key:text} }` — effective strings, `common` always
  included, `Cache-Control: no-cache`, `Vary: Accept-Language, Cookie, Authorization,
  X-LMS-Locale`. `version` = `<platformHash>.<tenantReleaseNo>`.

Signed-in user:

- `GET /api/v1/me/locale` → `{ locale | null }`
- `PUT /api/v1/me/locale` `{ locale }` → `400 LOCALE_NOT_ENABLED` when not enabled.

Translation management (`/api/v1/i18n/admin`, capability in brackets):

- `GET /entries?locale&namespace&q&status&page&size` [VIEW] →
  `{ counts{total,missing,translated,needsReview,overridden,drafts}, page }`, items
  `{ key, namespace, source, context, platformValue, platformStatus, suggestion,
  publishedOverride, overrideNeedsReview, draft{action,value,version,updatedAt}|null,
  effectiveValue, origin: OVERRIDE|PLATFORM|SOURCE }`
- `PUT /drafts/{locale}/{key}` `{ value, expectedVersion }` [EDIT]
- `POST /drafts/{locale}/{key}/reset` `{ expectedVersion }` [EDIT]
- `DELETE /drafts/{locale}/{key}?expectedVersion=` [EDIT]
- `GET /preview-catalog?locale&ns` [VIEW] — drafts applied, `no-store`
- `POST /import?locale&dryRun` (multipart `file`) [EDIT]
- `GET /export?locale&scope=OVERRIDES|EFFECTIVE&format=po|mo` [VIEW]
- `GET /releases?locale` [VIEW], `POST /publish` `{ locale, expectedVersion, summary }`
  [PUBLISH], `POST /releases/{id}/rollback` `{ expectedVersion, summary }` [PUBLISH]

Errors: `TRANSLATION_INVALID` (422, `details.problems[]`), `TRANSLATION_DRAFT_STALE`
(409), `TRANSLATION_RELEASE_STALE` (409), `NOTHING_TO_PUBLISH` (409),
`UNKNOWN_MESSAGE_KEY` (404), `LOCALE_NOT_SUPPORTED` (400), `LOCALE_NOT_ENABLED` (400),
`PO_IMPORT_INVALID` (422), `PAYLOAD_TOO_LARGE` (413).

API error messages: the server keeps stable codes and its English `message`; the client
localizes exactly once via `errors.<CODE>` keys and falls back to the server message.

## 7. Permissions

New `DomainArea.TRANSLATIONS` (VIEW, CREATE_EDIT, APPROVE), held by Tenant Admin.
Capabilities in Staff → Permissions (group "Translations"), configurable for
Operator/Manager/seeded staff roles, off by default:
`TRANSLATIONS_VIEW`, `TRANSLATIONS_EDIT` (edit drafts, import),
`TRANSLATIONS_PUBLISH` (publish, rollback). Language settings use the existing tenant
settings permission. Platform catalogs have no runtime write path.

## 8. Tenant-authored content

UI catalogs never hold tenant content. Course public title/description variants:

- `course.content_locale` (nullable; legacy rows stay `NULL` = "not declared"; nothing
  is inferred or relabelled).
- `course_localization (tenant_id, course_id, locale, title, description, status
  DRAFT|PUBLISHED, version, …)` — one canonical course row keeps its id, slug/URL,
  owner and authorization; variants are additional fields only.
- Public reads return the PUBLISHED variant for the resolved locale, else the original,
  with `contentLocale` (the locale actually served, or `null` for legacy original) and
  `availableLocales`. Drafts are visible only through the authorized editor/preview.
- Editing: Tenant Admin course editor → "Translations" (COURSES_EDIT); publishing a
  variant uses the same capability as editing the course.

Not yet localized (reported, not hidden): announcements/broadcasts, lesson text,
exams, media, names and identifiers.

## 9. Caching

- Server: platform catalog loaded once at startup; effective tenant catalogs cached in
  memory keyed `(tenantId, locale, releaseNo)` — a new release changes the key.
- Client: React Query key `["i18n", host, locale, version|"latest", namespaces, draft?]`;
  switching tenant (host) or locale changes the key; preview catalogs are never shared
  with the production key.

## 10. Rollout and coverage

Order: (1) pipeline + backend catalog/publishing; (2) runtime, switcher, tenant home,
sign-in, Teacher Portal (shell, My Courses, My Profile); (3) Translation Management UI;
(4) course content variants; (5) remaining surfaces module by module.

The per-surface coverage table is maintained in `docs/operations/i18n-runbook.md`.

## 11. As built: differences from the plan above

These are binding. Where they conflict with §5, §6 or §8, this section wins.

- **Releases**: `{id, locale, versionNo, summary, entryCount, rollbackOfReleaseId,
  publishedBy (user id), publishedAt, current}`, newest first. Publish and rollback
  return `201`. Both release tables reject UPDATE/DELETE with a database trigger and
  use append-only repositories.
- **Import report**: counts instead of lists. The shape is `{locale, dryRun, valid,
  entries, created, updated, unchanged, skippedEmpty, skippedFuzzy, skippedObsolete,
  unknownKeys[], sourceChanged[], invalid[{key, problems[]}]}`. A file-level rejection
  returns 422 with `details.reason` set to one of `EMPTY`, `NOT_TEXT`, `ENCODING`,
  `SYNTAX`, `LANGUAGE_MISMATCH` or `CHARSET`. `dryRun` defaults to `true`.
- **Drafts**: send `expectedVersion: null` when no draft exists. A blank value is
  rejected (422, problem `EMPTY`). `DELETE` returns `204`. The entries status filter
  value is `DRAFT`, and the page size is capped at 200.
- **Locale resolution**: Accept-Language is used when there is no *usable*
  `lms_locale` cookie (an invalid or disabled cookie counts as absent). A `locale`
  query parameter, valid or not, takes the place of the `X-LMS-Locale` header.
- **Course variants** live in `coursemanagement` (the module that owns the table),
  which calls `localizationmanagement.api`.
  - `GET /courses/{id}/localizations` returns `{courseId, contentLocale, originalTitle,
    variants[{locale, title, description, status, publishedTitle,
    publishedDescription, publishedAt, publishedBy, hasUnpublishedChanges, version,
    updatedAt}]}`.
  - `published_title`/`published_description` columns keep the public text
    stable while an editor works on the draft.
  - Publish and unpublish take `{expectedVersion}`. A stale version returns 409
    `COURSE_LOCALIZATION_STALE`. The description limit is 20000.
  - No endpoint sets `course.content_locale` yet, so it stays `NULL` ("not declared").
- **Supported tags** are also a constant in
  `ConfigPropertyRegistry.SUPPORTED_LOCALES`, because tenant management cannot depend
  on the localization module. A test keeps the constant equal to the generated
  catalog.
- **API errors in the client**: English keeps the server's own (more specific)
  message. Sinhala and Tamil use `errors.<CODE>`, falling back to the server message.
  `USER_SUSPENDED` has the same text as `INVALID_CREDENTIALS`, so the message does not
  reveal whether an account exists.
- **CORS**: `X-LMS-Locale` was added to the allowed headers of the local CORS
  configuration. A deployment that serves the API cross-origin must also allow it.
- **Email**: only the platform-default password-reset email is localized, using the
  recipient's locale. A tenant-customized template is sent as written.
