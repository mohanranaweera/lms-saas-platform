package com.lms.liveclassmanagement.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Pure unit coverage for {@link ClassSession}'s two independent state machines (Wave 4 plan §8). */
class ClassSessionTest {

	private static ClassSession newScheduledSession() {
		Instant start = Instant.now().plus(1, ChronoUnit.DAYS);
		Instant end = start.plus(1, ChronoUnit.HOURS);
		return new ClassSession(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), null, "Algebra Live",
				"description", start, end, MeetingProvider.ZOOM);
	}

	// ------------------------------------------------------------------
	// ClassSessionStatus transitions.
	// ------------------------------------------------------------------

	@Test
	void newSessionStartsScheduledAndPending() {
		ClassSession session = newScheduledSession();
		assertThat(session.getStatus()).isEqualTo(ClassSessionStatus.SCHEDULED);
		assertThat(session.getProviderStatus()).isEqualTo(ClassSessionProviderStatus.PENDING);
	}

	@Test
	void startTransitionsScheduledToLive() {
		ClassSession session = newScheduledSession();
		session.start();
		assertThat(session.getStatus()).isEqualTo(ClassSessionStatus.LIVE);
	}

	@Test
	void startFailsWhenNotScheduled() {
		ClassSession session = newScheduledSession();
		session.start();
		assertThatThrownBy(session::start).isInstanceOf(IllegalStateException.class);
	}

	@Test
	void completeTransitionsLiveToCompleted() {
		ClassSession session = newScheduledSession();
		session.start();
		session.complete();
		assertThat(session.getStatus()).isEqualTo(ClassSessionStatus.COMPLETED);
	}

	@Test
	void completeFailsWhenNotLive() {
		ClassSession session = newScheduledSession();
		assertThatThrownBy(session::complete).isInstanceOf(IllegalStateException.class);
	}

	@Test
	void cancelIsLegalFromScheduled() {
		ClassSession session = newScheduledSession();
		session.cancel();
		assertThat(session.getStatus()).isEqualTo(ClassSessionStatus.CANCELLED);
	}

	@Test
	void cancelIsLegalFromLive() {
		ClassSession session = newScheduledSession();
		session.start();
		session.cancel();
		assertThat(session.getStatus()).isEqualTo(ClassSessionStatus.CANCELLED);
	}

	@Test
	void cancelFailsWhenAlreadyCompleted() {
		ClassSession session = newScheduledSession();
		session.start();
		session.complete();
		assertThatThrownBy(session::cancel).isInstanceOf(IllegalStateException.class);
	}

	@Test
	void cancelFailsWhenAlreadyCancelled() {
		ClassSession session = newScheduledSession();
		session.cancel();
		assertThatThrownBy(session::cancel).isInstanceOf(IllegalStateException.class);
	}

	@Test
	void rescheduleIsLegalOnlyWhileScheduled() {
		ClassSession session = newScheduledSession();
		Instant newStart = Instant.now().plus(2, ChronoUnit.DAYS);
		Instant newEnd = newStart.plus(1, ChronoUnit.HOURS);

		session.reschedule("New title", "New description", newStart, newEnd);

		assertThat(session.getTitle()).isEqualTo("New title");
		assertThat(session.getScheduledStart()).isEqualTo(newStart);

		session.start();
		assertThatThrownBy(() -> session.reschedule("x", "y", newStart, newEnd))
			.isInstanceOf(IllegalStateException.class);
	}

	// ------------------------------------------------------------------
	// Provisioning-outcome methods - independent of ClassSessionStatus.
	// ------------------------------------------------------------------

	@Test
	void markProvisionedSetsProvisionedAndClearsFailureReason() {
		ClassSession session = newScheduledSession();
		session.markProvisioningFailed("boom");
		session.markProvisioned("FAKE-ZOOM-abc");
		assertThat(session.getProviderStatus()).isEqualTo(ClassSessionProviderStatus.PROVISIONED);
		assertThat(session.getProviderReference()).isEqualTo("FAKE-ZOOM-abc");
		assertThat(session.getProviderFailureReason()).isNull();
	}

	@Test
	void markProvisioningFailedSetsFailedAndReason() {
		ClassSession session = newScheduledSession();
		session.markProvisioningFailed("Provider unreachable");
		assertThat(session.getProviderStatus()).isEqualTo(ClassSessionProviderStatus.FAILED);
		assertThat(session.getProviderFailureReason()).isEqualTo("Provider unreachable");
	}

	@Test
	void isRetryableTrueForPendingAndFailedFalseForProvisioned() {
		ClassSession session = newScheduledSession();
		assertThat(session.isRetryable()).isTrue(); // PENDING
		session.markProvisioningFailed("boom");
		assertThat(session.isRetryable()).isTrue(); // FAILED
		session.markProvisioned("FAKE-ZOOM-abc");
		assertThat(session.isRetryable()).isFalse(); // PROVISIONED
	}

	@Test
	void provisioningOutcomeIsIndependentOfLifecycleStatus() {
		ClassSession session = newScheduledSession();
		session.start(); // LIVE
		session.markProvisioningFailed("boom"); // still allowed while LIVE
		assertThat(session.getStatus()).isEqualTo(ClassSessionStatus.LIVE);
		assertThat(session.getProviderStatus()).isEqualTo(ClassSessionProviderStatus.FAILED);
	}

}
