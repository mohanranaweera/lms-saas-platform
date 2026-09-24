package com.lms.integrationmanagement.storage;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

/**
 * The exact negation of {@link ObjectStorageConfiguredCondition}, expressed
 * as its own standalone {@link Condition} class (rather than making {@link
 * UnavailableObjectStorageApi}'s activation depend on {@link
 * S3ObjectStorageApi}'s bean NOT being registered) so activation does not
 * depend on bean registration order - exactly one of the two conditions ever
 * matches for a given environment (both delegate to {@link
 * ObjectStorageConfiguredCondition#isConfigured}, one negated), so exactly
 * one {@code ObjectStorageApi} bean is ever registered.
 */
final class ObjectStorageNotConfiguredCondition implements Condition {

	@Override
	public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
		return !ObjectStorageConfiguredCondition.isConfigured(context);
	}

}
