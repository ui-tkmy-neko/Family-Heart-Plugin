# FamilyHeartPlugin — Self-Repair Verification Report

## Verification policy
- Time limit: none.
- Repair loop: no fixed iteration limit; continue until deterministic audit violations are resolved.
- Decision review: findings from each pass are re-evaluated instead of assuming the previous repair is correct.
- Required verification count: 3 complete passes after the final repair.

## Repairs made during this audit
1. Fixed role-only acceptance: `/fh accept husband|wife` now searches specifically for the newest pending marriage request instead of accidentally selecting the newest request of any type.
2. Fixed role validation: a spouse role is rejected for non-marriage requests.
3. Fixed economy error reporting: insufficient-funds messages now use the cost key matching the actual request type rather than always showing the marriage cost.
4. Fixed legacy SQLite migration cleanup: request-linked action rows for removed `CUSTOM_ITEM` requests are deleted before the obsolete request rows.
5. Corrected README wording so it agrees with the same-role marriage rejection implemented by the plugin.
6. Added `tools/self_repair_audit.py` as a repeatable deterministic audit program.

## Three required final verification passes
### Pass 1 — PASS
`tools/self_repair_audit.py`
- Kotlin delimiter/lexical checks: PASS
- Removed custom-item feature checks: PASS
- MySQL/MariaDB reference checks: PASS
- Java 25/POM checks: PASS
- SQLite dependency check: PASS
- Paper soft-dependency check: PASS
- Acceptance command contract checks: PASS
- Marriage role selector check: PASS
- README consistency check: PASS
- Stale target JAR check: PASS

### Pass 2 — PASS
The same complete deterministic audit was executed again after Pass 1. No violations found.

### Pass 3 — PASS
The same complete deterministic audit was executed a third time. No violations found.

## Additional compiler-oriented verification
`kotlinc` was executed against the source tree. The invocation cannot perform a dependency-backed project compile because Paper/Vault/LuckPerms/Hikari/SQLite dependencies are not present in the standalone compiler classpath and the environment uses JDK 21 while this project targets Java 25. The compiler output contained no parser/syntax errors and no remaining `CUSTOM_ITEM`/`CustomItem` unresolved-symbol errors; the reported failures were dependency/API resolution failures.

## Runtime limitation
A real Paper 26.2 + Java 25 server boot/integration test was not possible in this environment. Therefore this report does not claim a live-server test passed.

## Final status
**PASS — deterministic source/configuration audit passed three consecutive times after the final repair.**
