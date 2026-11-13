# I18n Runbook (I18N-01)

Design and contracts: `docs/architecture/i18n-translation-management.md`.
All commands run from the repository root with Node 24. Add
`--disable-warning=MODULE_TYPELESS_PACKAGE_JSON` to silence Node's notice about
running the shared `icu.ts` file.

## Add a UI message

1. Pick the namespace file in `i18n/source/` (`common`, `public`, `auth`, `teacher`,
   `admin`, `errors`, `email`, `i18nAdmin`). The key must start with that namespace:
   `teacher.courses.emptyTitle`.
2. Add English text plus a translator note:
   `"teacher.courses.emptyTitle": { "source": "No courses yet", "context": "Empty state heading on My Courses" }`.
   Use named placeholders (`{name}`) and ICU plurals
   (`{count, plural, one {# course} other {# courses}}`). Never put HTML in a message.
3. Use it: React `t("teacher.courses.emptyTitle")`, Java `translator.msg("email.…", locale, params)`.
   Keys must be literal strings so the extractor can see them. A dynamic key (such
   as `errors.<CODE>`) must have every possible value registered.
4. `node scripts/i18n/catalog.mjs extract` (updates `i18n/messages.pot` and merges
   `i18n/po/*.po`), then `node scripts/i18n/catalog.mjs generate`.
5. Commit the registry, POT, PO and generated JSON together. CI runs
   `catalog.mjs check` and fails if a key is unregistered, a message is invalid, or a
   generated file is stale.

Changing an English source text marks every existing translation of that key
`fuzzy` (needs review) and records the old text in `#| msgid`. The translation is
kept but stops shipping until a reviewer clears the flag. Removed keys become `#~`
obsolete entries; they are never deleted automatically.

## Translate platform strings (PO files)

- Edit `i18n/po/si-LK.po` / `i18n/po/ta-LK.po` in any PO editor (Poedit, Loco,
  Weblate) or a text editor. Keep `msgctxt` and `msgid` unchanged and edit `msgstr`
  only. Keep the ICU structure: the same placeholder names and the same plural
  argument, with an `other` branch.
- Clear the `fuzzy` flag only when a fluent reviewer has checked the text. A
  non-fuzzy entry merged through a reviewed pull request counts as reviewed.
- Run `generate` and then `check`.
- Optional gettext binary: `node scripts/i18n/catalog.mjs mo si-LK` writes
  `i18n/build/si-LK.mo` (not committed). It contains ICU strings, so gettext clients
  can use simple placeholders directly but need an ICU formatter for plurals.

## Machine translation (Google Cloud Translation)

Machine translation produces **drafts for reviewers**, not finished translations.

1. Create an API key in Google Cloud: enable "Cloud Translation API" and restrict
   the key to that API. Never commit the key or paste it into chat or tickets.
2. In your own terminal (so the key stays out of shell history shared with tools):
   - PowerShell: `$env:GOOGLE_TRANSLATE_API_KEY = "<key>"`
   - bash: `export GOOGLE_TRANSLATE_API_KEY=<key>`
3. `node scripts/i18n/machine-translate.mjs si-LK --dry-run` shows how many
   entries are empty. Drop `--dry-run` to translate them (`--limit N` to try a few
   first).
4. `node scripts/i18n/catalog.mjs generate`, then commit the PO and generated JSON.

What the script guarantees:

- It only fills **empty** entries. It never overwrites a human translation.
- Placeholders (`{name}`), `#` and plural structure are never sent for translation.
  Each plural branch is translated separately, and every result is re-validated
  against the English source. A result that fails validation is left empty and
  listed.
- Every filled entry is flagged `fuzzy` and gets the comment
  "machine translation (Google Cloud Translation), needs human review". Visitors keep
  seeing English until a reviewer checks the text and clears `fuzzy`.
- Tenant admins see these drafts as suggestions in Settings → Translations.
- The key is sent in the `X-goog-api-key` header, never in the URL, and is never
  printed.

## Apply drafts from any source (AI, translator, spreadsheet)

Put the drafts in a JSON map `{ "<key>": "<translation>" }`, then:

`node scripts/i18n/apply-drafts.mjs si-LK drafts.json "<where the text came from>"`

Like the machine-translation script, it only fills empty entries, validates each one
against the English source and flags it `fuzzy`. It also records the provenance as a
translator comment.

## Add a language

1. Add it to `i18n/locales.json` and to `ConfigPropertyRegistry.SUPPORTED_LOCALES` (canonical tag `xx-LK`, language, autonym, `dir`,
   aliases).
2. Add its CLDR integer plural categories to `PLURAL_CATEGORIES`/`pluralCategory` in
   `frontend/src/i18n/icu.ts` and to the Java `MessageFormatter`. Add vectors to
   `i18n/spec/format-vectors.json`; both test suites must pass.
3. If the script is not covered by the current font stack, add a Noto font in the
   root layout.
4. `extract` creates `i18n/po/<tag>.po`; translate, then `generate`.
5. Every supported language is enabled for every tenant by default, so the
   switcher offers it immediately. English stays the default. Tenant admins can turn
   languages off in Settings → Translations → Languages.

## Tenant overrides: edit, import, publish, roll back

Permissions (Staff → Permissions → Translations): `TRANSLATIONS_VIEW`,
`TRANSLATIONS_EDIT` (drafts and import), `TRANSLATIONS_PUBLISH` (publish and
rollback). Tenant Admin holds all three.

1. Settings → Translations. Pick a locale, filter by module or status, and search by
   key or English text.
2. Edit the translation and save it as a draft. A draft is never served to visitors.
   "Reset to platform translation" queues the override's removal for the next
   release.
3. Turn on **Preview drafts** to see the drafts in your own session.
4. **Import PO**: choose the locale and file. Run the dry run first and review the
   additions, changes, invalid messages and unknown keys. An import with any invalid
   message is rejected without writing anything. An import only writes drafts.
5. **Publish**: enter a change summary. This creates the next release number with
   your name and the time. If someone else published first you get a "stale" error;
   reload and try again.
6. **Roll back**: in History, choose an earlier release. Rolling back creates a new
   release with that release's content. No release is ever deleted.
7. **Export**: choose the locale, the scope (`OVERRIDES` for your tenant's published
   overrides only, or `EFFECTIVE` for everything you serve) and the format (`po`/`mo`).

## Course content variants

Tenant Admin → Courses → edit → Translations. Enter the title and description for a
locale, save the draft, preview it, then publish. Public course pages serve the
published variant for the visitor's language and fall back to the original. The
course URL, owner and access rules stay the same. The original course's language
is shown as "not declared" until an editor sets it. Nothing is inferred
automatically.

## Troubleshooting

- *A string shows in English in Sinhala/Tamil:* the platform entry is missing or
  fuzzy, or the tenant override is blank or invalid. Check the counts in Settings →
  Translations.
- *A visitor sees an old string after publishing:* the catalog `version` changes on
  every release. Hard-refresh the page. Server caches are keyed by release number.
- *`check` fails with "stale":* run `extract` and then `generate`, and commit the
  results.

## Coverage by surface (2026-11-13)

"Localized" means every visible string, accessible label, state and validation
message on that surface goes through the catalog. "Switcher only" means the language
switcher is present but the surface's text is still hard-coded English.

| Surface | Status |
|---|---|
| Tenant public home (hero, banners, Today's Classes, featured courses, teachers, CTA) | Localized (except the Today's Classes time-zone label) |
| Public header, footer, skip link, tab titles | Localized |
| Public course list/detail: course title and description | Content variants served; page chrome **not** localized; `PriceBadge` English |
| Sign-in page and auth shell (form, validation, policy consent, API errors) | Localized |
| Teacher Portal shell (nav, mobile menu, logout, footer) | Localized (except `ReconnectBanner`) |
| Teacher → My Courses | Localized |
| Teacher → My Profile | Localized |
| Shared loading / error / permission-denied states, theme toggle | Localized |
| Tenant Admin → Settings → Translations; course Translations tab | Localized (`i18nAdmin.*`) |
| Tenant Admin portal nav and all other admin pages | Switcher only |
| Student portal | Switcher only |
| Platform Admin portal (login title/description props) | Switcher only |
| Registration, forgot/reset password, account setup pages | Not localized |
| Password-reset email (platform default template) | Localized (recipient locale) |
| Account-setup email, in-app copy of the reset notice, other notifications | Not localized |
| PDF/CSV exports | Not localized (CSV stays canonical by design) |
| Announcements/broadcasts, lesson text, exams | Not localized (no content variants yet) |

### Translation status

411 registered keys: common 44, auth 34, errors 18, public 92, teacher 62,
i18nAdmin 158, email 3. **`si-LK` and `ta-LK` have 0 reviewed platform translations.**
`si-LK` has AI drafts (Claude, 2026-11-13) for all 411 keys, every one flagged
`fuzzy` with the comment "AI draft translation (Claude), needs review by a fluent
Sinhala speaker". `ta-LK` has the same for all 411 keys ("... a fluent Tamil
speaker"). Visitors still see English until a reviewer checks each entry and clears
`fuzzy`. Tenant admins see the drafts as suggestions.
Review first: `auth.*`, `public.*`, `teacher.*`, `errors.*`. Known judgement calls to
check in both languages: `auth.policyConsent.*` (verb-final word order around the
policy links),
`public.todaysClasses.browseSuffix` (reworded so it reads after the link), the
sign-in/sign-out terms (Sinhala පුරනය වන්න / ඉවත් වන්න, Tamil உள்நுழைக / வெளியேறு), and
Tamil "Tute" (குறிப்புகள்).
Tenants can already publish their own overrides. Run
`node scripts/i18n/catalog.mjs generate` to print the current counts.
