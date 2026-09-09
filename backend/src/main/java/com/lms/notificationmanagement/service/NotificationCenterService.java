package com.lms.notificationmanagement.service;

import com.lms.common.api.PageResponse;
import com.lms.common.error.NotFoundException;
import com.lms.identityaccessservice.api.AuthenticatedPrincipal;
import com.lms.identityaccessservice.api.AuthenticatedPrincipalHolder;
import com.lms.notificationmanagement.domain.InAppNotification;
import com.lms.notificationmanagement.repository.InAppNotificationRepository;
import java.time.Instant;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Backs {@code NotificationController} (plan §9.5/§10, Flow C). Every method
 * is self-scoped to {@code AuthenticatedPrincipalHolder.get().userId()} - the
 * caller's own trusted id, never a client-supplied one - on top of the
 * structural {@code tenant_id} filter every {@link InAppNotificationRepository}
 * finder already applies. No {@code DomainArea}/permission check is used here
 * (plan §2/§15): this is a self-service "my own records" read/write, the same
 * authorization shape as a student's own exam-attempt history.
 */
@Service
public class NotificationCenterService {

	/** Mirrors {@code ExamAttemptService}'s defensive page-size clamp (plan §12/§22). */
	private static final int MAX_PAGE_SIZE = 100;

	private final InAppNotificationRepository inAppNotificationRepository;

	public NotificationCenterService(InAppNotificationRepository inAppNotificationRepository) {
		this.inAppNotificationRepository = inAppNotificationRepository;
	}

	@Transactional(readOnly = true)
	public PageResponse<InAppNotificationView> listMyNotifications(Pageable pageable) {
		AuthenticatedPrincipal principal = AuthenticatedPrincipalHolder.get();
		Pageable safePageable = clampPageSize(pageable);
		Page<InAppNotification> page = inAppNotificationRepository.findByRecipientUserId(principal.userId(),
				safePageable);
		return PageResponse.from(page.map(NotificationCenterService::toView));
	}

	/**
	 * Updates {@code read_at} only on a row the caller owns; {@code
	 * NotFoundException} (404, never 403) if the id doesn't resolve to the
	 * caller's own row - per plan §10's explicit anti-enumeration requirement,
	 * existence of another user's row must never be revealed.
	 */
	@Transactional
	public InAppNotificationView markRead(UUID notificationId) {
		AuthenticatedPrincipal principal = AuthenticatedPrincipalHolder.get();
		InAppNotification row = inAppNotificationRepository.findByIdAndRecipientUserId(notificationId,
				principal.userId()).orElseThrow(() -> new NotFoundException("Notification not found"));
		row.markRead(Instant.now());
		inAppNotificationRepository.save(row);
		return toView(row);
	}

	/** Backs the Notification Center nav badge - self-scoped exactly like {@link #listMyNotifications}. */
	@Transactional(readOnly = true)
	public long getMyUnreadCount() {
		AuthenticatedPrincipal principal = AuthenticatedPrincipalHolder.get();
		return inAppNotificationRepository.countByRecipientUserIdAndReadAtIsNull(principal.userId());
	}

	private Pageable clampPageSize(Pageable pageable) {
		if (pageable.getPageSize() <= MAX_PAGE_SIZE) {
			return pageable;
		}
		return PageRequest.of(pageable.getPageNumber(), MAX_PAGE_SIZE, pageable.getSort());
	}

	private static InAppNotificationView toView(InAppNotification entity) {
		return new InAppNotificationView(entity.getId(), entity.getTitle(), entity.getBody(), entity.getReadAt(),
				entity.getCreatedAt());
	}

}
