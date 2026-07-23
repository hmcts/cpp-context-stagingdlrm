-- Script 3 — PCF DLRM processing status report
--
-- Reports, for every case_id passed in, the latest processing status
-- recorded in pcfdlrm's event_log table.
--
-- Parameter (psql -v, quoted-literal substitution):
--   :'case_ids' — comma-separated case_id UUIDs, e.g.
--                 '58957522-d94f-4432-9fd5-55a8fa1cf414,e6105f12-241f-4257-8125-9639e081a438,...'
--                 (built by pcfdlrm-report.sh from Script 2's output CSV's
--                 case_id column, skipping blank values).
--
-- stream_id in pcfdlrm's event_log is the caseId itself (both
-- pcfdlrm.command.receive-migrated-case-file and
-- pcfdlrm.command.accept-migrated-case key on caseId — MigratedCaseFileHandler.java),
-- so the join to the input case_ids is direct, no JSON path matching needed.
-- payload/metadata are stored as `text`, hence the explicit ::jsonb casts.
--
-- Fatal vs. non-fatal: pcfdlrm_status/description is the ONLY fatal signal —
-- it's derived purely from migrated-case-file-processed's
-- processingIsSuccessful + description, which in turn reflects a small
-- hardcoded allowlist of case-blocking ProblemCodes inside
-- MigratedCaseFileAggregate's hasXxx() checks (e.g. hasInvalidOffenceCode,
-- hasInvalidOuCode). Every other column below (validation_warning_*,
-- defendant_validation_failed_*, hearing_validation_failed_count,
-- material_*_count) is always non-blocking, REGARDLESS of the word "failed"
-- in some of the underlying event names — confirmed live: a case with 22
-- defendant-validation-failed events still ended up PROCESSED.

WITH input_cases AS (
    -- case_urn is derived from pcfdlrm's own events, not passed in — pulled
    -- from whichever of the two case-level entry events is available for
    -- this stream (processed: flat caseUrn field; received: nested via
    -- receiveMigratedCaseFile.migratedCaseDetails.caseDetails.
    -- prosecutorCaseReference). NULL/blank for a case pcfdlrm never received
    -- at all (NOT_RECEIVED_BY_PCFDLRM), since there's genuinely no PCF-side
    -- case_urn to show in that scenario.
    SELECT
        ids.case_id,
        (
            SELECT CASE e.name
                       WHEN 'pcfdlrm.events.migrated-case-file-processed'
                           THEN e.payload::jsonb ->> 'caseUrn'
                       WHEN 'pcfdlrm.events.migrated-case-file-received'
                           THEN e.payload::jsonb #>> '{receiveMigratedCaseFile,migratedCaseDetails,caseDetails,prosecutorCaseReference}'
                   END
            FROM event_log e
            WHERE e.stream_id = ids.case_id
              AND e.name IN (
                  'pcfdlrm.events.migrated-case-file-processed',
                  'pcfdlrm.events.migrated-case-file-received'
              )
            ORDER BY e.position_in_stream DESC
            LIMIT 1
        ) AS case_urn
    FROM (
        SELECT trim(case_id)::uuid AS case_id
        FROM unnest(string_to_array(:'case_ids', ',')) AS case_id
        WHERE trim(case_id) <> ''
    ) ids
),

status_events AS (
    -- Only the case-level lifecycle events determine status — material/
    -- defendant/hearing/warning events are auxiliary signals (counted
    -- separately below) and must not be mistaken for "the latest event"
    -- just because they happen to have a higher position_in_stream.
    SELECT
        e.stream_id,
        e.position_in_stream,
        e.name AS event_name,
        e.date_created,
        CASE e.name
            WHEN 'pcfdlrm.events.migrated-case-file-processed'
                THEN (e.payload::jsonb ->> 'processingIsSuccessful')::boolean
        END AS processing_successful,
        CASE e.name
            WHEN 'pcfdlrm.events.migrated-case-file-processed'
                THEN e.payload::jsonb ->> 'description'
        END AS description
    FROM event_log e
    WHERE e.stream_id IN (SELECT case_id FROM input_cases)
      AND e.name IN (
          'pcfdlrm.events.migrated-case-file-received',
          'pcfdlrm.events.migrated-case-validated-creation-pending',
          'pcfdlrm.events.migrated-case-file-processed',
          'pcfdlrm.events.migrated-case-not-found-in-automation'
      )
),

latest_per_case AS (
    -- A genuine acceptance (processingIsSuccessful=true) always wins over any
    -- later event on the same stream, mirroring Script 2's protection against
    -- a stale retry landing after a real success. Absent any success, falls
    -- back to strict latest-event-wins by position_in_stream.
    SELECT DISTINCT ON (stream_id)
        stream_id, event_name, processing_successful, description, date_created
    FROM status_events
    ORDER BY stream_id, (processing_successful IS TRUE) DESC, position_in_stream DESC
),

warning_counts AS (
    -- migrated-case-validated-with-warnings is one generic event covering
    -- several validation categories via its own `type` field (observed live:
    -- "Case validation", "Defendant validation", "Hearing validation",
    -- "Offence validation", plus legacy "Material validation for Xhibit" no
    -- longer produced by current code) — combined into one count here, with
    -- the type/message breakdown preserved in the details string per event.
    SELECT stream_id,
           COUNT(*) AS validation_warning_count,
           string_agg(COALESCE(type, 'Unknown') || ': ' || COALESCE(message, ''), ' | ') AS validation_warning_details
    FROM (
        SELECT stream_id,
               payload::jsonb ->> 'type' AS type,
               payload::jsonb ->> 'message' AS message
        FROM event_log
        WHERE stream_id IN (SELECT case_id FROM input_cases)
          AND name = 'pcfdlrm.events.migrated-case-validated-with-warnings'
    ) w
    GROUP BY stream_id
),

defendant_validation_failed_counts AS (
    -- defendant-validation-failed carries a `problems` array (extracted as a
    -- "code1; code2" summary per event, joined across events per case).
    SELECT stream_id,
           COUNT(*) AS defendant_validation_failed_count,
           string_agg(detail, ' | ') AS defendant_validation_failed_details
    FROM (
        SELECT stream_id,
               (SELECT string_agg(p ->> 'code', '; ')
                FROM jsonb_array_elements(payload::jsonb -> 'problems') AS p) AS detail
        FROM event_log
        WHERE stream_id IN (SELECT case_id FROM input_cases)
          AND name = 'pcfdlrm.events.defendant-validation-failed'
    ) d
    GROUP BY stream_id
),

hearing_validation_failed_counts AS (
    -- Legacy/removed event (2 rows total in production, no current code path
    -- produces it) whose payload carries no structured detail beyond
    -- caseId/caseUrn/submissionId, hence count only, no details column.
    SELECT stream_id, COUNT(*) AS hearing_validation_failed_count
    FROM event_log
    WHERE stream_id IN (SELECT case_id FROM input_cases)
      AND name = 'pcfdlrm.events.hearing-validation-failed'
    GROUP BY stream_id
),

material_uploaded_counts AS (
    -- material-added fires when upload is only *requested* (cross-context
    -- round-trip to the "material" service still pending); material-added-
    -- pending-process fires once that upload is genuinely confirmed, despite
    -- its name. Confirmed live: the two counts have already diverged (199 vs
    -- 197). COUNT(DISTINCT materialId) guards against redelivery duplicates.
    SELECT stream_id, COUNT(DISTINCT payload::jsonb ->> 'materialId') AS material_uploaded_count
    FROM event_log
    WHERE stream_id IN (SELECT case_id FROM input_cases)
      AND name = 'pcfdlrm.events.material-added-pending-process'
    GROUP BY stream_id
)

SELECT
    ic.case_urn,
    ic.case_id,
    CASE
        WHEN lpc.event_name IS NULL THEN 'NOT_RECEIVED_BY_PCFDLRM'
        WHEN lpc.processing_successful IS TRUE THEN 'PROCESSED'
        WHEN lpc.event_name = 'pcfdlrm.events.migrated-case-file-processed'
             AND lpc.processing_successful IS FALSE THEN 'REJECTED'
        WHEN lpc.event_name = 'pcfdlrm.events.migrated-case-not-found-in-automation' THEN 'NOT_FOUND_IN_AUTOMATION'
        WHEN lpc.event_name IN (
            'pcfdlrm.events.migrated-case-file-received',
            'pcfdlrm.events.migrated-case-validated-creation-pending'
        ) THEN 'RECEIVED'
        ELSE 'UNKNOWN'
    END AS pcfdlrm_status,
    COALESCE(CASE WHEN lpc.processing_successful IS FALSE THEN lpc.description END, '') AS description,
    COALESCE(wc.validation_warning_count, 0) AS validation_warning_count,
    COALESCE(wc.validation_warning_details, '') AS validation_warning_details,
    COALESCE(dvfc.defendant_validation_failed_count, 0) AS defendant_validation_failed_count,
    COALESCE(dvfc.defendant_validation_failed_details, '') AS defendant_validation_failed_details,
    COALESCE(hvfc.hearing_validation_failed_count, 0) AS hearing_validation_failed_count,
    COALESCE(muc.material_uploaded_count, 0) AS material_uploaded_count,
    lpc.event_name AS latest_event,
    lpc.date_created AS last_updated
FROM input_cases ic
LEFT JOIN latest_per_case lpc ON lpc.stream_id = ic.case_id
LEFT JOIN warning_counts wc ON wc.stream_id = ic.case_id
LEFT JOIN defendant_validation_failed_counts dvfc ON dvfc.stream_id = ic.case_id
LEFT JOIN hearing_validation_failed_counts hvfc ON hvfc.stream_id = ic.case_id
LEFT JOIN material_uploaded_counts muc ON muc.stream_id = ic.case_id
ORDER BY ic.case_urn;
