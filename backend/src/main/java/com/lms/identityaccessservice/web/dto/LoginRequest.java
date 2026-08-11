package com.lms.identityaccessservice.web.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

/**
 * Deliberately carries no {@code tenant_id} field (risk R6, plan §14) -
 * tenant identity comes exclusively from {@code TenantResolutionFilter}'s
 * subdomain resolution, never from client input.
 */
public record LoginRequest(@NotBlank @Email String email, @NotBlank String password) {

}
