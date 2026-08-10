package com.lms.identityaccessservice.api;

/**
 * Action category within a {@link DomainArea}, matching the V/C-E/D/A columns
 * of {@code docs/requirements/user-roles-and-permissions.md} §2's permission
 * matrix: {@code VIEW}, {@code CREATE_EDIT} (create/edit collapsed into one
 * grant, matching the source matrix's "C/E" column), {@code DELETE}, and
 * {@code APPROVE} (a distinct, higher-trust action than create/edit - e.g.
 * approving a payment slip, publishing exam results).
 */
public enum PermissionAction {

	VIEW, CREATE_EDIT, DELETE, APPROVE

}
