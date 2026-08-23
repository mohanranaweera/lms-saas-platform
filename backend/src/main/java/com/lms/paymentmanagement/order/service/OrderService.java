package com.lms.paymentmanagement.order.service;

import com.lms.common.error.ConflictException;
import com.lms.common.error.NotFoundException;
import com.lms.common.tenant.TenantContext;
import com.lms.coursemanagement.api.CourseLookupApi;
import com.lms.identityaccessservice.api.AuthenticatedPrincipal;
import com.lms.identityaccessservice.api.AuthenticatedPrincipalHolder;
import com.lms.paymentmanagement.order.domain.StudentOrder;
import com.lms.paymentmanagement.order.repository.StudentOrderRepository;
import com.lms.paymentmanagement.payment.domain.Payment;
import com.lms.paymentmanagement.payment.repository.PaymentRepository;
import com.lms.paymentmanagement.support.PaymentDomainAccessGuard;
import java.math.BigDecimal;
import java.util.UUID;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Orchestrates PAY-1's order lifecycle. {@code tenant_id}/{@code
 * student_id} are ALWAYS resolved from {@link TenantContext}/{@link
 * AuthenticatedPrincipalHolder} - never from any request-body field, per
 * plan §3/§12 (the request DTO structurally has no such field at all).
 */
@Service
@Transactional
public class OrderService {

	private static final String STUDENT_ROLE = "STUDENT";

	/**
	 * Platform-wide default currency. {@code course.price} carries no
	 * currency column anywhere in this codebase (a single implicit currency
	 * is assumed platform-wide/per-tenant at MVP, per V19's own header
	 * comment) - this constant is this implementation's own placeholder
	 * resolution of that documented gap, not a ratified business decision.
	 */
	static final String DEFAULT_CURRENCY = "USD";

	private final StudentOrderRepository studentOrderRepository;

	private final PaymentRepository paymentRepository;

	private final CourseLookupApi courseLookupApi;

	private final TenantContext tenantContext;

	private final PaymentDomainAccessGuard accessGuard;

	public OrderService(StudentOrderRepository studentOrderRepository, PaymentRepository paymentRepository,
			CourseLookupApi courseLookupApi, TenantContext tenantContext, PaymentDomainAccessGuard accessGuard) {
		this.studentOrderRepository = studentOrderRepository;
		this.paymentRepository = paymentRepository;
		this.courseLookupApi = courseLookupApi;
		this.tenantContext = tenantContext;
		this.accessGuard = accessGuard;
	}

	/**
	 * Server-side only. {@code courseId} must resolve to a published course
	 * within the caller's own tenant; {@code amount}/{@code currency} are
	 * snapshotted from {@link CourseLookupApi#getCurrentPrice(UUID)} at this
	 * instant, never re-read later.
	 */
	public OrderView createOrder(UUID courseId) {
		AuthenticatedPrincipal principal = requireStudent();
		// Existence (in the caller's own tenant) is checked FIRST and
		// separately from "is it published" - CourseLookupApi#getCurrentPrice
		// resolves for any tenant-owned course regardless of status, so a
		// cross-tenant or genuinely nonexistent courseId is 404 (never
		// distinguishable from each other, per CourseLookupApi's own
		// javadoc), while a real, in-tenant-but-unpublished course is 409 -
		// matching the plan's API contract (§10: "404 if courseId doesn't
		// resolve within the caller's tenant", separate from the "409 if the
		// course isn't published/priced" case). Checking isPublished() first
		// (the previous ordering) collapsed both cases into 409, which leaks
		// nothing sensitive but is the wrong status code for the
		// cross-tenant/nonexistent case.
		BigDecimal price = courseLookupApi.getCurrentPrice(courseId)
			.orElseThrow(() -> new NotFoundException("Course not found"));
		if (!courseLookupApi.isPublished(courseId)) {
			throw new ConflictException("Course is not available for enrollment");
		}
		StudentOrder order = new StudentOrder(tenantContext.getTenantId(), principal.userId(), courseId, price,
				DEFAULT_CURRENCY);
		order = studentOrderRepository.save(order);
		return toView(order);
	}

	@Transactional(readOnly = true)
	public OrderView getOrder(UUID id) {
		return toView(loadOrderForCaller(id));
	}

	@Transactional(readOnly = true)
	public OrderPaymentStatusView getPaymentStatus(UUID id) {
		StudentOrder order = loadOrderForCaller(id);
		return paymentRepository.findLatestByOrderId(order.getId())
			.map(OrderService::toPaymentStatusView)
			.orElseGet(OrderPaymentStatusView::noPaymentAttemptYet);
	}

	/**
	 * Tenant/owner-checked read used by {@code PaymentInitiationService} -
	 * only the owning student (never staff, even with a {@code VIEW} grant)
	 * may initiate a payment attempt on their own order.
	 */
	@Transactional(readOnly = true)
	public StudentOrder loadOrderOwnedByCurrentStudent(UUID id) {
		StudentOrder order = studentOrderRepository.findById(id)
			.orElseThrow(() -> new NotFoundException("Order not found"));
		AuthenticatedPrincipal principal = AuthenticatedPrincipalHolder.get();
		if (!STUDENT_ROLE.equals(principal.role()) || !principal.userId().equals(order.getStudentId())) {
			throw new AccessDeniedException("You do not have permission to perform this action");
		}
		return order;
	}

	private StudentOrder loadOrderForCaller(UUID id) {
		StudentOrder order = studentOrderRepository.findById(id)
			.orElseThrow(() -> new NotFoundException("Order not found"));
		accessGuard.requireOwnerOrStaffView(order.getStudentId());
		return order;
	}

	private AuthenticatedPrincipal requireStudent() {
		AuthenticatedPrincipal principal = AuthenticatedPrincipalHolder.get();
		if (!STUDENT_ROLE.equals(principal.role())) {
			// Defense in depth - OrderController already gates this with
			// hasRole('STUDENT'), mirroring CourseService's established
			// "every public method independently re-checks" discipline.
			throw new AccessDeniedException("Only a student may perform this action");
		}
		return principal;
	}

	private static OrderPaymentStatusView toPaymentStatusView(Payment payment) {
		return new OrderPaymentStatusView(true, payment.getId(), payment.getStatus(), payment.getConfirmedAt());
	}

	private static OrderView toView(StudentOrder order) {
		return new OrderView(order.getId(), order.getStudentId(), order.getCourseId(), order.getAmount(),
				order.getCurrency(), order.getStatus(), order.getCreatedAt(), order.getUpdatedAt());
	}

}
