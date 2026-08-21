-- PHASE-10 hardening: close append-only ledger field-code mutation bypasses.
SET ROLE sjg_owner;

CREATE OR REPLACE FUNCTION attendance.prevent_p008_quota_ledger_mutation()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
  IF OLD.field_code='QUOTA_LEDGER' THEN
    RAISE EXCEPTION 'P008 quota ledger is append-only';
  END IF;
  IF TG_OP='UPDATE' THEN
    IF NEW.field_code='QUOTA_LEDGER' THEN
      RAISE EXCEPTION 'P008 quota ledger entries must be inserted, never converted from mutable rows';
    END IF;
  END IF;
  IF TG_OP='DELETE' THEN
    RETURN OLD;
  END IF;
  RETURN NEW;
END
$$;

CREATE OR REPLACE FUNCTION attendance.prevent_p009_timeoff_ledger_mutation()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
  IF OLD.field_code='TIME_OFF_LEDGER' THEN
    RAISE EXCEPTION 'P009 time-off ledger is append-only';
  END IF;
  IF TG_OP='UPDATE' THEN
    IF NEW.field_code='TIME_OFF_LEDGER' THEN
      RAISE EXCEPTION 'P009 time-off ledger entries must be inserted, never converted from mutable rows';
    END IF;
  END IF;
  IF TG_OP='DELETE' THEN
    RETURN OLD;
  END IF;
  RETURN NEW;
END
$$;

DO $$
BEGIN
  IF NOT EXISTS (
    SELECT 1
    FROM pg_constraint
    WHERE conname='ck_p008_quota_ledger_entry'
  ) THEN
    ALTER TABLE attendance.leave_request_item
      ADD CONSTRAINT ck_p008_quota_ledger_entry
      CHECK (
        field_code <> 'QUOTA_LEDGER'
        OR (
          item_key IS NOT NULL
          AND item_key IN ('RESERVE','DEDUCT','RELEASE','ADJUST')
          AND item_value_number IS NOT NULL
          AND (
            (item_key='ADJUST' AND item_value_number <> 0)
            OR (item_key<>'ADJUST' AND item_value_number > 0)
          )
        )
      );
  END IF;

  IF NOT EXISTS (
    SELECT 1
    FROM pg_constraint
    WHERE conname='ck_p009_timeoff_ledger_entry'
  ) THEN
    ALTER TABLE attendance.overtime_request_item
      ADD CONSTRAINT ck_p009_timeoff_ledger_entry
      CHECK (
        field_code <> 'TIME_OFF_LEDGER'
        OR (
          item_key IS NOT NULL
          AND btrim(item_key) <> ''
          AND item_value_number IS NOT NULL
          AND item_value_number > 0
        )
      );
  END IF;
END
$$;

RESET ROLE;
