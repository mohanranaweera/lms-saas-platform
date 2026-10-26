package com.lms.usermanagement.api;

import java.time.Instant;
import java.util.UUID;

/** Tenant-owned student registration fact for Registration Report composition. */
public record RegistrationReportStudent(UUID studentProfileId, UUID userId, String indexNumber, String name,
		String email, String mobile, Instant registeredAt, UUID registeredBy, String registrationMethod) {

}
