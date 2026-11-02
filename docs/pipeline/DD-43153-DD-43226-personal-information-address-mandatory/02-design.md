# Design — DD-43226

## Decision

Add `"address"` to the `required` array in `personal-information.json`. Single-line schema
change; `address` is already declared as a property.

## Change

`stagingdlrm-domain/stagingdlrm-domain-value-schema/.../schema/personal-information.json`:

```json
"required": [
  "surname",
  "address"
]
```

## Notes

- `address` resolves via `$ref` to `pcf-address.json` (`id` = `.../schemas/address.json`) —
  no new file needed.
- Scoped to `personalInformation`. `parent-guardian-information.json` and
  `migrated-defendant.json` keep the address optional.
