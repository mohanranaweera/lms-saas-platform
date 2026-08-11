package com.lms.usermanagement.staff.web.dto;

import java.util.UUID;

/**
 * Public response shape for {@code /api/v1/staff} endpoints. Never the
 * {@code StaffProfile} JPA entity - composed by {@code StaffController} from
 * {@code StaffService}'s {@code StaffAccount} result.
 */
public record StaffResponse(UUID id, String name, String email, String roleCode, String status) {

}
