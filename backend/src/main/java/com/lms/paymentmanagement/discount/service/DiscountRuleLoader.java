package com.lms.paymentmanagement.discount.service;

import com.lms.paymentmanagement.discount.domain.DiscountCourseRole;
import com.lms.paymentmanagement.discount.domain.DiscountRule;
import com.lms.paymentmanagement.discount.domain.DiscountRuleVersion;
import com.lms.paymentmanagement.discount.domain.DiscountRuleVersionCourse;
import com.lms.paymentmanagement.discount.repository.DiscountRuleVersionCourseRepository;
import com.lms.paymentmanagement.discount.repository.DiscountRuleVersionRepository;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * Loads rules' current versions and course lists in three tenant-scoped
 * batched reads (headers given, versions, courses) - never one read per rule.
 */
@Component
public class DiscountRuleLoader {

	/** A rule header with its current version and course lists (courses sorted by id for determinism). */
	public record LoadedRule(DiscountRule rule, DiscountRuleVersion version, List<UUID> requiredCourseIds,
			List<UUID> rewardCourseIds) {

		public RuleSnapshot snapshot() {
			DiscountRuleVersion v = version;
			return new RuleSnapshot(rule.getId(), v.getId(), v.getDiscountType(), v.getName(), v.getCode(),
					v.getSelectionMode(), v.getMinCount(), v.getValueType(), v.getValue(), v.getFreeMonths(),
					v.getValidFrom(), v.getValidTo(), v.getMaxUses(), v.getMinPurchaseAmount(), v.getCurrency(),
					requiredCourseIds, rewardCourseIds);
		}

	}

	private final DiscountRuleVersionRepository versionRepository;

	private final DiscountRuleVersionCourseRepository courseRepository;

	public DiscountRuleLoader(DiscountRuleVersionRepository versionRepository,
			DiscountRuleVersionCourseRepository courseRepository) {
		this.versionRepository = versionRepository;
		this.courseRepository = courseRepository;
	}

	/** Rules without a resolvable current version are skipped (cannot happen with V92's FKs). */
	public List<LoadedRule> load(List<DiscountRule> rules) {
		if (rules.isEmpty()) {
			return List.of();
		}
		Set<UUID> versionIds = rules.stream()
			.map(DiscountRule::getCurrentVersionId)
			.filter(java.util.Objects::nonNull)
			.collect(Collectors.toSet());
		Map<UUID, DiscountRuleVersion> versions = new HashMap<>();
		versionRepository.findAllByIdIn(versionIds).forEach(version -> versions.put(version.getId(), version));
		Map<UUID, List<DiscountRuleVersionCourse>> coursesByVersion = new HashMap<>();
		courseRepository.findAllByVersionIdIn(versionIds)
			.forEach(course -> coursesByVersion.computeIfAbsent(course.getVersionId(), id -> new ArrayList<>())
				.add(course));
		Map<UUID, LoadedRule> loaded = new LinkedHashMap<>();
		for (DiscountRule rule : rules) {
			DiscountRuleVersion version = versions.get(rule.getCurrentVersionId());
			if (version == null) {
				continue;
			}
			List<DiscountRuleVersionCourse> courses = coursesByVersion.getOrDefault(version.getId(), List.of());
			loaded.put(rule.getId(), new LoadedRule(rule, version, courseIds(courses, DiscountCourseRole.REQUIRED),
					courseIds(courses, DiscountCourseRole.REWARD)));
		}
		return List.copyOf(loaded.values());
	}

	private static List<UUID> courseIds(List<DiscountRuleVersionCourse> courses, DiscountCourseRole role) {
		return courses.stream()
			.filter(course -> course.getRole() == role)
			.map(DiscountRuleVersionCourse::getCourseId)
			.sorted(Comparator.comparing(UUID::toString))
			.toList();
	}

}
