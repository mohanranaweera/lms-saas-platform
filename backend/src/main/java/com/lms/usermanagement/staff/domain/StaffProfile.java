package com.lms.usermanagement.staff.domain;

import com.lms.common.persistence.Auditable;
import com.lms.common.persistence.TenantOwned;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.util.UUID;

/**
 * The operational/profile aspect of a staff account, owned by
 * {@code user-management} (MVP-005 Staff Management), mapped 1:1 onto
 * {@code staff_profile} (V10). The credential aspect (email, password hash,
 * role, status) lives on {@code identity-access-service}'s {@code
 * tenant_user} row instead - {@code userId} below is an opaque foreign key
 * value only, never a JPA {@code @ManyToOne} across the module boundary (per
 * {@code .claude/rules/architecture.md}: a module must never import another
 * domain's {@code domain} classes). Any read/mutation of the credential row
 * goes through {@code identity-access-service}'s {@code api} package.
 */
@Entity
@Table(name = "staff_profile")
public class StaffProfile extends Auditable implements TenantOwned {

	@Column(name = "tenant_id", nullable = false, updatable = false)
	private UUID tenantId;

	@Column(name = "user_id", nullable = false, updatable = false)
	private UUID userId;

	/** Maintained as "first last" for every existing reader (V10 column, NOT NULL). */
	@Column(name = "name", nullable = false)
	private String name;

	/** STAFF-MGMT-02 (V118); {@code null} only on rows written before V118 or by raw SQL. */
	@Column(name = "first_name")
	private String firstName;

	@Column(name = "last_name")
	private String lastName;

	/** Contact phone as entered (string, never a number type); not the sign-in mobile. */
	@Column(name = "contact_number")
	private String contactNumber;

	protected StaffProfile() {
	}

	public StaffProfile(UUID tenantId, UUID userId, String name) {
		this.tenantId = tenantId;
		this.userId = userId;
		this.name = name;
	}

	public StaffProfile(UUID tenantId, UUID userId, String firstName, String lastName, String contactNumber) {
		this.tenantId = tenantId;
		this.userId = userId;
		updateProfile(firstName, lastName, contactNumber);
	}

	/** STAFF-MGMT-02: replaces the profile fields and re-derives {@code name}. */
	public void updateProfile(String firstName, String lastName, String contactNumber) {
		this.firstName = firstName;
		this.lastName = lastName;
		this.contactNumber = contactNumber;
		this.name = (lastName == null || lastName.isBlank()) ? firstName : firstName + " " + lastName;
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

	/** First name, or (legacy rows with only {@code name}) its first word. */
	public String displayFirstName() {
		if (firstName != null && !firstName.isBlank()) {
			return firstName;
		}
		String trimmed = (name == null) ? "" : name.strip();
		int space = trimmed.indexOf(' ');
		return (space < 0) ? trimmed : trimmed.substring(0, space);
	}

	/** Last name, or (legacy rows with only {@code name}) everything after its first word. */
	public String displayLastName() {
		if (firstName != null && !firstName.isBlank()) {
			return lastName;
		}
		String trimmed = (name == null) ? "" : name.strip();
		int space = trimmed.indexOf(' ');
		return (space < 0) ? null : trimmed.substring(space + 1).strip();
	}

	public String getContactNumber() {
		return contactNumber;
	}

}
