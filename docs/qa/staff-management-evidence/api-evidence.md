# STAFF-MGMT-02 live API evidence (run mw7e3ceo, 2026-11-08T05:26:18.768Z)

Tokens, passwords and invite tokens are redacted. Bodies truncated.


## Setup

  - `POST /auth/login` (A tenantadmin@demo.test) → **200** `{"success":true,"data":{"accessToken":"<redacted>","expiresInSeconds":900,"sessionId":"01a1b99f-2d0a-787c-9068-6b50f0e1b087","mustChangePass`
  - `POST /auth/login` (B admin@academy.test) → **200** `{"success":true,"data":{"accessToken":"<redacted>","expiresInSeconds":900,"sessionId":"01a1b99f-2e00-719e-8a71-a941654e76be","mustChangePass`
  - `GET /me/permissions` (A) → **200** `{"success":true,"data":{"role":"TENANT_ADMIN","capabilities":["FINANCE_VIEW_INCOME_SUMMARY","FINANCE_VIEW_EXPENSES","FINANCE_DELETE_CATEGORY","FINANCE_ACCOUNT_CHANGE_CONFIGURATION","FINANCE_ACCOUNT_VIEW_CONFIGURATION","F`
- **PASS** [setup] GET /me/permissions exists on running backend (current tree)
  - `GET /staff/permission-matrix` (A) → **200** `{"success":true,"data":{"version":0,"roles":[{"code":"OPERATOR","displayName":"Operator"},{"code":"MANAGER","displayName":"Manager"},{"code":"TEACHER","displayName":"Teacher"}],"groups":[{"key":"FINANCE","label":"Finance`
  - initial matrix version=0
  - `POST /staff` (B body={"firstName":"Bella","lastName":"TenantBmw7e3ceo","email":"b-mgr-mw7e3ceo@example.test","mobileNumber":"+94770009999","roleCode":"MANAGER"}) → **201** `{"success":true,"data":{"id":"01a1b99f-3019-71bd-bd26-c5216fc2fb7b","firstName":"Bella","lastName":"TenantBmw7e3ceo","name":"Bella TenantBmw7e3ceo","email":"b-mgr-mw7e3ceo@example.test","contactNumber":"+94770009999","ro`
  - `POST /courses` (B body={"name":"B course mw7e3ceo","status":"PUBLIC","pricingModel":"FREE"}) → **400** `{"success":false,"data":null,"error":{"code":"VALIDATION_ERROR","message":"teacherId is required","fieldErrors":[]},"timestamp":"2026-11-08T05:26:20.183357300Z","traceId":"91d3b259-c9e2-43be-994b-f4b750b0cc0a"}`
  - `POST /students` (B body={"name":"B Student mw7e3ceo","email":"b-stu-mw7e3ceo@example.test","password":"<redacted>"}) → **201** `{"success":true,"data":{"id":"01a1b99f-31e5-77a0-8043-1c6ad7d905c4","name":"B Student mw7e3ceo","email":"b-stu-mw7e3ceo@example.test","roleCode":"STUDENT","status":"ACTIVE"},"error":null,"timestamp":"2026-11-08T05:26:20.`
  - B staff=01a1b99f-3019-71bd-bd26-c5216fc2fb7b course=undefined student=01a1b99f-31e5-77a0-8043-1c6ad7d905c4
  - `GET /staff?size=100` (B) → **200** `{"success":true,"data":{"content":[{"id":"01a1b99f-3019-71bd-bd26-c5216fc2fb7b","firstName":"Bella","lastName":"TenantBmw7e3ceo","name":"Bella TenantBmw7e3ceo","email":"b-mgr-mw7e3ceo@example.test","contactNumber":"+9477`

## A1 create staff (invite, no password) / reopen / duplicate

  - `GET /staff/assignable-roles` (A) → **200** `{"success":true,"data":[{"code":"OPERATOR","displayName":"Operator"},{"code":"MANAGER","displayName":"Manager"}],"error":null,"timestamp":"2026-11-08T05:26:20.517707700Z","traceId":"cf9d7110-771e-4e9b-a02f-d1f217232956"}`
- **PASS** [A1] assignable roles = Operator, Manager only — [{"code":"OPERATOR","displayName":"Operator"},{"code":"MANAGER","displayName":"Manager"}]
  - `POST /staff` (A body={"firstName":"Olivia","lastName":"Operatormw7e3ceo","email":"op-mw7e3ceo@example.test","mobileNumber":"+94 77 000 1234","roleCode":"OPERATOR","password":"<redac) → **201** `{"success":true,"data":{"id":"01a1b99f-331b-75bb-afe5-ccad3a81d194","firstName":"Olivia","lastName":"Operatormw7e3ceo","name":"Olivia Operatormw7e3ceo","email":"op-mw7e3ceo@example.test","contactNumber":"+94 77 000 1234"`
- **PASS** [A1] POST /staff Operator → 201 INVITED, no password in response — status=INVITED
  - `GET /staff/01a1b99f-331b-75bb-afe5-ccad3a81d194` (A) → **200** `{"success":true,"data":{"id":"01a1b99f-331b-75bb-afe5-ccad3a81d194","firstName":"Olivia","lastName":"Operatormw7e3ceo","name":"Olivia Operatormw7e3ceo","email":"op-mw7e3ceo@example.test","contactNumber":"+94 77 000 1234"`
- **PASS** [A1] reopen GET /staff/{id} → INVITED, role OPERATOR
  - `POST /staff` (A body={"firstName":"Dup","lastName":"Dup","email":"OP-MW7E3CEO@EXAMPLE.TEST","mobileNumber":"+94770001235","roleCode":"OPERATOR"}) → **409** `{"success":false,"data":null,"error":{"code":"CONFLICT","message":"An account with this email already exists in this institute","fieldErrors":[]},"timestamp":"2026-11-08T05:26:20.819456500Z","traceId":"d4dd1b76-9153-4042`
- **PASS** [A1] duplicate email (case-varied) → 409 — {"code":"CONFLICT","message":"An account with this email already exists in this institute","fieldErrors":[]}
  - `POST /staff` (A body={"firstName":"Dup","lastName":"B","email":"b-mgr-mw7e3ceo@example.test","mobileNumber":"+94770001236","roleCode":"OPERATOR"}) → **201** `{"success":true,"data":{"id":"01a1b99f-3451-7a62-a207-815c54f706d8","firstName":"Dup","lastName":"B","name":"Dup B","email":"b-mgr-mw7e3ceo@example.test","contactNumber":"+94770001236","roleCode":"OPERATOR","roleName":"O`
- **PASS** [A1] email that exists only in tenant B is NOT a conflict in A (201) — no other-tenant data leak
  - `POST /staff` (A body={"firstName":"Mark","lastName":"Managermw7e3ceo","email":"mgr-mw7e3ceo@example.test","mobileNumber":"+94770002000","roleCode":"MANAGER"}) → **201** `{"success":true,"data":{"id":"01a1b99f-34f5-755a-9e37-02b81bee6865","firstName":"Mark","lastName":"Managermw7e3ceo","name":"Mark Managermw7e3ceo","email":"mgr-mw7e3ceo@example.test","contactNumber":"+94770002000","roleCo`
- **PASS** [A1] POST /staff Manager → 201 INVITED
  - `POST /auth/login` (A op-mw7e3ceo@example.test) → **401** `{"success":false,"data":null,"error":{"code":"INVALID_CREDENTIALS","message":"Invalid email or password","fieldErrors":[]},"timestamp":"2026`
- **PASS** [A1] body-supplied password is ignored (login with it fails)
  - `GET /staff?size=100` (B) → **200** `{"success":true,"data":{"content":[{"id":"01a1b99f-3019-71bd-bd26-c5216fc2fb7b","firstName":"Bella","lastName":"TenantBmw7e3ceo","name":"Bella TenantBmw7e3ceo","email":"b-mgr-mw7e3ceo@example.test","contactNumber":"+9477`
- **PASS** [A1] body-supplied tenantId ignored: tenant B list unchanged
- **PASS** [A1] invite email received for op-mw7e3ceo@example.test — subject="Set up your Demo Institute account", link=http://demo.lms.test:3000/account-setup#token=<redacted>
  - `POST /auth/password-tokens/verify` (A body={"token":"<redacted>"}) → **200** `{"success":true,"data":{"purpose":"ACCOUNT_SETUP","emailMasked":"o***@example.test","expiresAt":"2026-11-11T05:26:23.037486Z"},"error":null,"timestamp":"2026-11-08T05:26:23.160789500Z","traceId":"28e8c4d8-0a31-4046-b2f0-`
  - verify purpose=ACCOUNT_SETUP
  - `POST /auth/password-tokens/complete` (A body={"token":"<redacted>","newPassword":"<redacted>"}) → **204** ``
- **PASS** [A1] account setup completed via emailed link — status 204
  - `POST /auth/login` (A op-mw7e3ceo@example.test) → **200** `{"success":true,"data":{"accessToken":"<redacted>","expiresInSeconds":900,"sessionId":"01a1b99f-3e88-700c-a6d4-2720cd53bbef","mustChangePass`
- **PASS** [A1] invite email received for mgr-mw7e3ceo@example.test — subject="Set up your Demo Institute account", link=http://demo.lms.test:3000/account-setup#token=<redacted>
  - `POST /auth/password-tokens/verify` (A body={"token":"<redacted>"}) → **200** `{"success":true,"data":{"purpose":"ACCOUNT_SETUP","emailMasked":"m***@example.test","expiresAt":"2026-11-11T05:26:23.211691Z"},"error":null,"timestamp":"2026-11-08T05:26:23.681641200Z","traceId":"9fa1f820-6a16-48ed-89e0-`
  - verify purpose=ACCOUNT_SETUP
  - `POST /auth/password-tokens/complete` (A body={"token":"<redacted>","newPassword":"<redacted>"}) → **204** ``
- **PASS** [A1] account setup completed via emailed link — status 204
  - `POST /auth/login` (A mgr-mw7e3ceo@example.test) → **200** `{"success":true,"data":{"accessToken":"<redacted>","expiresInSeconds":900,"sessionId":"01a1b99f-4034-788b-8bbb-72e94c90b084","mustChangePass`
- **PASS** [A1] Operator signs in after setup
- **PASS** [A1] Manager signs in after setup
  - `GET /staff/01a1b99f-331b-75bb-afe5-ccad3a81d194` (A) → **200** `{"success":true,"data":{"id":"01a1b99f-331b-75bb-afe5-ccad3a81d194","firstName":"Olivia","lastName":"Operatormw7e3ceo","name":"Olivia Operatormw7e3ceo","email":"op-mw7e3ceo@example.test","contactNumber":"+94 77 000 1234"`
- **PASS** [A1] status ACTIVE after setup — ACTIVE

## A2 edit profile

  - `PATCH /staff/01a1b99f-331b-75bb-afe5-ccad3a81d194` (A body={"firstName":"Olive","lastName":"Operatormw7e3ceo","contactNumber":"+94 71 555 0101","roleCode":"TENANT_ADMIN","email":"x@example.test"}) → **200** `{"success":true,"data":{"id":"01a1b99f-331b-75bb-afe5-ccad3a81d194","firstName":"Olive","lastName":"Operatormw7e3ceo","name":"Olive Operatormw7e3ceo","email":"op-mw7e3ceo@example.test","contactNumber":"+94 71 555 0101","`
- **PASS** [A2] PATCH profile → 200
  - `GET /staff/01a1b99f-331b-75bb-afe5-ccad3a81d194` (A) → **200** `{"success":true,"data":{"id":"01a1b99f-331b-75bb-afe5-ccad3a81d194","firstName":"Olive","lastName":"Operatormw7e3ceo","name":"Olive Operatormw7e3ceo","email":"op-mw7e3ceo@example.test","contactNumber":"+94 71 555 0101","`
- **PASS** [A2] reload shows change; role/email in body ignored
  - `PATCH /staff/01a1b99f-331b-75bb-afe5-ccad3a81d194` (A body={"firstName":"","contactNumber":"abc"}) → **400** `{"success":false,"data":null,"error":{"code":"VALIDATION_ERROR","message":"Request validation failed","fieldErrors":[{"field":"contactNumber","message":"must be a valid phone number"},{"field":"firstName","message":"must`
- **PASS** [A2] invalid profile → 400

## A5 forged role / Operator denied / self / cross-tenant

  - `POST /staff` (A body={"firstName":"F","lastName":"Forged","email":"forged-tenant_admin-mw7e3ceo@example.test","mobileNumber":"+94770003000","roleCode":"TENANT_ADMIN"}) → **400** `{"success":false,"data":null,"error":{"code":"VALIDATION_ERROR","message":"'TENANT_ADMIN' is not an assignable staff role","fieldErrors":[]},"timestamp":"2026-11-08T05:26:24.279156900Z","traceId":"d26d74f9-ecab-4e3b-a769`
- **PASS** [A5] create with roleCode=TENANT_ADMIN → 400 — got 400
  - `POST /staff` (A body={"firstName":"F","lastName":"Forged","email":"forged-finance_staff-mw7e3ceo@example.test","mobileNumber":"+94770003000","roleCode":"FINANCE_STAFF"}) → **400** `{"success":false,"data":null,"error":{"code":"VALIDATION_ERROR","message":"'FINANCE_STAFF' is not an assignable staff role","fieldErrors":[]},"timestamp":"2026-11-08T05:26:24.306791800Z","traceId":"c01ee14f-c0dc-4067-bd3`
- **PASS** [A5] create with roleCode=FINANCE_STAFF → 400 — got 400
  - `POST /staff` (A body={"firstName":"F","lastName":"Forged","email":"forged-platform_admin-mw7e3ceo@example.test","mobileNumber":"+94770003000","roleCode":"PLATFORM_ADMIN"}) → **400** `{"success":false,"data":null,"error":{"code":"VALIDATION_ERROR","message":"'PLATFORM_ADMIN' is not an assignable staff role","fieldErrors":[]},"timestamp":"2026-11-08T05:26:24.345066700Z","traceId":"646bf3ef-1f75-4c6e-9e`
- **PASS** [A5] create with roleCode=PLATFORM_ADMIN → 400 — got 400
  - `POST /staff` (A body={"firstName":"F","lastName":"Forged","email":"forged-teacher-mw7e3ceo@example.test","mobileNumber":"+94770003000","roleCode":"TEACHER"}) → **400** `{"success":false,"data":null,"error":{"code":"VALIDATION_ERROR","message":"'TEACHER' is not an assignable staff role","fieldErrors":[]},"timestamp":"2026-11-08T05:26:24.373121700Z","traceId":"d5a2e1f4-083e-4dee-82e6-05f0`
- **PASS** [A5] create with roleCode=TEACHER → 400 — got 400
  - `POST /staff` (A body={"firstName":"F","lastName":"Forged","email":"forged-garbage-mw7e3ceo@example.test","mobileNumber":"+94770003000","roleCode":"garbage"}) → **400** `{"success":false,"data":null,"error":{"code":"VALIDATION_ERROR","message":"'garbage' is not an assignable staff role","fieldErrors":[]},"timestamp":"2026-11-08T05:26:24.399356900Z","traceId":"07ba5c12-73e8-491c-badc-15e4`
- **PASS** [A5] create with roleCode=garbage → 400 — got 400
  - `PUT /staff/01a1b99f-331b-75bb-afe5-ccad3a81d194/role` (A body={"roleCode":"TENANT_ADMIN"}) → **400** `{"success":false,"data":null,"error":{"code":"VALIDATION_ERROR","message":"'TENANT_ADMIN' is not an assignable staff role","fieldErrors":[]},"timestamp":"2026-11-08T05:26:24.429386300Z","traceId":"94944512-a23c-41a0-bf46`
- **PASS** [A5] PUT role=TENANT_ADMIN → 400 — got 400
  - `PUT /staff/01a1b99f-331b-75bb-afe5-ccad3a81d194/role` (A body={"roleCode":"FINANCE_STAFF"}) → **400** `{"success":false,"data":null,"error":{"code":"VALIDATION_ERROR","message":"'FINANCE_STAFF' is not an assignable staff role","fieldErrors":[]},"timestamp":"2026-11-08T05:26:24.459479600Z","traceId":"9a91cec0-e349-411c-b28`
- **PASS** [A5] PUT role=FINANCE_STAFF → 400 — got 400
  - `PUT /staff/01a1b99f-331b-75bb-afe5-ccad3a81d194/role` (A body={"roleCode":"garbage"}) → **400** `{"success":false,"data":null,"error":{"code":"VALIDATION_ERROR","message":"'garbage' is not an assignable staff role","fieldErrors":[]},"timestamp":"2026-11-08T05:26:24.496939Z","traceId":"bc815aa8-8b75-4fdb-9e9d-5f89f7d`
- **PASS** [A5] PUT role=garbage → 400 — got 400
  - `GET /staff` (A) → **403** `{"success":false,"data":null,"error":{"code":"FORBIDDEN","message":"You do not have permission to perform this action","fieldErrors":[]},"timestamp":"2026-11-08T05:26:24.525870400Z","traceId":"f9a06358-8a2a-4f27-bcaf-033`
- **PASS** [A5] Operator GET /staff → 403 — got 403
  - `GET /staff/01a1b99f-331b-75bb-afe5-ccad3a81d194` (A) → **403** `{"success":false,"data":null,"error":{"code":"FORBIDDEN","message":"You do not have permission to perform this action","fieldErrors":[]},"timestamp":"2026-11-08T05:26:24.549840900Z","traceId":"11a5212f-80a6-4c89-b72d-2bb`
- **PASS** [A5] Operator GET /staff/{id} → 403 — got 403
  - `POST /staff` (A body={"firstName":"X","lastName":"Y","email":"x-mw7e3ceo@example.test","mobileNumber":"+94770004000","roleCode":"OPERATOR"}) → **403** `{"success":false,"data":null,"error":{"code":"FORBIDDEN","message":"You do not have permission to perform this action","fieldErrors":[]},"timestamp":"2026-11-08T05:26:24.581282400Z","traceId":"6d7f73bd-1e78-45e4-849d-199`
- **PASS** [A5] Operator POST /staff → 403 — got 403
  - `PUT /staff/01a1b99f-34f5-755a-9e37-02b81bee6865/role` (A body={"roleCode":"OPERATOR"}) → **403** `{"success":false,"data":null,"error":{"code":"FORBIDDEN","message":"You do not have permission to perform this action","fieldErrors":[]},"timestamp":"2026-11-08T05:26:24.620370400Z","traceId":"feef9283-2e4b-4307-af98-004`
- **PASS** [A5] Operator PUT /staff/{id}/role → 403 — got 403
  - `POST /staff/01a1b99f-34f5-755a-9e37-02b81bee6865/deactivate` (A) → **403** `{"success":false,"data":null,"error":{"code":"FORBIDDEN","message":"You do not have permission to perform this action","fieldErrors":[]},"timestamp":"2026-11-08T05:26:24.668462900Z","traceId":"a7c318a2-b8ce-4f60-8c7d-814`
- **PASS** [A5] Operator POST /staff/{id}/deactivate → 403 — got 403
  - `GET /staff/permission-matrix` (A) → **403** `{"success":false,"data":null,"error":{"code":"FORBIDDEN","message":"You do not have permission to perform this action","fieldErrors":[]},"timestamp":"2026-11-08T05:26:24.697819900Z","traceId":"505fb3a6-ce40-42d4-b8a9-d22`
- **PASS** [A5] Operator GET /staff/permission-matrix → 403 — got 403
  - `PUT /staff/permission-matrix` (A body={"expectedVersion":0,"changes":[{"roleCode":"OPERATOR","capabilityKey":"COURSES_ADD","granted":true}]}) → **403** `{"success":false,"data":null,"error":{"code":"FORBIDDEN","message":"You do not have permission to perform this action","fieldErrors":[]},"timestamp":"2026-11-08T05:26:24.726980Z","traceId":"e3e7c34d-6d78-4a5a-b165-622ba6`
- **PASS** [A5] Operator PUT /staff/permission-matrix → 403 — got 403
  - `GET /staff/activities` (A) → **403** `{"success":false,"data":null,"error":{"code":"FORBIDDEN","message":"You do not have permission to perform this action","fieldErrors":[]},"timestamp":"2026-11-08T05:26:24.770694100Z","traceId":"3b438232-93a2-44e6-a8a8-534`
- **PASS** [A5] Operator GET /staff/activities → 403 — got 403
  - `PUT /staff/6f716fd8-3b55-45d5-baca-a8db974ec22a/role` (A body={"roleCode":"OPERATOR"}) → **404** `{"success":false,"data":null,"error":{"code":"NOT_FOUND","message":"Staff account not found","fieldErrors":[]},"timestamp":"2026-11-08T05:26:24.811679900Z","traceId":"c3ce4f6c-29ba-4e3b-ac43-77076e4866b8"}`
- **PASS** [A5] admin changing own role rejected (4xx) — got 404
  - `POST /staff/6f716fd8-3b55-45d5-baca-a8db974ec22a/deactivate` (A) → **404** `{"success":false,"data":null,"error":{"code":"NOT_FOUND","message":"Staff account not found","fieldErrors":[]},"timestamp":"2026-11-08T05:26:24.848591200Z","traceId":"92a2a985-1146-405b-a2d7-178cb8230fd0"}`
- **PASS** [A5] admin deactivating self rejected (4xx) — got 404
  - `GET /me/permissions` (A) → **200** `{"success":true,"data":{"role":"TENANT_ADMIN","capabilities":["FINANCE_VIEW_INCOME_SUMMARY","FINANCE_VIEW_EXPENSES","FINANCE_DELETE_CATEGORY","FINANCE_ACCOUNT_CHANGE_CONFIGURATION","FINANCE_ACCOUNT_VIEW_CONFIGURATION","F`
- **PASS** [A5] admin still active after self attempts
  - `GET /staff/01a1b99f-3019-71bd-bd26-c5216fc2fb7b` (A) → **404** `{"success":false,"data":null,"error":{"code":"NOT_FOUND","message":"Staff account not found","fieldErrors":[]},"timestamp":"2026-11-08T05:26:24.900095400Z","traceId":"761a35c6-7be3-42e4-abdf-4a23e91caa18"}`
- **PASS** [A5] tenant-A admin GET tenant-B staff /staff/{bId} → 404 — got 404
  - `PATCH /staff/01a1b99f-3019-71bd-bd26-c5216fc2fb7b` (A body={"firstName":"Hijack"}) → **404** `{"success":false,"data":null,"error":{"code":"NOT_FOUND","message":"Staff account not found","fieldErrors":[]},"timestamp":"2026-11-08T05:26:24.932475300Z","traceId":"57f12b73-165c-432a-a70c-03d4d65dad67"}`
- **PASS** [A5] tenant-A admin PATCH tenant-B staff /staff/{bId} → 404 — got 404
  - `PUT /staff/01a1b99f-3019-71bd-bd26-c5216fc2fb7b/role` (A body={"roleCode":"OPERATOR"}) → **404** `{"success":false,"data":null,"error":{"code":"NOT_FOUND","message":"Staff account not found","fieldErrors":[]},"timestamp":"2026-11-08T05:26:24.962774200Z","traceId":"4aa874ea-6f1e-4d5d-aae7-c8bb8cf00d42"}`
- **PASS** [A5] tenant-A admin PUT tenant-B staff /staff/{bId}/role → 404 — got 404
  - `POST /staff/01a1b99f-3019-71bd-bd26-c5216fc2fb7b/deactivate` (A) → **404** `{"success":false,"data":null,"error":{"code":"NOT_FOUND","message":"Staff account not found","fieldErrors":[]},"timestamp":"2026-11-08T05:26:24.995372600Z","traceId":"47ad8774-c9ec-4846-85d9-f137f3e7c52a"}`
- **PASS** [A5] tenant-A admin POST tenant-B staff /staff/{bId}/deactivate → 404 — got 404
  - `POST /staff/01a1b99f-3019-71bd-bd26-c5216fc2fb7b/resend-invite` (A) → **404** `{"success":false,"data":null,"error":{"code":"NOT_FOUND","message":"Staff account not found","fieldErrors":[]},"timestamp":"2026-11-08T05:26:25.026831600Z","traceId":"7c50f2b3-6451-4840-99f3-b1d786c2bad6"}`
- **PASS** [A5] tenant-A admin POST tenant-B staff /staff/{bId}/resend-invite → 404 — got 404
  - `GET /staff/01a1b99f-3019-71bd-bd26-c5216fc2fb7b` (B) → **200** `{"success":true,"data":{"id":"01a1b99f-3019-71bd-bd26-c5216fc2fb7b","firstName":"Bella","lastName":"TenantBmw7e3ceo","name":"Bella TenantBmw7e3ceo","email":"b-mgr-mw7e3ceo@example.test","contactNumber":"+94770009999","ro`
- **PASS** [A5] tenant-B staff untouched

## A3 reassign role (existing token reflects next request)

  - `PUT /staff/01a1b99f-331b-75bb-afe5-ccad3a81d194/role` (A body={"roleCode":"MANAGER"}) → **200** `{"success":true,"data":{"id":"01a1b99f-331b-75bb-afe5-ccad3a81d194","firstName":"Olive","lastName":"Operatormw7e3ceo","name":"Olive Operatormw7e3ceo","email":"op-mw7e3ceo@example.test","contactNumber":"+94 71 555 0101","`
- **PASS** [A3] PUT role OPERATOR→MANAGER → 200
  - `GET /me/permissions` (A) → **200** `{"success":true,"data":{"role":"MANAGER","capabilities":[],"permissionVersion":0},"error":null,"timestamp":"2026-11-08T05:26:25.128653600Z","traceId":"459dd0a4-28c9-4863-854e-f1c24eb98181"}`
- **PASS** [A3] Operator's existing token now reports role MANAGER — MANAGER
  - `PUT /staff/01a1b99f-331b-75bb-afe5-ccad3a81d194/role` (A body={"roleCode":"OPERATOR"}) → **200** `{"success":true,"data":{"id":"01a1b99f-331b-75bb-afe5-ccad3a81d194","firstName":"Olive","lastName":"Operatormw7e3ceo","name":"Olive Operatormw7e3ceo","email":"op-mw7e3ceo@example.test","contactNumber":"+94 71 555 0101","`
- **PASS** [A3] PUT role MANAGER→OPERATOR → 200
  - `GET /me/permissions` (A) → **200** `{"success":true,"data":{"role":"OPERATOR","capabilities":[],"permissionVersion":0},"error":null,"timestamp":"2026-11-08T05:26:25.188562700Z","traceId":"e8366c2b-0599-4d09-917c-ad6d90b87334"}`
- **PASS** [A3] existing token reports OPERATOR again

## A4 deactivate / reactivate

  - `POST /staff/01a1b99f-331b-75bb-afe5-ccad3a81d194/deactivate` (A) → **200** `{"success":true,"data":{"id":"01a1b99f-331b-75bb-afe5-ccad3a81d194","firstName":"Olive","lastName":"Operatormw7e3ceo","name":"Olive Operatormw7e3ceo","email":"op-mw7e3ceo@example.test","contactNumber":"+94 71 555 0101","`
- **PASS** [A4] deactivate → 200 SUSPENDED — SUSPENDED
  - `GET /me/permissions` (A) → **401** `{"success":false,"data":null,"error":{"code":"SESSION_REVOKED","message":"Your session is no longer valid. Please sign in again.","fieldErrors":[]},"timestamp":"2026-11-08T05:26:25.254638600Z","traceId":"24d5ea32-13f0-49`
- **PASS** [A4] existing Operator token denied on next request — got 401
  - `POST /auth/login` (A op-mw7e3ceo@example.test) → **403** `{"success":false,"data":null,"error":{"code":"USER_SUSPENDED","message":"This account has been suspended. Please contact your institute admi`
- **PASS** [A4] login while deactivated rejected — got 403
  - `POST /staff/01a1b99f-331b-75bb-afe5-ccad3a81d194/deactivate` (A) → **200** `{"success":true,"data":{"id":"01a1b99f-331b-75bb-afe5-ccad3a81d194","firstName":"Olive","lastName":"Operatormw7e3ceo","name":"Olive Operatormw7e3ceo","email":"op-mw7e3ceo@example.test","contactNumber":"+94 71 555 0101","`
  - repeat deactivate → 200
  - `GET /staff?size=100` (B) → **200** `{"success":true,"data":{"content":[{"id":"01a1b99f-3019-71bd-bd26-c5216fc2fb7b","firstName":"Bella","lastName":"TenantBmw7e3ceo","name":"Bella TenantBmw7e3ceo","email":"b-mgr-mw7e3ceo@example.test","contactNumber":"+9477`
- **PASS** [A4] tenant B staff list unaffected
  - `POST /staff/01a1b99f-331b-75bb-afe5-ccad3a81d194/reactivate` (A) → **200** `{"success":true,"data":{"id":"01a1b99f-331b-75bb-afe5-ccad3a81d194","firstName":"Olive","lastName":"Operatormw7e3ceo","name":"Olive Operatormw7e3ceo","email":"op-mw7e3ceo@example.test","contactNumber":"+94 71 555 0101","`
- **PASS** [A4] reactivate → 200 ACTIVE — ACTIVE
  - `GET /me/permissions` (A) → **401** `{"success":false,"data":null,"error":{"code":"SESSION_REVOKED","message":"Your session is no longer valid. Please sign in again.","fieldErrors":[]},"timestamp":"2026-11-08T05:26:25.517453400Z","traceId":"624dd0cc-b27a-4a`
  - pre-deactivation access token after reactivate → 401 (informational)
  - `POST /auth/login` (A op-mw7e3ceo@example.test) → **200** `{"success":true,"data":{"accessToken":"<redacted>","expiresInSeconds":900,"sessionId":"01a1b99f-46a2-747a-84b4-0bf8db4d627c","mustChangePass`
- **PASS** [A4] login after reactivate works
  - `POST /staff` (A body={"firstName":"Ivy","lastName":"Invitedmw7e3ceo","email":"inv-mw7e3ceo@example.test","mobileNumber":"+94770005000","roleCode":"OPERATOR"}) → **201** `{"success":true,"data":{"id":"01a1b99f-472b-7721-bd25-588c651fa8c9","firstName":"Ivy","lastName":"Invitedmw7e3ceo","name":"Ivy Invitedmw7e3ceo","email":"inv-mw7e3ceo@example.test","contactNumber":"+94770005000","roleCode`
  - `POST /staff/01a1b99f-472b-7721-bd25-588c651fa8c9/deactivate` (A) → **200** `{"success":true,"data":{"id":"01a1b99f-472b-7721-bd25-588c651fa8c9","firstName":"Ivy","lastName":"Invitedmw7e3ceo","name":"Ivy Invitedmw7e3ceo","email":"inv-mw7e3ceo@example.test","contactNumber":"+94770005000","roleCode`
  - `POST /staff/01a1b99f-472b-7721-bd25-588c651fa8c9/reactivate` (A) → **200** `{"success":true,"data":{"id":"01a1b99f-472b-7721-bd25-588c651fa8c9","firstName":"Ivy","lastName":"Invitedmw7e3ceo","name":"Ivy Invitedmw7e3ceo","email":"inv-mw7e3ceo@example.test","contactNumber":"+94770005000","roleCode`
- **PASS** [A4] invited→deactivated→reactivated returns to INVITED (not ACTIVE without setup) — INVITED
  - `POST /staff/01a1b99f-472b-7721-bd25-588c651fa8c9/resend-invite` (A) → **200** `{"success":true,"data":{"id":"01a1b99f-472b-7721-bd25-588c651fa8c9","firstName":"Ivy","lastName":"Invitedmw7e3ceo","name":"Ivy Invitedmw7e3ceo","email":"inv-mw7e3ceo@example.test","contactNumber":"+94770005000","roleCode`
  - `POST /staff/01a1b99f-472b-7721-bd25-588c651fa8c9/resend-invite` (A) → **429** `{"success":false,"data":null,"error":{"code":"TOO_MANY_REQUESTS","message":"An invitation was just sent. Try again in a minute.","fieldErrors":[]},"timestamp":"2026-11-08T05:26:26.050257400Z","traceId":"076422b0-ef5c-4b1`
- **PASS** [A4] resend invite → 200, immediate repeat throttled (429) — 200/429
  - `POST /staff/01a1b99f-331b-75bb-afe5-ccad3a81d194/resend-invite` (A) → **409** `{"success":false,"data":null,"error":{"code":"CONFLICT","message":"This account has already been set up","fieldErrors":[]},"timestamp":"2026-11-08T05:26:26.085556400Z","traceId":"c97d4294-1a62-40ae-8515-4a947aa2a092"}`
- **PASS** [A4] resend invite for ACTIVE staff rejected (4xx) — got 409

## A9 pre-grant: Operator existing token denied

  - `GET /teachers` (A) → **200** `{"success":true,"data":[{"id":"01a0a8be-0d10-7e63-83fe-4ba9f31cbfc9","name":"Mohan Ranaweera","firstName":"Mohan","lastName":"Ranaweera","email":"mohanranaweera@gmail.com","contactNumber":"0722262628","instituteCommissio`
  - `GET /students/directory?size=1` (A) → **200** `{"success":true,"data":{"content":[{"id":"01a0d48c-f9f6-7a22-bab0-08626d4e0812","indexNumber":"0001","firstName":"Demo","lastName":"Student","email":"demostudent@gmail.com","mobile":null,"lastLoginAt":"2026-10-29T07:47:3`
  - A student id=01a0d48c-f9f6-7a22-bab0-08626d4e0812; row keys=id,indexNumber,firstName,lastName,email,mobile,lastLoginAt,status,hasPhoto
  - `POST /courses` (A body={"name":"OP course mw7e3ceo 8u3u","status":"PUBLIC","pricingModel":"FREE","teacherIds":["01a0fcc9-2745-79a9-8cb6-3dfef15a4efa"]}) → **403** `{"success":false,"data":null,"error":{"code":"FORBIDDEN","message":"You do not have permission to perform this action","fieldErrors":[]},"timestamp":"2026-11-08T05:26:26.380217700Z","traceId":"57677ff4-5d48-4cd2-98bd-1bd`
- **PASS** [A9] before grant: courseAdd → 403 — got 403
  - `GET /students/directory?size=5` (A) → **403** `{"success":false,"data":null,"error":{"code":"FORBIDDEN","message":"You do not have permission to perform this action","fieldErrors":[]},"timestamp":"2026-11-08T05:26:26.397372300Z","traceId":"d805ab8c-0e40-47b2-aef5-356`
- **PASS** [A9] before grant: studentView → 403 — got 403
  - `GET /students/directory/export.csv` (A) → **403** `{"success":false,"data":null,"error":{"code":"FORBIDDEN","message":"You do not have permission to perform this action","fieldErrors":[]},"timestamp":"2026-11-08T05:26:26.413212300Z","traceId":"73944a88-afd0-4d7f-846d-683`
- **PASS** [A9] before grant: csv → 403 — got 403
  - `GET /ledger/dashboard?size=5` (A) → **403** `{"success":false,"data":null,"error":{"code":"FORBIDDEN","message":"You do not have permission to perform this action","fieldErrors":[]},"timestamp":"2026-11-08T05:26:26.430110Z","traceId":"ba8ddd86-a4fd-4086-8f2f-a50316`
- **PASS** [A9] before grant: payView → 403 — got 403
  - `POST /students/qr-export` (A body={"studentIds":["01a0d48c-f9f6-7a22-bab0-08626d4e0812"]}) → **403** `{"success":false,"data":null,"error":{"code":"FORBIDDEN","message":"You do not have permission to perform this action","fieldErrors":[]},"timestamp":"2026-11-08T05:26:26.446719900Z","traceId":"52050e7d-e17c-4c44-a547-b3e`
- **PASS** [A9] before grant: qr → 403 — got 403
  - `POST /free-cards` (A body={"studentId":"01a0d48c-f9f6-7a22-bab0-08626d4e0812","cardType":"FULL","reason":"live test","idempotencyKey":"fc-mw7e3ceo"}) → **403** `{"success":false,"data":null,"error":{"code":"FORBIDDEN","message":"You do not have permission to perform this action","fieldErrors":[]},"timestamp":"2026-11-08T05:26:26.471551800Z","traceId":"2c49e76a-cd39-48a6-94aa-d59`
- **PASS** [A9] before grant: freeCard → 403 — got 403

## A6 single grant save + A9 enable

  - `GET /staff/permission-matrix` (A) → **200** `{"success":true,"data":{"version":0,"roles":[{"code":"OPERATOR","displayName":"Operator"},{"code":"MANAGER","displayName":"Manager"},{"code":"TEACHER","displayName":"Teacher"}],"groups":[{"key":"FINANCE","label":"Finance`
  - `GET /staff/permission-matrix` (A) → **200** `{"success":true,"data":{"version":0,"roles":[{"code":"OPERATOR","displayName":"Operator"},{"code":"MANAGER","displayName":"Manager"},{"code":"TEACHER","displayName":"Teacher"}],"groups":[{"key":"FINANCE","label":"Finance`
  - `PUT /staff/permission-matrix` (A body={"expectedVersion":0,"changes":[{"roleCode":"OPERATOR","capabilityKey":"COURSES_ADD","granted":true}]}) → **200** `{"success":true,"data":{"version":1,"roles":[{"code":"OPERATOR","displayName":"Operator"},{"code":"MANAGER","displayName":"Manager"},{"code":"TEACHER","displayName":"Teacher"}],"groups":[{"key":"FINANCE","label":"Finance`
- **PASS** [A6] single grant save → 200, version+1 — v0→v1
  - `GET /staff/permission-matrix` (A) → **200** `{"success":true,"data":{"version":1,"roles":[{"code":"OPERATOR","displayName":"Operator"},{"code":"MANAGER","displayName":"Manager"},{"code":"TEACHER","displayName":"Teacher"}],"groups":[{"key":"FINANCE","label":"Finance`
- **PASS** [A6] reload: OPERATOR/COURSES_ADD = GRANTED
  - `POST /courses` (A body={"name":"OP course mw7e3ceo k8t7","status":"PUBLIC","pricingModel":"FREE","teacherIds":["01a0fcc9-2745-79a9-8cb6-3dfef15a4efa"]}) → **201** `{"success":true,"data":{"id":"01a1b99f-4a37-79a8-89e2-73600f36832c","teacherId":"01a0fcc9-2745-79a9-8cb6-3dfef15a4efa","name":"OP course mw7e3ceo k8t7","slug":"op-course-mw7e3ceo-k8t7","category":"General","subject":null`
- **PASS** [A9] after grant COURSES_ADD: POST /courses with existing token → 201 — got 201 null
  - `GET /students/directory?size=5` (A) → **403** `{"success":false,"data":null,"error":{"code":"FORBIDDEN","message":"You do not have permission to perform this action","fieldErrors":[]},"timestamp":"2026-11-08T05:26:26.749060900Z","traceId":"5e5f2329-2929-4c07-bddc-01c`
- **PASS** [A9] COURSES_ADD does not authorize student view (403)
  - `GET /staff/permission-matrix` (A) → **200** `{"success":true,"data":{"version":1,"roles":[{"code":"OPERATOR","displayName":"Operator"},{"code":"MANAGER","displayName":"Manager"},{"code":"TEACHER","displayName":"Teacher"}],"groups":[{"key":"FINANCE","label":"Finance`
  - `PUT /staff/permission-matrix` (A body={"expectedVersion":1,"changes":[{"roleCode":"OPERATOR","capabilityKey":"STUDENTS_VIEW","granted":true}]}) → **200** `{"success":true,"data":{"version":2,"roles":[{"code":"OPERATOR","displayName":"Operator"},{"code":"MANAGER","displayName":"Manager"},{"code":"TEACHER","displayName":"Teacher"}],"groups":[{"key":"FINANCE","label":"Finance`
  - `GET /students/directory?size=5` (A) → **200** `{"success":true,"data":{"content":[{"id":"01a0d48c-f9f6-7a22-bab0-08626d4e0812","indexNumber":"0001","firstName":"Demo","lastName":"Student","email":"demostudent@gmail.com","mobile":null,"lastLoginAt":"2026-10-29T07:47:3`
- **PASS** [A9] after STUDENTS_VIEW: directory → 200 — got 200
  - `GET /students/directory/export.csv` (A) → **403** `{"success":false,"data":null,"error":{"code":"FORBIDDEN","message":"You do not have permission to perform this action","fieldErrors":[]},"timestamp":"2026-11-08T05:26:26.868290Z","traceId":"bc694f50-708e-4c84-8b1c-cd34db`
- **PASS** [A9] sibling: STUDENTS_VIEW ≠ STUDENTS_EXPORT_CSV (csv 403) — got 403
  - `POST /students/qr-export` (A body={"studentIds":["01a0d48c-f9f6-7a22-bab0-08626d4e0812"]}) → **403** `{"success":false,"data":null,"error":{"code":"FORBIDDEN","message":"You do not have permission to perform this action","fieldErrors":[]},"timestamp":"2026-11-08T05:26:26.886012300Z","traceId":"6d650502-c15c-4f47-8c84-329`
- **PASS** [A9] sibling: STUDENTS_VIEW ≠ STUDENTS_EXPORT_QR (qr 403) — got 403

## A6 group grant save (Operator payments pair, Manager PAYMENTS group)

  - `GET /staff/permission-matrix` (A) → **200** `{"success":true,"data":{"version":2,"roles":[{"code":"OPERATOR","displayName":"Operator"},{"code":"MANAGER","displayName":"Manager"},{"code":"TEACHER","displayName":"Teacher"}],"groups":[{"key":"FINANCE","label":"Finance`
  - `PUT /staff/permission-matrix` (A body={"expectedVersion":2,"changes":[{"roleCode":"OPERATOR","capabilityKey":"PAYMENTS_VIEW","granted":true},{"roleCode":"OPERATOR","capabilityKey":"PAYMENTS_ADD","gr) → **200** `{"success":true,"data":{"version":3,"roles":[{"code":"OPERATOR","displayName":"Operator"},{"code":"MANAGER","displayName":"Manager"},{"code":"TEACHER","displayName":"Teacher"}],"groups":[{"key":"FINANCE","label":"Finance`
- **PASS** [A6] two-cell save (atomic) → 200
  - `GET /ledger/dashboard?size=5` (A) → **200** `{"success":true,"data":{"content":[{"id":"01a18896-a442-77bb-92fe-22dfd3eea100","orderId":"01a18896-91e2-7c89-bb8b-020802f00d8a","paymentId":"01a18896-a437-77d3-89c0-d145e468e5c7","entryType":"PAYMENT_CONFIRMED","amount"`
- **PASS** [A9] after PAYMENTS_VIEW: GET /ledger/dashboard → 200 — got 200
  - `POST /free-cards` (A body={"studentId":"01a0d48c-f9f6-7a22-bab0-08626d4e0812","cardType":"FULL","reason":"live test","idempotencyKey":"fc-mw7e3ceo"}) → **403** `{"success":false,"data":null,"error":{"code":"FORBIDDEN","message":"You do not have permission to perform this action","fieldErrors":[]},"timestamp":"2026-11-08T05:26:27.064571800Z","traceId":"48db3768-bf3f-4e63-bf22-6af`
- **PASS** [A9] sibling: PAYMENTS_ADD ≠ PAYMENTS_ISSUE_FREE_CARDS (POST /free-cards 403) — got 403
  - `GET /staff/permission-matrix` (A) → **200** `{"success":true,"data":{"version":3,"roles":[{"code":"OPERATOR","displayName":"Operator"},{"code":"MANAGER","displayName":"Manager"},{"code":"TEACHER","displayName":"Teacher"}],"groups":[{"key":"FINANCE","label":"Finance`
  - `GET /staff/permission-matrix` (A) → **200** `{"success":true,"data":{"version":3,"roles":[{"code":"OPERATOR","displayName":"Operator"},{"code":"MANAGER","displayName":"Manager"},{"code":"TEACHER","displayName":"Teacher"}],"groups":[{"key":"FINANCE","label":"Finance`
  - `PUT /staff/permission-matrix` (A body={"expectedVersion":3,"changes":[{"roleCode":"MANAGER","capabilityKey":"PAYMENTS_VIEW","granted":true},{"roleCode":"MANAGER","capabilityKey":"PAYMENTS_ADD","gran) → **200** `{"success":true,"data":{"version":4,"roles":[{"code":"OPERATOR","displayName":"Operator"},{"code":"MANAGER","displayName":"Manager"},{"code":"TEACHER","displayName":"Teacher"}],"groups":[{"key":"FINANCE","label":"Finance`
- **PASS** [A6] group save MANAGER × PAYMENTS (6 cells) → 200
  - `GET /staff/permission-matrix` (A) → **200** `{"success":true,"data":{"version":4,"roles":[{"code":"OPERATOR","displayName":"Operator"},{"code":"MANAGER","displayName":"Manager"},{"code":"TEACHER","displayName":"Teacher"}],"groups":[{"key":"FINANCE","label":"Finance`
- **PASS** [A6] reload: all those MANAGER cells GRANTED; LOCKED row unchanged
  - `GET /me/permissions` (A) → **200** `{"success":true,"data":{"role":"MANAGER","capabilities":["PAYMENTS_VIEW","PAYMENTS_ADD","PAYMENTS_ISSUE_FREE_CARDS","PAYMENTS_LIST_UNDO","PAYMENTS_UNDO","PAYMENTS_APPROVE_PENDING"],"permissionVersion":4},"error":null,"ti`
- **PASS** [A6] Manager /me/permissions reflects group grant — 6 caps
  - `GET /staff/permission-matrix` (A) → **200** `{"success":true,"data":{"version":4,"roles":[{"code":"OPERATOR","displayName":"Operator"},{"code":"MANAGER","displayName":"Manager"},{"code":"TEACHER","displayName":"Teacher"}],"groups":[{"key":"FINANCE","label":"Finance`
  - `PUT /staff/permission-matrix` (A body={"expectedVersion":4,"changes":[{"roleCode":"OPERATOR","capabilityKey":"STUDENTS_EXPORT_CSV","granted":true},{"roleCode":"OPERATOR","capabilityKey":"STUDENTS_EX) → **200** `{"success":true,"data":{"version":5,"roles":[{"code":"OPERATOR","displayName":"Operator"},{"code":"MANAGER","displayName":"Manager"},{"code":"TEACHER","displayName":"Teacher"}],"groups":[{"key":"FINANCE","label":"Finance`
  - `GET /students/directory/export.csv` (A) → **200** `﻿Index Number,First Name,Last Name,Email,Mobile,Last Login,Status
0001,Demo,Student,demostudent@gmail.com,,2026-10-29 13:17,ACTIVE
0002,Demo,Student,student@demo.test,0722262629,2026-11-06 23:04,ACTIVE
0003,Kasunmw6ex`
- **PASS** [A9] after STUDENTS_EXPORT_CSV: csv → 200 text/csv — got 200 text/csv;charset=UTF-8
  - `POST /students/qr-export` (A body={"studentIds":["01a0d48c-f9f6-7a22-bab0-08626d4e0812"]}) → **200** `<29692 bytes application/pdf>`
- **PASS** [A9] after STUDENTS_EXPORT_QR: qr-export → 200 PDF — got 200 application/pdf

## A9 cross-tenant with grants

  - `GET /staff/permission-matrix` (A) → **200** `{"success":true,"data":{"version":5,"roles":[{"code":"OPERATOR","displayName":"Operator"},{"code":"MANAGER","displayName":"Manager"},{"code":"TEACHER","displayName":"Teacher"}],"groups":[{"key":"FINANCE","label":"Finance`
  - `PUT /staff/permission-matrix` (A body={"expectedVersion":5,"changes":[{"roleCode":"OPERATOR","capabilityKey":"COURSES_VIEW","granted":true},{"roleCode":"OPERATOR","capabilityKey":"STUDENTS_VIEW_PROF) → **200** `{"success":true,"data":{"version":6,"roles":[{"code":"OPERATOR","displayName":"Operator"},{"code":"MANAGER","displayName":"Manager"},{"code":"TEACHER","displayName":"Teacher"}],"groups":[{"key":"FINANCE","label":"Finance`
  - `GET /courses/undefined` (A) → **400** `{"success":false,"data":null,"error":{"code":"VALIDATION_ERROR","message":"Request parameter 'id' has an invalid value","fieldErrors":[]},"timestamp":"2026-11-08T05:26:31.099697Z","traceId":"1e286dbf-b01e-4109-9eb1-7b88d`
- **FAIL** [A9] Operator(A, granted) GET /courses/undefined → 404/403 — got 400
  - `PATCH /courses/undefined` (A body={"name":"hijack"}) → **400** `{"success":false,"data":null,"error":{"code":"VALIDATION_ERROR","message":"Request parameter 'id' has an invalid value","fieldErrors":[]},"timestamp":"2026-11-08T05:26:31.127041100Z","traceId":"fa62802b-34c4-4d1f-93a9-fa`
- **FAIL** [A9] Operator(A, granted) PATCH /courses/undefined → 404/403 — got 400
  - `GET /students/01a1b99f-31e5-77a0-8043-1c6ad7d905c4` (A) → **404** `{"success":false,"data":null,"error":{"code":"NOT_FOUND","message":"Student account not found","fieldErrors":[]},"timestamp":"2026-11-08T05:26:31.177664800Z","traceId":"df73c968-7a15-4005-87a7-80823788e5f9"}`
- **PASS** [A9] Operator(A, granted) GET /students/{B id} → 404/403 — got 404
  - `GET /students/01a1b99f-31e5-77a0-8043-1c6ad7d905c4/detail` (A) → **404** `{"success":false,"data":null,"error":{"code":"NOT_FOUND","message":"Student account not found","fieldErrors":[]},"timestamp":"2026-11-08T05:26:31.212755400Z","traceId":"4d1713b1-9a32-4445-9501-756bd0a41042"}`
- **PASS** [A9] Operator(A, granted) GET /students/{B id}/detail → 404/403 — got 404
  - `POST /students/qr-export` (A body={"studentIds":["01a1b99f-31e5-77a0-8043-1c6ad7d905c4"]}) → **200** `<832 bytes application/pdf>`
- **FAIL** [A9] Operator QR export with tenant-B student id → not a B label (4xx) — got 200 application/pdf
  - `GET /students/directory/export.csv` (A) → **200** `﻿Index Number,First Name,Last Name,Email,Mobile,Last Login,Status
0001,Demo,Student,demostudent@gmail.com,,2026-10-29 13:17,ACTIVE
0002,Demo,Student,student@demo.test,0722262629,2026-11-06 23:04,ACTIVE
0003,Kasunmw6ex`
- **PASS** [A9] Operator CSV export contains no tenant-B student
  - `GET /courses?size=100` (A) → **200** `{"success":true,"data":{"content":[{"id":"01a0a8c1-c4fd-755c-8fc5-c993482707a4","teacherId":"01a0fcc9-2745-79a9-8cb6-3dfef15a4efa","name":"2028 Biology Theory Class","slug":"2028-biology-theory-class","category":"Biology`
- **PASS** [A9] Operator course list contains no tenant-B course
  - `GET /courses/undefined` (B) → **400** `{"success":false,"data":null,"error":{"code":"VALIDATION_ERROR","message":"Request parameter 'id' has an invalid value","fieldErrors":[]},"timestamp":"2026-11-08T05:26:31.523137600Z","traceId":"64c3e2f8-d3a3-425e-8bce-6f`
- **FAIL** [A9] tenant-B course name untouched

## A9 revoke → next request denied (same token)

  - `GET /staff/permission-matrix` (A) → **200** `{"success":true,"data":{"version":6,"roles":[{"code":"OPERATOR","displayName":"Operator"},{"code":"MANAGER","displayName":"Manager"},{"code":"TEACHER","displayName":"Teacher"}],"groups":[{"key":"FINANCE","label":"Finance`
  - `GET /staff/permission-matrix` (A) → **200** `{"success":true,"data":{"version":6,"roles":[{"code":"OPERATOR","displayName":"Operator"},{"code":"MANAGER","displayName":"Manager"},{"code":"TEACHER","displayName":"Teacher"}],"groups":[{"key":"FINANCE","label":"Finance`
  - `PUT /staff/permission-matrix` (A body={"expectedVersion":6,"changes":[{"roleCode":"OPERATOR","capabilityKey":"COURSES_VIEW","granted":false},{"roleCode":"OPERATOR","capabilityKey":"COURSES_ADD","gra) → **200** `{"success":true,"data":{"version":7,"roles":[{"code":"OPERATOR","displayName":"Operator"},{"code":"MANAGER","displayName":"Manager"},{"code":"TEACHER","displayName":"Teacher"}],"groups":[{"key":"FINANCE","label":"Finance`
- **PASS** [A9] revoke all 9 Operator grants → 200
  - `POST /courses` (A body={"name":"OP course mw7e3ceo o2wr","status":"PUBLIC","pricingModel":"FREE","teacherIds":["01a0fcc9-2745-79a9-8cb6-3dfef15a4efa"]}) → **403** `{"success":false,"data":null,"error":{"code":"FORBIDDEN","message":"You do not have permission to perform this action","fieldErrors":[]},"timestamp":"2026-11-08T05:26:31.663943300Z","traceId":"52a40153-857f-4f3c-8cbd-93f`
- **PASS** [A9] after revoke: courseAdd → 403 on next request — got 403
  - `GET /students/directory?size=5` (A) → **403** `{"success":false,"data":null,"error":{"code":"FORBIDDEN","message":"You do not have permission to perform this action","fieldErrors":[]},"timestamp":"2026-11-08T05:26:31.692001500Z","traceId":"c1702d94-2b52-4c7c-8e1b-4cf`
- **PASS** [A9] after revoke: studentView → 403 on next request — got 403
  - `GET /students/directory/export.csv` (A) → **403** `{"success":false,"data":null,"error":{"code":"FORBIDDEN","message":"You do not have permission to perform this action","fieldErrors":[]},"timestamp":"2026-11-08T05:26:31.721278600Z","traceId":"86a06d94-f488-4735-8596-1ad`
- **PASS** [A9] after revoke: csv → 403 on next request — got 403
  - `GET /ledger/dashboard?size=5` (A) → **403** `{"success":false,"data":null,"error":{"code":"FORBIDDEN","message":"You do not have permission to perform this action","fieldErrors":[]},"timestamp":"2026-11-08T05:26:31.763459700Z","traceId":"8b98b06e-6c9f-4b2f-a16d-833`
- **PASS** [A9] after revoke: payView → 403 on next request — got 403
  - `POST /students/qr-export` (A body={"studentIds":["01a0d48c-f9f6-7a22-bab0-08626d4e0812"]}) → **403** `{"success":false,"data":null,"error":{"code":"FORBIDDEN","message":"You do not have permission to perform this action","fieldErrors":[]},"timestamp":"2026-11-08T05:26:31.803686700Z","traceId":"1aa4ca56-f3eb-411f-ad1e-423`
- **PASS** [A9] after revoke: qr → 403 on next request — got 403

## A7 locked / unavailable / unknown → 400, no partial write

  - `GET /staff/permission-matrix` (A) → **200** `{"success":true,"data":{"version":7,"roles":[{"code":"OPERATOR","displayName":"Operator"},{"code":"MANAGER","displayName":"Manager"},{"code":"TEACHER","displayName":"Teacher"}],"groups":[{"key":"FINANCE","label":"Finance`
  - `GET /staff/activities?q=permissions&size=50` (A) → **200** `{"success":true,"data":{"content":[{"id":"01a1b99f-5dc2-7320-bbbc-fd5c1ca2e7e7","action":"staff.role_permissions_changed","actionLabel":"Staff role permissions changed","actorName":"t•••@demo.test","actorRole":"Tenant Ad`
  - `PUT /staff/permission-matrix` (A body={"expectedVersion":7,"changes":[{"roleCode":"OPERATOR","capabilityKey":"MEDIA_VIEW","granted":true},{"roleCode":"OPERATOR","capabilityKey":"STAFF_VIEW","granted) → **400** `{"success":false,"data":null,"error":{"code":"VALIDATION_ERROR","message":"STAFF_VIEW is locked and cannot be changed","fieldErrors":[]},"timestamp":"2026-11-08T05:26:32.047075Z","traceId":"18a1f248-c182-4934-80cb-2c7cae`
  - `GET /staff/permission-matrix` (A) → **200** `{"success":true,"data":{"version":7,"roles":[{"code":"OPERATOR","displayName":"Operator"},{"code":"MANAGER","displayName":"Manager"},{"code":"TEACHER","displayName":"Teacher"}],"groups":[{"key":"FINANCE","label":"Finance`
- **PASS** [A7] LOCKED OPERATOR/STAFF_VIEW (+ valid MEDIA_VIEW in same batch) → 400, no partial write — got 400, v=7, MEDIA_VIEW=NOT_GRANTED
  - `PUT /staff/permission-matrix` (A body={"expectedVersion":7,"changes":[{"roleCode":"OPERATOR","capabilityKey":"MEDIA_VIEW","granted":true},{"roleCode":"OPERATOR","capabilityKey":"STUDENTS_LOG_AS","gr) → **400** `{"success":false,"data":null,"error":{"code":"VALIDATION_ERROR","message":"STUDENTS_LOG_AS is locked and cannot be changed","fieldErrors":[]},"timestamp":"2026-11-08T05:26:32.143344300Z","traceId":"354d6529-24da-4107-878`
  - `GET /staff/permission-matrix` (A) → **200** `{"success":true,"data":{"version":7,"roles":[{"code":"OPERATOR","displayName":"Operator"},{"code":"MANAGER","displayName":"Manager"},{"code":"TEACHER","displayName":"Teacher"}],"groups":[{"key":"FINANCE","label":"Finance`
- **PASS** [A7] LOCKED OPERATOR/STUDENTS_LOG_AS (+ valid MEDIA_VIEW in same batch) → 400, no partial write — got 400, v=7, MEDIA_VIEW=NOT_GRANTED
  - `PUT /staff/permission-matrix` (A body={"expectedVersion":7,"changes":[{"roleCode":"OPERATOR","capabilityKey":"MEDIA_VIEW","granted":true},{"roleCode":"OPERATOR","capabilityKey":"TUTE_CREATE","grante) → **400** `{"success":false,"data":null,"error":{"code":"VALIDATION_ERROR","message":"TUTE_CREATE is unavailable and cannot be changed","fieldErrors":[]},"timestamp":"2026-11-08T05:26:32.227178200Z","traceId":"e45f2ebe-db52-4e5e-b3`
  - `GET /staff/permission-matrix` (A) → **200** `{"success":true,"data":{"version":7,"roles":[{"code":"OPERATOR","displayName":"Operator"},{"code":"MANAGER","displayName":"Manager"},{"code":"TEACHER","displayName":"Teacher"}],"groups":[{"key":"FINANCE","label":"Finance`
- **PASS** [A7] UNAVAILABLE OPERATOR/TUTE_CREATE (+ valid MEDIA_VIEW in same batch) → 400, no partial write — got 400, v=7, MEDIA_VIEW=NOT_GRANTED
  - `PUT /staff/permission-matrix` (A body={"expectedVersion":7,"changes":[{"roleCode":"OPERATOR","capabilityKey":"MEDIA_VIEW","granted":true},{"roleCode":"OPERATOR","capabilityKey":"NOT_A_CAPABILITY","g) → **400** `{"success":false,"data":null,"error":{"code":"VALIDATION_ERROR","message":"Unknown capability","fieldErrors":[]},"timestamp":"2026-11-08T05:26:32.326152200Z","traceId":"3fa84e73-165d-4a36-bb44-ac3ecf79e992"}`
  - `GET /staff/permission-matrix` (A) → **200** `{"success":true,"data":{"version":7,"roles":[{"code":"OPERATOR","displayName":"Operator"},{"code":"MANAGER","displayName":"Manager"},{"code":"TEACHER","displayName":"Teacher"}],"groups":[{"key":"FINANCE","label":"Finance`
- **PASS** [A7] unknown key (+ valid MEDIA_VIEW in same batch) → 400, no partial write — got 400, v=7, MEDIA_VIEW=NOT_GRANTED
  - `PUT /staff/permission-matrix` (A body={"expectedVersion":7,"changes":[{"roleCode":"OPERATOR","capabilityKey":"MEDIA_VIEW","granted":true},{"roleCode":"TEACHER","capabilityKey":"COURSES_ADD","granted) → **400** `{"success":false,"data":null,"error":{"code":"VALIDATION_ERROR","message":"COURSES_ADD is not configurable for TEACHER","fieldErrors":[]},"timestamp":"2026-11-08T05:26:32.464360600Z","traceId":"b8d78c44-3a51-426b-b4c3-1e`
  - `GET /staff/permission-matrix` (A) → **200** `{"success":true,"data":{"version":7,"roles":[{"code":"OPERATOR","displayName":"Operator"},{"code":"MANAGER","displayName":"Manager"},{"code":"TEACHER","displayName":"Teacher"}],"groups":[{"key":"FINANCE","label":"Finance`
- **PASS** [A7] non-configurable TEACHER/COURSES_ADD (+ valid MEDIA_VIEW in same batch) → 400, no partial write — got 400, v=7, MEDIA_VIEW=NOT_GRANTED
  - `PUT /staff/permission-matrix` (A body={"expectedVersion":7,"changes":[{"roleCode":"OPERATOR","capabilityKey":"MEDIA_VIEW","granted":true},{"roleCode":"TENANT_ADMIN","capabilityKey":"COURSES_ADD","gr) → **400** `{"success":false,"data":null,"error":{"code":"VALIDATION_ERROR","message":"Unknown or non-configurable role","fieldErrors":[]},"timestamp":"2026-11-08T05:26:32.522731100Z","traceId":"4bbaeee3-ccc3-40de-9894-b426263b300c"`
  - `GET /staff/permission-matrix` (A) → **200** `{"success":true,"data":{"version":7,"roles":[{"code":"OPERATOR","displayName":"Operator"},{"code":"MANAGER","displayName":"Manager"},{"code":"TEACHER","displayName":"Teacher"}],"groups":[{"key":"FINANCE","label":"Finance`
- **PASS** [A7] role TENANT_ADMIN (+ valid MEDIA_VIEW in same batch) → 400, no partial write — got 400, v=7, MEDIA_VIEW=NOT_GRANTED
  - `PUT /staff/permission-matrix` (A body={"expectedVersion":7,"changes":[{"roleCode":"OPERATOR","capabilityKey":"MEDIA_VIEW","granted":true},{"roleCode":"FINANCE_STAFF","capabilityKey":"PAYMENTS_VIEW",) → **400** `{"success":false,"data":null,"error":{"code":"VALIDATION_ERROR","message":"Unknown or non-configurable role","fieldErrors":[]},"timestamp":"2026-11-08T05:26:32.571680700Z","traceId":"0f5d76c0-4e25-4a07-b35e-1459b7338253"`
  - `GET /staff/permission-matrix` (A) → **200** `{"success":true,"data":{"version":7,"roles":[{"code":"OPERATOR","displayName":"Operator"},{"code":"MANAGER","displayName":"Manager"},{"code":"TEACHER","displayName":"Teacher"}],"groups":[{"key":"FINANCE","label":"Finance`
- **PASS** [A7] role FINANCE_STAFF (+ valid MEDIA_VIEW in same batch) → 400, no partial write — got 400, v=7, MEDIA_VIEW=NOT_GRANTED

## A8 stale expectedVersion → 409

  - `PUT /staff/permission-matrix` (A body={"expectedVersion":6,"changes":[{"roleCode":"OPERATOR","capabilityKey":"MEDIA_VIEW","granted":true}]}) → **409** `{"success":false,"data":null,"error":{"code":"PERMISSIONS_STALE","message":"Permissions were changed by someone else. Reload the latest permissions and try again.","fieldErrors":[]},"timestamp":"2026-11-08T05:26:32.62482`
  - `GET /staff/permission-matrix` (A) → **200** `{"success":true,"data":{"version":7,"roles":[{"code":"OPERATOR","displayName":"Operator"},{"code":"MANAGER","displayName":"Manager"},{"code":"TEACHER","displayName":"Teacher"}],"groups":[{"key":"FINANCE","label":"Finance`
- **PASS** [A8] stale expectedVersion → 409 PERMISSIONS_STALE, no write — 409 PERMISSIONS_STALE
  - `PUT /staff/permission-matrix` (A body={"expectedVersion":12,"changes":[{"roleCode":"OPERATOR","capabilityKey":"MEDIA_VIEW","granted":true}]}) → **409** `{"success":false,"data":null,"error":{"code":"PERMISSIONS_STALE","message":"Permissions were changed by someone else. Reload the latest permissions and try again.","fieldErrors":[]},"timestamp":"2026-11-08T05:26:32.68545`
- **PASS** [A8] future expectedVersion → 409 — got 409
  - `PUT /staff/permission-matrix` (A body={"expectedVersion":7,"changes":[{"roleCode":"OPERATOR","capabilityKey":"COURSES_ADD","granted":true}]}) → **403** `{"success":false,"data":null,"error":{"code":"FORBIDDEN","message":"You do not have permission to perform this action","fieldErrors":[]},"timestamp":"2026-11-08T05:26:32.714632800Z","traceId":"a0c76f49-7d3c-4f91-987d-bb5`
- **PASS** [A8] Operator cannot save matrix (403) — delegation
  - `GET /staff/activities?q=permissions&size=50` (A) → **200** `{"success":true,"data":{"content":[{"id":"01a1b99f-5dc2-7320-bbbc-fd5c1ca2e7e7","action":"staff.role_permissions_changed","actionLabel":"Staff role permissions changed","actorName":"t•••@demo.test","actorRole":"Tenant Ad`
- **PASS** [A11] failed/rejected saves wrote no activity entry — 7 → 7

## A10 Teacher narrowing (existing onboarding: POST /teachers)

  - `POST /teachers` (A body={"name":"Tara Teachermw7e3ceo","firstName":"Tara","lastName":"Teachermw7e3ceo","email":"teacher-mw7e3ceo@example.test","password":"<redacted>"}) → **201** `{"success":true,"data":{"id":"01a1b99f-632d-743e-b5c2-0647cc156510","name":"Tara Teachermw7e3ceo","firstName":"Tara","lastName":"Teachermw7e3ceo","email":"teacher-mw7e3ceo@example.test","contactNumber":null,"instituteCom`
- **PASS** [A10] teacher created via POST /teachers → 201 — got 201
  - `POST /auth/login` (A teacher-mw7e3ceo@example.test) → **403** `{"success":false,"data":null,"error":{"code":"USER_SUSPENDED","message":"This account has been suspended. Please contact your institute admi`
  - `POST /courses` (A body={"name":"T own mw7e3ceo","status":"PUBLIC","pricingModel":"FREE","teacherIds":["01a1b99f-6323-70cd-9b8d-b1022997f82f"]}) → **400** `{"success":false,"data":null,"error":{"code":"VALIDATION_ERROR","message":"teacherId does not reference an active Teacher in this tenant","fieldErrors":[]},"timestamp":"2026-11-08T05:26:33.203890Z","traceId":"e006443b-c0`
  - `POST /courses` (A body={"name":"T other mw7e3ceo","status":"PUBLIC","pricingModel":"FREE","teacherIds":["01a0fcc9-2745-79a9-8cb6-3dfef15a4efa"]}) → **201** `{"success":true,"data":{"id":"01a1b99f-6406-75e7-8d05-9affc81ae794","teacherId":"01a0fcc9-2745-79a9-8cb6-3dfef15a4efa","name":"T other mw7e3ceo","slug":"t-other-mw7e3ceo","category":"General","subject":null,"stream":null`
  - `POST /courses/undefined/modules` (A body={"title":"M1 mw7e3ceo"}) → **401** `{"success":false,"data":null,"error":{"code":"UNAUTHENTICATED","message":"Authentication is required","fieldErrors":[]},"timestamp":"2026-11-08T05:26:33.269359Z","traceId":"e38a459e-d501-482b-a9f2-6eca0d3a9a71"}`
- **FAIL** [A10] default: teacher creates module in own course → 201 — got 401
  - `POST /courses/01a1b99f-6406-75e7-8d05-9affc81ae794/modules` (A body={"title":"X mw7e3ceo"}) → **401** `{"success":false,"data":null,"error":{"code":"UNAUTHENTICATED","message":"Authentication is required","fieldErrors":[]},"timestamp":"2026-11-08T05:26:33.282547200Z","traceId":"dc2d61b6-48d9-4845-afba-030309b43a62"}`
- **FAIL** [A10] teacher on another teacher's course → 403/404 — got 401
  - `POST /courses/undefined/modules` (A body={"title":"X mw7e3ceo"}) → **401** `{"success":false,"data":null,"error":{"code":"UNAUTHENTICATED","message":"Authentication is required","fieldErrors":[]},"timestamp":"2026-11-08T05:26:33.296920900Z","traceId":"a675ef72-1e26-4b8e-ad59-5af0717a0bbe"}`
- **FAIL** [A10] teacher on tenant-B course → 403/404 — got 401
  - `GET /staff/permission-matrix` (A) → **200** `{"success":true,"data":{"version":7,"roles":[{"code":"OPERATOR","displayName":"Operator"},{"code":"MANAGER","displayName":"Manager"},{"code":"TEACHER","displayName":"Teacher"}],"groups":[{"key":"FINANCE","label":"Finance`
  - `PUT /staff/permission-matrix` (A body={"expectedVersion":7,"changes":[{"roleCode":"TEACHER","capabilityKey":"MODULES_ADD","granted":false}]}) → **200** `{"success":true,"data":{"version":8,"roles":[{"code":"OPERATOR","displayName":"Operator"},{"code":"MANAGER","displayName":"Manager"},{"code":"TEACHER","displayName":"Teacher"}],"groups":[{"key":"FINANCE","label":"Finance`
  - `POST /courses/undefined/modules` (A body={"title":"M2 mw7e3ceo"}) → **401** `{"success":false,"data":null,"error":{"code":"UNAUTHENTICATED","message":"Authentication is required","fieldErrors":[]},"timestamp":"2026-11-08T05:26:33.367285500Z","traceId":"ad7a80ab-1879-4ad6-9d3f-1ca1e2617d1b"}`
- **FAIL** [A10] after revoke MODULES_ADD: own-course module create → 403 (same token) — got 401
  - `GET /courses/undefined/modules` (A) → **401** `{"success":false,"data":null,"error":{"code":"UNAUTHENTICATED","message":"Authentication is required","fieldErrors":[]},"timestamp":"2026-11-08T05:26:33.390052500Z","traceId":"155342e8-7e4f-4ba2-bf71-6d05bd9d501f"}`
- **FAIL** [A10] revoking MODULES_ADD leaves MODULES_VIEW (200) — got 401
  - `GET /staff/permission-matrix` (A) → **200** `{"success":true,"data":{"version":8,"roles":[{"code":"OPERATOR","displayName":"Operator"},{"code":"MANAGER","displayName":"Manager"},{"code":"TEACHER","displayName":"Teacher"}],"groups":[{"key":"FINANCE","label":"Finance`
  - `PUT /staff/permission-matrix` (A body={"expectedVersion":8,"changes":[{"roleCode":"TEACHER","capabilityKey":"MODULES_ADD","granted":true}]}) → **200** `{"success":true,"data":{"version":9,"roles":[{"code":"OPERATOR","displayName":"Operator"},{"code":"MANAGER","displayName":"Manager"},{"code":"TEACHER","displayName":"Teacher"}],"groups":[{"key":"FINANCE","label":"Finance`
  - `POST /courses/undefined/modules` (A body={"title":"M3 mw7e3ceo"}) → **401** `{"success":false,"data":null,"error":{"code":"UNAUTHENTICATED","message":"Authentication is required","fieldErrors":[]},"timestamp":"2026-11-08T05:26:33.449791600Z","traceId":"f6dc4bb6-6009-428e-86c4-4fb6a703c0e5"}`
- **FAIL** [A10] after regrant: own-course module create → 201 — got 401
  - `POST /courses/01a1b99f-6406-75e7-8d05-9affc81ae794/modules` (A body={"title":"X2 mw7e3ceo"}) → **401** `{"success":false,"data":null,"error":{"code":"UNAUTHENTICATED","message":"Authentication is required","fieldErrors":[]},"timestamp":"2026-11-08T05:26:33.460911300Z","traceId":"0ca9dade-a5dd-4eff-a1e8-1b39e36574ae"}`
- **FAIL** [A10] after regrant: another teacher's course still 403/404 — got 401
  - `GET /staff` (A) → **401** `{"success":false,"data":null,"error":{"code":"UNAUTHENTICATED","message":"Authentication is required","fieldErrors":[]},"timestamp":"2026-11-08T05:26:33.471208500Z","traceId":"013babb3-a531-4825-9539-547ed9230e0a"}`
- **FAIL** [A10] teacher cannot list staff (403)

## A11 / A12 activities

  - `GET /staff/activities?size=50` (A) → **200** `{"success":true,"data":{"content":[{"id":"01a1b99f-64d8-7b09-b1ce-55f3770498c0","action":"staff.role_permissions_changed","actionLabel":"Staff role permissions changed","actorName":"t•••@demo.test","actorRole":"Tenant Ad`
  - sample entry: {"id":"01a1b99f-64d8-7b09-b1ce-55f3770498c0","action":"staff.role_permissions_changed","actionLabel":"Staff role permissions changed","actorName":"t•••@demo.test","actorRole":"Tenant Admin","targetEntity":"tenant_role_permission_version","targetReference":"24c4ed08-f732-46a5-a5c2-4f998d55327d","occurredAt":"2026-11-08T05:26:33.432464Z","details":{"granted":[{"role":"TEACHER","capability":"MODULES_ADD"}],"revoked":[],"toVersion":9,"fromVersion":8}}
  - actions seen (last 50): staff.role_permissions_changed, course.created, teacher.created, student.directory_exported, staff.invite_resent, staff.reactivated, staff.deactivated, staff.invited, staff.role_changed, staff.profile_updated, auth.account_setup_completed, student.impersonation_ended, student.impersonation_started, student.photo_removed, media_file.deleted, student.photo_updated, media_file.uploaded, student.created, course.participation_created, identity.login_mobile_changed
- **PASS** [A11] activity present: staff.invited
- **PASS** [A11] activity present: staff.profile_updated
- **PASS** [A11] activity present: staff.role_changed
- **PASS** [A11] activity present: staff.deactivated
- **PASS** [A11] activity present: staff.reactivated
- **PASS** [A11] activity present: staff.invite_resent
- **PASS** [A11] activity present: staff.role_permissions_changed
- **PASS** [A11] role_changed entry carries actor, role, target, from/to — {"id":"01a1b99f-4488-7eca-b1d7-f869c041c7c4","action":"staff.role_changed","actionLabel":"Staff role changed","actorName":"t•••@demo.test","actorRole":"Tenant Admin","targetEntity":"staff_profile","targetReference":"01a1b99f-331b-75bb-afe5-ccad3a81d194","occurredAt":"2026-11-08T05:26:25.160006Z","details":{"to":"OPERATOR","from":"MANAGER"}}
- **PASS** [A11] permissions_changed entry has version + granted/revoked summary — {"id":"01a1b99f-64d8-7b09-b1ce-55f3770498c0","action":"staff.role_permissions_changed","actionLabel":"Staff role permissions changed","actorName":"t•••@demo.test","actorRole":"Tenant Admin","targetEntity":"tenant_role_permission_version","targetReference":"24c4ed08-f732-46a5-a5c2-4f998d55327d","occurredAt":"2026-11-08T05:26:33.432464Z","details":{"granted":[{"role":"TEACHER","capability":"MODULES_
  - `GET /staff/activities?size=50&page=0` (A) → **200** `{"success":true,"data":{"content":[{"id":"01a1b99f-64d8-7b09-b1ce-55f3770498c0","action":"staff.role_permissions_changed","actionLabel":"Staff role permissions changed","actorName":"t•••@demo.test","actorRole":"Tenant Ad`
  - `GET /staff/activities?size=50&page=1` (A) → **200** `{"success":true,"data":{"content":[{"id":"01a1b620-9b5a-7acf-ac8e-d67a1710ca76","action":"identity.login_mobile_changed","actionLabel":"Identity login mobile changed","actorName":"t•••@demo.test","actorRole":"Tenant Admi`
  - `GET /staff/activities?size=50&page=2` (A) → **200** `{"success":true,"data":{"content":[{"id":"01a1b1ea-cd62-7f82-b457-522a07a0eff1","action":"course.archived","actionLabel":"Course archived","actorName":"t•••@demo.test","actorRole":"Tenant Admin","targetEntity":"course","`
  - `GET /staff/activities?size=50&page=3` (A) → **200** `{"success":true,"data":{"content":[{"id":"01a1b1e8-049a-77ef-a87d-2fc2b8958da4","action":"course.archived","actionLabel":"Course archived","actorName":"t•••@demo.test","actorRole":"Tenant Admin","targetEntity":"course","`
  - `GET /staff/activities?size=50&page=4` (A) → **200** `{"success":true,"data":{"content":[{"id":"01a1b1e3-fdc9-75a7-90c7-74fcd4b2c4d3","action":"course.created","actionLabel":"Course created","actorName":"t•••@demo.test","actorRole":"Tenant Admin","targetEntity":"course","ta`
  - `GET /staff/activities?size=50&page=5` (A) → **200** `{"success":true,"data":{"content":[{"id":"01a18895-79be-7b85-af68-5bea0669ca1d","action":"tenant_config.updated","actionLabel":"Tenant config updated","actorName":"t•••@demo.test","actorRole":"Tenant Admin","targetEntity`
  - `GET /staff/activities?size=50&page=6` (A) → **200** `{"success":true,"data":{"content":[{"id":"01a18726-a768-77ba-93f6-251c36f5fbaa","action":"content_entitlement.granted","actionLabel":"Content entitlement granted","actorName":"t•••@demo.test","actorRole":"Tenant Admin","`
  - `GET /staff/activities?size=50&page=7` (A) → **200** `{"success":true,"data":{"content":[{"id":"01a1862a-30f1-7043-8811-c754135ef737","action":"tenant_config.updated","actionLabel":"Tenant config updated","actorName":"t•••@demo.test","actorRole":"Tenant Admin","targetEntity`
  - `GET /staff/activities?size=50&page=8` (A) → **200** `{"success":true,"data":{"content":[{"id":"01a1861a-4035-75ad-b2f6-9d3fe8577424","action":"tenant_config.updated","actionLabel":"Tenant config updated","actorName":"t•••@demo.test","actorRole":"Tenant Admin","targetEntity`
  - `GET /staff/activities?size=50&page=9` (A) → **200** `{"success":true,"data":{"content":[{"id":"01a178fe-ae88-7095-8175-77ca8fc90489","action":"media_file.uploaded","actionLabel":"Media file uploaded","actorName":"t•••@demo.test","actorRole":"Tenant Admin","targetEntity":"m`
- **FAIL** [A11] no password/token/hash in any of 500 activity entries — {"id":"01a18ab1-791c-7cca-8683-90b1f8e1005c","action":"tenant_config.updated","actionLabel":"Tenant config updated","actorName":"t•••@demo.test","actorRole":"Tenant Admin","targetEntity":"tenant_config_entry","targetReference":"01a18ab1-7918-71a8-93ea-c4abca5ce9a2","occurredAt":"2026-10-30T02:44:09.
- **PASS** [A11] operator email masked in details
  - `GET /staff/activities?q=Operatormw7e3ceo&size=50` (A) → **200** `{"success":true,"data":{"content":[{"id":"01a1b99f-5c7b-7e2f-a182-7ffa2f9a977c","action":"student.directory_exported","actionLabel":"Student directory exported","actorName":"Olive Operatormw7e3ceo","actorRole":"Operator"`
- **PASS** [A12] search q=<operator surname> returns only matching entries — 4 of 500
  - `GET /staff/activities?size=3&page=0` (A) → **200** `{"success":true,"data":{"content":[{"id":"01a1b99f-64d8-7b09-b1ce-55f3770498c0","action":"staff.role_permissions_changed","actionLabel":"Staff role permissions changed","actorName":"t•••@demo.test","actorRole":"Tenant Ad`
  - `GET /staff/activities?size=3&page=1` (A) → **200** `{"success":true,"data":{"content":[{"id":"01a1b99f-6346-75a1-81a9-4cb1d12da19a","action":"teacher.created","actionLabel":"Teacher created","actorName":"t•••@demo.test","actorRole":"Tenant Admin","targetEntity":"teacher_p`
- **PASS** [A12] pagination size=3 pages 0/1 disjoint, totalElements consistent — total=1167
  - `GET /staff/activities?size=500` (A) → **200** `{"success":true,"data":{"content":[{"id":"01a1b99f-64d8-7b09-b1ce-55f3770498c0","action":"staff.role_permissions_changed","actionLabel":"Staff role permissions changed","actorName":"t•••@demo.test","actorRole":"Tenant Ad`
- **PASS** [A12] size>50 clamped or rejected — status 200 size=50
  - `GET /staff/activities?size=50&page=0` (B) → **200** `{"success":true,"data":{"content":[{"id":"01a1b99f-31f1-755a-90ee-e9830b4f0dbe","action":"student.created","actionLabel":"Student created","actorName":"a•••@academy.test","actorRole":"Tenant Admin","targetEntity":"studen`
- **PASS** [A12] tenant B admin activities (34) contain none of A's entries
  - `GET /staff/activities?q=Operatormw7e3ceo` (B) → **200** `{"success":true,"data":{"content":[],"page":0,"size":20,"totalElements":0,"totalPages":0},"error":null,"timestamp":"2026-11-08T05:26:34.250711600Z","traceId":"826af0f1-8229-45a5-badf-a56451aa1a2e"}`
- **PASS** [A12] tenant B search for A's operator → 0 results
- **PASS** [A12] tenant B sees its own staff.invited entry
  - `GET /staff/activities` (A) → **403** `{"success":false,"data":null,"error":{"code":"FORBIDDEN","message":"You do not have permission to perform this action","fieldErrors":[]},"timestamp":"2026-11-08T05:26:34.265587400Z","traceId":"d311629a-11bd-44ef-8c65-cfa`
- **PASS** [A12] Operator cannot read activities (403)

## Cleanup

  - `GET /staff/permission-matrix` (A) → **200** `{"success":true,"data":{"version":9,"roles":[{"code":"OPERATOR","displayName":"Operator"},{"code":"MANAGER","displayName":"Manager"},{"code":"TEACHER","displayName":"Teacher"}],"groups":[{"key":"FINANCE","label":"Finance`
  - `GET /staff/permission-matrix` (A) → **200** `{"success":true,"data":{"version":9,"roles":[{"code":"OPERATOR","displayName":"Operator"},{"code":"MANAGER","displayName":"Manager"},{"code":"TEACHER","displayName":"Teacher"}],"groups":[{"key":"FINANCE","label":"Finance`
  - `PUT /staff/permission-matrix` (A body={"expectedVersion":9,"changes":[{"roleCode":"MANAGER","capabilityKey":"PAYMENTS_VIEW","granted":false},{"roleCode":"MANAGER","capabilityKey":"PAYMENTS_ADD","gra) → **200** `{"success":true,"data":{"version":10,"roles":[{"code":"OPERATOR","displayName":"Operator"},{"code":"MANAGER","displayName":"Manager"},{"code":"TEACHER","displayName":"Teacher"}],"groups":[{"key":"FINANCE","label":"Financ`
  - `DELETE /courses/01a1b99f-4a37-79a8-89e2-73600f36832c` (A) → **200** `{"success":true,"data":null,"error":null,"timestamp":"2026-11-08T05:26:34.480263600Z","traceId":"1d438502-7ed4-4915-9f61-f54b24cc7be5"}`
  - `DELETE /courses/01a1b99f-6406-75e7-8d05-9affc81ae794` (A) → **200** `{"success":true,"data":null,"error":null,"timestamp":"2026-11-08T05:26:34.528768200Z","traceId":"eb0df15a-8a4e-40ce-98f1-8e956c216354"}`
  - revoked 6 grants; teacher defaults intact; deleted synthetic A courses

## Summary

- setup: 1/1 checks passed
- A1: 15/15 checks passed
- A2: 3/3 checks passed
- A5: 25/25 checks passed
- A3: 4/4 checks passed
- A4: 9/9 checks passed
- A9: 25/29 checks passed — FAILED: Operator(A, granted) GET /courses/undefined → 404/403; Operator(A, granted) PATCH /courses/undefined → 404/403; Operator QR export with tenant-B student id → not a B label (4xx); tenant-B course name untouched
- A6: 6/6 checks passed
- A7: 7/7 checks passed
- A8: 3/3 checks passed
- A11: 11/12 checks passed — FAILED: no password/token/hash in any of 500 activity entries
- A10: 1/9 checks passed — FAILED: default: teacher creates module in own course → 201; teacher on another teacher's course → 403/404; teacher on tenant-B course → 403/404; after revoke MODULES_ADD: own-course module create → 403 (same token); revoking MODULES_ADD leaves MODULES_VIEW (200); after regrant: own-course module create → 201; after regrant: another teacher's course still 403/404; teacher cannot list staff (403)
- A12: 7/7 checks passed

# Resume run mw7e8adx (A9 course cross-tenant, A10 teacher, A9 QR/B-id observation)


## Setup (resume)

  - `POST /auth/login` (A tenantadmin@demo.test) → **200** `{"success":true,"data":{"accessToken":"<redacted>","expiresInSeconds":900,"sessionId":"01a1b9a2-b324-7903-84d4-c75aa32334c5","mustChangePass`
  - `POST /auth/login` (B admin@academy.test) → **200** `{"success":true,"data":{"accessToken":"<redacted>","expiresInSeconds":900,"sessionId":"01a1b9a2-b498-7a18-9423-b6e85796a786","mustChangePass`
  - `POST /teachers` (B body={"name":"B Teacher mw7e8adx","email":"b-teacher-mw7e8adx@example.test","password":"<redacted>"}) → **201** `{"success":true,"data":{"id":"01a1b9a2-b690-7179-9aa0-7676c63c5ed4","name":"B Teacher mw7e8adx","firstName":null,"lastName":null,"email":"b-teacher-mw7e8adx@example.test","contactNumber":null,"instituteCommissionPercent"`
  - `POST /teachers/01a1b9a2-b690-7179-9aa0-7676c63c5ed4/approve` (B) → **200** `{"success":true,"data":{"id":"01a1b9a2-b690-7179-9aa0-7676c63c5ed4","name":"B Teacher mw7e8adx","firstName":null,"lastName":null,"email":"b-teacher-mw7e8adx@example.test","contactNumber":null,"instituteCommissionPercent"`
  - B teacher approve → 200
  - `POST /courses` (B body={"name":"B course mw7e8adx","status":"PUBLIC","pricingModel":"FREE","teacherIds":["01a1b9a2-b663-72ca-b078-e8b90925634c"]}) → **201** `{"success":true,"data":{"id":"01a1b9a2-b82c-7434-bd83-4256d84e1b44","teacherId":"01a1b9a2-b663-72ca-b078-e8b90925634c","name":"B course mw7e8adx","slug":"b-course-mw7e8adx","category":"General","subject":null,"stream":nu`
- **PASS** [setup] tenant-B course seeded — status 201

## A9 course cross-tenant with grants (Operator existing token)

  - `POST /auth/login` (A op-mw7e3ceo@example.test) → **200** `{"success":true,"data":{"accessToken":"<redacted>","expiresInSeconds":900,"sessionId":"01a1b9a2-ba3d-7f3e-afd6-87d3f97b2cab","mustChangePass`
  - `GET /staff/permission-matrix` (A) → **200** `{"success":true,"data":{"version":10,"roles":[{"code":"OPERATOR","displayName":"Operator"},{"code":"MANAGER","displayName":"Manager"},{"code":"TEACHER","displayName":"Teacher"}],"groups":[{"key":"FINANCE","label":"Financ`
  - `PUT /staff/permission-matrix` (A body={"expectedVersion":10,"changes":[{"roleCode":"OPERATOR","capabilityKey":"COURSES_VIEW","granted":true},{"roleCode":"OPERATOR","capabilityKey":"COURSES_EDIT","gr) → **200** `{"success":true,"data":{"version":11,"roles":[{"code":"OPERATOR","displayName":"Operator"},{"code":"MANAGER","displayName":"Manager"},{"code":"TEACHER","displayName":"Teacher"}],"groups":[{"key":"FINANCE","label":"Financ`
  - `GET /courses?size=5` (A) → **200** `{"success":true,"data":{"content":[{"id":"01a0a8c1-c4fd-755c-8fc5-c993482707a4","teacherId":"01a0fcc9-2745-79a9-8cb6-3dfef15a4efa","name":"2028 Biology Theory Class","slug":"2028-biology-theory-class","category":"Biology`
- **PASS** [A9] Operator with COURSES_VIEW lists tenant-A courses (200)
  - `GET /courses/01a1b9a2-b82c-7434-bd83-4256d84e1b44` (A) → **404** `{"success":false,"data":null,"error":{"code":"NOT_FOUND","message":"Course not found","fieldErrors":[]},"timestamp":"2026-11-08T05:30:12.846942500Z","traceId":"13dcbee1-0225-4632-af19-3e16b2459cac"}`
- **PASS** [A9] Operator(A, granted) GET /courses/{B course} → 404/403 — got 404
  - `PATCH /courses/01a1b9a2-b82c-7434-bd83-4256d84e1b44` (A body={"name":"hijack"}) → **404** `{"success":false,"data":null,"error":{"code":"NOT_FOUND","message":"Course not found","fieldErrors":[]},"timestamp":"2026-11-08T05:30:13.063551600Z","traceId":"2f0dec9a-5b2c-4b53-b801-558ae5e872b5"}`
- **PASS** [A9] Operator(A, granted) PATCH /courses/{B course} → 404/403 — got 404
  - `POST /courses/01a1b9a2-b82c-7434-bd83-4256d84e1b44/modules` (A body={"title":"hijack"}) → **404** `{"success":false,"data":null,"error":{"code":"NOT_FOUND","message":"Course not found","fieldErrors":[]},"timestamp":"2026-11-08T05:30:13.167874Z","traceId":"98236742-92a2-43c0-8fbe-2ac689d23951"}`
- **PASS** [A9] Operator(A, granted) POST /courses/{B course}/modules → 404/403 — got 404
  - `GET /courses/01a1b9a2-b82c-7434-bd83-4256d84e1b44/modules` (A) → **403** `{"success":false,"data":null,"error":{"code":"FORBIDDEN","message":"You do not have permission to perform this action","fieldErrors":[]},"timestamp":"2026-11-08T05:30:13.277537800Z","traceId":"4cb85ce4-c83d-4a81-a56d-016`
- **PASS** [A9] Operator(A, granted) GET /courses/{B course}/modules → 404/403 — got 403
  - `GET /courses/01a1b9a2-b82c-7434-bd83-4256d84e1b44` (B) → **200** `{"success":true,"data":{"id":"01a1b9a2-b82c-7434-bd83-4256d84e1b44","teacherId":"01a1b9a2-b663-72ca-b078-e8b90925634c","name":"B course mw7e8adx","slug":"b-course-mw7e8adx","category":"General","subject":null,"stream":nu`
- **PASS** [A9] tenant-B course untouched
  - `GET /courses/01a1b9a2-b82c-7434-bd83-4256d84e1b44/modules` (B) → **200** `{"success":true,"data":[],"error":null,"timestamp":"2026-11-08T05:30:13.544264700Z","traceId":"1752a786-94a1-4405-ac7d-fd24b715e134"}`
- **PASS** [A9] no module created in tenant-B course
  - `GET /staff/permission-matrix` (A) → **200** `{"success":true,"data":{"version":11,"roles":[{"code":"OPERATOR","displayName":"Operator"},{"code":"MANAGER","displayName":"Manager"},{"code":"TEACHER","displayName":"Teacher"}],"groups":[{"key":"FINANCE","label":"Financ`
  - `PUT /staff/permission-matrix` (A body={"expectedVersion":11,"changes":[{"roleCode":"OPERATOR","capabilityKey":"COURSES_VIEW","granted":false},{"roleCode":"OPERATOR","capabilityKey":"COURSES_EDIT","g) → **200** `{"success":true,"data":{"version":12,"roles":[{"code":"OPERATOR","displayName":"Operator"},{"code":"MANAGER","displayName":"Manager"},{"code":"TEACHER","displayName":"Teacher"}],"groups":[{"key":"FINANCE","label":"Financ`
  - `GET /courses?size=5` (A) → **403** `{"success":false,"data":null,"error":{"code":"FORBIDDEN","message":"You do not have permission to perform this action","fieldErrors":[]},"timestamp":"2026-11-08T05:30:13.738282100Z","traceId":"852c642e-b8fa-46e3-a66c-1cd`
- **PASS** [A9] after revoke: same token GET /courses → 403 — got 403

## A10 Teacher narrowing (POST /teachers → approve → login)

  - `POST /teachers` (A body={"name":"Tara Teachermw7e8adx","firstName":"Tara","lastName":"Teachermw7e8adx","email":"teacher2-mw7e8adx@example.test","password":"<redacted>"}) → **201** `{"success":true,"data":{"id":"01a1b9a2-c2a0-70e0-b32a-a5fdc88c20f6","name":"Tara Teachermw7e8adx","firstName":"Tara","lastName":"Teachermw7e8adx","email":"teacher2-mw7e8adx@example.test","contactNumber":null,"instituteCo`
  - `POST /teachers/01a1b9a2-c2a0-70e0-b32a-a5fdc88c20f6/approve` (A) → **200** `{"success":true,"data":{"id":"01a1b9a2-c2a0-70e0-b32a-a5fdc88c20f6","name":"Tara Teachermw7e8adx","firstName":"Tara","lastName":"Teachermw7e8adx","email":"teacher2-mw7e8adx@example.test","contactNumber":null,"instituteCo`
- **PASS** [A10] teacher onboarded (create 201 + approve 200) — 201/200
  - `POST /auth/login` (A teacher2-mw7e8adx@example.test) → **200** `{"success":true,"data":{"accessToken":"<redacted>","expiresInSeconds":900,"sessionId":"01a1b9a2-c440-74b6-9817-d005fd3c26ad","mustChangePass`
- **PASS** [A10] approved teacher signs in — got 200
  - `GET /teachers` (A) → **200** `{"success":true,"data":[{"id":"01a0a8be-0d10-7e63-83fe-4ba9f31cbfc9","name":"Mohan Ranaweera","firstName":"Mohan","lastName":"Ranaweera","email":"mohanranaweera@gmail.com","contactNumber":"0722262628","instituteCommissio`
  - `POST /courses` (A body={"name":"T own mw7e8adx","status":"PUBLIC","pricingModel":"FREE","teacherIds":["01a1b9a2-c290-79c5-9e56-345387e35784"]}) → **201** `{"success":true,"data":{"id":"01a1b9a2-c6eb-76a0-86ed-cac26729ffb9","teacherId":"01a1b9a2-c290-79c5-9e56-345387e35784","name":"T own mw7e8adx","slug":"t-own-mw7e8adx","category":"General","subject":null,"stream":null,"gr`
  - `POST /courses` (A body={"name":"T other mw7e8adx","status":"PUBLIC","pricingModel":"FREE","teacherIds":["01a0fcc9-2745-79a9-8cb6-3dfef15a4efa"]}) → **201** `{"success":true,"data":{"id":"01a1b9a2-c77f-7d42-8a51-9d2834dbae15","teacherId":"01a0fcc9-2745-79a9-8cb6-3dfef15a4efa","name":"T other mw7e8adx","slug":"t-other-mw7e8adx","category":"General","subject":null,"stream":null`
  - `GET /me/permissions` (A) → **403** `{"success":false,"data":null,"error":{"code":"PASSWORD_CHANGE_REQUIRED","message":"You must change your password before continuing.","fieldErrors":[]},"timestamp":"2026-11-08T05:30:15.405953400Z","traceId":"001fbf51-e97f`
- **FAIL** [A10] teacher /me/permissions includes MODULES_ADD by default
  - `POST /courses/01a1b9a2-c6eb-76a0-86ed-cac26729ffb9/modules` (A body={"title":"M1 mw7e8adx"}) → **403** `{"success":false,"data":null,"error":{"code":"PASSWORD_CHANGE_REQUIRED","message":"You must change your password before continuing.","fieldErrors":[]},"timestamp":"2026-11-08T05:30:15.438921300Z","traceId":"98332ea2-2728`
- **FAIL** [A10] default: own-course module create → 201 — got 403
  - `POST /courses/01a1b9a2-c77f-7d42-8a51-9d2834dbae15/modules` (A body={"title":"X1 mw7e8adx"}) → **403** `{"success":false,"data":null,"error":{"code":"PASSWORD_CHANGE_REQUIRED","message":"You must change your password before continuing.","fieldErrors":[]},"timestamp":"2026-11-08T05:30:15.471124600Z","traceId":"7ffc1a44-aded`
- **PASS** [A10] another teacher's course → 403/404 — got 403
  - `POST /courses/01a1b9a2-b82c-7434-bd83-4256d84e1b44/modules` (A body={"title":"X1 mw7e8adx"}) → **403** `{"success":false,"data":null,"error":{"code":"PASSWORD_CHANGE_REQUIRED","message":"You must change your password before continuing.","fieldErrors":[]},"timestamp":"2026-11-08T05:30:15.503099900Z","traceId":"ceca93ce-5e84`
- **PASS** [A10] tenant-B course → 403/404 — got 403
  - `GET /staff/permission-matrix` (A) → **200** `{"success":true,"data":{"version":12,"roles":[{"code":"OPERATOR","displayName":"Operator"},{"code":"MANAGER","displayName":"Manager"},{"code":"TEACHER","displayName":"Teacher"}],"groups":[{"key":"FINANCE","label":"Financ`
  - `PUT /staff/permission-matrix` (A body={"expectedVersion":12,"changes":[{"roleCode":"TEACHER","capabilityKey":"MODULES_ADD","granted":false}]}) → **200** `{"success":true,"data":{"version":13,"roles":[{"code":"OPERATOR","displayName":"Operator"},{"code":"MANAGER","displayName":"Manager"},{"code":"TEACHER","displayName":"Teacher"}],"groups":[{"key":"FINANCE","label":"Financ`
  - `GET /me/permissions` (A) → **403** `{"success":false,"data":null,"error":{"code":"PASSWORD_CHANGE_REQUIRED","message":"You must change your password before continuing.","fieldErrors":[]},"timestamp":"2026-11-08T05:30:15.667461400Z","traceId":"40d57d1a-e3d2`
- **PASS** [A10] teacher /me/permissions drops MODULES_ADD after revoke
  - `POST /courses/01a1b9a2-c6eb-76a0-86ed-cac26729ffb9/modules` (A body={"title":"M2 mw7e8adx"}) → **403** `{"success":false,"data":null,"error":{"code":"PASSWORD_CHANGE_REQUIRED","message":"You must change your password before continuing.","fieldErrors":[]},"timestamp":"2026-11-08T05:30:15.707900300Z","traceId":"d5e042c2-c11f`
- **PASS** [A10] after revoke: own-course module create → 403 (same token) — got 403
  - `GET /courses/01a1b9a2-c6eb-76a0-86ed-cac26729ffb9/modules` (A) → **403** `{"success":false,"data":null,"error":{"code":"PASSWORD_CHANGE_REQUIRED","message":"You must change your password before continuing.","fieldErrors":[]},"timestamp":"2026-11-08T05:30:15.750628Z","traceId":"0b89bab5-7c31-42`
- **FAIL** [A10] MODULES_VIEW unaffected (200) — got 403
  - `POST /courses/01a1b9a2-c6eb-76a0-86ed-cac26729ffb9/modules` (A body={"title":"Admin mw7e8adx"}) → **201** `{"success":true,"data":{"id":"01a1b9a2-c987-7a9e-bd1c-959489af0b51","courseId":"01a1b9a2-c6eb-76a0-86ed-cac26729ffb9","title":"Admin mw7e8adx","sequence":1,"price":null,"createdAt":"2026-11-08T05:30:15.820559300Z","updat`
- **PASS** [A10] Tenant Admin unaffected by teacher revoke (201) — got 201
  - `GET /staff/permission-matrix` (A) → **200** `{"success":true,"data":{"version":13,"roles":[{"code":"OPERATOR","displayName":"Operator"},{"code":"MANAGER","displayName":"Manager"},{"code":"TEACHER","displayName":"Teacher"}],"groups":[{"key":"FINANCE","label":"Financ`
  - `PUT /staff/permission-matrix` (A body={"expectedVersion":13,"changes":[{"roleCode":"TEACHER","capabilityKey":"MODULES_ADD","granted":true}]}) → **200** `{"success":true,"data":{"version":14,"roles":[{"code":"OPERATOR","displayName":"Operator"},{"code":"MANAGER","displayName":"Manager"},{"code":"TEACHER","displayName":"Teacher"}],"groups":[{"key":"FINANCE","label":"Financ`
  - `POST /courses/01a1b9a2-c6eb-76a0-86ed-cac26729ffb9/modules` (A body={"title":"M3 mw7e8adx"}) → **403** `{"success":false,"data":null,"error":{"code":"PASSWORD_CHANGE_REQUIRED","message":"You must change your password before continuing.","fieldErrors":[]},"timestamp":"2026-11-08T05:30:16.005144700Z","traceId":"8e56856d-f8f1`
- **FAIL** [A10] after regrant: own-course module create → 201 — got 403
  - `POST /courses/01a1b9a2-c77f-7d42-8a51-9d2834dbae15/modules` (A body={"title":"X2 mw7e8adx"}) → **403** `{"success":false,"data":null,"error":{"code":"PASSWORD_CHANGE_REQUIRED","message":"You must change your password before continuing.","fieldErrors":[]},"timestamp":"2026-11-08T05:30:16.037875900Z","traceId":"8a2b51fe-5515`
- **PASS** [A10] after regrant: another teacher's course still 403/404 (grant never elevates) — got 403
  - `GET /staff` (A) → **403** `{"success":false,"data":null,"error":{"code":"PASSWORD_CHANGE_REQUIRED","message":"You must change your password before continuing.","fieldErrors":[]},"timestamp":"2026-11-08T05:30:16.084758400Z","traceId":"facb4198-2e18`
- **PASS** [A10] teacher GET /staff → 403 — got 403
  - `PUT /staff/permission-matrix` (A body={"expectedVersion":0,"changes":[{"roleCode":"TEACHER","capabilityKey":"MODULES_ADD","granted":true}]}) → **403** `{"success":false,"data":null,"error":{"code":"PASSWORD_CHANGE_REQUIRED","message":"You must change your password before continuing.","fieldErrors":[]},"timestamp":"2026-11-08T05:30:16.151891200Z","traceId":"363e3684-3f33`
- **PASS** [A10] teacher PUT permission-matrix → 403 — got 403

## A9 QR export with foreign ids (observation)

  - `POST /students/qr-export` (A body={"studentIds":["01a1b99f-31e5-77a0-8043-1c6ad7d905c4"]}) → **200** `<832 bytes application/pdf>`
  - `POST /students/qr-export` (A body={"studentIds":["01a1b99f-0000-7000-8000-000000000000"]}) → **200** `<832 bytes application/pdf>`
- **PASS** [A9] QR export with tenant-B id is indistinguishable from a random id (no B label, no existence leak) — B: 200 <832 bytes application/pdf>; random: 200 <832 bytes application/pdf>

## Cleanup (resume)

  - `DELETE /courses/01a1b9a2-c6eb-76a0-86ed-cac26729ffb9` (A) → **200** `{"success":true,"data":null,"error":null,"timestamp":"2026-11-08T05:30:16.541815700Z","traceId":"7b36bb56-84d5-486e-99a3-11cbcffd85eb"}`
  - `DELETE /courses/01a1b9a2-c77f-7d42-8a51-9d2834dbae15` (A) → **200** `{"success":true,"data":null,"error":null,"timestamp":"2026-11-08T05:30:16.609106600Z","traceId":"27721db7-45c2-4f4b-94a1-3acc81fed39d"}`
  - `GET /staff/permission-matrix` (A) → **200** `{"success":true,"data":{"version":14,"roles":[{"code":"OPERATOR","displayName":"Operator"},{"code":"MANAGER","displayName":"Manager"},{"code":"TEACHER","displayName":"Teacher"}],"groups":[{"key":"FINANCE","label":"Financ`
- **PASS** [cleanup] no Operator/Manager grants left; TEACHER MODULES_ADD back to GRANTED — leftover=0

## Summary

- setup: 1/1 checks passed
- A9: 9/9 checks passed
- A10: 10/14 checks passed — FAILED: teacher /me/permissions includes MODULES_ADD by default; default: own-course module create → 201; MODULES_VIEW unaffected (200); after regrant: own-course module create → 201
- cleanup: 1/1 checks passed

# Resume run mw7e91cu (A10 rerun after mandatory first-login password change)

  - `POST /auth/login` (A tenantadmin@demo.test) → **200** `{"success":true,"data":{"accessToken":"<redacted>","expiresInSeconds":900,"sessionId":"01a1b9a3-3ade-7496-9341-3b89322b3490","mustChangePass`

## A10 Teacher narrowing (POST /teachers → approve → login)

  - `POST /teachers` (A body={"name":"Tara Teachermw7e91cu","firstName":"Tara","lastName":"Teachermw7e91cu","email":"teacher3-mw7e91cu@example.test","password":"<redacted>"}) → **201** `{"success":true,"data":{"id":"01a1b9a3-3c5a-7559-a968-3ba836b2f4e9","name":"Tara Teachermw7e91cu","firstName":"Tara","lastName":"Teachermw7e91cu","email":"teacher3-mw7e91cu@example.test","contactNumber":null,"instituteCo`
  - `POST /teachers/01a1b9a3-3c5a-7559-a968-3ba836b2f4e9/approve` (A) → **200** `{"success":true,"data":{"id":"01a1b9a3-3c5a-7559-a968-3ba836b2f4e9","name":"Tara Teachermw7e91cu","firstName":"Tara","lastName":"Teachermw7e91cu","email":"teacher3-mw7e91cu@example.test","contactNumber":null,"instituteCo`
- **PASS** [A10] teacher onboarded (create 201 + approve 200) — 201/200
  - `POST /auth/login` (A teacher3-mw7e91cu@example.test) → **200** `{"success":true,"data":{"accessToken":"<redacted>","expiresInSeconds":900,"sessionId":"01a1b9a3-3dc1-7481-9d85-f4e80cd83208","mustChangePass`
- **PASS** [A10] approved teacher signs in — got 200
  - `POST /auth/password` (A body={"currentPassword":"<redacted>","newPassword":"<redacted>"}) → **200** `{"success":true,"data":{"accessToken":"<redacted>","expiresInSeconds":900},"error":null,"timestamp":"2026-11-08T05:30:46.390383200Z","traceId":"42fc416d-1438-4780-a723-ecc7473a700f"}`
- **PASS** [A10] first-login password change (onboarding) → 200 — got 200
  - `GET /teachers` (A) → **200** `{"success":true,"data":[{"id":"01a0a8be-0d10-7e63-83fe-4ba9f31cbfc9","name":"Mohan Ranaweera","firstName":"Mohan","lastName":"Ranaweera","email":"mohanranaweera@gmail.com","contactNumber":"0722262628","instituteCommissio`
  - `POST /courses` (A body={"name":"T own mw7e91cu","status":"PUBLIC","pricingModel":"FREE","teacherIds":["01a1b9a3-3c4e-70f4-af96-0ca02bb853b3"]}) → **201** `{"success":true,"data":{"id":"01a1b9a3-4392-7633-ac65-d55503e4ed07","teacherId":"01a1b9a3-3c4e-70f4-af96-0ca02bb853b3","name":"T own mw7e91cu","slug":"t-own-mw7e91cu","category":"General","subject":null,"stream":null,"gr`
  - `POST /courses` (A body={"name":"T other mw7e91cu","status":"PUBLIC","pricingModel":"FREE","teacherIds":["01a0fcc9-2745-79a9-8cb6-3dfef15a4efa"]}) → **201** `{"success":true,"data":{"id":"01a1b9a3-4403-7c09-8b0f-cf3703ed3c65","teacherId":"01a0fcc9-2745-79a9-8cb6-3dfef15a4efa","name":"T other mw7e91cu","slug":"t-other-mw7e91cu","category":"General","subject":null,"stream":null`
  - `GET /me/permissions` (A) → **200** `{"success":true,"data":{"role":"TEACHER","capabilities":["COURSES_VIEW","COURSES_VIEW_STUDENTS","MODULES_VIEW","MODULES_ADD","MODULES_DELETE","EXAMS_VIEW"],"permissionVersion":14},"error":null,"timestamp":"2026-11-08T05:`
- **PASS** [A10] teacher /me/permissions includes MODULES_ADD by default
  - `POST /courses/01a1b9a3-4392-7633-ac65-d55503e4ed07/modules` (A body={"title":"M1 mw7e91cu"}) → **201** `{"success":true,"data":{"id":"01a1b9a3-4499-785a-8e46-0faa3837e2f7","courseId":"01a1b9a3-4392-7633-ac65-d55503e4ed07","title":"M1 mw7e91cu","sequence":1,"price":null,"createdAt":"2026-11-08T05:30:47.321750700Z","updatedA`
- **PASS** [A10] default: own-course module create → 201 — got 201
  - `POST /courses/01a1b9a3-4403-7c09-8b0f-cf3703ed3c65/modules` (A body={"title":"X1 mw7e91cu"}) → **403** `{"success":false,"data":null,"error":{"code":"FORBIDDEN","message":"You do not have permission to perform this action","fieldErrors":[]},"timestamp":"2026-11-08T05:30:47.373484400Z","traceId":"93b45560-8807-4f71-b424-f9e`
- **PASS** [A10] another teacher's course → 403/404 — got 403
  - `POST /courses/01a1b9a2-b82c-7434-bd83-4256d84e1b44/modules` (A body={"title":"X1 mw7e91cu"}) → **404** `{"success":false,"data":null,"error":{"code":"NOT_FOUND","message":"Course not found","fieldErrors":[]},"timestamp":"2026-11-08T05:30:47.418245400Z","traceId":"ff1de859-6365-48cd-8253-d1bb562d7d55"}`
- **PASS** [A10] tenant-B course → 403/404 — got 404
  - `GET /staff/permission-matrix` (A) → **200** `{"success":true,"data":{"version":14,"roles":[{"code":"OPERATOR","displayName":"Operator"},{"code":"MANAGER","displayName":"Manager"},{"code":"TEACHER","displayName":"Teacher"}],"groups":[{"key":"FINANCE","label":"Financ`
  - `PUT /staff/permission-matrix` (A body={"expectedVersion":14,"changes":[{"roleCode":"TEACHER","capabilityKey":"MODULES_ADD","granted":false}]}) → **200** `{"success":true,"data":{"version":15,"roles":[{"code":"OPERATOR","displayName":"Operator"},{"code":"MANAGER","displayName":"Manager"},{"code":"TEACHER","displayName":"Teacher"}],"groups":[{"key":"FINANCE","label":"Financ`
  - `GET /me/permissions` (A) → **200** `{"success":true,"data":{"role":"TEACHER","capabilities":["COURSES_VIEW","COURSES_VIEW_STUDENTS","MODULES_VIEW","MODULES_DELETE","EXAMS_VIEW"],"permissionVersion":15},"error":null,"timestamp":"2026-11-08T05:30:47.57219090`
- **PASS** [A10] teacher /me/permissions drops MODULES_ADD after revoke
  - `POST /courses/01a1b9a3-4392-7633-ac65-d55503e4ed07/modules` (A body={"title":"M2 mw7e91cu"}) → **403** `{"success":false,"data":null,"error":{"code":"FORBIDDEN","message":"You do not have permission to perform this action","fieldErrors":[]},"timestamp":"2026-11-08T05:30:47.607846400Z","traceId":"5b76d434-7aa9-4b0a-b3ee-7d6`
- **PASS** [A10] after revoke: own-course module create → 403 (same token) — got 403
  - `GET /courses/01a1b9a3-4392-7633-ac65-d55503e4ed07/modules` (A) → **200** `{"success":true,"data":[{"id":"01a1b9a3-4499-785a-8e46-0faa3837e2f7","courseId":"01a1b9a3-4392-7633-ac65-d55503e4ed07","title":"M1 mw7e91cu","sequence":1,"price":null,"createdAt":"2026-11-08T05:30:47.321751Z","updatedAt"`
- **PASS** [A10] MODULES_VIEW unaffected (200) — got 200
  - `POST /courses/01a1b9a3-4392-7633-ac65-d55503e4ed07/modules` (A body={"title":"Admin mw7e91cu"}) → **201** `{"success":true,"data":{"id":"01a1b9a3-4613-7b67-a8d9-9c5622f270a9","courseId":"01a1b9a3-4392-7633-ac65-d55503e4ed07","title":"Admin mw7e91cu","sequence":2,"price":null,"createdAt":"2026-11-08T05:30:47.700152Z","updatedA`
- **PASS** [A10] Tenant Admin unaffected by teacher revoke (201) — got 201
  - `GET /staff/permission-matrix` (A) → **200** `{"success":true,"data":{"version":15,"roles":[{"code":"OPERATOR","displayName":"Operator"},{"code":"MANAGER","displayName":"Manager"},{"code":"TEACHER","displayName":"Teacher"}],"groups":[{"key":"FINANCE","label":"Financ`
  - `PUT /staff/permission-matrix` (A body={"expectedVersion":15,"changes":[{"roleCode":"TEACHER","capabilityKey":"MODULES_ADD","granted":true}]}) → **200** `{"success":true,"data":{"version":16,"roles":[{"code":"OPERATOR","displayName":"Operator"},{"code":"MANAGER","displayName":"Manager"},{"code":"TEACHER","displayName":"Teacher"}],"groups":[{"key":"FINANCE","label":"Financ`
  - `POST /courses/01a1b9a3-4392-7633-ac65-d55503e4ed07/modules` (A body={"title":"M3 mw7e91cu"}) → **201** `{"success":true,"data":{"id":"01a1b9a3-46b0-7704-a4d6-720442c41494","courseId":"01a1b9a3-4392-7633-ac65-d55503e4ed07","title":"M3 mw7e91cu","sequence":3,"price":null,"createdAt":"2026-11-08T05:30:47.857520300Z","updatedA`
- **PASS** [A10] after regrant: own-course module create → 201 — got 201
  - `POST /courses/01a1b9a3-4403-7c09-8b0f-cf3703ed3c65/modules` (A body={"title":"X2 mw7e91cu"}) → **403** `{"success":false,"data":null,"error":{"code":"FORBIDDEN","message":"You do not have permission to perform this action","fieldErrors":[]},"timestamp":"2026-11-08T05:30:47.914253700Z","traceId":"068ac4f4-8621-4331-a640-25a`
- **PASS** [A10] after regrant: another teacher's course still 403/404 (grant never elevates) — got 403
  - `GET /staff` (A) → **403** `{"success":false,"data":null,"error":{"code":"FORBIDDEN","message":"You do not have permission to perform this action","fieldErrors":[]},"timestamp":"2026-11-08T05:30:47.961439500Z","traceId":"fb701401-5061-4f52-b87b-44a`
- **PASS** [A10] teacher GET /staff → 403 — got 403
  - `PUT /staff/permission-matrix` (A body={"expectedVersion":0,"changes":[{"roleCode":"TEACHER","capabilityKey":"MODULES_ADD","granted":true}]}) → **403** `{"success":false,"data":null,"error":{"code":"FORBIDDEN","message":"You do not have permission to perform this action","fieldErrors":[]},"timestamp":"2026-11-08T05:30:48.002397100Z","traceId":"0e6d3cdf-ef6e-469c-9144-6cd`
- **PASS** [A10] teacher PUT permission-matrix → 403 — got 403
  - `DELETE /courses/01a1b9a3-4392-7633-ac65-d55503e4ed07` (A) → **200** `{"success":true,"data":null,"error":null,"timestamp":"2026-11-08T05:30:48.067824200Z","traceId":"6b52cefe-d290-447c-b170-7ef6e1c60381"}`
  - `DELETE /courses/01a1b9a3-4403-7c09-8b0f-cf3703ed3c65` (A) → **200** `{"success":true,"data":null,"error":null,"timestamp":"2026-11-08T05:30:48.122246300Z","traceId":"317f2f86-7eb1-4346-b518-5886b1a07e95"}`
  - `GET /staff/permission-matrix` (A) → **200** `{"success":true,"data":{"version":16,"roles":[{"code":"OPERATOR","displayName":"Operator"},{"code":"MANAGER","displayName":"Manager"},{"code":"TEACHER","displayName":"Teacher"}],"groups":[{"key":"FINANCE","label":"Financ`
- **PASS** [cleanup] TEACHER MODULES_ADD back to GRANTED

## Summary

- A10: 15/15 checks passed
- cleanup: 1/1 checks passed
