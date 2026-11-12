package com.lms.contentmanagement.library.domain;

import com.lms.common.persistence.BaseEntity;
import com.lms.common.persistence.TenantOwned;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * An explicit grant of one folder to one member (V122 {@code media_folder_grant},
 * MEDIA-FOLDERS-01 section 4). No inheritance: it covers {@link #getFolderId()} only. Upserting
 * a different level updates the row (who / when re-recorded); revoking deletes it. Every change
 * is audited by {@code MediaFolderService}.
 */
@Entity
@Table(name = "media_folder_grant")
public class MediaFolderGrant extends BaseEntity implements TenantOwned {

	@Column(name = "tenant_id", nullable = false, updatable = false)
	private UUID tenantId;

	@Column(name = "folder_id", nullable = false, updatable = false)
	private UUID folderId;

	@Column(name = "grantee_user_id", nullable = false, updatable = false)
	private UUID granteeUserId;

	@Enumerated(EnumType.STRING)
	@Column(name = "access_level", nullable = false, length = 10)
	private MediaFolderAccessLevel accessLevel;

	@Column(name = "granted_by", nullable = false)
	private UUID grantedBy;

	@Column(name = "granted_at", nullable = false)
	private Instant grantedAt;

	protected MediaFolderGrant() {
	}

	public MediaFolderGrant(UUID tenantId, UUID folderId, UUID granteeUserId, MediaFolderAccessLevel accessLevel,
			UUID grantedBy, Instant grantedAt) {
		this.tenantId = tenantId;
		this.folderId = folderId;
		this.granteeUserId = granteeUserId;
		this.accessLevel = accessLevel;
		this.grantedBy = grantedBy;
		this.grantedAt = grantedAt;
	}

	@Override
	public UUID getTenantId() {
		return tenantId;
	}

	@Override
	public void setTenantId(UUID tenantId) {
		this.tenantId = tenantId;
	}

	public UUID getFolderId() {
		return folderId;
	}

	public UUID getGranteeUserId() {
		return granteeUserId;
	}

	public MediaFolderAccessLevel getAccessLevel() {
		return accessLevel;
	}

	public UUID getGrantedBy() {
		return grantedBy;
	}

	public Instant getGrantedAt() {
		return grantedAt;
	}

	public void changeLevel(MediaFolderAccessLevel newLevel, UUID actorId, Instant at) {
		this.accessLevel = newLevel;
		this.grantedBy = actorId;
		this.grantedAt = at;
	}

}
