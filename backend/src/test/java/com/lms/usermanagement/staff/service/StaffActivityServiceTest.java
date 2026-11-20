package com.lms.usermanagement.staff.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Unit coverage for the Staff Activities label and redaction helpers (STAFF-MGMT-02). */
class StaffActivityServiceTest {

	@Test
	void actionsAreHumanised() {
		assertThat(StaffActivityService.humanise("staff.role_changed")).isEqualTo("Staff role changed");
		assertThat(StaffActivityService.humanise("payment_slip.approved_with_override"))
			.isEqualTo("Payment slip approved with override");
		assertThat(StaffActivityService.humanise("")).isEmpty();
	}

	@Test
	void sensitiveKeysAreRemovedAtEveryDepth() {
		Map<String, Object> nested = new LinkedHashMap<>();
		nested.put("refreshToken", "abc");
		nested.put("role", "OPERATOR");
		Map<String, Object> metadata = new LinkedHashMap<>();
		metadata.put("email", "j***@example.test");
		metadata.put("newPassword", "x");
		metadata.put("PasswordHash", "y");
		metadata.put("clientSecret", "z");
		metadata.put("otpCode", "1234");
		metadata.put("changes", List.of(nested));
		metadata.put("detail", Map.of("tokenHash", "h", "to", "MANAGER"));

		Map<String, Object> redacted = StaffActivityService.redact(metadata);

		assertThat(redacted).containsOnlyKeys("email", "changes", "detail");
		assertThat(redacted.get("changes")).isEqualTo(List.of(Map.of("role", "OPERATOR")));
		assertThat(redacted.get("detail")).isEqualTo(Map.of("to", "MANAGER"));
		assertThat(StaffActivityService.redact(null)).isEmpty();
	}

	@Test
	void urlLikeKeysAreDroppedAndContactValuesMasked() {
		Map<String, Object> metadata = new LinkedHashMap<>();
		metadata.put("downloadUrl", "https://storage.example/obj?sig=abc");
		metadata.put("inviteLink", "https://app.example/setup?t=abc");
		metadata.put("apiKey", "k");
		metadata.put("studentEmail", "jane.doe@example.test");
		metadata.put("loginMobile", "+94771234567");
		metadata.put("maskedEmail", "j***@example.test");
		metadata.put("action", "staff.invited");

		Map<String, Object> redacted = StaffActivityService.redact(metadata);

		assertThat(redacted).containsOnlyKeys("studentEmail", "loginMobile", "maskedEmail", "action");
		assertThat(redacted.get("studentEmail")).isEqualTo("j•••@example.test");
		assertThat(redacted.get("loginMobile")).isEqualTo("•••567");
		assertThat(redacted.get("maskedEmail")).isEqualTo("j***@example.test");
	}

}
