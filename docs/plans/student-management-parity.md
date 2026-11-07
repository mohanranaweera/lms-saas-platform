# Plan — STU-MGMT-02 Student Registration & Management parity

Spec/contract: `docs/specs/student-management-parity.md`. ADR: `docs/adr/ADR-033-student-support-impersonation.md`.
Branch `feat/student-management-parity`. Backend and frontend land as separate commits.

| Item | Answer |
|---|---|
| Goal | Klass-parity student directory, registration/edit, profile image, drawer + full profile, QR display/bulk export, secure Log as Student; re-verify Today's Classes |
| Database | One additive migration (V117): `student_profile` + first/last name, guardian_nic, profile_media_file_id, qr_token, pending_approval, removed_at/by; new `student_remark`, `impersonation_session`; `media_file` usage `STUDENT_PROFILE` |
| API | New endpoints in spec §5; existing endpoints unchanged |
| Security | New impersonation session type (ADR-033); private photo via signed URLs; QR opaque token; CSV formula escaping |
| Tenant | Every new table has `tenant_id` NOT NULL FK + leading index; every query via tenant-aware repositories; cross-tenant negative tests on each new endpoint |
| Tests | Backend IT (Testcontainers) per endpoint incl. cross-tenant, concurrency (index), QR decode (ZXing), impersonation lifecycle; Playwright mocked + live screenshots |

## Slices and ownership

| Slice | Backend (`backend-springboot-engineer`) | Frontend (`frontend-nextjs-engineer`) |
|---|---|---|
| A | migration, directory/search/CSV, registration v2 + edit, free participation on behalf, approve, delete, photo | grid, tabs, search, create/edit sheet, photo editor |
| B | detail, remarks, last login | drawer, full profile page (reusing existing tabs/hooks) |
| C | QR token/payload/png/rotate/resolve, PDF labels (ZXing + Jasper/PDF) | profile QR, Bulk Print QRs dialog |
| D | impersonation session, filter, read-only guard, audit | start dialog, `/impersonate` tab, banner, exit, cache clear |
| E | — | Today's Classes re-verification |

File ownership: backend agent only `backend/**`; frontend agent only `frontend/**`; parent owns `docs/**`.
Reviews: `security-reviewer` (impersonation, QR, media, tenant), `qa-test-engineer` (targeted verification).
