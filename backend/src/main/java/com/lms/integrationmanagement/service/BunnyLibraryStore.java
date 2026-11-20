package com.lms.integrationmanagement.service;

import com.lms.auditlogmanagement.api.AuditLogApi;
import com.lms.auditlogmanagement.api.AuditLogEntry;
import com.lms.common.error.NotFoundException;
import com.lms.integrationmanagement.domain.BunnyLibraryStatus;
import com.lms.integrationmanagement.domain.BunnyTeacherLibrary;
import com.lms.integrationmanagement.gateway.media.BunnyAccountClient.BunnyLibrary;
import com.lms.integrationmanagement.repository.BunnyTeacherLibraryRepository;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The short, separately committed transactions around Bunny library creation
 * (MEDIA-INT-01A) - the same split as {@link MediaConnectionStore}: {@link
 * BunnyLibraryService} calls Bunny with no transaction open, then persists
 * the outcome here. Outcomes are audited in the same transaction.
 */
@Service
public class BunnyLibraryStore {

	static final String TARGET_ENTITY = "bunny_teacher_library";

	private final BunnyTeacherLibraryRepository repository;

	private final AuditLogApi auditLogApi;

	public BunnyLibraryStore(BunnyTeacherLibraryRepository repository, AuditLogApi auditLogApi) {
		this.repository = repository;
		this.auditLogApi = auditLogApi;
	}

	/**
	 * Commits the PROVISIONING row before Bunny is called. A second live row
	 * for the same teacher violates {@code uq_bunny_teacher_library_live}.
	 * @throws org.springframework.dao.DataIntegrityViolationException another
	 * request is already setting up this teacher's library
	 */
	@Transactional
	public BunnyTeacherLibrary insertProvisioning(UUID tenantId, UUID teacherUserId, UUID connectionId,
			String libraryName) {
		return repository
			.saveAndFlush(BunnyTeacherLibrary.provisioning(tenantId, teacherUserId, connectionId, libraryName));
	}

	/** @throws org.springframework.dao.DataIntegrityViolationException the Bunny library is already mapped */
	@Transactional
	public BunnyTeacherLibrary activate(UUID rowId, BunnyLibrary library, boolean adopted, UUID actorId) {
		BunnyTeacherLibrary row = require(rowId);
		row.activate(library.id(), library.name(), library.pullZoneId());
		BunnyTeacherLibrary saved = repository.saveAndFlush(row);
		Map<String, Object> metadata = new HashMap<>();
		metadata.put("teacherUserId", saved.getTeacherUserId().toString());
		metadata.put("bunnyLibraryId", library.id());
		metadata.put("connectionId", saved.getConnectionId().toString());
		metadata.put("adopted", adopted);
		auditLogApi.record(new AuditLogEntry(actorId, "media_integration.bunny_library_created", TARGET_ENTITY,
				saved.getId(), null, metadata));
		return saved;
	}

	@Transactional
	public BunnyTeacherLibrary fail(UUID rowId, String sanitisedError, UUID actorId) {
		BunnyTeacherLibrary row = require(rowId);
		row.fail(sanitisedError);
		BunnyTeacherLibrary saved = repository.saveAndFlush(row);
		auditLogApi.record(new AuditLogEntry(actorId, "media_integration.bunny_library_failed", TARGET_ENTITY,
				saved.getId(), sanitisedError,
				Map.of("teacherUserId", saved.getTeacherUserId().toString(), "connectionId",
						saved.getConnectionId().toString())));
		return saved;
	}

	/**
	 * Takes over an interrupted (stale) PROVISIONING row. A row another
	 * request has already taken over is left alone instead of throwing
	 * (security review L-1); the live-row unique index then decides which
	 * request goes on to create the library.
	 * @return whether this call marked the row FAILED
	 */
	@Transactional
	public boolean failIfProvisioning(UUID rowId, String sanitisedError, UUID actorId) {
		BunnyTeacherLibrary row = require(rowId);
		if (row.getStatus() != BunnyLibraryStatus.PROVISIONING) {
			return false;
		}
		fail(rowId, sanitisedError, actorId);
		return true;
	}

	private BunnyTeacherLibrary require(UUID rowId) {
		return repository.findById(rowId).orElseThrow(() -> new NotFoundException("Library not found"));
	}

}
