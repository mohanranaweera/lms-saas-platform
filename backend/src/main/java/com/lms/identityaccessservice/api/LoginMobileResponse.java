package com.lms.identityaccessservice.api;

/** ADR-031: an account's sign-in mobile in E.164 form, or {@code null} when it signs in by email only. */
public record LoginMobileResponse(String loginMobile) {

}
