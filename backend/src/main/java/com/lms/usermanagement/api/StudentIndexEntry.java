package com.lms.usermanagement.api;

import java.util.UUID;

/**
 * RPT-02 (spec student-payment-report.md section 6): a student's display name
 * and per-tenant index number, keyed by the opaque cross-domain {@code userId}
 * other modules store as {@code studentId}. {@code indexNumber} is a string -
 * its leading zeros are significant ("0084") and must never be parsed to a
 * number. Never the {@code StudentProfile} entity.
 */
public record StudentIndexEntry(UUID userId, String name, String indexNumber) {

}
