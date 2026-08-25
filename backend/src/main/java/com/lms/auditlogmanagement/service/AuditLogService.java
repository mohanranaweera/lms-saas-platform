package com.lms.auditlogmanagement.service;

import com.lms.auditlogmanagement.api.AuditLogApi;
import com.lms.auditlogmanagement.api.AuditLogEntry;
import com.lms.auditlogmanagement.domain.AuditLog;
import com.lms.auditlogmanagement.repository.AuditLogRepository;
import com.lms.common.tenant.TenantContext;
import java.time.Instant;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * Implements {@link AuditLogApi}. {@code @Transactional} here uses Spring's
 * default {@code REQUIRED} propagation - when called from within an
 * already-open transaction (the expected/only real call shape, e.g. {@code
 * SlipReviewService#approve}), this method joins that same transaction
 * rather than opening a new one, so the audit write commits/rolls back
 * atomically with the state change it documents.
 */
@Service
public class AuditLogService implements AuditLogApi {

	private final AuditLogRepository auditLogRepository;

	private final TenantContext tenantContext;

	private final ObjectMapper objectMapper;

	public AuditLogService(AuditLogRepository auditLogRepository, TenantContext tenantContext,
			ObjectMapper objectMapper) {
		this.auditLogRepository = auditLogRepository;
		this.tenantContext = tenantContext;
		this.objectMapper = objectMapper;
	}

	@Override
	@Transactional
	public void record(AuditLogEntry entry) {
		if (entry == null) {
			throw new IllegalArgumentException("entry must not be null");
		}
		AuditLog auditLog = new AuditLog(tenantContext.getTenantId(), entry.actorId(), entry.action(),
				entry.targetEntity(), entry.targetId(), entry.reason(), serializeMetadata(entry.metadata()),
				Instant.now());
		auditLogRepository.save(auditLog);
	}

	private String serializeMetadata(Map<String, Object> metadata) {
		if (metadata == null || metadata.isEmpty()) {
			return null;
		}
		try {
			return objectMapper.writeValueAsString(metadata);
		}
		catch (JacksonException e) {
			// A metadata map that cannot be serialized is a caller bug, not a
			// runtime condition to swallow - failing loudly here (rather than
			// silently dropping metadata) keeps the audit row from ever
			// looking complete when it isn't.
			throw new IllegalStateException("Failed to serialize audit log metadata", e);
		}
	}

}
