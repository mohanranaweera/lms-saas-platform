package com.lms.usermanagement.api;

import java.util.UUID;

/** One tenant-scoped student display row for the Unpaid Students Report. */
public record UnpaidStudentProfile(UUID studentProfileId, UUID userId, String indexNumber, String name, String email,
		String mobile) {

}
