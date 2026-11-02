#!/usr/bin/env bash
# Version 0.1.0 (2026-07-17)
#
# env.sh — sets up all environment variables the DLRM reconciliation pipeline
# scripts (function-app-report.sh, stagingdlrm-report.sh, pcfdlrm-report.sh,
# listing-report.sh, run-all.sh) need, for one target environment at a time.
#
# MUST BE SOURCED, not executed — a script's own `export`s never escape its
# own subshell, so running this as `./env.sh dev04` sets nothing in your
# shell and silently does nothing useful. Source it instead:
#
# USAGE:
#   source ./env.sh <dev04|ste|prd>
#   # or, equivalently:
#   . ./env.sh <dev04|ste|prd>

if [[ "${BASH_SOURCE[0]}" == "${0}" ]]; then
  echo "Error: this file must be sourced, not executed." >&2
  echo "Run:   source ${BASH_SOURCE[0]} <dev04|ste|prd>" >&2
  exit 1
fi

_ENV_SH_TARGET="${1:-}"

case "$_ENV_SH_TARGET" in
  dev04)
    # --- Azure Blob Storage / Function App (function-app-report.sh) ---
    export DLRM_STORAGE_ACCOUNT="TODO: set dev04 storage account"

    # --- stagingdlrm (stagingdlrm-report.sh) ---
    export STAGINGDLRM_DB_PORT="5436"
    export STAGINGDLRM_DB_USER="TODO: set this to the dev04 stagingdlrm DB user"

    # --- pcfdlrm (pcfdlrm-report.sh) ---
    export PCFDLRM_DB_PORT="5437"
    export PCFDLRM_DB_USER="TODO: set this to the dev04 pcfdlrm DB user"

    # --- Listing (listing-report.sh) ---
    export LISTING_DB_PORT="5438"
    export LISTING_DB_USER="pgreadonly"

    # --- Optional: shared DB password, see note above (leave unset to prompt) ---
    export PGPASSWORD="TODO: set DB password for dev04"
    ;;

  ste)
    # --- Azure Blob Storage / Function App (function-app-report.sh) ---
    export DLRM_STORAGE_ACCOUNT="TODO: set this to the STE storage account name"

    # --- stagingdlrm (stagingdlrm-report.sh) ---
    export STAGINGDLRM_DB_PORT="5434"
    export STAGINGDLRM_DB_USER="TODO: set this to the STE stagingdlrm DB user"

    # --- pcfdlrm (pcfdlrm-report.sh) ---
    export PCFDLRM_DB_PORT="5434"
    export PCFDLRM_DB_USER="TODO: set this to the STE pcfdlrm DB user"

    # --- Listing (listing-report.sh) ---
    export LISTING_DB_PORT="5434"
    export LISTING_DB_USER="TODO: set this to the STE listing DB user"

    # --- Optional: shared DB password, see note above (leave unset to prompt) ---
    export PGPASSWORD="TODO: set this to the STE DB password"
    ;;

  prd)
     # --- Azure Blob Storage / Function App (function-app-report.sh) ---
     export DLRM_STORAGE_ACCOUNT="TODO: set this to the PRD storage account name"
#     export AZURE_STORAGE_CONNECTION_STRING="XXXXX"

     # --- stagingdlrm (stagingdlrm-report.sh) ---
     export STAGINGDLRM_DB_PORT="5436"
     export STAGINGDLRM_DB_USER="TODO: set this to the PRD stagingdlrm DB user"

     # --- pcfdlrm (pcfdlrm-report.sh) ---
     export PCFDLRM_DB_PORT="5437"
     export PCFDLRM_DB_USER="TODO: set this to the PRD pcfdlrm DB user"

     # --- Listing (listing-report.sh) ---
     export LISTING_DB_PORT="5438"
     export LISTING_DB_USER="TODO: set this to the PRD listing DB user"

     # --- Optional: shared DB password, see note above (leave unset to prompt) ---
     export PGPASSWORD="TODO: set this to the PRD DB password"
    ;;

  *)
    echo "Usage: source ${BASH_SOURCE[0]} <dev04|ste|prd>" >&2
    unset _ENV_SH_TARGET
    return 1
    ;;
esac

echo "==> env.sh: environment variables set for '$_ENV_SH_TARGET'." >&2
unset _ENV_SH_TARGET
