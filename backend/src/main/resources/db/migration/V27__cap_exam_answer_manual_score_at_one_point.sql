-- exam-management (MVP-017 follow-up review): DB-level backstop for the
-- "manual marking score has no upper bound" grading-integrity gap found
-- during review. This schema has no per-question "max points" concept -
-- every question (MCQ or STRUCTURED) is worth a fixed one point
-- (ResultsPublishingService.POINTS_PER_QUESTION), so a marker-supplied
-- manual_score above 1 would let a single structured answer silently
-- outscore its own question in the published result's maxScore
-- computation. The service layer now also rejects this (400,
-- MarkAnswerRequest's @DecimalMax) - this CHECK is a belt-and-suspenders
-- backstop, mirroring V26's own "prefer schema-enforced invariants where
-- cheaply expressible" posture for this module.
--
-- Purely additive: adds one CHECK constraint to the already-shipped
-- exam_answer table (V26). No existing row can violate it today (V26's
-- ck_exam_answer_manual_score_nonnegative already excludes negative
-- values, and no manual_score above 1 has been recorded through the
-- application, since MarkingQueueService is the only write path).

ALTER TABLE exam_answer
    ADD CONSTRAINT ck_exam_answer_manual_score_at_most_one_point
        CHECK (manual_score IS NULL OR manual_score <= 1);
