package com.lms.contentmanagement.library.domain;

import com.lms.common.persistence.TenantOwned;
import com.lms.common.persistence.TimestampedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.util.UUID;

/**
 * One folder of the Media Library Uploads tab (V122 {@code media_folder}, MEDIA-FOLDERS-01).
 * Organises {@link MediaFile}s only ({@code media_file.folder_id}); never internal videos.
 * {@code parentId} {@code null} = root. Every tree mutation runs under the tenant's
 * media-folders advisory lock ({@code MediaFolderService}); {@link #getVersion()} backs the
 * optional {@code expectedVersion} on rename / move / delete / transfer.
 */
@Entity
@Table(name = "media_folder")
public class MediaFolder extends TimestampedEntity implements TenantOwned {

	public static final int MAX_NAME_LENGTH = 100;

	@Column(name = "tenant_id", nullable = false, updatable = false)
	private UUID tenantId;

	@Column(name = "parent_id")
	private UUID parentId;

	@Column(name = "name", nullable = false, length = MAX_NAME_LENGTH)
	private String name;

	@Column(name = "owner_user_id", nullable = false)
	private UUID ownerUserId;

	@Column(name = "created_by", nullable = false, updatable = false)
	private UUID createdBy;

	@Version
	@Column(name = "version", nullable = false)
	private long version;

	protected MediaFolder() {
	}

	public MediaFolder(UUID tenantId, UUID parentId, String name, UUID ownerUserId) {
		this.tenantId = tenantId;
		this.parentId = parentId;
		this.name = name;
		this.ownerUserId = ownerUserId;
		this.createdBy = ownerUserId;
	}

	@Override
	public UUID getTenantId() {
		return tenantId;
	}

	@Override
	public void setTenantId(UUID tenantId) {
		this.tenantId = tenantId;
	}

	public UUID getParentId() {
		return parentId;
	}

	public String getName() {
		return name;
	}

	public UUID getOwnerUserId() {
		return ownerUserId;
	}

	public UUID getCreatedBy() {
		return createdBy;
	}

	public long getVersion() {
		return version;
	}

	public void rename(String newName) {
		this.name = newName;
	}

	public void moveTo(UUID newParentId) {
		this.parentId = newParentId;
	}

	public void transferTo(UUID newOwnerUserId) {
		this.ownerUserId = newOwnerUserId;
	}

}
