-- Script 2 — Staging DLRM processing status report
--
-- Reports, for every case submitted under a given batch, the latest
-- processing status recorded in stagingdlrm's event_log table.
--
-- Parameters (psql -v, quoted-literal substitution):
--   :'source_system' — e.g. XHIBIT
--   :'batch_id'       — e.g. test_7_cases_2705
--
-- stream_id in event_log is the submissionId itself (every command handler
-- in StagingdlrmCommandHandler calls appendEventsToStream(submissionId, ...)),
-- so batch scoping only needs to match on azureLocation, not a separate
-- aggregate-id lookup. payload/metadata are stored as `text`, hence the
-- explicit ::jsonb casts throughout.

WITH batch_streams AS (
    -- Step 1: resolve each stream's identity fields (submissionId, caseUrn,
    -- azureLocation) from its "entry" event — migrated-case-submission-
    -- received or error-migrated-case-submission-received, always the first
    -- event on any stream — and keep only the streams whose entry event
    -- falls under this batch's blob path prefix. submission_id needs no JSON
    -- extraction at all: it's literally the stream_id. These 3 fields are
    -- effectively invariant for the rest of the stream's life (azureLocation
    -- is copied verbatim from this same event by the aggregate for every
    -- later processed/duplicate event; caseUrn is the case's identity and
    -- shouldn't change across resubmissions), so they're resolved once here
    -- rather than re-derived per event in stream_events below. The WHERE
    -- clause's CASE is a necessary duplicate of the one in the SELECT list —
    -- Postgres evaluates WHERE before SELECT aliases exist, so the LIKE
    -- filter can't reference the azure_location column being computed above
    -- it. DISTINCT ON + earliest position_in_stream guards the (unenforced by
    -- code, currently unseen in live data) edge case of a stream that somehow
    -- has both a received and an error event.
    SELECT DISTINCT ON (stream_id)
        stream_id,
        :'batch_id' AS batch_id,
        stream_id::text AS submission_id,
        CASE name
            WHEN 'stagingdlrm.events.migrated-case-submission-received'
                THEN payload::jsonb #>> '{migratedCaseSubmission,migratedCase,caseDetails,prosecutorCaseReference}'
            WHEN 'stagingdlrm.events.migrated-case-submission-rejected'
                THEN payload::jsonb #>> '{migratedCaseSubmission,migratedCase,caseDetails,prosecutorCaseReference}'
            WHEN 'stagingdlrm.events.error-migrated-case-submission-received'
                THEN payload::jsonb #>> '{errorMigratedCaseSubmission,caseUrn}'
        END AS case_urn,
        CASE name
            WHEN 'stagingdlrm.events.migrated-case-submission-received'
                THEN payload::jsonb #>> '{migratedCaseSubmission,azureLocation}'
            WHEN 'stagingdlrm.events.migrated-case-submission-rejected'
                THEN payload::jsonb #>> '{migratedCaseSubmission,azureLocation}'
            WHEN 'stagingdlrm.events.error-migrated-case-submission-received'
                THEN payload::jsonb #>> '{errorMigratedCaseSubmission,azureLocation}'
        END AS azure_location,
        -- Only available when a received event exists on the stream — error
        -- events never carry the submitted case content at all (confirmed
        -- live: their raw `payload` field, described as "raw/original
        -- payload text", is empty in production), so these are NULL for
        -- ERROR-status rows.
        CASE name
            WHEN 'stagingdlrm.events.migrated-case-submission-received'
                THEN jsonb_array_length(payload::jsonb #> '{migratedCaseSubmission,migratedCase,hearings}')
            WHEN 'stagingdlrm.events.migrated-case-submission-rejected'
                THEN jsonb_array_length(payload::jsonb #> '{migratedCaseSubmission,migratedCase,hearings}')
        END AS hearing_count,
        CASE name
            WHEN 'stagingdlrm.events.migrated-case-submission-received'
                THEN jsonb_array_length(payload::jsonb #> '{migratedCaseSubmission,migratedCase,defendants}')
            WHEN 'stagingdlrm.events.migrated-case-submission-rejected'
                THEN jsonb_array_length(payload::jsonb #> '{migratedCaseSubmission,migratedCase,defendants}')
        END AS defendant_count,
        -- Unlike hearings/defendants, `materials` is genuinely optional and
        -- absent (not an empty array) on most real submissions (confirmed
        -- live: 5846 of 6084 received events have no materials key at all,
        -- vs. 238 with real content) — COALESCE to 0 within the received
        -- branch so "no materials" reads as a real fact, not as unavailable
        -- data; stays NULL when no received event exists at all (ERROR rows).
        CASE name
            WHEN 'stagingdlrm.events.migrated-case-submission-received'
                THEN COALESCE(jsonb_array_length(payload::jsonb #> '{migratedCaseSubmission,materials}'), 0)
            WHEN 'stagingdlrm.events.migrated-case-submission-rejected'
                THEN COALESCE(jsonb_array_length(payload::jsonb #> '{migratedCaseSubmission,materials}'), 0)
        END AS material_count
    FROM event_log
    WHERE name IN (
        'stagingdlrm.events.migrated-case-submission-received',
        'stagingdlrm.events.migrated-case-submission-rejected',
        'stagingdlrm.events.error-migrated-case-submission-received'
    )
    AND CASE name
        WHEN 'stagingdlrm.events.migrated-case-submission-received'
            THEN payload::jsonb #>> '{migratedCaseSubmission,azureLocation}'
        WHEN 'stagingdlrm.events.migrated-case-submission-rejected'
            THEN payload::jsonb #>> '{migratedCaseSubmission,azureLocation}'
        WHEN 'stagingdlrm.events.error-migrated-case-submission-received'
            THEN payload::jsonb #>> '{errorMigratedCaseSubmission,azureLocation}'
    END LIKE :'source_system' || '/' || :'batch_id' || '/%'
    ORDER BY stream_id, position_in_stream ASC
),

stream_events AS (
    -- Step 2: pull every event on those streams, extracting only the fields
    -- that genuinely vary by event type — caseId/processingIsSuccessful/
    -- description, which only exist on the processed and error events.
    -- (submission_id/case_urn/azure_location are already resolved above.)
    SELECT
        e.stream_id,
        e.position_in_stream,
        e.name AS event_name,
        e.date_created,
        CASE e.name
            WHEN 'stagingdlrm.events.migrated-case-submission-processed'
                THEN e.payload::jsonb #>> '{migratedCaseSubmissionProcessed,caseId}'
        END AS case_id,
        CASE e.name
            WHEN 'stagingdlrm.events.migrated-case-submission-processed'
                THEN (e.payload::jsonb #>> '{migratedCaseSubmissionProcessed,processingIsSuccessful}')::boolean
        END AS processing_successful,
        CASE e.name
            WHEN 'stagingdlrm.events.migrated-case-submission-processed'
                THEN e.payload::jsonb #>> '{migratedCaseSubmissionProcessed,description}'
            WHEN 'stagingdlrm.events.error-migrated-case-submission-received'
                THEN e.payload::jsonb #>> '{errorMigratedCaseSubmission,errorMessage}'
        END AS description
    FROM event_log e
    WHERE e.stream_id IN (SELECT stream_id FROM batch_streams)
),

latest_per_case AS (
    -- Step 3: pick the representative event per stream (per case) — a genuine
    -- success always wins, even if a later duplicate/already-exists resubmission
    -- lands on the same stream afterward (the aggregate's dedup-by-submissionId
    -- check never clears once a case is known, so a stale retry can still
    -- produce a later DuplicatedMigratedCaseSubmissionReceived + failed
    -- MigratedCaseSubmissionProcessed pair on a stream that already succeeded —
    -- that shouldn't shadow the earlier real success). Absent any success,
    -- falls back to strict latest-event-wins by position_in_stream. Any event
    -- name not mapped above (e.g. case-already-processed-and-exists-in-
    -- progression, or a bare duplicate-received with no companion processed
    -- event) simply carries all-NULL case_id/processing_successful/description
    -- and, if ever picked as the winner, falls through to status=UNKNOWN below.
    SELECT DISTINCT ON (stream_id)
        stream_id, case_id, event_name, processing_successful, description, date_created
    FROM stream_events
    ORDER BY stream_id, (processing_successful IS TRUE) DESC, position_in_stream DESC
),

duplicate_counts AS (
    -- Step 4: how many times this stream saw a duplicate resubmission — kept
    -- separate from `status` so a resubmission after an earlier success is
    -- visible without shadowing that success (see latest_per_case above).
    SELECT stream_id, COUNT(*) AS duplicate_submissions_received
    FROM stream_events
    WHERE event_name = 'stagingdlrm.events.duplicate-migrated-case-submission-received'
    GROUP BY stream_id
)

SELECT
    bs.batch_id,
    bs.submission_id,
    bs.case_urn,
    lpc.case_id,
    CASE
        WHEN lpc.event_name = 'stagingdlrm.events.migrated-case-submission-processed'
             AND lpc.processing_successful IS TRUE THEN 'PROCESSED'
        WHEN lpc.event_name = 'stagingdlrm.events.migrated-case-submission-processed'
             AND lpc.description = 'Duplicate Submission ID' THEN 'DUPLICATE'
        WHEN lpc.event_name = 'stagingdlrm.events.migrated-case-submission-processed'
             AND lpc.description = 'Case Already exists in progression' THEN 'CASE_ALREADY_EXISTS'
        WHEN lpc.event_name = 'stagingdlrm.events.migrated-case-submission-processed'
             AND lpc.description = 'Migrated case submission rejected by validation rule(s)' THEN 'VALIDATION_REJECTED'
        WHEN lpc.event_name = 'stagingdlrm.events.migrated-case-submission-rejected' THEN 'VALIDATION_REJECTED'
        WHEN lpc.event_name = 'stagingdlrm.events.migrated-case-submission-processed'
             AND lpc.processing_successful IS FALSE THEN 'PROCESSED_FAILED'
        WHEN lpc.event_name = 'stagingdlrm.events.error-migrated-case-submission-received' THEN 'ERROR'
        WHEN lpc.event_name = 'stagingdlrm.events.migrated-case-submission-received'       THEN 'RECEIVED'
        ELSE 'UNKNOWN'
    END AS status,
    lpc.description,
    COALESCE(dc.duplicate_submissions_received, 0) AS duplicate_submissions_received,
    bs.azure_location,
    bs.hearing_count,
    bs.defendant_count,
    bs.material_count,
    lpc.event_name AS latest_event,
    lpc.date_created AS last_updated
FROM latest_per_case lpc
JOIN batch_streams bs ON bs.stream_id = lpc.stream_id
LEFT JOIN duplicate_counts dc ON dc.stream_id = lpc.stream_id
ORDER BY bs.case_urn;
