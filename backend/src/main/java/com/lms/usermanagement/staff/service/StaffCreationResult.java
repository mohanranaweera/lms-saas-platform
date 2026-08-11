package com.lms.usermanagement.staff.service;

/**
 * Result of {@link StaffService#createStaff}, deliberately distinct from
 * {@link StaffAccount} (the result of {@code listStaff}/{@code getStaff}):
 * this is the one and only place the server-generated {@code
 * temporaryPassword} exists in a returned value, for {@code web} to map into
 * {@code StaffCreateResponse}'s one-time return. {@code listStaff}/{@code
 * getStaff} return bare {@link StaffAccount} with no password field, so it
 * is structurally impossible for the generated password to leak through
 * those read paths.
 */
public record StaffCreationResult(StaffAccount account, String temporaryPassword) {

}
