# Zoom Integration Setup Guide

**Status:** Wave 0 validation checklist  
**Date:** 2026-10-12  
**Initial host strategy:** `POOLED_HOST`  
**Release 1:** standard meetings and AOC Player  
**Release 2:** participation attendance and recordings  
**Release 3:** recurring meetings and webinars

> This guide is a setup checklist, not a production approval. Do not place secrets, tokens, ZAK/OBF values or real participant data in this file.

## 1. Zoom application

Create or confirm a Zoom **General OAuth** application for tenant administrators to authorize external tenant Zoom accounts. Do not use deprecated JWT app guidance and do not use Server-to-Server OAuth as the default tenant connection model.

Record separately in the deployment secret manager:

- OAuth client ID;
- OAuth client secret;
- redirect URI configuration;
- Meeting SDK app credentials/secrets;
- webhook secret token;
- any provider-approved signing or OBF configuration.

None of these values belong in source control, the database in plaintext, frontend code, player bundles, audit records or logs.

## 2. Redirect and OAuth configuration

Configure the central callback:

```text
GET /api/v1/integrations/oauth/zoom/callback
```

The application must register the exact public callback origin used by the environment. The runtime state binds the callback to:

- tenant ID;
- named integration ID;
- initiating Tenant Admin ID;
- provider/app configuration version where required;
- PKCE verifier/nonce;
- one-time expiry.

Required behavior:

- authorization code flow;
- `state` generated with a CSPRNG and consumed once;
- PKCE S256;
- pending confirmation by the initiating Tenant Admin;
- tenant/integration resolution from server-held state only;
- fixed tenant return path with no open redirect;
- refresh-token rotation persisted atomically;
- revoke/deauthorize handling;
- no access or refresh token in a frontend response.

## 3. Scope matrix to confirm

Scope names are version-sensitive. Confirm the exact current granular names in the Zoom Marketplace app before implementation and record the approved matrix in the Wave 0 validation document.

| Capability | Required for | Validation status |
|---|---|---|
| Authorized user/account identity | Connect/health | `PENDING_SANDBOX` |
| Account user listing/synchronization | `POOLED_HOST` candidate catalog | `PENDING_SANDBOX` |
| User status/license/capability fields | Pool eligibility/start validation | `PENDING_SANDBOX` |
| Enable pool member | Tenant Admin host-pool configuration | `PENDING_IMPLEMENTATION` |
| Create meeting for allocated user | Release 1 scheduling | `PENDING_SANDBOX` |
| Update/delete meeting for allocated user | Release 1 lifecycle | `PENDING_SANDBOX` |
| Retrieve allocated host ZAK | Meeting SDK role-1 start-on-behalf | `PENDING_SANDBOX` |
| Participant/report access | Release 2 attendance | `RELEASE_2_VALIDATION` |
| Recording metadata/files | Release 2 recordings | `RELEASE_2_VALIDATION` |
| Webinar roles/registration | Release 3 webinars | `RELEASE_3_VALIDATION` |
| Webhook subscriptions | Meeting/participant/recording events | `PENDING_MARKETPLACE` |

Current official documentation identifies account-level user management and token operations such as `user:master` plus granular user-management scopes. The project must not assume these are granted to every General OAuth authorization; the authorizing Zoom admin role and Marketplace app configuration must be tested.

## 4. Pooled Host setup

For each tenant integration:

1. Tenant Admin creates the named integration card.
2. Tenant Admin connects that card through General OAuth.
3. Backend resolves and stores the connected Zoom account identity.
4. Backend synchronizes the minimal external Zoom-user catalog.
5. Tenant Admin reviews user status/license/capability information.
6. Tenant Admin explicitly enables eligible users as host-pool members and optionally configures approved priority/capacity metadata.
7. Tenant Admin sets `ALL_TEACHERS` or selects which LMS teachers may use the integration.
8. Course/session scheduling selects the LMS teacher and integration; the backend allocates and reserves a pool host.
9. The provider meeting is created for that allocated Zoom user, not implicitly for the OAuth authorizer.
10. The LMS session/meeting stores the reservation, pool member, actual provider host and capability snapshot.

Rules:

- A synchronized Zoom user is not automatically a host-pool member.
- Allowed-teacher policy authorizes integration use; it does not map a teacher to a host.
- Every enabled pool user must independently meet the current host entitlement/license requirement.
- Missing/stale/inactive/unlicensed/no-capacity pool state blocks scheduling/start.
- There is no random, fixed-host or mapped-teacher fallback in Release 1; allocation fails when no eligible capacity exists.
- The system never shares Zoom credentials or dynamically transfers a paid license.

## 5. Meeting SDK and AOC Player setup

Enable the Meeting SDK according to the selected Client View or Component View decision. The decision is blocked until the dedicated AOC/Tauri/WebView2 spike is complete.

Current documented requirements to validate:

- SDK signature is generated server-side;
- signature role `1` starts a meeting/webinar as host;
- signature role `0` joins as participant;
- host start requires the allocated pool host's ZAK where required;
- webinar joins require `userEmail`;
- OBF may be required for applicable external-account participant joins;
- no SDK secret, ZAK or OBF is stored in URLs/browser storage or logged.

The AOC Player integration must use a single-use opaque launch code and server exchange. It must not receive a raw Zoom `start_url`/`join_url` as the supported protected path.

## 6. Webhooks

Configure the current Zoom webhook subscription and endpoint after the target event set is confirmed. The endpoint must:

- verify `x-zm-request-timestamp` and `x-zm-signature` over the raw body before parsing;
- reject stale, malformed or unsigned requests;
- answer `endpoint.url_validation` using the documented HMAC response;
- resolve tenant/integration/session only through platform-held mappings;
- persist an idempotency key/body hash before applying effects;
- acknowledge quickly and process durable effects asynchronously where needed;
- handle deauthorization with provider-derived data retention/purge rules;
- keep LMS-owned academic/audit history separate from Zoom-derived credentials and user/profile data.

Confirm exact event names/payloads for:

- meeting started/ended;
- participant joined/left;
- recording completed/processing;
- recurring occurrence changes;
- webinar started/ended/participant or panelist events;
- app deauthorization.

## 7. Release checklist

### Release 1 — pooled-host standard meetings

- [ ] General OAuth app configured and reviewed for target distribution.
- [ ] Required account-user synchronization and selected-user meeting-management scopes confirmed.
- [ ] At least two synthetic Zoom host users validated.
- [ ] Host-pool enable/disable behavior defined and tested.
- [ ] User-specific meeting create/update/delete for allocated users proven.
- [ ] Per-user host capability/license and concurrency behavior documented.
- [ ] Two LMS teachers can use the same integration with different allocated hosts.
- [ ] Pooled host ZAK/role-1 start-on-behalf proven.
- [ ] Participant role-0 join and external-account OBF behavior proven.
- [ ] No-capacity allocation rejection proven.
- [ ] Client/Component View spike completed for AOC Player.
- [ ] Production host scheduling gate changed from `BLOCKED` only after all evidence is reviewed.

### Release 2 — attendance and recordings

- [ ] Participant identity/report fields mapped.
- [ ] Attendance threshold/grace/override policy approved.
- [ ] Recording completion/multiple-file contract proven.
- [ ] Integration-specific Media Library tab and provenance model implemented/tested.
- [ ] Secure recording playback grant proven.
- [ ] Deauthorization/data-retention behavior reviewed.

### Release 3 — recurring meetings and webinars

- [ ] Recurring occurrence IDs and lifecycle proven.
- [ ] Webinar entitlement and roles proven.
- [ ] Webinar participant/registration/report behavior proven.
- [ ] Occurrence-level attendance and recording tests pass.
- [ ] Separate security and tenant-isolation review completed.

## 8. Official references

- OAuth: <https://developers.zoom.us/docs/integrations/oauth.md>
- End-user authorization: <https://developers.zoom.us/docs/integrations/end-user-auth.md>
- Meeting SDK auth: <https://developers.zoom.us/docs/meeting-sdk/auth.md>
- Meeting SDK OBF FAQ: <https://developers.zoom.us/docs/meeting-sdk/obf-faq.md>
- Component View meetings/webinars: <https://developers.zoom.us/docs/meeting-sdk/web/component-view/meetings-webinars.md>
- Users API: <https://developers.zoom.us/docs/api/users/ma.md>
- Meetings API: <https://developers.zoom.us/docs/api/meetings/ma.md>
- Webhooks: <https://developers.zoom.us/docs/api/webhooks.md>
