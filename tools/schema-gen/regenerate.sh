#!/usr/bin/env bash
#
# Regenerate every committed LIBRA-analysis artefact, in dependency order.
#
# The individual scripts default their OUTPUT to the current working directory, so they can be
# run ad hoc without touching the repo. This wrapper is the opposite: it writes to the committed
# locations under docs/analysis/libra-ingestion/, so a workbook revision can be picked up with
# one command.
#
#   ./tools/schema-gen/regenerate.sh                 # refresh the committed artefacts
#   ./tools/schema-gen/regenerate.sh --dry-run       # show what would run, write nothing
#   ./tools/schema-gen/regenerate.sh /tmp/scratch    # write the whole set somewhere else
#
# Inputs are the scripts' own defaults (the committed workbook, the in-repo canonical schema
# module, the func-app's own resources, and the sibling repo checkouts). Override them per-script
# instead of here — every script takes --workbook / --source / --canonical / --funcapp / --pcfdlrm
# etc. See each script's --help.
#
# Only LIBRA is generated from the workbook. XHIBIT is already in production, so its schema is
# READ (from the func-app resources and the canonical module), never re-derived.

set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO="$(cd "$HERE/../.." && pwd)"

DRY_RUN=""
if [[ "${1:-}" == "--dry-run" ]]; then
    DRY_RUN="yes"
    shift
fi

DEST="${1:-$REPO/docs/analysis/libra-ingestion}"

run() {
    echo "+ $*"
    [[ -n "$DRY_RUN" ]] || "$@"
}

echo "Regenerating LIBRA analysis artefacts into: $DEST"
[[ -n "$DRY_RUN" ]] && echo "(dry run — nothing will be written)"
echo

[[ -n "$DRY_RUN" ]] || mkdir -p "$DEST/schema/libra" "$DEST/schema/canonical"

# 1. Flatten both live schema sets so each can be diffed as one document. The canonical run
#    resolves the justice.gov.uk core refs from the core-domain checkout; it warns and stubs them
#    if it is absent. Must come first: step 2 copies its shared primitives from the canonical one.
run python3 "$HERE/flatten-canonical-schema.py" \
    --out "$DEST/schema/canonical/staging-dlrm-canonical-flattened.json"

run python3 "$HERE/flatten-canonical-schema.py" \
    --source "$REPO/stagingdlrm-azure-functions/src/main/resources" \
    --root stagingdlrm.case-submission.json \
    --out "$DEST/schema/canonical/staging-dlrm-funcapp-flattened.json"

# 2. The LIBRA schema, generated from the workbook against the canonical schema as its reference.
run python3 "$HERE/generate-dlrm-schema.py" --out-dir "$DEST/schema/libra" \
    --reference "$DEST/schema/canonical/staging-dlrm-canonical-flattened.json"

# 3. The field-level impact matrix — depends on all three schemas above, and verifies its curated
#    PCFDLRM/core claims against those checkouts. Exits non-zero if a claim no longer holds.
run python3 "$HERE/build-schema-impact.py" --out-dir "$DEST" \
    --libra     "$DEST/schema/libra/dlrm-libra-0.13.json" \
    --canonical "$DEST/schema/canonical/staging-dlrm-canonical-flattened.json" \
    --funcapp   "$DEST/schema/canonical/staging-dlrm-funcapp-flattened.json" \
    --out

echo
echo "Done. Re-read the analysis doc's §1 and §7 rather than re-deriving by hand:"
echo "  $DEST/libra-schema-impact.md"
