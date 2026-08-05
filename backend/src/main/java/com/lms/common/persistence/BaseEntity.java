package com.lms.common.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Id;
import jakarta.persistence.MappedSuperclass;
import java.util.UUID;

/**
 * Base type for every JPA entity on the platform. Provides a single,
 * immutable identifier strategy (UUIDv7, generated application-side) so no
 * entity hand-rolls its own ID generation.
 */
@MappedSuperclass
public abstract class BaseEntity {

	@Id
	@UuidV7
	@Column(name = "id", updatable = false, nullable = false)
	private UUID id;

	public UUID getId() {
		return id;
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) {
			return true;
		}
		if (!(o instanceof BaseEntity other)) {
			return false;
		}
		return id != null && id.equals(other.id);
	}

	@Override
	public int hashCode() {
		return getClass().hashCode();
	}

}
