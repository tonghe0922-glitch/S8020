-- PHASE-09 / P005: source-backed policy/notice version, audience, receipt and execution lifecycle.
-- Source flow: S01 publish version -> S02 resolve org/position audience -> S03 deliver -> S04 employee read ->
-- S05 confirm/sign -> S06 understanding validation -> S07 execute -> S08 responsible acceptance ->
-- S09 overdue reminder/escalation -> S10 archive handoff -> END.
-- HTTP identifiers are PHASE-09 engineering contracts; the source snapshot explicitly contains no business HTTP records.
SET ROLE sjg_owner;

ALTER TABLE collaboration.notice
    ADD COLUMN IF NOT EXISTS policy_code VARCHAR(64),
    ADD COLUMN IF NOT EXISTS policy_version INTEGER,
    ADD COLUMN IF NOT EXISTS published_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS target_center_id UUID,
    ADD COLUMN IF NOT EXISTS target_position_code VARCHAR(50),
    ADD COLUMN IF NOT EXISTS understanding_pass_score INTEGER DEFAULT 80,
    ADD COLUMN IF NOT EXISTS execution_due_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS archived_at TIMESTAMPTZ;

CREATE UNIQUE INDEX IF NOT EXISTS uq_collaboration_notice_policy_version
    ON collaboration.notice(tenant_id, policy_code, policy_version)
    WHERE policy_code IS NOT NULL AND policy_version IS NOT NULL AND NOT is_deleted;

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname='ck_collaboration_notice_p005_policy_version') THEN
        ALTER TABLE collaboration.notice ADD CONSTRAINT ck_collaboration_notice_p005_policy_version
            CHECK (policy_version IS NULL OR policy_version > 0);
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname='ck_collaboration_notice_p005_pass_score') THEN
        ALTER TABLE collaboration.notice ADD CONSTRAINT ck_collaboration_notice_p005_pass_score
            CHECK (understanding_pass_score IS NULL OR understanding_pass_score BETWEEN 0 AND 100);
    END IF;
END
$$;

CREATE TABLE IF NOT EXISTS collaboration.notice_recipient (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL,
    notice_id UUID NOT NULL,
    employee_id UUID NOT NULL,
    identity_id UUID NOT NULL,
    org_id UUID NOT NULL,
    position_id UUID,
    position_code VARCHAR(50),
    delivery_status VARCHAR(20) NOT NULL DEFAULT 'QUEUED',
    delivered_at TIMESTAMPTZ,
    read_at TIMESTAMPTZ,
    confirmed_at TIMESTAMPTZ,
    understanding_score INTEGER,
    understanding_passed_at TIMESTAMPTZ,
    execution_summary TEXT,
    executed_at TIMESTAMPTZ,
    accepted_at TIMESTAMPTZ,
    accepted_by UUID,
    last_reminded_at TIMESTAMPTZ,
    escalation_count INTEGER NOT NULL DEFAULT 0,
    version_no INTEGER NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    is_deleted BOOLEAN NOT NULL DEFAULT FALSE,
    CONSTRAINT uq_collaboration_notice_recipient UNIQUE (tenant_id, notice_id, employee_id),
    CONSTRAINT fk_collaboration_notice_recipient_notice FOREIGN KEY (notice_id) REFERENCES collaboration.notice(id),
    CONSTRAINT ck_collaboration_notice_recipient_delivery CHECK (delivery_status IN ('QUEUED','DELIVERED')),
    CONSTRAINT ck_collaboration_notice_recipient_score CHECK (understanding_score IS NULL OR understanding_score BETWEEN 0 AND 100),
    CONSTRAINT ck_collaboration_notice_recipient_escalation CHECK (escalation_count >= 0),
    CONSTRAINT ck_collaboration_notice_read_before_confirm CHECK (confirmed_at IS NULL OR (read_at IS NOT NULL AND confirmed_at >= read_at)),
    CONSTRAINT ck_collaboration_notice_confirm_before_understanding CHECK (understanding_passed_at IS NULL OR (confirmed_at IS NOT NULL AND understanding_passed_at >= confirmed_at)),
    CONSTRAINT ck_collaboration_notice_understanding_before_execution CHECK (executed_at IS NULL OR (understanding_passed_at IS NOT NULL AND executed_at >= understanding_passed_at)),
    CONSTRAINT ck_collaboration_notice_execution_before_acceptance CHECK (accepted_at IS NULL OR (executed_at IS NOT NULL AND accepted_at >= executed_at))
);

CREATE INDEX IF NOT EXISTS ix_collaboration_notice_recipient_employee
    ON collaboration.notice_recipient(tenant_id, employee_id, created_at DESC)
    WHERE NOT is_deleted;
CREATE INDEX IF NOT EXISTS ix_collaboration_notice_recipient_stage
    ON collaboration.notice_recipient(tenant_id, notice_id, delivered_at, read_at, confirmed_at, understanding_passed_at, executed_at, accepted_at)
    WHERE NOT is_deleted;

CREATE TABLE IF NOT EXISTS collaboration.notice_receipt_event (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL,
    notice_id UUID NOT NULL,
    recipient_id UUID NOT NULL,
    employee_id UUID NOT NULL,
    actor_employee_id UUID NOT NULL,
    event_type VARCHAR(40) NOT NULL,
    evidence_json JSONB,
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT fk_collaboration_notice_receipt_event_notice FOREIGN KEY (notice_id) REFERENCES collaboration.notice(id),
    CONSTRAINT fk_collaboration_notice_receipt_event_recipient FOREIGN KEY (recipient_id) REFERENCES collaboration.notice_recipient(id),
    CONSTRAINT ck_collaboration_notice_receipt_event_type CHECK (event_type IN (
        'DELIVERED','READ','CONFIRMED','UNDERSTANDING_FAILED','UNDERSTANDING_PASSED','EXECUTED','ACCEPTED','REMINDER','ESCALATED'))
);

CREATE INDEX IF NOT EXISTS ix_collaboration_notice_receipt_event_timeline
    ON collaboration.notice_receipt_event(tenant_id, notice_id, employee_id, occurred_at, id);

ALTER TABLE collaboration.notice_recipient ENABLE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS p_tenant_collaboration_notice_recipient ON collaboration.notice_recipient;
CREATE POLICY p_tenant_collaboration_notice_recipient ON collaboration.notice_recipient
    USING (tenant_id = current_setting('app.tenant_id', true)::uuid)
    WITH CHECK (tenant_id = current_setting('app.tenant_id', true)::uuid);
ALTER TABLE collaboration.notice_receipt_event ENABLE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS p_tenant_collaboration_notice_receipt_event ON collaboration.notice_receipt_event;
CREATE POLICY p_tenant_collaboration_notice_receipt_event ON collaboration.notice_receipt_event
    USING (tenant_id = current_setting('app.tenant_id', true)::uuid)
    WITH CHECK (tenant_id = current_setting('app.tenant_id', true)::uuid);

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
        NEW.official_attachments IS DISTINCT FROM OLD.official_attachments OR
        NEW.recipient_scope IS DISTINCT FROM OLD.recipient_scope OR
        NEW.target_center_id IS DISTINCT FROM OLD.target_center_id OR
        NEW.target_position_code IS DISTINCT FROM OLD.target_position_code OR
        NEW.effective_start_at IS DISTINCT FROM OLD.effective_start_at OR
        NEW.effective_end_at IS DISTINCT FROM OLD.effective_end_at
    ) THEN
        RAISE EXCEPTION 'P005 published notice content/audience is immutable; publish a new policy version' USING ERRCODE='55000';
    END IF;
    RETURN NEW;
END
$$;
DROP TRIGGER IF EXISTS trg_p005_published_notice_immutable ON collaboration.notice;
CREATE TRIGGER trg_p005_published_notice_immutable
BEFORE UPDATE ON collaboration.notice
FOR EACH ROW EXECUTE FUNCTION collaboration.guard_p005_published_notice_immutable();

CREATE OR REPLACE FUNCTION collaboration.guard_p005_receipt_event_append_only()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'P005 receipt events are append-only' USING ERRCODE='55000';
END
$$;
DROP TRIGGER IF EXISTS trg_p005_receipt_event_append_only ON collaboration.notice_receipt_event;
CREATE TRIGGER trg_p005_receipt_event_append_only
BEFORE UPDATE OR DELETE ON collaboration.notice_receipt_event
FOR EACH ROW EXECUTE FUNCTION collaboration.guard_p005_receipt_event_append_only();

INSERT INTO core.sequence_rule(
    id,tenant_id,rule_code,prefix_template,date_pattern,current_value,step,created_at,updated_at,is_deleted)
SELECT gen_random_uuid(),'${sjg_tenant_id}'::uuid,'P005','P005-','yyyyMMdd',0,1,now(),now(),false
WHERE NOT EXISTS (
    SELECT 1 FROM core.sequence_rule
    WHERE tenant_id='${sjg_tenant_id}'::uuid AND rule_code='P005' AND NOT is_deleted
);

DO $$
DECLARE
    p_definition_id uuid;
    p_version_id uuid;
BEGIN
    SELECT d.id INTO p_definition_id
      FROM workflow.wf_definition d
     WHERE d.tenant_id='${sjg_tenant_id}'::uuid AND d.process_code='P005' AND d.enabled AND NOT d.is_deleted
     ORDER BY d.created_at,d.id LIMIT 1;

    IF p_definition_id IS NULL THEN
        p_definition_id := gen_random_uuid();
        INSERT INTO workflow.wf_definition(
            id,tenant_id,process_code,process_name,module_code,owner_schema,owner_table,enabled,created_at,updated_at,is_deleted)
        VALUES (p_definition_id,'${sjg_tenant_id}'::uuid,'P005','制度、通知与执行回执','全员公共能力',
                'collaboration','notice',true,now(),now(),false);
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM workflow.wf_version v
         WHERE v.tenant_id='${sjg_tenant_id}'::uuid AND v.definition_id=p_definition_id
           AND v.status='PUBLISHED' AND NOT v.is_deleted
    ) THEN
        p_version_id := gen_random_uuid();
        INSERT INTO workflow.wf_version(
            id,tenant_id,definition_id,version_no,status,effective_at,definition_json,checksum,created_at,updated_at,is_deleted)
        VALUES (
            p_version_id,'${sjg_tenant_id}'::uuid,p_definition_id,1,'DRAFT',NULL,
            jsonb_build_object(
                'processCode','P005','source','PHASE09_SOURCE_CONTRACT',
                'states',jsonb_build_array('S01','S02','S03','S04','S05','S06','S07','S08','S09','S10','END'),
                'guards',jsonb_build_array('server-resolved-audience','read-before-confirm','confirm-before-understanding','understanding-before-execution','published-version-immutable')),
            'phase09-p005-source-flow-v1',now(),now(),false
        );

        INSERT INTO workflow.wf_node(
            id,tenant_id,version_id,node_code,node_name,node_type,actor_rule,sort_no,created_at,updated_at,is_deleted)
        VALUES
          (gen_random_uuid(),'${sjg_tenant_id}'::uuid,p_version_id,'S01','制度/通知版本发布','START',NULL,10,now(),now(),false),
          (gen_random_uuid(),'${sjg_tenant_id}'::uuid,p_version_id,'S02','按组织岗位确定范围','TASK','{"resolver":"CONTEXT_EMPLOYEE_IDS","field":"managerCandidateIds"}'::jsonb,20,now(),now(),false),
          (gen_random_uuid(),'${sjg_tenant_id}'::uuid,p_version_id,'S03','消息送达','TASK','{"resolver":"CONTEXT_EMPLOYEE_IDS","field":"managerCandidateIds"}'::jsonb,30,now(),now(),false),
          (gen_random_uuid(),'${sjg_tenant_id}'::uuid,p_version_id,'S04','员工阅读','TASK','{"resolver":"CONTEXT_EMPLOYEE_IDS","field":"recipientEmployeeIds"}'::jsonb,40,now(),now(),false),
          (gen_random_uuid(),'${sjg_tenant_id}'::uuid,p_version_id,'S05','确认/阅签','TASK','{"resolver":"CONTEXT_EMPLOYEE_IDS","field":"recipientEmployeeIds"}'::jsonb,50,now(),now(),false),
          (gen_random_uuid(),'${sjg_tenant_id}'::uuid,p_version_id,'S06','考试或理解验证','TASK','{"resolver":"CONTEXT_EMPLOYEE_IDS","field":"recipientEmployeeIds"}'::jsonb,60,now(),now(),false),
          (gen_random_uuid(),'${sjg_tenant_id}'::uuid,p_version_id,'S07','执行任务','TASK','{"resolver":"CONTEXT_EMPLOYEE_IDS","field":"recipientEmployeeIds"}'::jsonb,70,now(),now(),false),
          (gen_random_uuid(),'${sjg_tenant_id}'::uuid,p_version_id,'S08','责任人验收','TASK','{"resolver":"CONTEXT_EMPLOYEE_IDS","field":"managerCandidateIds"}'::jsonb,80,now(),now(),false),
          (gen_random_uuid(),'${sjg_tenant_id}'::uuid,p_version_id,'S09','未完成催办升级','TASK','{"resolver":"CONTEXT_EMPLOYEE_IDS","field":"managerCandidateIds"}'::jsonb,90,now(),now(),false),
          (gen_random_uuid(),'${sjg_tenant_id}'::uuid,p_version_id,'S10','档案移交','TASK','{"resolver":"CONTEXT_EMPLOYEE_IDS","field":"managerCandidateIds"}'::jsonb,100,now(),now(),false),
          (gen_random_uuid(),'${sjg_tenant_id}'::uuid,p_version_id,'END','已关闭','END',NULL,110,now(),now(),false);

        INSERT INTO workflow.wf_transition(
            id,tenant_id,version_id,from_node_code,action_code,to_node_code,condition_expr,is_rollback,created_at,updated_at,is_deleted)
        VALUES
          (gen_random_uuid(),'${sjg_tenant_id}'::uuid,p_version_id,'S01','PUBLISH','S02',NULL,false,now(),now(),false),
          (gen_random_uuid(),'${sjg_tenant_id}'::uuid,p_version_id,'S02','RESOLVE_AUDIENCE','S03',NULL,false,now(),now(),false),
          (gen_random_uuid(),'${sjg_tenant_id}'::uuid,p_version_id,'S03','QUEUE_DELIVERY','S04',NULL,false,now(),now(),false),
          (gen_random_uuid(),'${sjg_tenant_id}'::uuid,p_version_id,'S04','COMPLETE_READ','S05',NULL,false,now(),now(),false),
          (gen_random_uuid(),'${sjg_tenant_id}'::uuid,p_version_id,'S05','COMPLETE_CONFIRM','S06',NULL,false,now(),now(),false),
          (gen_random_uuid(),'${sjg_tenant_id}'::uuid,p_version_id,'S06','PASS_UNDERSTANDING','S07',NULL,false,now(),now(),false),
          (gen_random_uuid(),'${sjg_tenant_id}'::uuid,p_version_id,'S07','SUBMIT_EXECUTION','S08',NULL,false,now(),now(),false),
          (gen_random_uuid(),'${sjg_tenant_id}'::uuid,p_version_id,'S08','ACCEPT_EXECUTION','S09',NULL,false,now(),now(),false),
          (gen_random_uuid(),'${sjg_tenant_id}'::uuid,p_version_id,'S09','RESOLVE_ESCALATIONS','S10',NULL,false,now(),now(),false),
          (gen_random_uuid(),'${sjg_tenant_id}'::uuid,p_version_id,'S10','ARCHIVE','END',NULL,false,now(),now(),false);

        UPDATE workflow.wf_version
           SET status='PUBLISHED',effective_at=now(),updated_at=now()
         WHERE tenant_id='${sjg_tenant_id}'::uuid AND id=p_version_id AND status='DRAFT' AND NOT is_deleted;
        IF NOT FOUND THEN
            RAISE EXCEPTION 'P005 workflow version could not be published' USING ERRCODE='55000';
        END IF;
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM workflow.wf_form_definition f
         WHERE f.tenant_id='${sjg_tenant_id}'::uuid AND f.form_code='CTR-P005-F03'
           AND f.process_code='P005' AND f.node_code='S01' AND f.enabled AND NOT f.is_deleted
    ) THEN
        INSERT INTO workflow.wf_form_definition(
            id,tenant_id,form_code,form_name,process_code,node_code,version_no,
            field_schema,layout_schema,validation_schema,visibility_matrix,edit_matrix,
            enabled,created_at,updated_at,is_deleted)
        VALUES (
            gen_random_uuid(),'${sjg_tenant_id}'::uuid,'CTR-P005-F03',
            '制度、通知与执行回执-制度·通知版本发布-任务分派与资源计划单','P005','S01',1,
            '{"type":"object","properties":{"process_instance_no":{"type":"string","readOnly":true},"submission_no":{"type":"string","readOnly":true},"process_code":{"type":"string","readOnly":true},"form_code":{"type":"string","readOnly":true},"form_version":{"type":"integer","readOnly":true},"policy_code":{"type":"string"},"official_subject":{"type":"string"},"official_type":{"type":"string"},"official_content":{"type":"string"},"period_or_course_no":{"type":"string"},"visibility_level":{"type":"string"},"target_center_id":{"type":"string"},"target_position_code":{"type":"string"},"understanding_pass_score":{"type":"integer"},"execution_due_at":{"type":"string","format":"date-time"}},"required":["policy_code","official_subject","official_type","official_content","period_or_course_no","visibility_level","target_center_id"]}'::jsonb,
            '{"sections":["系统标识","版本发布","适用范围","执行要求"]}'::jsonb,
            '{"serverAuthoritative":["process_instance_no","submission_no","process_code","form_code","form_version","policy_version","recipientEmployeeIds"]}'::jsonb,
            '{"employee":"RECIPIENT","center":"AUTHORIZED_SCOPE","tech":"METADATA_ONLY"}'::jsonb,
            '{"employee":[],"center":["policy_code","official_subject","official_type","official_content","period_or_course_no","visibility_level","target_center_id","target_position_code","understanding_pass_score","execution_due_at"],"tech":[]}'::jsonb,
            true,now(),now(),false
        );
    END IF;
END
$$;

RESET ROLE;