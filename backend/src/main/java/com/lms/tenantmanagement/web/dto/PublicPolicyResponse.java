package com.lms.tenantmanagement.web.dto;

/**
 * One published institute policy (LOGIN-01 Phase B). {@code kind} is
 * {@code terms}, {@code refund} or {@code privacy}; {@code text} is plain text,
 * or {@code null} when the institute hasn't published it.
 */
public record PublicPolicyResponse(String kind, String text) {

}
