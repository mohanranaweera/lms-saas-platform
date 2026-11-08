package com.lms.usermanagement.staff.service;

/** A role Staff Management may assign (server-derived: Operator, Manager, the seeded staff sub-roles). */
public record AssignableRole(String code, String displayName) {

}
