package com.lms.usermanagement.staff.web.dto;

import java.util.UUID;

/**
 * Public response shape for {@code POST /api/v1/staff} only - deliberately
 * distinct from {@link StaffResponse} (used by {@code GET /api/v1/staff} and
 * {@code GET /api/v1/staff/{id}}) because this is the one and only response
 * that ever carries {@code temporaryPassword}, the server-generated raw
 * login secret for the newly created account. It is returned here exactly
 * once for the admin to relay out-of-band; it is never persisted in
 * plaintext, never logged, and never obtainable again through any read path
 * - reusing this type (instead of {@link StaffResponse}) for list/detail
 * reads would reintroduce that leak, so {@code StaffController} must never
 * map a list/detail read to this type.
 */
public record StaffCreateResponse(UUID id, String name, String email, String roleCode, String status,
		String temporaryPassword) {

}
