# Tenant Appearance and Public Home Parity

Status: COMPLETE for all screenshot-confirmed requirements (pass 2, 2026-10-29), verified in a live browser against the local stack. R20 (receipt rendering) is BLOCKED: no receipt renderer exists. U1 is out of scope. Previous "PARTIAL" claims were re-audited from code and runtime.

References: `docs/references/Klass-Visual-Configuration-Options/Klass-Visual-configuration-Settings.png`,
`home-page-based-on-settings.png`. No Klass names, logos, banners, contacts or sample data are copied.

## 1. Audit findings (pass 2, before edits)

| # | Area | Defect found in code/runtime |
|---|---|---|
| A1 | Public layout | `(public)/layout.tsx` renders a hard-coded "LMS Platform" header/footer **and** `page.tsx` renders its own header/footer → double chrome on `/`. Other public routes never show tenant logo/name. |
| A2 | Theme | `TenantThemeProvider` is mounted with no `theme` prop → tenant colors/fonts are never applied anywhere. It also writes to `document.documentElement`, which would restyle admin screens. |
| A3 | Fonts | Only `GEIST/SYSTEM/SERIF`; no Google Fonts, no search, sample or reset. |
| A4 | Colors | Only primary/secondary as free text inputs; no picker, presets, accent/background/surface/text, or contrast feedback. |
| A5 | Logos | Header logo forced into `size-9` (36 px square) → wide logos render tiny; footer `size-8`. No size tokens, no guidance, no preview. |
| A6 | Banners | Settings exposes raw JSON URL arrays **and** single-asset upload fields; uploaded banner assets are never shown on the home page (projection reads only URL config); banner upload replaces nothing / list impossible. |
| A7 | Featured courses | Raw JSON UUID input; projection returns `pricingModel/amount/currency/cover = null` → cards show "Explore course details" instead of price/access labels. |
| A8 | Teachers | Home "teachers" section is static text; no carousel, no View Profile, despite `/api/v1/public/teachers` and `/teachers/[id]` existing. |
| A9 | Public assets | Signed URLs expire after 5 min while the page may stay open/cached → broken images. |
| A10 | Settings UI | Not Klass-like: no Appearance tab within Settings, no toggles with descriptions, no Visit Page/View Support Page actions, no Language section. |
| A11 | Language | No i18n exists; the platform supports English only. |
| A12 | Migrations | V104 (`tenant_asset`) is already applied on `lms_dev`; it must not be edited. Any schema change needs V105+. |

## 2. Requirements matrix

Legend — Source: **S** = confirmed by reference screenshot, **P** = additional requirement from the task prompt, **U** = unverified reference behavior (not visible in screenshots). Evidence files are in `docs/qa/tenant-appearance/`.

| ID | Src | Requirement | Defect (audit) | Fix location | Verification | Status |
|---|---|---|---|---|---|---|
| R1 | S | Appearance tab in Settings; light bg, white cards, headings, separators | A10 | `settings/appearance/page.tsx`, `components/settings/settings-tab-strip.tsx` | Live: `03-settings-desktop/mobile.png` | PASS |
| R2 | S | Logo / favicon / receipt logo upload-change-remove + guidance | A5 | `asset-upload-field.tsx`, `lib/branding/*`; BE per-type limits | Live UI upload 201 → reload → public header (`07-*`); GIF rejected (e2e) | PASS |
| R3 | S | Homepage URL is the logo destination | — | `public-chrome.tsx` | e2e "external homepage URL becomes the logo destination" | PASS |
| R4 | S | Public page / Sub-hero / CTA switches with descriptions | — | settings page + home | Live: toggles off → sections absent (`09-*`); disabled page (`10-*`) | PASS |
| R5 | S | Featured courses multi-select | A7 | settings multi-select; BE `getPublishedCourseCards` | Live: 2 picked in UI → same 2 on public home with real price labels (`08-*`) | PASS |
| R6 | S | Support video + View support page | — | settings + home `#support` | e2e embed test; link `/#support` | PASS |
| R7 | S | Desktop/mobile banner galleries (≤6, 2 MB) | A6 | `BannerGallery`; BE list + cap | Live: 2 desktop + 1 mobile uploaded in UI → `<picture>` at 1440/390 (`07-*`); cap 422 (BE IT + e2e) | PASS |
| R8 | S | Visit page action | — | settings | e2e | PASS |
| R9 | S | Default language | A11 | settings section E | Shows English (disabled), with an explanation that English is the only supported language | PASS (English only) |
| R10 | S | Home composition: header, hero, sub-hero, featured, teachers, CTA, footer with sitemap/contact | A1, A7, A8 | `(public)/layout.tsx`, `public-chrome.tsx`, `(public)/page.tsx` | Live `01-*`, `07-*`, `13-*` at 1440/768/390/320; chrome rendered once (e2e) | PASS |
| R11 | P | Heading/body font pickers: curated, search, sample, default, reset | A3 | `font-picker.tsx`; BE allowlist | Live: Poppins/Lato chosen in UI → persisted → public h1 Poppins, body Lato | PASS |
| R12 | P | Self-hosted predeclared fonts, swap, fallbacks incl. Sinhala/Tamil | A3 | `lib/fonts/public-fonts.ts` | Live: only 3 font files load (configured family); all `.woff2` blocked → metric fallback, no overflow (`16-*`) | PASS |
| R13 | P | 5 theme colors, picker+hex+swatch+reset, presets, contrast feedback, server validation | A4 | `theme-colors.tsx`, `lib/theme/color.ts`; BE registry | Live: Forest preset saved → `--primary #15803D`; low contrast 400 server-side, Save blocked in UI (e2e) | PASS |
| R14 | P | Tokens scoped to public pages; admin not restyled | A2 | `public-theme-scope.tsx`, `globals.css` | Live: `<html>` has no tenant vars on any public page; admin font fixed to Geist (was falling back to serif because `--font-sans` referenced itself) | PASS |
| R15 | P | Logo size tokens, contain, reserved box, guidance, padded/low-res warnings, desktop+mobile preview | A5 | `public-logo.tsx`, `globals.css`, `logo-size-preview.tsx`, `image-checks.ts` | Live (`14-header-*`): wide 147/187/227 px for Small/Standard/Large at desktop; square 56×56; padded and low-res logos warned in UI (`15-*`) | PASS |
| R16 | P | Teacher carousel: prev/next, swipe, keyboard, 0/1/many, View profile, optional auto-rotate | A8 | `teacher-carousel.tsx`; BE `teacher_auto_rotate` | Live 390 px: Next scrolls, keyboard Prev, View profile → `/teachers/{id}` (`11-*`, `12-*`); e2e 0/1/many, pause, reduced motion | PASS |
| R17 | P | 390/768/1440 + 320 no horizontal overflow | — | FE | Live `scrollWidth − innerWidth = 0` on public pages at all four widths | PASS (portal shell header overflows by 9 px on all admin pages at 390 px; pre-existing) |
| R18 | P | Tenant isolation and authorization | — | BE tests + live | Live: demo (Poppins/Lato/green) and academy (Montserrat/Roboto/purple) render independently; academy → demo asset 404; demo course IDs on academy are dropped; student 403, anonymous 401 | PASS |
| R19 | P | Asset URLs survive page use | A9 | BE TTL 60 min | Observed `X-Amz-Expires=3600`; React Query staleTime 10 min | PASS |
| R20 | P | Receipt logo on documents, history-safe | — | — | No receipt/invoice renderer exists; the logo is stored and validated only | BLOCKED (no receipt renderer) |
| U1 | U | Language switcher / theme-mode toggle in header | — | — | No i18n and no dark-mode toggle exist in the product | BLOCKED (not in product) |

## 3. API / settings contract (pass 2)

All BRANDING keys live in the existing typed `tenant_config_entry` framework (no migration needed).

| Key | Type | Default | Server validation |
|---|---|---|---|
| `primary_color` | hex | null → `#1D4ED8` | `^#[0-9A-Fa-f]{6}$`; vs `background_color` ≥ 3.0:1 when both in batch |
| `accent_color` (new) | hex | null → `#F59E0B` | hex |
| `background_color` (new) | hex | null → `#FFFFFF` | hex |
| `surface_color` (new) | hex | null → `#FFFFFF` | hex |
| `text_color` (new) | hex | null → `#0F172A` | hex; ≥ 4.5:1 vs `background_color` and vs `surface_color` when present in same batch |
| `header_font_family` | enum | `INTER` | `INTER, ROBOTO, OPEN_SANS, LATO, POPPINS, MONTSERRAT, NOTO_SANS, NOTO_SERIF, GEIST, SYSTEM, SERIF` |
| `body_font_family` | enum | `INTER` | same list |
| `logo_display_size` (new) | enum | `STANDARD` | `SMALL, STANDARD, LARGE` |
| `teacher_auto_rotate` (new) | bool | false | boolean |
| existing | — | — | `homepage_url`, `public_page_enabled`, `sub_hero_enabled`, `cta_enabled`, `featured_course_ids`, `support_video_url`, legacy `*_banner_urls`, `secondary_color` unchanged |

Button labels on primary/accent are derived client-side (white or `#0F172A`, whichever contrasts more — always ≥ 4.5:1).

Tenant assets (`/api/v1/tenant-assets`, `BRANDING_SETTINGS` permission):
- `POST ?type=` multipart — PNG/JPEG sniffed. Max: logo/receipt 2 MB, favicon 1 MB, banners 2 MB. Logo/favicon/receipt are singletons (replace). Banners: max 6 live per type → 422 beyond.
- `GET ?type=DESKTOP_HERO_BANNER` (new) — live assets oldest-first, each with a preview URL.
- `GET /by-type/{type}`, `GET /{id}/preview-url`, `DELETE /{id}` unchanged.

Public projection `GET /api/v1/public/tenant-config/home` (anonymous, host-resolved tenant) adds:
`theme {primaryColor, accentColor, backgroundColor, surfaceColor, textColor, headingFont, bodyFont}`,
`logoDisplaySize`, `teacherAutoRotate`, `supportEmail`, `supportPhone`; banner lists come from live banner
assets (fallback: legacy URL config); featured courses carry real pricing/access fields and cover URL, in
configured order, published + same-tenant only. Asset URLs are signed for 60 min; response `Cache-Control: no-cache`.

## 4. Decisions

- **Fonts**: self-hosted through a predeclared `next/font/google` registry (build-time downloads, served from
  our origin, `display: swap`, `preload: false`). Browsers fetch only the families actually referenced by the
  active tenant. No runtime request to Google, so no CSP change. Arbitrary families are impossible by design.
- **Scripts**: UI is English-only (no i18n). Curated fonts are Latin. Every stack ends with Noto Sans Sinhala →
  Noto Sans Tamil → system fallbacks, so Sinhala/Tamil course or teacher names render without claiming the
  Latin font supports them.
- **Logo sizes** (height × max-width, desktop / mobile): Small 44×180 / 36×140, Standard 56×220 / 44×170,
  Large 68×260 / 52×200. Footer uses 56 px. A 600×180 wide logo therefore renders ~187 px wide at Standard desktop. Images use `object-fit: contain` in a reserved box.
- **Language**: English is the only supported language; the section shows it read-only with that explanation.

## 5. Evidence

Visual evidence (local synthetic media; demo + academy tenants; captured 2026-10-29) in `docs/qa/tenant-appearance/`:

- `01-baseline-home-{desktop,tablet,mobile,narrow}.png` — before: existing tenant logo, default Inter, `#0F172A`.
- `03-settings-{desktop,mobile}.png`, `05-settings-edited-desktop.png`, `06-settings-after-reload.png` — Appearance settings.
- `07-after-home-{desktop,tablet,mobile,narrow}.png` — after: Poppins/Lato, Forest preset, wide logo, uploaded banners.
- `08-featured-selected-desktop.png`, `09-toggles-off-desktop.png`, `10-public-disabled-desktop.png`.
- `11-teacher-carousel-mobile.png`, `12-teacher-profile-*.png`.
- `13-academy-home-*.png` vs `13-demo-home-check-*.png` — two tenants, different appearance.
- `14-header-<logo>-<size>-{desktop,mobile}.png` — wide/square/padded/low-res logos at Small/Standard/Large.
- `15-settings-warning-logo-{padded,lowres}.png` — upload quality warnings.
- `16-fonts-{normal,blocked}-desktop.png` — font loading and blocked-font fallback.

After verification, demo and academy appearance settings were restored to their prior values.

Tests: backend `ConfigPropertyRegistryAppearanceTest`, `TenantAppearanceIntegrationTest` (Testcontainers) and neighbours; frontend Playwright `public-home-appearance` (19), `tenant-appearance-settings` (9), `unit/appearance-theme` (13), `route-groups`.

## 6. Pass 3 (2026-10-29): portals, presets, Settings menu

| ID | Requirement | Fix location | Verification | Status |
|---|---|---|---|---|
| R21 | Tenant logo, favicon, tab title and footer in Student and Teacher portals | `components/branding/portal-branding.tsx` (`BrandedDashboardShell`), `dashboard-shell.tsx` brand/footer slots, `lib/branding/use-tenant-head.ts` | Live: both portals show logo (sidebar + compact mobile header), `Student Portal \| <institute>` title, favicon link, contact footer (`20-portal-*`); e2e `portal-branding.spec.ts` (5), including the unresolved-tenant fallback | PASS |
| R22 | More theme presets | `lib/theme/color.ts` (12 presets: + Teal, Lagoon, Indigo, Crimson, Rose, Slate, Charcoal & Gold, Midnight (dark)) | Unit test checks every preset for AA text, primary and button labels | PASS |
| R23 | Sidebar "Branding" → "Settings"; General / Student fields / Integrations become Settings tabs and leave the sidebar | `tenant-admin-nav.tsx`, `nav-links.tsx` (`activePrefixes`), `settings-tab-strip.tsx` (`SettingsHeader`, Integrations tab keeps its permission rule), general/student-fields/integrations pages | Live: sidebar shows Settings + Appearance only; Settings stays highlighted on every tab (`21-settings-*`); e2e nav and settings specs | PASS |

Defects found and fixed in this pass:
- A dark preset left a stale "primary needs 3:1" error because only the changed field was re-validated; all five color fields now re-validate together (regression test added).
- The portal header overflowed sideways on phones (student 209 px; admin and teacher at 320 px). Header nav and actions now wrap; 0 px overflow at 390 and 320 for all three portals.

Portals keep platform colors and fonts. Only logo, favicon, title and footer are tenant-branded there.

## 7. Pass 4 (2026-10-29): Settings → Video

| ID | Requirement | Fix location | Verification | Status |
|---|---|---|---|---|
| R24 | Merge Video providers + Video settings into one page, shown as a Settings tab | `settings/video/{page,video-providers-section,video-settings-section}.tsx`; tab in `settings-tab-strip.tsx` (INTEGRATIONS view rule); sidebar items removed; index card merged; internal links updated | Live: tab shows both sections; sidebar has no Video Providers/Settings (`23-settings-video-*`); `tenant-admin-video-settings.spec.ts` 21/21, media-library link test | PASS |
| R25 | Old URLs and the YouTube connect callback keep working | `next.config.ts` `redirects()`: `/settings/video-providers` and `/settings/video-configuration` → `/settings/video` (307, query kept). The backend `TENANT_VIDEO_RETURN_PATH` is unchanged | `?pending=` / `?error=` callback tests pass through the redirect; live `?error=denied` shows its message on the new tab | PASS |

## 8. Pass 5 (2026-10-29): site media out of the Media Library; playback consistency

**Decision: classify files by purpose at upload ("file areas"), not a visibility setting.** Each stored file gets a
fixed `usage_area` (V105: `COURSE_LIBRARY` | `TEACHER_PROFILE`). The library list, counts, open, rename, delete and
lesson attach only see `COURSE_LIBRARY`. Site files are managed where they are used (Appearance, teacher profile)
and listed read-only in **Settings → Site media**. A toggle was rejected: it would only hide files in the UI, leave
them in the API and attachable to lessons, and differ per tenant. Branding images were already separate (`tenant_asset`, V104).

| ID | Requirement | Fix | Verification | Status |
|---|---|---|---|---|
| R26 | Site media not in the Media Library | V105 + `MediaFileUsage`; `findLiveLibraryById`; teacher forms upload `usage=TEACHER_PROFILE` | `MediaFileLibraryIntegrationTest` (new test, 12/12); live: library 4 files, 0 teacher photos (`31-*`) | PASS |
| R27 | A place to manage site media | Settings → Site media tab (branding images, banners, teacher photos, support video, each linking to where it's changed) | Live `32-settings-site-media.png` | PASS |
| R28 | YouTube preview in the course builder | `StaffVideoPreview`: YouTube previews play from the server-revealed YouTube link (youtube-nocookie); other providers keep `SecureVideoPlayer` | Live: YouTube iframe + internal `<video>` ready (`33-*`) | PASS |
| R29 | Consistent, honest playback errors | `lessonAccessMessage` shared by the web player and the AOC Player launcher (EXPIRED, NOT_STARTED, PAYMENT_*, …) | `e2e/unit/playback-errors.spec.ts`; live ended lesson shows "This lesson has ended…" (`34-*`) | PASS |
| R30 | Internal videos in the AOC Player | Root cause: the player proxy (`media_proxy.rs`) forwarded the media element's open range `bytes=0-`; storage returned the whole file and anything over the 16 MB cap was refused (2028 Biology video: 20,228,680 bytes), and `file.ts` swallowed the first error, leaving a black screen. Fix: every upstream request is a closed range of at most 4 MiB (`bounded_range`); the file engine retries and then shows an error | `cargo test` 70/70 (new: bounded ranges, 20 MB open-range streaming), clippy clean; real MinIO file played through an equivalent chunking proxy in Chromium incl. a seek to 150 s (`36-*`). Release builds still need `AOC_MEDIA_ORIGINS` set to the production storage origin | PASS (desktop UI not driven here) |

## 9. Pass 6 (2026-10-29): delete for every site file

| ID | Requirement | Fix | Verification | Status |
|---|---|---|---|---|
| R31 | Remove a teacher's profile photo | `DELETE /api/v1/teachers/{id}/profile-photo` (TEACHERS CREATE_EDIT, audited `teacher.profile_photo_removed`); "Remove photo" in the teacher form (applied on Save) | `MediaFileLibraryIntegrationTest` (13/13): 200 for admin, 403 teacher, 404 other tenant; `TeacherServiceTest` (34/34) | PASS |
| R32 | Replaced or removed photos don't leave files behind | `TeacherService.discardUnusedPhoto` → `MediaFileService.discardTeacherProfilePhoto`: soft delete + audit, object removed after commit; only `TEACHER_PROFILE` files no teacher uses and no lesson references (a library image used as a photo is kept) | Integration + unit tests | PASS |
| R33 | Delete every site file from Settings → Site media | Confirmed Delete/Remove for logo, favicon, receipt logo, each banner, teacher photos and the support video; shown only to roles allowed to change them (server re-checks) | Live in the synthetic academy tenant: 4 deletes via the UI, DB confirms all four (`35-*`) | PASS |

## 10. LOGIN-01 (2026-10-29): sign-in branding, light/dark, policies

- **Auth pages** (`(auth)` group: login, register, password flows) show the host institute's logo
  (uploaded site logo, else legacy URL; institute-name monogram fallback) inside the tenant public
  theme, from `GET /public/tenant-config/home`. Loading = neutral skeleton; unresolved = neutral
  platform text. Platform Admin login keeps platform branding.
- **Light/dark switch** (sun/moon, `aria-pressed`) top-right on auth pages, in the public header and
  in the signed-in header. Preference per browser (`localStorage["lms-theme"]`, default = OS),
  applied by an inline `<head>` script before first paint (no flash, no hydration mismatch).
- **Dark tenant palette**: `PublicThemeScope` emits light (`--tl-*`) and dark (`--td-*`) token
  sets; CSS picks one via `.dark`. Dark keeps the tenant primary/accent (lightened until ≥3:1 on the
  dark background) and replaces the tenant's custom background/surface/text with dark tokens.
- **Policies**: Settings → Policies (Terms of Service, Refund Policy, Privacy Policy; plain text).
  Public `/terms`, `/refund-policy`, `/privacy`; consent line on auth pages linking published terms
  and privacy policy; footer "Policies" links for every published policy.
- **Mobile sign-in** (ADR-031): EMAIL / MOBILE tabs on the tenant login when
  `loginOptions.mobileEnabled`; Settings → General → Login options (switch, phone number country,
  own sign-in mobile); teacher profile and staff list "Sign-in mobile".
