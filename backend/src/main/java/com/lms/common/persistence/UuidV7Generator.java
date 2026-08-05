package com.lms.common.persistence;

import java.security.SecureRandom;
import java.util.EnumSet;
import java.util.UUID;
import org.hibernate.engine.spi.SharedSessionContractImplementor;
import org.hibernate.generator.BeforeExecutionGenerator;
import org.hibernate.generator.EventType;

/**
 * Generates RFC 9562 UUID version 7 identifiers: a 48-bit millisecond
 * timestamp followed by random bits. Time-ordered UUIDs keep primary-key
 * index locality under high insert volume, unlike random (v4) UUIDs.
 */
public class UuidV7Generator implements BeforeExecutionGenerator {

	private static final SecureRandom RANDOM = new SecureRandom();

	public static UUID generate() {
		long timestamp = System.currentTimeMillis();
		byte[] randomBytes = new byte[10];
		RANDOM.nextBytes(randomBytes);

		long mostSigBits = (timestamp & 0xFFFFFFFFFFFFL) << 16;
		mostSigBits |= 0x7000L; // version 7
		mostSigBits |= ((randomBytes[0] & 0x0F) << 8);
		mostSigBits |= (randomBytes[1] & 0xFF);

		long leastSigBits = 0x8000000000000000L; // variant 10 (RFC 4122/9562)
		leastSigBits |= ((long) (randomBytes[2] & 0x3F)) << 56;
		for (int i = 3; i < 10; i++) {
			leastSigBits |= ((long) (randomBytes[i] & 0xFF)) << (8 * (9 - i));
		}
		return new UUID(mostSigBits, leastSigBits);
	}

	@Override
	public Object generate(SharedSessionContractImplementor session, Object owner, Object currentValue,
			EventType eventType) {
		return generate();
	}

	@Override
	public EnumSet<EventType> getEventTypes() {
		return EnumSet.of(EventType.INSERT);
	}

}
