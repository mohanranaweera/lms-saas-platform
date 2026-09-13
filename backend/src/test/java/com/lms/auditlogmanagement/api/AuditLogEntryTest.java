package com.lms.auditlogmanagement.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Plain unit test, no Spring context - {@link AuditLogEntry} is a pure
 * record with only a compact-constructor validation. Covers every branch of
 * that constructor, matching {@code AuditLogSpecificationsTest}'s "plain
 * unit test" style for a pure-code class in this module.
 */
class AuditLogEntryTest {

	private static final UUID ACTOR_ID = UUID.randomUUID();

	private static final UUID TARGET_ID = UUID.randomUUID();

	@Test
	void nullActorIdThrows() {
		assertThatThrownBy(
				() -> new AuditLogEntry(null, "course.price_changed", "course", TARGET_ID, null, null)).isInstanceOf(
				IllegalArgumentException.class).hasMessageContaining("actorId");
	}

	@Test
	void nullTargetIdThrows() {
		assertThatThrownBy(
				() -> new AuditLogEntry(ACTOR_ID, "course.price_changed", "course", null, null, null)).isInstanceOf(
				IllegalArgumentException.class).hasMessageContaining("targetId");
	}

	@Test
	void nullActionThrows() {
		assertThatThrownBy(() -> new AuditLogEntry(ACTOR_ID, null, "course", TARGET_ID, null, null)).isInstanceOf(
				IllegalArgumentException.class).hasMessageContaining("action");
	}

	@Test
	void blankActionThrows() {
		assertThatThrownBy(() -> new AuditLogEntry(ACTOR_ID, "   ", "course", TARGET_ID, null, null)).isInstanceOf(
				IllegalArgumentException.class).hasMessageContaining("action");
	}

	@Test
	void nullTargetEntityThrows() {
		assertThatThrownBy(
				() -> new AuditLogEntry(ACTOR_ID, "course.price_changed", null, TARGET_ID, null, null)).isInstanceOf(
				IllegalArgumentException.class).hasMessageContaining("targetEntity");
	}

	@Test
	void blankTargetEntityThrows() {
		assertThatThrownBy(
				() -> new AuditLogEntry(ACTOR_ID, "course.price_changed", "   ", TARGET_ID, null, null)).isInstanceOf(
				IllegalArgumentException.class).hasMessageContaining("targetEntity");
	}

	@Test
	void blankReasonThrows() {
		assertThatThrownBy(
				() -> new AuditLogEntry(ACTOR_ID, "course.price_changed", "course", TARGET_ID, "   ", null))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("reason");
	}

	@Test
	void nullReasonIsAllowed() {
		AuditLogEntry entry = new AuditLogEntry(ACTOR_ID, "course.price_changed", "course", TARGET_ID, null, null);

		assertThat(entry.reason()).isNull();
	}

	@Test
	void nonBlankReasonIsAllowed() {
		AuditLogEntry entry = new AuditLogEntry(ACTOR_ID, "payment.refunded", "payment_refund", TARGET_ID,
				"Student requested a partial refund", null);

		assertThat(entry.reason()).isEqualTo("Student requested a partial refund");
	}

	@Test
	void nullMetadataIsAllowed() {
		AuditLogEntry entry = new AuditLogEntry(ACTOR_ID, "course.price_changed", "course", TARGET_ID, null, null);

		assertThat(entry.metadata()).isNull();
	}

	@Test
	void metadataIsDefensivelyCopiedSoMutatingTheOriginalMapAfterConstructionDoesNotAffectTheEntry() {
		Map<String, Object> original = new HashMap<>();
		original.put("previousPrice", "10.00");
		AuditLogEntry entry = new AuditLogEntry(ACTOR_ID, "course.price_changed", "course", TARGET_ID, null,
				original);

		original.put("newPrice", "20.00");
		original.remove("previousPrice");

		assertThat(entry.metadata()).containsOnlyKeys("previousPrice");
		assertThat(entry.metadata()).containsEntry("previousPrice", "10.00");
	}

	@Test
	void metadataReturnedIsImmutable() {
		AuditLogEntry entry = new AuditLogEntry(ACTOR_ID, "course.price_changed", "course", TARGET_ID, null,
				Map.of("previousPrice", "10.00"));

		assertThatThrownBy(() -> entry.metadata().put("newPrice", "20.00")).isInstanceOf(
				UnsupportedOperationException.class);
	}

	@Test
	void ofFactoryProducesAnEntryWithNullReasonAndMetadata() {
		AuditLogEntry entry = AuditLogEntry.of(ACTOR_ID, "course.price_changed", "course", TARGET_ID);

		assertThat(entry.actorId()).isEqualTo(ACTOR_ID);
		assertThat(entry.action()).isEqualTo("course.price_changed");
		assertThat(entry.targetEntity()).isEqualTo("course");
		assertThat(entry.targetId()).isEqualTo(TARGET_ID);
		assertThat(entry.reason()).isNull();
		assertThat(entry.metadata()).isNull();
	}

}
