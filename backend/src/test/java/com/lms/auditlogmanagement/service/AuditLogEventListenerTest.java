package com.lms.auditlogmanagement.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import com.lms.auditlogmanagement.api.AuditLogApi;
import com.lms.auditlogmanagement.api.AuditLogEntry;
import com.lms.contentmanagement.api.MaterialDeletedEvent;
import com.lms.coursemanagement.api.CourseArchiveStateChangedEvent;
import com.lms.coursemanagement.api.CourseBillingConfigurationChangedEvent;
import com.lms.coursemanagement.api.CourseBillingPeriodAddedEvent;
import com.lms.coursemanagement.api.CourseClonedEvent;
import com.lms.coursemanagement.api.CoursePriceChangedEvent;
import com.lms.coursemanagement.api.CoursePricingModelChangedEvent;
import com.lms.coursemanagement.course.domain.CoursePricingModel;
import com.lms.paymentmanagement.api.OrderCustomAmountAppliedEvent;
import com.lms.paymentmanagement.api.PaymentRefundedEvent;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Plain Mockito unit test directly against {@link AuditLogEventListener} -
 * no Spring context, mirrors {@code
 * NotificationDispatchServiceTenantContextSymmetryTest}'s established style.
 *
 * <p><b>Idempotency note (plan §18):</b> this codebase has no {@code
 * @EnableAsync}/custom {@code ApplicationEventMulticaster} anywhere
 * (verified by inspection - no such configuration exists in any {@code
 * config} package), so {@code ApplicationEventPublisher.publishEvent(...)}
 * is synchronous, same-thread, and non-persisted. An event published this
 * way can never be redelivered by the framework itself (there is no queue,
 * broker, or retry mechanism sitting between publisher and listener), so no
 * duplicate-delivery/idempotency test is required for any listener method
 * below - a "called exactly once" assertion per event completely
 * characterizes the behavior.
 */
@ExtendWith(MockitoExtension.class)
class AuditLogEventListenerTest {

	@Mock
	private AuditLogApi auditLogApi;

	@Test
	void onCoursePriceChangedRecordsExactlyOneEntryWithExpectedFields() {
		AuditLogEventListener listener = new AuditLogEventListener(auditLogApi);
		UUID tenantId = UUID.randomUUID();
		UUID courseId = UUID.randomUUID();
		UUID changedBy = UUID.randomUUID();
		CoursePriceChangedEvent event = new CoursePriceChangedEvent(tenantId, courseId, changedBy,
				new BigDecimal("10.00"), new BigDecimal("20.00"), Instant.now());

		listener.onCoursePriceChanged(event);

		ArgumentCaptor<AuditLogEntry> captor = ArgumentCaptor.forClass(AuditLogEntry.class);
		verify(auditLogApi).record(captor.capture());
		verifyNoMoreInteractions(auditLogApi);
		AuditLogEntry entry = captor.getValue();
		assertThat(entry.actorId()).isEqualTo(changedBy);
		assertThat(entry.action()).isEqualTo("course.price_changed");
		assertThat(entry.targetEntity()).isEqualTo("course");
		assertThat(entry.targetId()).isEqualTo(courseId);
		assertThat(entry.reason()).isNull();
		assertThat(entry.metadata()).containsOnlyKeys("previousPrice", "newPrice");
		assertThat(entry.metadata()).containsEntry("previousPrice", new BigDecimal("10.00"));
		assertThat(entry.metadata()).containsEntry("newPrice", new BigDecimal("20.00"));
		// The event's own tenantId must never leak into the captured entry -
		// AuditLogEntry has no tenant field at all, so asserting the entry's
		// full field set (above) already proves this; explicitly assert the
		// raw tenantId value doesn't appear anywhere in the entry's toString
		// as a defense-in-depth sanity check too.
		assertThat(entry.toString()).doesNotContain(tenantId.toString());
	}

	@Test
	void onMaterialDeletedRecordsExactlyOneEntryWithExpectedFields() {
		AuditLogEventListener listener = new AuditLogEventListener(auditLogApi);
		UUID tenantId = UUID.randomUUID();
		UUID materialId = UUID.randomUUID();
		UUID lessonId = UUID.randomUUID();
		UUID moduleId = UUID.randomUUID();
		UUID courseId = UUID.randomUUID();
		UUID uploadedBy = UUID.randomUUID();
		UUID deletedBy = UUID.randomUUID();
		MaterialDeletedEvent event = new MaterialDeletedEvent(tenantId, materialId, lessonId, moduleId, courseId,
				"Lecture Notes", "application/pdf", "storage/key/1", uploadedBy, deletedBy, Instant.now());

		listener.onMaterialDeleted(event);

		ArgumentCaptor<AuditLogEntry> captor = ArgumentCaptor.forClass(AuditLogEntry.class);
		verify(auditLogApi).record(captor.capture());
		verifyNoMoreInteractions(auditLogApi);
		AuditLogEntry entry = captor.getValue();
		assertThat(entry.actorId()).isEqualTo(deletedBy);
		assertThat(entry.action()).isEqualTo("material.deleted");
		assertThat(entry.targetEntity()).isEqualTo("material");
		assertThat(entry.targetId()).isEqualTo(materialId);
		assertThat(entry.reason()).isNull();
		assertThat(entry.metadata()).containsOnlyKeys("title", "courseId");
		assertThat(entry.metadata()).containsEntry("title", "Lecture Notes");
		assertThat(entry.metadata()).containsEntry("courseId", courseId);
		assertThat(entry.toString()).doesNotContain(tenantId.toString());
	}

	@Test
	void onPaymentRefundedRecordsExactlyOneEntryWithExpectedFields() {
		AuditLogEventListener listener = new AuditLogEventListener(auditLogApi);
		UUID tenantId = UUID.randomUUID();
		UUID paymentId = UUID.randomUUID();
		UUID refundId = UUID.randomUUID();
		UUID actorUserId = UUID.randomUUID();
		UUID studentId = UUID.randomUUID();
		PaymentRefundedEvent event = new PaymentRefundedEvent(tenantId, paymentId, refundId, actorUserId,
				new BigDecimal("15.00"), "Student requested a partial refund", Instant.now(), studentId);

		listener.onPaymentRefunded(event);

		ArgumentCaptor<AuditLogEntry> captor = ArgumentCaptor.forClass(AuditLogEntry.class);
		verify(auditLogApi).record(captor.capture());
		verifyNoMoreInteractions(auditLogApi);
		AuditLogEntry entry = captor.getValue();
		assertThat(entry.actorId()).isEqualTo(actorUserId);
		assertThat(entry.action()).isEqualTo("payment.refunded");
		assertThat(entry.targetEntity()).isEqualTo("payment_refund");
		assertThat(entry.targetId()).isEqualTo(refundId);
		assertThat(entry.reason()).isEqualTo("Student requested a partial refund");
		assertThat(entry.metadata()).containsOnlyKeys("paymentId", "amount");
		assertThat(entry.metadata()).containsEntry("paymentId", paymentId);
		assertThat(entry.metadata()).containsEntry("amount", new BigDecimal("15.00"));
		assertThat(entry.toString()).doesNotContain(tenantId.toString());
	}

	// ------------------------------------------------------------------
	// Wave 2 (course/class billing foundation).
	// ------------------------------------------------------------------

	/**
	 * Regression test for the bug this module's own review found: {@code
	 * sessionRate == null} (a course NOT priced {@code SESSION}) must not
	 * reach {@link AuditLogEntry}'s constructor as a null-valued map entry -
	 * {@code Map.copyOf} throws {@link NullPointerException} on one, even
	 * though a wholly-null {@code metadata} map is accepted fine. Found via
	 * {@code CourseBillingAndLifecycleIntegrationTest} surfacing a real
	 * {@code 500} where a {@code 200} was expected.
	 */
	@Test
	void onCourseBillingConfigurationChangedWithANullSessionRateOmitsItFromMetadataRatherThanThrowing() {
		AuditLogEventListener listener = new AuditLogEventListener(auditLogApi);
		UUID courseId = UUID.randomUUID();
		UUID changedBy = UUID.randomUUID();
		CourseBillingConfigurationChangedEvent event = new CourseBillingConfigurationChangedEvent(UUID.randomUUID(),
				courseId, changedBy, null, "USD", false, true, Instant.now());

		listener.onCourseBillingConfigurationChanged(event);

		ArgumentCaptor<AuditLogEntry> captor = ArgumentCaptor.forClass(AuditLogEntry.class);
		verify(auditLogApi).record(captor.capture());
		AuditLogEntry entry = captor.getValue();
		assertThat(entry.action()).isEqualTo("course.billing_configuration_changed");
		assertThat(entry.metadata()).doesNotContainKey("sessionRate");
		assertThat(entry.metadata()).containsEntry("currency", "USD");
	}

	@Test
	void onCourseBillingConfigurationChangedWithANonNullSessionRateIncludesIt() {
		AuditLogEventListener listener = new AuditLogEventListener(auditLogApi);
		CourseBillingConfigurationChangedEvent event = new CourseBillingConfigurationChangedEvent(UUID.randomUUID(),
				UUID.randomUUID(), UUID.randomUUID(), new BigDecimal("12.00"), "USD", false, true, Instant.now());

		listener.onCourseBillingConfigurationChanged(event);

		ArgumentCaptor<AuditLogEntry> captor = ArgumentCaptor.forClass(AuditLogEntry.class);
		verify(auditLogApi).record(captor.capture());
		assertThat(captor.getValue().metadata()).containsEntry("sessionRate", new BigDecimal("12.00"));
	}

	/** Mirrors the {@code sessionRate} regression above, for {@code previousAmount} on the first-ever period added. */
	@Test
	void onCourseBillingPeriodAddedWithNoPriorPeriodOmitsPreviousAmountRatherThanThrowing() {
		AuditLogEventListener listener = new AuditLogEventListener(auditLogApi);
		CourseBillingPeriodAddedEvent event = new CourseBillingPeriodAddedEvent(UUID.randomUUID(), UUID.randomUUID(),
				UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), null, new BigDecimal("30.00"), "USD",
				Instant.now(), Instant.now());

		listener.onCourseBillingPeriodAdded(event);

		ArgumentCaptor<AuditLogEntry> captor = ArgumentCaptor.forClass(AuditLogEntry.class);
		verify(auditLogApi).record(captor.capture());
		AuditLogEntry entry = captor.getValue();
		assertThat(entry.action()).isEqualTo("course.billing_period_added");
		assertThat(entry.metadata()).doesNotContainKey("previousAmount");
		assertThat(entry.metadata()).containsEntry("newAmount", new BigDecimal("30.00"));
	}

	@Test
	void onCourseArchiveStateChangedUsesTheArchivedOrUnarchivedActionName() {
		AuditLogEventListener listener = new AuditLogEventListener(auditLogApi);
		UUID courseId = UUID.randomUUID();
		UUID actorId = UUID.randomUUID();

		listener.onCourseArchiveStateChanged(
				new CourseArchiveStateChangedEvent(UUID.randomUUID(), courseId, actorId, true, Instant.now()));
		listener.onCourseArchiveStateChanged(
				new CourseArchiveStateChangedEvent(UUID.randomUUID(), courseId, actorId, false, Instant.now()));

		ArgumentCaptor<AuditLogEntry> captor = ArgumentCaptor.forClass(AuditLogEntry.class);
		verify(auditLogApi, org.mockito.Mockito.times(2)).record(captor.capture());
		assertThat(captor.getAllValues()).extracting(AuditLogEntry::action)
			.containsExactly("course.archived", "course.unarchived");
	}

	/**
	 * Fix 3 (Phase E architecture review, ADR-015): {@code cloneCourse}
	 * previously published no event at all, so this listener never ran for
	 * it - this proves the new wiring records exactly one entry, keyed on the
	 * NEW clone's id (never the source's), with the source id traceable via
	 * metadata.
	 */
	@Test
	void onCourseClonedRecordsExactlyOneEntryKeyedOnTheNewCloneIdWithTheSourceIdInMetadata() {
		AuditLogEventListener listener = new AuditLogEventListener(auditLogApi);
		UUID sourceCourseId = UUID.randomUUID();
		UUID newCourseId = UUID.randomUUID();
		UUID actorId = UUID.randomUUID();
		CourseClonedEvent event = new CourseClonedEvent(UUID.randomUUID(), sourceCourseId, newCourseId, actorId,
				Instant.now());

		listener.onCourseCloned(event);

		ArgumentCaptor<AuditLogEntry> captor = ArgumentCaptor.forClass(AuditLogEntry.class);
		verify(auditLogApi).record(captor.capture());
		verifyNoMoreInteractions(auditLogApi);
		AuditLogEntry entry = captor.getValue();
		assertThat(entry.actorId()).isEqualTo(actorId);
		assertThat(entry.action()).isEqualTo("course.cloned");
		assertThat(entry.targetEntity()).isEqualTo("course");
		assertThat(entry.targetId()).isEqualTo(newCourseId);
		assertThat(entry.reason()).isNull();
		assertThat(entry.metadata()).containsOnlyKeys("sourceCourseId");
		assertThat(entry.metadata()).containsEntry("sourceCourseId", sourceCourseId);
	}

	@Test
	void onCoursePricingModelChangedRecordsPreviousAndNewValues() {
		AuditLogEventListener listener = new AuditLogEventListener(auditLogApi);
		UUID courseId = UUID.randomUUID();
		UUID changedBy = UUID.randomUUID();

		listener.onCoursePricingModelChanged(new CoursePricingModelChangedEvent(UUID.randomUUID(), courseId,
				changedBy, CoursePricingModel.ONE_TIME, CoursePricingModel.SESSION, Instant.now()));

		ArgumentCaptor<AuditLogEntry> captor = ArgumentCaptor.forClass(AuditLogEntry.class);
		verify(auditLogApi).record(captor.capture());
		AuditLogEntry entry = captor.getValue();
		assertThat(entry.actorId()).isEqualTo(changedBy);
		assertThat(entry.metadata()).containsEntry("previousPricingModel", CoursePricingModel.ONE_TIME);
		assertThat(entry.metadata()).containsEntry("newPricingModel", CoursePricingModel.SESSION);
	}

	@Test
	void onOrderCustomAmountAppliedRecordsExactlyOneEntryWithExpectedFields() {
		AuditLogEventListener listener = new AuditLogEventListener(auditLogApi);
		UUID orderId = UUID.randomUUID();
		UUID courseId = UUID.randomUUID();
		UUID appliedBy = UUID.randomUUID();

		listener.onOrderCustomAmountApplied(new OrderCustomAmountAppliedEvent(UUID.randomUUID(), orderId, courseId,
				appliedBy, new BigDecimal("75.00"), "USD", Instant.now()));

		ArgumentCaptor<AuditLogEntry> captor = ArgumentCaptor.forClass(AuditLogEntry.class);
		verify(auditLogApi).record(captor.capture());
		AuditLogEntry entry = captor.getValue();
		assertThat(entry.actorId()).isEqualTo(appliedBy);
		assertThat(entry.action()).isEqualTo("order.custom_amount_applied");
		assertThat(entry.targetEntity()).isEqualTo("student_order");
		assertThat(entry.targetId()).isEqualTo(orderId);
		assertThat(entry.metadata()).containsEntry("amount", new BigDecimal("75.00"));
	}

	/**
	 * Documents plan §21 decision 2's gap as intentional (see {@link
	 * AuditLogEventListener}'s class javadoc) - this reflection-based
	 * assertion fails loudly if someone adds a listener for either webhook
	 * event without a deliberate review, since there is no legitimate
	 * authenticated actor behind a gateway webhook callback.
	 */
	@Test
	void declaresNoEventListenerForEitherPaymentWebhookEvent() {
		var listenerMethods = java.util.Arrays.stream(AuditLogEventListener.class.getDeclaredMethods())
			.filter(method -> method.isAnnotationPresent(org.springframework.context.event.EventListener.class))
			.toList();
		assertThat(listenerMethods).isNotEmpty();

		var listenedParameterTypes = listenerMethods.stream()
			.flatMap(method -> java.util.Arrays.stream(method.getParameterTypes()))
			.toList();

		assertThat(listenedParameterTypes).doesNotContain(com.lms.paymentmanagement.api.PaymentConfirmedEvent.class,
				com.lms.paymentmanagement.api.PaymentRejectedEvent.class);
	}

}
