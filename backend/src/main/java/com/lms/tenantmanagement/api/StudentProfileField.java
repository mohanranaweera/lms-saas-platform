package com.lms.tenantmanagement.api;

import static com.lms.tenantmanagement.api.StudentProfileFieldGroup.ACCOUNT;
import static com.lms.tenantmanagement.api.StudentProfileFieldGroup.ADDRESS;
import static com.lms.tenantmanagement.api.StudentProfileFieldGroup.CONTACT;
import static com.lms.tenantmanagement.api.StudentProfileFieldGroup.EDUCATION;
import static com.lms.tenantmanagement.api.StudentProfileFieldGroup.FAMILY;
import static com.lms.tenantmanagement.api.StudentProfileFieldGroup.IDENTITY;
import static com.lms.tenantmanagement.api.StudentProfileFieldGroup.OTHER;
import static com.lms.tenantmanagement.api.StudentProfileFieldGroup.PERSONAL;

import java.util.Map;
import java.util.Optional;

/**
 * The platform-defined catalogue of student profile fields (STU-PROF-01).
 * Tenants choose which of these are enabled / required / asked at
 * registration; they cannot add their own fields. Changing this catalogue is
 * a code release, never a per-tenant action.
 *
 * <p>{@code locked} fields ({@link #EMAIL}, {@link #FULL_NAME}) are always
 * enabled, required and asked at registration: they are the credential and
 * the profile's own {@code name}, edited through their existing endpoints,
 * and are listed here only so the settings page can show them.
 *
 * <p>{@code legacyRequireKey} names the pre-STU-PROF-01 {@code
 * ConfigDomain.STUDENT} flag a field inherits its default required-ness
 * from, so a tenant that has never saved its field settings behaves exactly
 * as before.
 *
 * <p>{@code sensitive} marks identity-document numbers: their values are
 * never written to logs or audit metadata.
 */
public enum StudentProfileField {

	EMAIL("Email", ACCOUNT, StudentProfileFieldType.TEXT, 255, false, true, null, false),
	FULL_NAME("Full name", ACCOUNT, StudentProfileFieldType.TEXT, 255, false, true, null, false),

	BIRTHDAY("Birthday", PERSONAL, StudentProfileFieldType.DATE, 10, false, false, null, false),
	PROFESSION("Profession", PERSONAL, StudentProfileFieldType.TEXT, 255, false, false, null, false),
	SPECIAL_TALENT("Special talent", PERSONAL, StudentProfileFieldType.TEXT, 255, false, false, null, false),

	MOBILE("Mobile number", CONTACT, StudentProfileFieldType.PHONE, 50, false, false, "require_mobile", true),
	FIXED_TELEPHONE("Fixed telephone", CONTACT, StudentProfileFieldType.PHONE, 50, false, false, null, false),
	WHATSAPP_NUMBER("WhatsApp number", CONTACT, StudentProfileFieldType.PHONE, 50, false, false, null, false),
	TELEGRAM_NUMBER("Telegram number", CONTACT, StudentProfileFieldType.PHONE, 50, false, false, null, false),
	TUTE_DELIVERY_PHONE_1("Tute delivery phone 1", CONTACT, StudentProfileFieldType.PHONE, 50, false, false, null,
			false),
	TUTE_DELIVERY_PHONE_2("Tute delivery phone 2", CONTACT, StudentProfileFieldType.PHONE, 50, false, false, null,
			false),

	ADDRESS_LINE_1("Address line 1", ADDRESS, StudentProfileFieldType.TEXT, 255, false, false, null, true),
	ADDRESS_LINE_2("Address line 2", ADDRESS, StudentProfileFieldType.TEXT, 255, false, false, null, false),
	CITY("City", ADDRESS, StudentProfileFieldType.TEXT, 100, false, false, null, true),
	POSTAL_CODE("Postal code", ADDRESS, StudentProfileFieldType.TEXT, 20, false, false, null, false),
	COUNTRY("Country", ADDRESS, StudentProfileFieldType.COUNTRY, 2, false, false, null, false),

	NIC_NUMBER("NIC number", IDENTITY, StudentProfileFieldType.TEXT, 20, true, false, null, false),
	PASSPORT_NUMBER("Passport number", IDENTITY, StudentProfileFieldType.TEXT, 20, true, false, null, false),
	DRIVING_LICENSE_NUMBER("Driving licence number", IDENTITY, StudentProfileFieldType.TEXT, 20, true, false, null,
			false),

	SCHOOL("School", EDUCATION, StudentProfileFieldType.TEXT, 255, false, false, "require_school", true),
	GRADE("Grade", EDUCATION, StudentProfileFieldType.TEXT, 50, false, false, "require_grade", true),
	AL_STREAM("A/L stream", EDUCATION, StudentProfileFieldType.TEXT, 50, false, false, "require_stream", true),
	AL_YEAR("A/L year", EDUCATION, StudentProfileFieldType.YEAR, 4, false, false, null, false),
	UNIVERSITY("University", EDUCATION, StudentProfileFieldType.TEXT, 255, false, false, null, false),
	LEARNING_MEDIUM("Learning medium", EDUCATION, StudentProfileFieldType.TEXT, 50, false, false, null, false),

	GUARDIAN("Guardian", FAMILY, StudentProfileFieldType.GUARDIAN, 255, false, false, "require_guardian_info", true),
	HAS_SIBLINGS("Has siblings", FAMILY, StudentProfileFieldType.BOOLEAN, 5, false, false, null, false),

	REFERRAL_CODE("Referral code", OTHER, StudentProfileFieldType.TEXT, 50, false, false, null, false);

	private final String label;

	private final StudentProfileFieldGroup group;

	private final StudentProfileFieldType type;

	private final int maxLength;

	private final boolean sensitive;

	private final boolean locked;

	private final String legacyRequireKey;

	private final boolean enabledByDefault;

	StudentProfileField(String label, StudentProfileFieldGroup group, StudentProfileFieldType type, int maxLength,
			boolean sensitive, boolean locked, String legacyRequireKey, boolean enabledByDefault) {
		this.label = label;
		this.group = group;
		this.type = type;
		this.maxLength = maxLength;
		this.sensitive = sensitive;
		this.locked = locked;
		this.legacyRequireKey = legacyRequireKey;
		this.enabledByDefault = enabledByDefault;
	}

	public String label() {
		return label;
	}

	public StudentProfileFieldGroup group() {
		return group;
	}

	public StudentProfileFieldType type() {
		return type;
	}

	/** Maximum text length; for {@link StudentProfileFieldType#GUARDIAN} it bounds the name part. */
	public int maxLength() {
		return maxLength;
	}

	public boolean sensitive() {
		return sensitive;
	}

	public boolean locked() {
		return locked;
	}

	public Optional<String> legacyRequireKey() {
		return Optional.ofNullable(legacyRequireKey);
	}

	/**
	 * The policy for a tenant that has no persisted setting for this field.
	 * Reproduces pre-STU-PROF-01 behaviour: the five legacy registration
	 * fields are shown at registration (optional unless their legacy {@code
	 * require_*} flag is on), the billing address fields are available on
	 * the profile, everything else is off.
	 *
	 * @param legacyStudentConfig the tenant's resolved {@code
	 * ConfigDomain.STUDENT} values (for the legacy {@code require_*} flags)
	 */
	public StudentProfileFieldPolicy defaultPolicy(Map<String, Object> legacyStudentConfig) {
		if (locked) {
			return new StudentProfileFieldPolicy(this, true, true, true);
		}
		if (legacyRequireKey != null) {
			boolean required = legacyStudentConfig.get(legacyRequireKey) instanceof Boolean b && b;
			return new StudentProfileFieldPolicy(this, true, required, true);
		}
		return new StudentProfileFieldPolicy(this, enabledByDefault, false, false);
	}

	/** Parses a field key, returning empty for an unknown key instead of throwing. */
	public static Optional<StudentProfileField> fromKey(String key) {
		if (key == null) {
			return Optional.empty();
		}
		for (StudentProfileField field : values()) {
			if (field.name().equals(key)) {
				return Optional.of(field);
			}
		}
		return Optional.empty();
	}

}
