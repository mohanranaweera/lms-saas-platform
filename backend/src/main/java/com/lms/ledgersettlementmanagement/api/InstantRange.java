package com.lms.ledgersettlementmanagement.api;

import java.time.Instant;
import java.util.Objects;

/** RPT-01: a half-open instant window {@code [fromInclusive, toExclusive)}. */
public record InstantRange(Instant fromInclusive, Instant toExclusive) {

	public InstantRange {
		Objects.requireNonNull(fromInclusive, "fromInclusive");
		Objects.requireNonNull(toExclusive, "toExclusive");
		if (!fromInclusive.isBefore(toExclusive)) {
			throw new IllegalArgumentException("fromInclusive must be before toExclusive");
		}
	}

	public boolean contains(Instant instant) {
		return instant != null && !instant.isBefore(fromInclusive) && instant.isBefore(toExclusive);
	}

}
