package com.lms.exammanagement.web.dto;

/** {@code POST /api/v1/exams/{id}/notify-marks} body (optional): {@code resend} confirms a second send. */
public record NotifyMarksRequest(Boolean resend) {

}
