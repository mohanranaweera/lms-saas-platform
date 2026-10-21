# Zoom Integration Foundation API

**Wave:** Release 1 / Backend Wave 1 (legacy foundation pending pooled-host amendment)  
**Status:** Foundation only; real Zoom OAuth/user synchronization/meeting calls are not implemented. The current `zoom_teacher_user_mapping` endpoint is legacy/future-strategy data and does not control `POOLED_HOST`.

All endpoints are tenant-admin endpoints. The tenant is resolved from the authenticated context; no request accepts `tenantId`.

## Permissions

- `INTEGRATIONS` `VIEW`: list/detail/external-user reads.
- `INTEGRATIONS` `CREATE_EDIT`: create/update/archive and mapping mutations.

Cross-tenant IDs resolve as `404 NOT_FOUND`. Credentials, OAuth tokens, ZAK, OBF and Meeting SDK secrets are never accepted or returned.

## Endpoints

```text
GET    /api/v1/tenant-admin/zoom-integrations
POST   /api/v1/tenant-admin/zoom-integrations
GET    /api/v1/tenant-admin/zoom-integrations/{integrationId}
PUT    /api/v1/tenant-admin/zoom-integrations/{integrationId}
DELETE /api/v1/tenant-admin/zoom-integrations/{integrationId}
GET    /api/v1/tenant-admin/zoom-integrations/{integrationId}/external-users
PUT    /api/v1/tenant-admin/zoom-integrations/{integrationId}/teacher-mappings/{teacherUserId}
DELETE /api/v1/tenant-admin/zoom-integrations/{integrationId}/teacher-mappings/{teacherUserId}
```

## Create/update request

```json
{
  "name": "AOC Zoom Biology",
  "description": "Synthetic development integration",
  "teacherPolicy": "SELECTED_TEACHERS",
  "allowedTeacherIds": ["<tenant-teacher-uuid>"]
}
```

`ALL_TEACHERS` requires an empty `allowedTeacherIds` list. `SELECTED_TEACHERS` requires at least one same-tenant teacher. The Release 1 host strategy is `POOLED_HOST`; allowed teachers authorize integration use but do not select or own a provider host. Host-pool membership and reservation APIs are planned for the next backend waves.

## Mapping request

```json
{
  "externalUserId": "<same-integration-external-user-uuid>"
}
```

The current Wave 1 mapping endpoint is retained only as legacy/future-strategy data from the superseded mapped-host foundation. It must not control `POOLED_HOST` allocation. The LMS teacher must be allowed by the integration policy for integration use; future pool-member APIs will explicitly enable eligible external users and future reservation APIs will allocate capacity. The external user must belong to the same tenant and integration and must not be inactive or deleted.

## Response fields

Responses contain only non-secret metadata:

- integration ID/name/description;
- lifecycle status and readiness (`UNVALIDATED` initially);
- teacher policy and host strategy;
- masked/display Zoom account metadata when available;
- allowed teachers;
- external-user snapshots with status, provider user ID, display/email and capability fields;
- active/revoked mappings.

Wave 1 does not connect or synchronize with Zoom. External-user data is empty until a later synchronization wave or synthetic test fixture supplies it. Production pooled-host scheduling remains disabled until pooled-host Wave 0 validation passes.
