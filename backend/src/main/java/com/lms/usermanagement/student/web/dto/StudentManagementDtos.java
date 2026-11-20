package com.lms.usermanagement.student.web.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** STU-MGMT-02 request/response DTOs (spec section 5). */
public final class StudentManagementDtos {

	private StudentManagementDtos() {
	}

	/** One directory row - one per student. */
	public record StudentDirectoryRow(UUID id, String indexNumber, String firstName, String lastName, String email,
			String mobile, Instant lastLoginAt, String status, boolean hasPhoto) {
	}

	/** {@code StudentDetail}: every section 3 field plus derived state. {@code photoUrl} is a 5-minute signed URL. */
	public record StudentDetail(UUID id, String indexNumber, String firstName, String lastName, String fullName,
			String email, String mobile, String whatsappNumber, LocalDate dateOfBirth, String addressLine,
			String addressLine2, String city, String postalCode, String guardianName, String guardianPhone,
			String guardianNic, String fixedPhone, String school, Integer alYear, String stream, String status,
			boolean pending, Instant registeredAt, Instant lastLoginAt, String photoUrl, List<UUID> enrolledCourseIds) {
	}

	/** {@code POST /students/v2}: the temporary password is present only when the server generated it. */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record StudentCreatedResponse(StudentDetail student, String temporaryPassword) {
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	public record StudentRemovalRequest(@NotBlank @Size(max = 500) String reason) {
	}

	public record PhotoUrlResponse(String url, Instant expiresAt) {
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	public record RemarkCreateRequest(@NotBlank @Size(max = 2000) String body) {
	}

	public record RemarkResponse(UUID id, String body, String authorName, Instant createdAt) {
	}

	public record QrResponse(String payload, Instant rotatedAt) {
	}

	public record QrRotateResponse(String payload) {
	}

	public record QrResolveResponse(UUID id, String indexNumber, String fullName) {
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	public record ImpersonationStartRequest(@NotBlank @Size(min = 5, max = 500) String reason) {
	}

	public record ImpersonatedStudent(UUID id, String indexNumber, String fullName) {
	}

	public record ImpersonationStartResponse(UUID sessionId, Instant expiresAt, ImpersonatedStudent student) {
	}

}
