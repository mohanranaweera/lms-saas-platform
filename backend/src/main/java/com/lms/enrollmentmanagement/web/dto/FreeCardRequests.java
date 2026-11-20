package com.lms.enrollmentmanagement.web.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.LocalDate;
import java.util.UUID;

/**
 * ADR-030 Free Card request bodies. No tenant, actor or amount fields - all
 * server-resolved; {@code studentId} is a student_profile id resolved
 * server-side within the caller's tenant. Field rules are enforced by {@code
 * FreeCardService} as 422s ({@code cardType} is a plain string so {@code HALF}
 * is a {@code FREE_CARD_TYPE_UNSUPPORTED}, not a deserialization error).
 */
public final class FreeCardRequests {

	private FreeCardRequests() {
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	public record IssueFreeCardRequest(UUID studentId, UUID courseId, String cardType, LocalDate validFrom,
			LocalDate validTo, String reason, UUID idempotencyKey) {

	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	public record RevokeFreeCardRequest(String reason) {

	}

}
