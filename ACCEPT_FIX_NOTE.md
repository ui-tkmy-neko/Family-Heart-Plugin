# Acceptance Fix Note

## Acceptance commands
- `/fh accept` accepts the newest pending request.
- `/fh accept <requestId>` accepts the specified pending request.
- `/fh accept husband|wife` accepts the newest marriage request with the selected role.
- `/fh accept <requestId> husband|wife` accepts the specified marriage request with the selected role.
- Marriage acceptance without a role is rejected with a clear instruction.
- Non-marriage requests do not require a spouse role.

## GUI
- Clicking Accept on a marriage request opens a husband/wife role selection menu.
- Other request types can still be accepted directly.
- GUI item colors are rendered from legacy `&`/`§` color codes rather than displayed literally.

## Removed feature
- The legacy custom-item command, configuration, resource, and service have been removed.
- Legacy `CUSTOM_ITEM` request rows are deleted during startup migration so RequestDao cannot encounter an obsolete enum value.
