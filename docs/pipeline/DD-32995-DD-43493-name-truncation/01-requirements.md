# 01 — Requirements

- **Story:** [DD-43493](https://tools.hmcts.net/jira/browse/DD-43493) (epic [DD-32995](https://tools.hmcts.net/jira/browse/DD-32995))
- **Base:** `team/libra1` @ `65f62ba`

| ID | Requirement |
|---|---|
| R1 | `personalInformation` forename, middleName, middleName2 and surname are mapped to PCFDLRM `firstName`, `givenName2`, `givenName3` and `lastName` truncated to their first 35 characters. This covers the defendant and the parent guardian, which share the mapping. |
| R2 | Individual alias names are passed through unchanged (not truncated). |
| R3 | Names of 35 characters or fewer, and null names, are unchanged. |
| R4 | No schema change; the gate still accepts names up to its existing limits. |

## Notes

- The mapping is shared by XHIBIT and LIBRA, so XHIBIT names over 35 characters are truncated too
  (previously they also failed PCFDLRM's schema).
- "No consecutive spaces": ignored for alias, defendant and guardian names (2026-10-12; DD-43494 A2,
  applied to DD-43493 D3).
- Alias names stay optional (BA, 2026-10-12, DD-43494 A3).
