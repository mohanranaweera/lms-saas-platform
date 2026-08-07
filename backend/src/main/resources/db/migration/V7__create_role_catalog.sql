-- Platform-global, fixed reference/display catalog for the 12 roles this
-- platform models (11 tenant-scoped values assignable to tenant_user.role,
-- plus PLATFORM_ADMIN included here for display/reference completeness only
-- -- platform_admin_user has no role column and never references this table;
-- role is implicit PLATFORM_ADMIN for every row there, per V4's own design).
--
-- Not tenant-owned: holds zero tenant-specific data, only fixed metadata
-- (display name, portal route group, self-registration flag). No tenant_id
-- column, per docs/requirements/user-roles-and-permissions.md's explicit
-- "fixed catalog, platform-global, no tenant_id" requirement -- this is not
-- an oversight, do not add tenant_id here.
--
-- `code` is the natural key (semantic, e.g. 'TENANT_ADMIN') -- no surrogate
-- UUID id, consistent with this being small, fixed reference data rather
-- than a growing tenant-owned entity.
--
-- `is_provisional` marks TEACHER_ASSISTANT: the role VALUE exists now (it
-- must be assignable), but its PERMISSION BOUNDARY is explicitly PROVISIONAL
-- / not ratified per docs/requirements/user-roles-and-permissions.md §3 --
-- this flag is the structural marker required so implementation never
-- silently presents that boundary as confirmed. Do not set it for any other
-- role.
--
-- Rows are fixed/seeded by this migration; no CRUD endpoint may mutate this
-- table in this module (a future module may add admin tooling, out of scope
-- here).

CREATE TABLE role (
    code                VARCHAR PRIMARY KEY,
    scope               VARCHAR NOT NULL CHECK (scope IN ('PLATFORM', 'TENANT')),
    display_name        VARCHAR NOT NULL,
    description         VARCHAR NULL,
    portal_route_group  VARCHAR NOT NULL,
    self_registers      BOOLEAN NOT NULL DEFAULT false,
    is_provisional      BOOLEAN NOT NULL DEFAULT false,
    is_active           BOOLEAN NOT NULL DEFAULT true,
    sort_order          INTEGER NOT NULL DEFAULT 0,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);

INSERT INTO role (code, scope, display_name, portal_route_group, self_registers, is_provisional, sort_order) VALUES
    ('PLATFORM_ADMIN',      'PLATFORM', 'Platform Admin',      'app/(platform-admin)/', false, false, 0),
    ('TENANT_ADMIN',        'TENANT',   'Tenant Admin',        'app/(tenant-admin)/',   false, false, 10),
    ('FINANCE_STAFF',       'TENANT',   'Finance Staff',       'app/(tenant-admin)/',   false, false, 20),
    ('COURSE_COORDINATOR',  'TENANT',   'Course Coordinator',  'app/(tenant-admin)/',   false, false, 30),
    ('STUDENT_SUPPORT',     'TENANT',   'Student Support',     'app/(tenant-admin)/',   false, false, 40),
    ('CONTENT_MANAGER',     'TENANT',   'Content Manager',     'app/(tenant-admin)/',   false, false, 50),
    ('EXAM_MANAGER',        'TENANT',   'Exam Manager',        'app/(tenant-admin)/',   false, false, 60),
    ('ATTENDANCE_OPERATOR', 'TENANT',   'Attendance Operator', 'app/(tenant-admin)/',   false, false, 70),
    ('READ_ONLY_AUDITOR',   'TENANT',   'Read-only Auditor',   'app/(tenant-admin)/',   false, false, 80),
    ('TEACHER',             'TENANT',   'Teacher',             'app/(teacher)/',        false, false, 90),
    ('TEACHER_ASSISTANT',   'TENANT',   'Teacher Assistant',   'app/(teacher)/',        false, true,  100),
    ('STUDENT',             'TENANT',   'Student',             'app/(student)/',        true,  false, 110);
