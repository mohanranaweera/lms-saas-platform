package com.lms.liveclassmanagement.service;

import java.time.Instant;

/** Service-layer command for {@code PATCH /class-sessions/{id}} - legal only while {@code SCHEDULED}. */
public record EditSessionCommand(String title, String description, Instant scheduledStart, Instant scheduledEnd) {

}
