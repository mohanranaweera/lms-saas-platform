package com.lms.common.api;

/** One field-level validation failure. */
public record FieldError(String field, String message) {

}
