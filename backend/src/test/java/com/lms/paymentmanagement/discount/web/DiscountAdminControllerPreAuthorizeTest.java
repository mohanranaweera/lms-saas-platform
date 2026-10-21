package com.lms.paymentmanagement.discount.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;

/**
 * DISC-01 (owner decision 2026-10-21): every admin discount endpoint carries a
 * controller-level {@code @PreAuthorize} on the DISCOUNTS area - VIEW for
 * reads, CREATE_EDIT for writes - as a second layer on top of {@code
 * DiscountAdminService}'s own check (the HTTP-level 403s are proven by the
 * integration tests; this pins the annotation itself, which a 403 alone cannot
 * distinguish from the service check).
 */
class DiscountAdminControllerPreAuthorizeTest {

	private static final String VIEW = "@permissionCheckService.hasPermission('DISCOUNTS', 'VIEW')";

	private static final String EDIT = "@permissionCheckService.hasPermission('DISCOUNTS', 'CREATE_EDIT')";

	@Test
	void everyEndpointIsGatedOnTheDiscountsArea() {
		Map<String, String> expressions = new TreeMap<>();
		List<Method> endpoints = Arrays.stream(DiscountAdminController.class.getDeclaredMethods())
			.filter(method -> method.isAnnotationPresent(GetMapping.class)
					|| method.isAnnotationPresent(PostMapping.class) || method.isAnnotationPresent(PutMapping.class)
					|| method.isAnnotationPresent(PatchMapping.class)
					|| method.isAnnotationPresent(DeleteMapping.class))
			.toList();
		assertThat(endpoints).hasSize(7);
		for (Method method : endpoints) {
			PreAuthorize preAuthorize = method.getAnnotation(PreAuthorize.class);
			assertThat(preAuthorize).as(method.getName()).isNotNull();
			expressions.put(method.getName(), preAuthorize.value());
			String expected = method.isAnnotationPresent(GetMapping.class) ? VIEW : EDIT;
			assertThat(preAuthorize.value()).as(method.getName()).isEqualTo(expected);
		}
		assertThat(expressions).containsEntry("list", VIEW)
			.containsEntry("get", VIEW)
			.containsEntry("courseOptions", VIEW)
			.containsEntry("create", EDIT)
			.containsEntry("update", EDIT)
			.containsEntry("setActive", EDIT)
			.containsEntry("archive", EDIT);
	}

}
