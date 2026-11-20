package com.lms.tenantmanagement.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Arrays;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** STU-PROF-01: default policies reproduce pre-STU-PROF-01 behaviour. */
class StudentProfileFieldTest {

	@Test
	void legacyRegistrationFieldsAreAskedAtRegistrationAndInheritTheirRequireFlag() {
		Map<String, Object> legacy = Map.of("require_school", true, "require_mobile", false);

		assertThat(StudentProfileField.SCHOOL.defaultPolicy(legacy))
			.isEqualTo(new StudentProfileFieldPolicy(StudentProfileField.SCHOOL, true, true, true));
		assertThat(StudentProfileField.MOBILE.defaultPolicy(legacy))
			.isEqualTo(new StudentProfileFieldPolicy(StudentProfileField.MOBILE, true, false, true));
		assertThat(StudentProfileField.GUARDIAN.defaultPolicy(Map.of()))
			.isEqualTo(new StudentProfileFieldPolicy(StudentProfileField.GUARDIAN, true, false, true));
	}

	@Test
	void billingAddressFieldsAreEnabledOnTheProfileAndEverythingNewIsOff() {
		assertThat(StudentProfileField.ADDRESS_LINE_1.defaultPolicy(Map.of()))
			.isEqualTo(new StudentProfileFieldPolicy(StudentProfileField.ADDRESS_LINE_1, true, false, false));
		assertThat(StudentProfileField.CITY.defaultPolicy(Map.of()).enabled()).isTrue();
		assertThat(StudentProfileField.NIC_NUMBER.defaultPolicy(Map.of()))
			.isEqualTo(new StudentProfileFieldPolicy(StudentProfileField.NIC_NUMBER, false, false, false));
	}

	@Test
	void lockedFieldsAreAlwaysOnAndIdentityNumbersAreSensitive() {
		assertThat(StudentProfileField.EMAIL.defaultPolicy(Map.of()))
			.isEqualTo(new StudentProfileFieldPolicy(StudentProfileField.EMAIL, true, true, true));
		assertThat(Arrays.stream(StudentProfileField.values()).filter(StudentProfileField::locked))
			.containsExactly(StudentProfileField.EMAIL, StudentProfileField.FULL_NAME);
		assertThat(Arrays.stream(StudentProfileField.values()).filter(StudentProfileField::sensitive)).containsExactly(
				StudentProfileField.NIC_NUMBER, StudentProfileField.PASSPORT_NUMBER,
				StudentProfileField.DRIVING_LICENSE_NUMBER);
	}

	@Test
	void policyRejectsRequiredOrRegistrationWithoutEnabled() {
		assertThatThrownBy(() -> new StudentProfileFieldPolicy(StudentProfileField.SCHOOL, false, true, false))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new StudentProfileFieldPolicy(StudentProfileField.SCHOOL, false, false, true))
			.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void fromKeyIsExactAndNullSafe() {
		assertThat(StudentProfileField.fromKey("NIC_NUMBER")).contains(StudentProfileField.NIC_NUMBER);
		assertThat(StudentProfileField.fromKey("nic_number")).isEmpty();
		assertThat(StudentProfileField.fromKey(null)).isEmpty();
	}

}
