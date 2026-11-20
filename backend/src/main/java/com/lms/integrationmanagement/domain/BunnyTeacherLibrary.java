package com.lms.integrationmanagement.domain;

import com.lms.common.persistence.Auditable;
import com.lms.common.persistence.TenantOwned;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.util.UUID;

/**
 * A teacher's own Bunny Stream video library inside the tenant's Bunny
 * account (MEDIA-INT-01A / ADR-018 Amendment 1, V61).
 *
 * <p>Holds no secret: the library's API key is read from Bunny with the
 * tenant's account key when needed. Rows are never deleted - a failed attempt
 * stays {@code FAILED} and a retry inserts a new row.
 */
@Entity
@Table(name = "bunny_teacher_library")
public class BunnyTeacherLibrary extends Auditable implements TenantOwned {

	private static final int MAX_ERROR_LENGTH = 500;

	@Column(name = "tenant_id", nullable = false, updatable = false)
	private UUID tenantId;

	@Column(name = "teacher_user_id", nullable = false, updatable = false)
	private UUID teacherUserId;

	@Column(name = "connection_id", nullable = false, updatable = false)
	private UUID connectionId;

	@Column(name = "bunny_library_id")
	private Long bunnyLibraryId;

	@Column(name = "library_name", nullable = false)
	private String libraryName;

	@Column(name = "pull_zone_id")
	private Long pullZoneId;

	@Enumerated(EnumType.STRING)
	@Column(name = "status", nullable = false, length = 15)
	private BunnyLibraryStatus status;

	@Column(name = "last_error", length = MAX_ERROR_LENGTH)
	private String lastError;

	protected BunnyTeacherLibrary() {
	}

	private BunnyTeacherLibrary(UUID tenantId, UUID teacherUserId, UUID connectionId, String libraryName) {
		this.tenantId = tenantId;
		this.teacherUserId = teacherUserId;
		this.connectionId = connectionId;
		this.libraryName = libraryName;
		this.status = BunnyLibraryStatus.PROVISIONING;
	}

	/** A new row, committed before Bunny is called. */
	public static BunnyTeacherLibrary provisioning(UUID tenantId, UUID teacherUserId, UUID connectionId,
			String libraryName) {
		return new BunnyTeacherLibrary(tenantId, teacherUserId, connectionId, libraryName);
	}

	public void activate(long bunnyLibraryId, String libraryName, Long pullZoneId) {
		if (status != BunnyLibraryStatus.PROVISIONING) {
			throw new IllegalStateException("Only a provisioning library can be activated");
		}
		this.bunnyLibraryId = bunnyLibraryId;
		if (libraryName != null && !libraryName.isBlank()) {
			this.libraryName = libraryName;
		}
		this.pullZoneId = pullZoneId;
		this.status = BunnyLibraryStatus.ACTIVE;
		this.lastError = null;
	}

	public void fail(String sanitisedError) {
		if (status != BunnyLibraryStatus.PROVISIONING) {
			throw new IllegalStateException("Only a provisioning library can fail");
		}
		this.status = BunnyLibraryStatus.FAILED;
		this.lastError = (sanitisedError == null || sanitisedError.length() <= MAX_ERROR_LENGTH) ? sanitisedError
				: sanitisedError.substring(0, MAX_ERROR_LENGTH);
	}

	@Override
	public UUID getTenantId() {
		return tenantId;
	}

	@Override
	public void setTenantId(UUID tenantId) {
		this.tenantId = tenantId;
	}

	public UUID getTeacherUserId() {
		return teacherUserId;
	}

	public UUID getConnectionId() {
		return connectionId;
	}

	public Long getBunnyLibraryId() {
		return bunnyLibraryId;
	}

	public String getLibraryName() {
		return libraryName;
	}

	public Long getPullZoneId() {
		return pullZoneId;
	}

	public BunnyLibraryStatus getStatus() {
		return status;
	}

	public String getLastError() {
		return lastError;
	}

}
