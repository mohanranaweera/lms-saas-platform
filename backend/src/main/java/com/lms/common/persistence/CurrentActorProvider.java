package com.lms.common.persistence;

import java.util.Optional;
import java.util.UUID;

/**
 * SPI for supplying the current request's authenticated actor id to
 * {@link AuditorAwareImpl}, without the shared kernel depending on any
 * business/domain module (per {@code .claude/rules/architecture.md} -
 * {@code com.lms.common} must never import {@code identity-access-service}).
 * A domain module registers an implementation as a bean; {@link
 * AuditorAwareImpl} looks it up optionally and falls back to {@link
 * Optional#empty()} when none is present (e.g. an unauthenticated/system
 * write, or a test slice that doesn't load the identity-access-service
 * module).
 */
public interface CurrentActorProvider {

	Optional<UUID> currentActorId();

}
