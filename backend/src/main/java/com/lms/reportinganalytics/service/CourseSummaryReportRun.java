package com.lms.reportinganalytics.service;

import java.util.UUID;

/** Redis value wrapper for one complete owner-bound Course Summary snapshot. */
public record CourseSummaryReportRun(UUID runId, CourseSummaryReport report) {

}
