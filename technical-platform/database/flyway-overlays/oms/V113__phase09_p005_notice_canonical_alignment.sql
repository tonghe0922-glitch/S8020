-- PHASE-09 / P005: align published-notice immutability with the canonical collaboration.notice schema.
-- V10 stores attachments/dynamic recipient scope outside the notice master row; V112 introduced P005 workflow fields.
-- Do not mutate V112 after it has been applied. Replace only the trigger function with fields that really exist.
SET ROLE sjg_owner;

CREATE OR REPLACE FUNCTION collaboration.guard_p005_published_notice_immutable()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF OLD.employee_event_type = 'P005_POLICY_NOTICE' AND OLD.published_at IS NOT NULL AND (
        NEW.employee_event_type IS DISTINCT FROM OLD.employee_event_type OR
        NEW.policy_code IS DISTINCT FROM OLD.policy_code OR
        NEW.policy_version IS DISTINCT FROM OLD.policy_version OR
        NEW.published_at IS DISTINCT FROM OLD.published_at OR
        NEW.official_subject IS DISTINCT FROM OLD.official_subject OR
        NEW.official_type IS DISTINCT FROM OLD.official_type OR
        NEW.official_content IS DISTINCT FROM OLD.official_content OR
        NEW.period_or_course_no IS DISTINCT FROM OLD.period_or_course_no OR
        NEW.visibility_level IS DISTINCT FROM OLD.visibility_level OR
        NEW.venue_channel IS DISTINCT FROM OLD.venue_channel OR
        NEW.business_date IS DISTINCT FROM OLD.business_date OR
        NEW.owner_center_id IS DISTINCT FROM OLD.owner_center_id OR
        NEW.owner_employee_id IS DISTINCT FROM OLD.owner_employee_id OR
        NEW.target_center_id IS DISTINCT FROM OLD.target_center_id OR
        NEW.target_position_code IS DISTINCT FROM OLD.target_position_code OR
        NEW.planned_start_at IS DISTINCT FROM OLD.planned_start_at OR
        NEW.planned_finish_at IS DISTINCT FROM OLD.planned_finish_at OR
        NEW.understanding_pass_score IS DISTINCT FROM OLD.understanding_pass_score OR
        NEW.execution_due_at IS DISTINCT FROM OLD.execution_due_at
    ) THEN
        RAISE EXCEPTION 'P005 published notice content/audience is immutable; publish a new policy version' USING ERRCODE='55000';
    END IF;
    RETURN NEW;
END
$$;

RESET ROLE;
