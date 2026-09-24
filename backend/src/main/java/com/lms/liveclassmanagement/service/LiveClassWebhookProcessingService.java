package com.lms.liveclassmanagement.service;

import com.lms.common.tenant.TenantContextHolder;
import com.lms.liveclassmanagement.api.ClassSessionAttendanceSyncedEvent;
import com.lms.liveclassmanagement.api.LiveClassWebhookApi;
import com.lms.liveclassmanagement.domain.ClassSession;
import com.lms.liveclassmanagement.domain.ClassSessionRecording;
import com.lms.liveclassmanagement.repository.ClassSessionRecordingRepository;
import com.lms.liveclassmanagement.repository.ClassSessionRepository;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Implements {@link LiveClassWebhookApi} - the only path {@code
 * integration-management}'s {@code LiveClassWebhookController} is permitted
 * to call into for a live-class webhook's actual state change, per that
 * interface's javadoc. Mirrors {@code
 * paymentmanagement.payment.service.PaymentConfirmationService}'s exact
 * "resolve tenant from our own row, never from the payload" webhook-trust
 * pattern.
 *
 * <h2>Why this class does NOT call {@code TenantContext#getTenantId()} up front</h2>
 * Same rationale as {@code PaymentConfirmationService}: the live-class
 * meeting-provider webhook has no subdomain and no JWT, so {@code
 * TenantResolutionFilter} never runs for this request and {@link
 * com.lms.common.tenant.TenantContext} is never populated the normal way.
 * {@link ClassSessionRepository#findByProviderReferenceAcrossTenantsForUpdate}
 * is the one repository method in this module that is not tenant-scoped,
 * used deliberately here to locate the {@link ClassSession} row by its
 * provider reference alone. That row's OWN {@code tenantId} - set correctly
 * when it was created during the earlier, authenticated schedule request -
 * is then the trusted tenant for the rest of this call, explicitly {@code
 * set} on {@link TenantContextHolder} in a {@code try} block and {@code
 * clear}ed in a {@code finally} block.
 */
@Service
public class LiveClassWebhookProcessingService implements LiveClassWebhookApi {

	private static final Logger log = LoggerFactory.getLogger(LiveClassWebhookProcessingService.class);

	private final ClassSessionRepository classSessionRepository;

	private final ClassSessionRecordingRepository recordingRepository;

	private final ApplicationEventPublisher eventPublisher;

	public LiveClassWebhookProcessingService(ClassSessionRepository classSessionRepository,
			ClassSessionRecordingRepository recordingRepository, ApplicationEventPublisher eventPublisher) {
		this.classSessionRepository = classSessionRepository;
		this.recordingRepository = recordingRepository;
		this.eventPublisher = eventPublisher;
	}

	@Override
	@Transactional(readOnly = true)
	public Optional<UUID> resolveTenantId(String providerReference) {
		if (providerReference == null || providerReference.isBlank()) {
			return Optional.empty();
		}
		return classSessionRepository.findByProviderReferenceAcrossTenants(providerReference).map(ClassSession::getTenantId);
	}

	@Override
	@Transactional
	public void handleRecordingCompleted(String providerReference, String providerRecordingReference,
			Integer durationSeconds) {
		Optional<ClassSession> resolved = resolveByProviderReference(providerReference, "recording.completed");
		if (resolved.isEmpty()) {
			return;
		}
		ClassSession session = resolved.get();
		try {
			TenantContextHolder.set(session.getTenantId());
			ClassSessionRecording recording = recordingRepository.findBySessionId(session.getId())
				.orElseGet(() -> new ClassSessionRecording(session.getTenantId(), session.getId()));
			recording.markAvailable(providerRecordingReference, durationSeconds);
			recordingRepository.save(recording);
			log.atInfo()
				.setMessage("live_class.recording_available")
				.addKeyValue("tenantId", session.getTenantId())
				.addKeyValue("sessionId", session.getId())
				.log();
		}
		finally {
			TenantContextHolder.clear();
		}
	}

	@Override
	@Transactional
	public void handleAttendanceSynced(String providerReference) {
		Optional<ClassSession> resolved = resolveByProviderReference(providerReference, "session.attendance_synced");
		if (resolved.isEmpty()) {
			return;
		}
		ClassSession session = resolved.get();
		// Publish only - no listener exists yet in attendance-management this
		// wave (Wave 4 plan §11 / PAR-10-03, deferred to Wave 8).
		eventPublisher.publishEvent(new ClassSessionAttendanceSyncedEvent(session.getTenantId(), session.getId(),
				session.getCourseId(), Instant.now()));
	}

	private Optional<ClassSession> resolveByProviderReference(String providerReference, String eventType) {
		if (providerReference == null || providerReference.isBlank()) {
			log.atWarn().setMessage("live_class.webhook.missing_provider_reference").addKeyValue("eventType", eventType).log();
			return Optional.empty();
		}
		Optional<ClassSession> resolved = classSessionRepository
			.findByProviderReferenceAcrossTenantsForUpdate(providerReference);
		if (resolved.isEmpty()) {
			log.atWarn()
				.setMessage("live_class.webhook.unresolvable_provider_reference")
				.addKeyValue("eventType", eventType)
				.log();
		}
		return resolved;
	}

}
