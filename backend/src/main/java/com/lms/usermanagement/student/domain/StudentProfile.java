package com.lms.usermanagement.student.domain;

import com.lms.common.persistence.Auditable;
import com.lms.common.persistence.TenantOwned;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import com.lms.tenantmanagement.api.StudentProfileField;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.hibernate.annotations.Generated;

/**
 * The operational/profile aspect of a student account, owned by
 * {@code user-management} (MVP-006 Student Management), mapped 1:1 onto
 * {@code student_profile} (V17). The credential aspect (email, password hash,
 * role, status) lives on {@code identity-access-service}'s {@code
 * tenant_user} row instead - {@code userId} below is an opaque foreign key
 * value only, never a JPA {@code @ManyToOne} across the module boundary (per
 * {@code .claude/rules/architecture.md}: a module must never import another
 * domain's {@code domain} classes). Any read/mutation of the credential row
 * goes through {@code identity-access-service}'s {@code api} package.
 *
 * <p>Wave 3 (Student registration expansion) adds the nullable guardian/
 * school/grade/stream/mobile columns (V44) and {@code registrationStatus}
 * (V45) - see those migrations' own header comments for why the profile
 * fields stay nullable (tenant-configurable required-ness, enforced at the
 * API layer, not the DB level).
 */
@Entity
@Table(name = "student_profile")
public class StudentProfile extends Auditable implements TenantOwned {

	@Column(name = "tenant_id", nullable = false, updatable = false)
	private UUID tenantId;

	@Column(name = "user_id", nullable = false, updatable = false)
	private UUID userId;

	@Column(name = "name", nullable = false)
	private String name;

	@Column(name = "guardian_name")
	private String guardianName;

	@Column(name = "guardian_phone")
	private String guardianPhone;

	@Column(name = "school")
	private String school;

	@Column(name = "grade")
	private String grade;

	@Column(name = "stream")
	private String stream;

	@Column(name = "mobile")
	private String mobile;

	/** The student's own billing address (V58) - required for online (PayHere) payment. */
	@Column(name = "address_line")
	private String addressLine;

	@Column(name = "city")
	private String city;

	// STU-PROF-01 (V59): remaining tenant-configurable catalogue fields. All
	// nullable; required-ness is per tenant, enforced in StudentProfileFieldsService.
	@Column(name = "date_of_birth")
	private LocalDate dateOfBirth;

	@Column(name = "fixed_phone")
	private String fixedPhone;

	@Column(name = "whatsapp_number")
	private String whatsappNumber;

	@Column(name = "telegram_number")
	private String telegramNumber;

	@Column(name = "tute_delivery_phone_1")
	private String tuteDeliveryPhone1;

	@Column(name = "tute_delivery_phone_2")
	private String tuteDeliveryPhone2;

	@Column(name = "address_line_2")
	private String addressLine2;

	@Column(name = "postal_code")
	private String postalCode;

	@Column(name = "country_code")
	private String countryCode;

	@Column(name = "nic_number")
	private String nicNumber;

	@Column(name = "passport_number")
	private String passportNumber;

	@Column(name = "driving_license_number")
	private String drivingLicenseNumber;

	@Column(name = "has_siblings")
	private Boolean hasSiblings;

	@Column(name = "profession")
	private String profession;

	@Column(name = "special_talent")
	private String specialTalent;

	@Column(name = "university")
	private String university;

	@Column(name = "learning_medium")
	private String learningMedium;

	@Column(name = "al_year")
	private Short alYear;

	@Column(name = "referral_code")
	private String referralCode;

	@Enumerated(EnumType.STRING)
	@Column(name = "registration_status", nullable = false)
	private StudentRegistrationStatus registrationStatus;

	/**
	 * V95 per-tenant index number ("0001", ..., "10000"; spec
	 * student-payment-report.md section 6). Database-generated and immutable:
	 * never written by the application (insertable/updatable = false, so the
	 * INSERT leaves it NULL and V95's BEFORE INSERT trigger takes the next
	 * number from {@code student_index_counter} in the same transaction);
	 * Hibernate reads the assigned value back after the insert. There is no
	 * setter - no application path supplies or changes it.
	 */
	@Generated
	@Column(name = "index_number", insertable = false, updatable = false, length = 20)
	private String indexNumber;

	// STU-MGMT-02 (V117).
	@Column(name = "first_name", length = 120)
	private String firstName;

	@Column(name = "last_name", length = 120)
	private String lastName;

	@Column(name = "guardian_nic", length = 20)
	private String guardianNic;

	@Column(name = "profile_media_file_id")
	private UUID profileMediaFileId;

	/** Opaque QR identification token (spec section 7); never derived from any student data. */
	@Column(name = "qr_token", nullable = false, length = 64)
	private String qrToken;

	@Column(name = "qr_rotated_at")
	private Instant qrRotatedAt;

	@Column(name = "pending_approval", nullable = false)
	private boolean pendingApproval;

	@Column(name = "removed_at")
	private Instant removedAt;

	@Column(name = "removed_by")
	private UUID removedBy;

	@Column(name = "removal_reason", length = 500)
	private String removalReason;

	private static final String WHITESPACE = "\\s+";

	protected StudentProfile() {
	}

	public StudentProfile(UUID tenantId, UUID userId, String name) {
		this(tenantId, userId, name, StudentRegistrationStatus.ADMIN_CREATED, null, null, null, null, null, null);
	}

	public StudentProfile(UUID tenantId, UUID userId, String name, StudentRegistrationStatus registrationStatus,
			String guardianName, String guardianPhone, String school, String grade, String stream, String mobile) {
		this.tenantId = tenantId;
		this.userId = userId;
		this.registrationStatus = registrationStatus;
		this.qrToken = StudentQrTokens.newToken();
		rename(name);
		this.guardianName = guardianName;
		this.guardianPhone = guardianPhone;
		this.school = school;
		this.grade = grade;
		this.stream = stream;
		this.mobile = mobile;
	}

	@Override
	public UUID getTenantId() {
		return tenantId;
	}

	@Override
	public void setTenantId(UUID tenantId) {
		this.tenantId = tenantId;
	}

	public UUID getUserId() {
		return userId;
	}

	public String getName() {
		return name;
	}

	public String getGuardianName() {
		return guardianName;
	}

	public String getGuardianPhone() {
		return guardianPhone;
	}

	public String getSchool() {
		return school;
	}

	public String getGrade() {
		return grade;
	}

	public String getStream() {
		return stream;
	}

	public String getMobile() {
		return mobile;
	}

	public String getWhatsappNumber() {
		return whatsappNumber;
	}

	public StudentRegistrationStatus getRegistrationStatus() {
		return registrationStatus;
	}

	/** The V95 index number (string - leading zeros are significant); assigned by the database. */
	public String getIndexNumber() {
		return indexNumber;
	}

	/**
	 * Updates this profile's own name only - Student Management's
	 * profile-edit endpoint (both the staff-facing edit and the self-service
	 * {@code /me} edit) needs a mutator that {@code StaffProfile} did not
	 * require in its first pass. Never touches {@code tenantId}/{@code
	 * userId}, which are immutable identity fields set only at construction.
	 */
	public void rename(String name) {
		String trimmed = name == null ? "" : name.strip();
		String[] parts = trimmed.split(WHITESPACE, 2);
		this.name = trimmed;
		this.firstName = truncate(parts[0], 120);
		this.lastName = parts.length > 1 ? truncate(parts[1], 120) : null;
	}

	/** STU-MGMT-02: sets both name parts; {@code name} is kept as "first last" for other modules. */
	public void renameParts(String firstName, String lastName) {
		this.firstName = firstName.strip();
		this.lastName = (lastName == null || lastName.isBlank()) ? null : lastName.strip();
		this.name = this.lastName == null ? this.firstName : this.firstName + " " + this.lastName;
	}

	private static String truncate(String value, int max) {
		return value.length() > max ? value.substring(0, max) : value;
	}

	/** First name; falls back to the first word of {@code name} for rows written outside the application. */
	public String getFirstName() {
		if (firstName != null) {
			return firstName;
		}
		return name == null ? null : name.strip().split(WHITESPACE, 2)[0];
	}

	public String getLastName() {
		if (firstName != null) {
			return lastName;
		}
		if (name == null) {
			return null;
		}
		String[] parts = name.strip().split(WHITESPACE, 2);
		return parts.length > 1 ? parts[1] : null;
	}

	public String getGuardianNic() {
		return guardianNic;
	}

	public UUID getProfileMediaFileId() {
		return profileMediaFileId;
	}

	public void setProfileMediaFileId(UUID profileMediaFileId) {
		this.profileMediaFileId = profileMediaFileId;
	}

	public String getQrToken() {
		return qrToken;
	}

	public Instant getQrRotatedAt() {
		return qrRotatedAt;
	}

	/** Replaces the QR token (revokes every printed card carrying the old one). */
	public void rotateQrToken(Instant now) {
		this.qrToken = StudentQrTokens.newToken();
		this.qrRotatedAt = now;
	}

	public boolean isPendingApproval() {
		return pendingApproval;
	}

	public void markPendingApproval() {
		this.pendingApproval = true;
	}

	public void approve() {
		this.pendingApproval = false;
	}

	public Instant getRemovedAt() {
		return removedAt;
	}

	public UUID getRemovedBy() {
		return removedBy;
	}

	public String getRemovalReason() {
		return removalReason;
	}

	public boolean isRemoved() {
		return removedAt != null;
	}

	/** Soft removal from the tenant: the row and all history stay. */
	public void markRemoved(UUID actorId, Instant now, String reason) {
		this.removedAt = now;
		this.removedBy = actorId;
		this.removalReason = reason;
	}

	public LocalDate getDateOfBirth() {
		return dateOfBirth;
	}

	public String getFixedPhone() {
		return fixedPhone;
	}

	public String getAddressLine2() {
		return addressLine2;
	}

	public String getPostalCode() {
		return postalCode;
	}

	public Short getAlYear() {
		return alYear;
	}

	/**
	 * STU-MGMT-02 staff edit of the allowlisted Klass form fields (spec section 3). Values
	 * are already validated/normalised by the caller; {@code null} clears a field. Never
	 * touches identity, index number, QR token or flags.
	 */
	public void updateManagedDetails(ManagedDetails details) {
		renameParts(details.firstName(), details.lastName());
		this.whatsappNumber = details.whatsappNumber();
		this.dateOfBirth = details.dateOfBirth();
		this.mobile = details.mobile();
		this.addressLine = details.addressLine1();
		this.addressLine2 = details.addressLine2();
		this.city = details.city();
		this.postalCode = details.postalCode();
		this.guardianName = details.guardianName();
		this.guardianPhone = details.guardianPhone();
		this.guardianNic = details.guardianNic();
		this.fixedPhone = details.homePhone();
		this.school = details.school();
		this.alYear = details.alYear();
		this.stream = details.alStream();
	}

	/** The allowlisted staff-managed fields of the STU-MGMT-02 form. */
	public record ManagedDetails(String firstName, String lastName, String whatsappNumber, LocalDate dateOfBirth,
			String mobile, String addressLine1, String addressLine2, String city, String postalCode,
			String guardianName, String guardianPhone, String guardianNic, String homePhone, String school,
			Short alYear, String alStream) {
	}

	public String getAddressLine() {
		return addressLine;
	}

	public String getCity() {
		return city;
	}

	/** Self-service billing address edit (PAY-GW-01 follow-up). */
	public void updateBillingAddress(String addressLine, String city) {
		this.addressLine = addressLine;
		this.city = city;
	}

	/**
	 * STU-PROF-01: the stored value of a tenant-configurable catalogue field,
	 * or {@code null} when unset. {@link StudentProfileField#GUARDIAN} returns
	 * a {@link GuardianDetails} (null only when both parts are unset). Locked
	 * fields (email, full name) are not handled here - they have their own
	 * endpoints.
	 */
	public Object readField(StudentProfileField field) {
		return switch (field) {
			case BIRTHDAY -> dateOfBirth;
			case PROFESSION -> profession;
			case SPECIAL_TALENT -> specialTalent;
			case MOBILE -> mobile;
			case FIXED_TELEPHONE -> fixedPhone;
			case WHATSAPP_NUMBER -> whatsappNumber;
			case TELEGRAM_NUMBER -> telegramNumber;
			case TUTE_DELIVERY_PHONE_1 -> tuteDeliveryPhone1;
			case TUTE_DELIVERY_PHONE_2 -> tuteDeliveryPhone2;
			case ADDRESS_LINE_1 -> addressLine;
			case ADDRESS_LINE_2 -> addressLine2;
			case CITY -> city;
			case POSTAL_CODE -> postalCode;
			case COUNTRY -> countryCode;
			case NIC_NUMBER -> nicNumber;
			case PASSPORT_NUMBER -> passportNumber;
			case DRIVING_LICENSE_NUMBER -> drivingLicenseNumber;
			case SCHOOL -> school;
			case GRADE -> grade;
			case AL_STREAM -> stream;
			case AL_YEAR -> alYear;
			case UNIVERSITY -> university;
			case LEARNING_MEDIUM -> learningMedium;
			case GUARDIAN -> (guardianName == null && guardianPhone == null) ? null
					: new GuardianDetails(guardianName, guardianPhone);
			case HAS_SIBLINGS -> hasSiblings;
			case REFERRAL_CODE -> referralCode;
			case EMAIL, FULL_NAME -> throw new IllegalArgumentException(field + " is not a profile-details field");
		};
	}

	/**
	 * STU-PROF-01: stores an already-validated value (see {@code
	 * StudentProfileFieldValueParser}) for a catalogue field; {@code null}
	 * clears it. Never touches {@code tenantId}/{@code userId}.
	 */
	public void writeField(StudentProfileField field, Object value) {
		switch (field) {
			case BIRTHDAY -> dateOfBirth = (LocalDate) value;
			case PROFESSION -> profession = (String) value;
			case SPECIAL_TALENT -> specialTalent = (String) value;
			case MOBILE -> mobile = (String) value;
			case FIXED_TELEPHONE -> fixedPhone = (String) value;
			case WHATSAPP_NUMBER -> whatsappNumber = (String) value;
			case TELEGRAM_NUMBER -> telegramNumber = (String) value;
			case TUTE_DELIVERY_PHONE_1 -> tuteDeliveryPhone1 = (String) value;
			case TUTE_DELIVERY_PHONE_2 -> tuteDeliveryPhone2 = (String) value;
			case ADDRESS_LINE_1 -> addressLine = (String) value;
			case ADDRESS_LINE_2 -> addressLine2 = (String) value;
			case CITY -> city = (String) value;
			case POSTAL_CODE -> postalCode = (String) value;
			case COUNTRY -> countryCode = (String) value;
			case NIC_NUMBER -> nicNumber = (String) value;
			case PASSPORT_NUMBER -> passportNumber = (String) value;
			case DRIVING_LICENSE_NUMBER -> drivingLicenseNumber = (String) value;
			case SCHOOL -> school = (String) value;
			case GRADE -> grade = (String) value;
			case AL_STREAM -> stream = (String) value;
			case AL_YEAR -> alYear = (Short) value;
			case UNIVERSITY -> university = (String) value;
			case LEARNING_MEDIUM -> learningMedium = (String) value;
			case GUARDIAN -> {
				GuardianDetails guardian = (GuardianDetails) value;
				guardianName = (guardian == null) ? null : guardian.name();
				guardianPhone = (guardian == null) ? null : guardian.phone();
			}
			case HAS_SIBLINGS -> hasSiblings = (Boolean) value;
			case REFERRAL_CODE -> referralCode = (String) value;
			case EMAIL, FULL_NAME -> throw new IllegalArgumentException(field + " is not a profile-details field");
		}
	}

}
