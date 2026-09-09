package com.lms.notificationmanagement.web;

import com.lms.common.api.ApiResponse;
import com.lms.common.api.PageResponse;
import com.lms.notificationmanagement.service.InAppNotificationView;
import com.lms.notificationmanagement.service.NotificationCenterService;
import com.lms.notificationmanagement.web.dto.NotificationResponse;
import com.lms.notificationmanagement.web.dto.UnreadCountResponse;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Notification Center list/mark-read (plan §9.5/§10, Flow C/D). Self-service,
 * recipient-scoped, no {@code DomainArea}/{@code @PreAuthorize} role
 * restriction - unlike {@code ExamAttemptController}'s student-only
 * endpoints, this endpoint must work for ANY authenticated role (Student
 * today; Teacher's own, currently-empty-by-construction activity feed later,
 * per plan §2/§4 Flow D) so no role gate is added here. Unauthenticated
 * requests are already rejected platform-wide by {@code
 * SecurityFilterChainConfig}'s {@code authorize.anyRequest().authenticated()}
 * default - confirmed by reading that config directly - so no endpoint-level
 * marker annotation is needed purely for "must be authenticated" either.
 */
@RestController
@RequestMapping("/api/v1/notifications")
public class NotificationController {

	private final NotificationCenterService notificationCenterService;

	public NotificationController(NotificationCenterService notificationCenterService) {
		this.notificationCenterService = notificationCenterService;
	}

	@GetMapping
	public ResponseEntity<ApiResponse<PageResponse<NotificationResponse>>> listMyNotifications(
			@PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable) {
		PageResponse<InAppNotificationView> page = notificationCenterService.listMyNotifications(pageable);
		return ResponseEntity.ok(ApiResponse.success(new PageResponse<>(
				page.content().stream().map(NotificationController::toResponse).toList(), page.page(), page.size(),
				page.totalElements(), page.totalPages())));
	}

	@GetMapping("/unread-count")
	public ResponseEntity<ApiResponse<UnreadCountResponse>> getMyUnreadCount() {
		long unreadCount = notificationCenterService.getMyUnreadCount();
		return ResponseEntity.ok(ApiResponse.success(new UnreadCountResponse(unreadCount)));
	}

	@PatchMapping("/{id}/read")
	public ResponseEntity<ApiResponse<NotificationResponse>> markRead(@PathVariable UUID id) {
		InAppNotificationView view = notificationCenterService.markRead(id);
		return ResponseEntity.ok(ApiResponse.success(toResponse(view)));
	}

	private static NotificationResponse toResponse(InAppNotificationView view) {
		return new NotificationResponse(view.id(), view.title(), view.body(), view.readAt(), view.createdAt());
	}

}
