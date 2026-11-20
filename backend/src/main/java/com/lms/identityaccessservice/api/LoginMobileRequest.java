package com.lms.identityaccessservice.api;

import jakarta.validation.constraints.Size;

/**
 * ADR-031: body of the sign-in mobile endpoints (teacher, staff, own account).
 * Any common format; normalised server-side. Blank or {@code null} removes
 * mobile sign-in for the account.
 */
public record LoginMobileRequest(@Size(max = 40) String loginMobile) {

}
