package com.lms.usermanagement.student.service;

import com.lms.auditlogmanagement.api.AuditLogApi;
import com.lms.auditlogmanagement.api.AuditLogEntry;
import com.lms.common.error.NotFoundException;
import com.lms.contentmanagement.api.StudentProfileMediaApi;
import com.lms.contentmanagement.api.StudentProfileMediaApi.SignedImage;
import com.lms.identityaccessservice.api.AuthenticatedPrincipalHolder;
import com.lms.identityaccessservice.api.DomainArea;
import com.lms.identityaccessservice.api.PermissionAction;
import com.lms.identityaccessservice.api.PermissionCheckService;
import com.lms.usermanagement.student.domain.StudentProfile;
import com.lms.usermanagement.student.repository.StudentProfileRepository;
import com.lms.usermanagement.student.web.dto.StudentManagementDtos.StudentDetail;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

/**
 * STU-MGMT-02 profile photos (spec section 6). Deliberately not {@code @Transactional}:
 * the upload stores bytes in object storage (an outbound call) with no transaction open,
 * then links the new file and discards the previous one (only when no other student
 * row references it) in one short transaction. Staff paths require {@code STUDENTS}
 * {@code CREATE_EDIT}/{@code VIEW}; the {@code /me} paths resolve the caller's own
 * profile from the authenticated principal (no id parameter at all).
 */
@Service
public class StudentPhotoService {

	@jakarta.persistence.PersistenceContext
	private jakarta.persistence.EntityManager entityManager;


	private static final Logger log = LoggerFactory.getLogger(StudentPhotoService.class);

	private final StudentProfileRepository studentProfileRepository;

	private final StudentProfileMediaApi studentProfileMediaApi;

	private final StudentManagementService studentManagementService;

	private final PermissionCheckService permissionCheckService;

	private final AuditLogApi auditLogApi;

	private final TransactionTemplate transactionTemplate;

	public StudentPhotoService(StudentProfileRepository studentProfileRepository,
			StudentProfileMediaApi studentProfileMediaApi, StudentManagementService studentManagementService,
			PermissionCheckService permissionCheckService, AuditLogApi auditLogApi,
			PlatformTransactionManager transactionManager) {
		this.studentProfileRepository = studentProfileRepository;
		this.studentProfileMediaApi = studentProfileMediaApi;
		this.studentManagementService = studentManagementService;
		this.permissionCheckService = permissionCheckService;
		this.auditLogApi = auditLogApi;
		this.transactionTemplate = new TransactionTemplate(transactionManager);
	}

	public StudentDetail uploadForStudent(UUID studentId, MultipartFile file) {
		permissionCheckService.requirePermission(DomainArea.STUDENTS, PermissionAction.CREATE_EDIT);
		UUID profileId = studentManagementService.requireManagedStudent(studentId).getId();
		replace(profileId, file);
		return transactionTemplate.execute(status -> studentManagementService
			.toDetail(studentManagementService.requireManagedStudent(profileId)));
	}

	public SignedImage uploadOwn(MultipartFile file) {
		UUID profileId = ownProfile().getId();
		UUID mediaId = replace(profileId, file);
		return studentProfileMediaApi.signUrl(mediaId).orElseThrow(() -> new NotFoundException("Photo not found"));
	}

	public void removeForStudent(UUID studentId) {
		permissionCheckService.requirePermission(DomainArea.STUDENTS, PermissionAction.CREATE_EDIT);
		UUID profileId = studentManagementService.requireManagedStudent(studentId).getId();
		replace(profileId, null);
	}

	public void removeOwn() {
		replace(ownProfile().getId(), null);
	}

	public SignedImage urlForStudent(UUID studentId) {
		permissionCheckService.requirePermission(DomainArea.STUDENTS, PermissionAction.VIEW);
		StudentProfile profile = studentManagementService.requireManagedStudent(studentId);
		return studentProfileMediaApi.signUrl(profile.getProfileMediaFileId())
			.orElseThrow(() -> new NotFoundException("Photo not found"));
	}

	public SignedImage urlOwn() {
		return studentProfileMediaApi.signUrl(ownProfile().getProfileMediaFileId())
			.orElseThrow(() -> new NotFoundException("Photo not found"));
	}

	/** Stores {@code file} (or clears the photo when null) and returns the new media id. */
	private UUID replace(UUID profileId, MultipartFile file) {
		UUID newMediaId = file == null ? null : studentProfileMediaApi.storeProfileImage(file);
		try {
			transactionTemplate.executeWithoutResult(status -> {
				StudentProfile profile = studentManagementService.requireManagedStudent(profileId);
				// Row lock + reload so two concurrent replacements serialise and the
				// loser discards the winner's file instead of orphaning it.
				entityManager.refresh(profile, jakarta.persistence.LockModeType.PESSIMISTIC_WRITE);
				UUID previous = profile.getProfileMediaFileId();
				if (newMediaId == null && previous == null) {
					return;
				}
				profile.setProfileMediaFileId(newMediaId);
				studentProfileRepository.saveAndFlush(profile);
				if (previous != null && !isReferenced(previous)) {
					studentProfileMediaApi.discardProfileImage(previous);
				}
				Map<String, Object> metadata = new LinkedHashMap<>();
				// Audit metadata values must be non-null: absent means "no photo".
				if (previous != null) {
					metadata.put("before", previous.toString());
				}
				if (newMediaId != null) {
					metadata.put("after", newMediaId.toString());
				}
				auditLogApi.record(new AuditLogEntry(AuthenticatedPrincipalHolder.get().userId(),
						newMediaId == null ? "student.photo_removed" : "student.photo_updated",
						StudentManagementService.TARGET, profile.getId(), null, metadata));
			});
		}
		catch (RuntimeException e) {
			if (newMediaId != null) {
				discardQuietly(newMediaId);
			}
			throw e;
		}
		return newMediaId;
	}

	private boolean isReferenced(UUID mediaFileId) {
		return studentProfileRepository.count((root, query, cb) -> cb.equal(root.get("profileMediaFileId"),
				mediaFileId)) > 0;
	}

	private void discardQuietly(UUID mediaFileId) {
		try {
			transactionTemplate.executeWithoutResult(status -> studentProfileMediaApi.discardProfileImage(mediaFileId));
		}
		catch (RuntimeException cleanup) {
			log.warn("Failed to discard student photo {} after a failed update", mediaFileId, cleanup);
		}
	}

	private StudentProfile ownProfile() {
		UUID userId = AuthenticatedPrincipalHolder.get().userId();
		return studentProfileRepository
			.findOne((root, query, cb) -> cb.and(cb.equal(root.get("userId"), userId), cb.isNull(root.get("removedAt"))))
			.orElseThrow(() -> new NotFoundException("Student account not found"));
	}

}
