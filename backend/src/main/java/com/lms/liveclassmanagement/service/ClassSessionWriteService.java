package com.lms.liveclassmanagement.service;

import com.lms.common.error.NotFoundException;
import com.lms.common.tenant.TenantContext;
import com.lms.liveclassmanagement.domain.ClassSession;
import com.lms.liveclassmanagement.domain.MeetingProvider;
import com.lms.liveclassmanagement.repository.ClassSessionRepository;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The short, separately-committed transactions either side of the (fake, but
 * architecturally-treated-as-external) meeting-provider call, per {@code
 * .claude/rules/backend.md}'s "do not span a transaction across an outbound
 * call to an external system" rule - mirrors {@code
 * paymentmanagement.payment.service.PaymentWriteService}'s exact shape and
 * rationale. Deliberately a separate bean from {@link
 * ClassSessionSchedulingService} (which is NOT itself {@code @Transactional})
 * - calling these methods from a different bean is what makes each one open
 * and commit its own transaction rather than both joining one enclosing
 * transaction that would then span the provider call in between.
 */
@Service
public class ClassSessionWriteService {

	private final ClassSessionRepository classSessionRepository;

	private final TenantContext tenantContext;

	public ClassSessionWriteService(ClassSessionRepository classSessionRepository, TenantContext tenantContext) {
		this.classSessionRepository = classSessionRepository;
		this.tenantContext = tenantContext;
	}

	@Transactional
	public ClassSession createPendingSession(UUID courseId, UUID teacherId, UUID lessonId, String title,
			String description, Instant scheduledStart, Instant scheduledEnd) {
		ClassSession session = new ClassSession(tenantContext.getTenantId(), courseId, teacherId, lessonId, title,
				description, scheduledStart, scheduledEnd, MeetingProvider.ZOOM);
		return classSessionRepository.save(session);
	}

	@Transactional
	public ClassSession markProvisioned(UUID sessionId, String providerReference) {
		ClassSession session = getOwned(sessionId);
		session.markProvisioned(providerReference);
		return classSessionRepository.save(session);
	}

	@Transactional
	public ClassSession markProvisioningFailed(UUID sessionId, String reason) {
		ClassSession session = getOwned(sessionId);
		session.markProvisioningFailed(reason);
		return classSessionRepository.save(session);
	}

	private ClassSession getOwned(UUID sessionId) {
		return classSessionRepository.findById(sessionId)
			.orElseThrow(() -> new NotFoundException("Class session not found"));
	}

}
