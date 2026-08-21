# PHASE-03 Flyway Database Baseline

This directory is generated from `Knowledge Base/03 数据库需求规则/03_SQL_DDL` by
`scripts/implementation/phase03_prepare_flyway.py`.

- `cluster/`: PostgreSQL cluster role contract; no passwords are committed.
- `oms/`: `sjg_oms` approved DDL + technical role/grant wrappers.
- `audit/`: `sjg_audit` approved DDL + immutable audit grant wrapper.
- `dw/`: `sjg_dw` approved DDL + analytics writer/reader grant wrapper.
- `manifest.json`: source path/hash → generated migration hash provenance.

Never hand-edit generated source migrations. Change the approved Knowledge Base source only through an approved
requirements/database change, then regenerate. Technical wrappers belong to PHASE-03 and are covered by integration tests.
Runtime passwords/login secret provisioning is environment-specific and MUST stay outside Git.
