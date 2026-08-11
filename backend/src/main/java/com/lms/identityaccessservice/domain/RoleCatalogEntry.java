package com.lms.identityaccessservice.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/**
 * Platform-global role catalog row ({@code role} table, V7). Holds both
 * {@code PLATFORM}- and {@code TENANT}-scope role codes as fixed reference
 * data - no {@code tenant_id}, since this table is not tenant-owned (see
 * {@link com.lms.identityaccessservice.repository.RoleCatalogRepository}'s
 * javadoc for why it intentionally does not extend
 * {@code TenantAwareRepository}).
 *
 * <p>Deliberately does NOT extend {@link com.lms.common.persistence.BaseEntity}
 * or {@link com.lms.common.persistence.TimestampedEntity} - both mandate a
 * UUID {@code @Id}, but this table's primary key is {@code code}, a
 * {@code String}. The {@code createdAt}/{@code updatedAt} auditing fields are
 * therefore declared directly here, mirroring {@code TimestampedEntity}'s
 * pattern ({@code @CreatedDate}/{@code @LastModifiedDate} +
 * {@code @EntityListeners(AuditingEntityListener.class)}) rather than
 * inheriting it.
 */
@Entity
@Table(name = "role")
@EntityListeners(AuditingEntityListener.class)
public class RoleCatalogEntry {

	/**
	 * Scope of a catalog row: {@code PLATFORM} (Platform Admin, documentary
	 * only per plan §7 - no FK relationship from {@code platform_admin_user}
	 * to this table) or {@code TENANT} (assignable via
	 * {@code tenant_user.role}, mirrored 1:1 by {@link Role}'s value set).
	 */
	public enum RoleScope {

		PLATFORM, TENANT

	}

	@Id
	@Column(name = "code", updatable = false, nullable = false)
	private String code;

	@Enumerated(EnumType.STRING)
	@Column(name = "scope", nullable = false)
	private RoleScope scope;

	@Column(name = "display_name", nullable = false)
	private String displayName;

	@Column(name = "description")
	private String description;

	@Column(name = "portal_route_group", nullable = false)
	private String portalRouteGroup;

	@Column(name = "self_registers", nullable = false)
	private boolean selfRegisters;

	@Column(name = "is_provisional", nullable = false)
	private boolean isProvisional;

	@Column(name = "is_active", nullable = false)
	private boolean isActive;

	@Column(name = "sort_order", nullable = false)
	private int sortOrder;

	@CreatedDate
	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	@LastModifiedDate
	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	protected RoleCatalogEntry() {
	}

	public String getCode() {
		return code;
	}

	public RoleScope getScope() {
		return scope;
	}

	public String getDisplayName() {
		return displayName;
	}

	public String getDescription() {
		return description;
	}

	public String getPortalRouteGroup() {
		return portalRouteGroup;
	}

	public boolean isSelfRegisters() {
		return selfRegisters;
	}

	public boolean isProvisional() {
		return isProvisional;
	}

	public boolean isActive() {
		return isActive;
	}

	public int getSortOrder() {
		return sortOrder;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

	public Instant getUpdatedAt() {
		return updatedAt;
	}

}
