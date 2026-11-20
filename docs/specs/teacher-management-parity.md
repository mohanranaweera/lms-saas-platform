# Teacher Management Parity

## Confirmed reference requirements

`docs/references/klass-Teacher/Main Page.png` confirms a tenant-admin teacher grid with teacher heading/icon, search, blue Add Teacher action, avatar/handle, First Name, Last Name, Email, Contact Number, Student Count, Commission, Actions, row separators and pagination. `Add Teacher.png` confirms a centered “Add New Teacher” scrollable form with circular fallback/avatar and Change action, two-column desktop fields, single-column narrow layout, First Name, Last Name, Email, Password, Contact Number, Institute Commission (%), Qualifications, Description, Cancel and Create Teacher.

## Gap analysis

Existing implementation had `TeacherProfile.name` only, credential email/password in tenant-scoped `tenant_user`, approval/suspend lifecycle, no edit endpoint, no profile fields, and full-array frontend filtering. Parity adds profile columns, profile-only PATCH, create DTO fields, typed frontend fields, and optional paged/search API parameters while retaining backwards-compatible unpaged list responses.

## Identity and tenant decisions

Teacher profile remains distinct from shared account identity. Tenant is resolved from trusted authenticated context and all profile/media references use same-tenant ownership. Password remains write-only through `UserProvisioningApi`; edit never accepts or changes password. Email remains account identity and is not editable by teacher profile PATCH. No global account is duplicated.

## Student-count definition

Count means distinct currently enrolled students across courses assigned to teacher, with each student counted once across multiple courses. Enrollment is free and payment is not required. Count must remain tenant-scoped and batch/N+1-safe when connected to the authoritative roster API.

## Commission precedence

Teacher profile stores current institute commission percentage, including explicit zero. Existing course-level commission rate wins when present. Teacher-level rate is fallback/default for future calculations where no course override exists. Financial records snapshot effective rate; profile changes never recompute historical payments, ledger entries, reports, or settlements. No new payout workflow is introduced here.

## Verified actions

Existing code verifies edit-like profile mutation via `PATCH /v1/teachers/{id}` and approval lifecycle endpoints. Existing suspend/reactivate behavior is tenant-admin-only, audited, and preserves memberships. Screenshot money, trash, row grip/ordering, and administrator-row restrictions remain unverified; no speculative payout, deletion, or drag-order behavior is implemented.

## Remaining limitations

Avatar persistence accepts an optional same-tenant media reference at API shape level; full teacher-specific media picker/upload wiring depends on an existing media ownership contract. Current count field is exposed but requires a dedicated batch roster/count integration before it is authoritative. Existing legacy rows may not have separate first/last fields and continue using legacy display name fallback.
