-- Script 4 — Listing report
--
-- Reports, for every case_id passed in, the hearings recorded for that case
-- in Listing's hearing/listed_cases tables, aggregated into one row per case
-- (case_id, case_reference, hearing_count, and a hearings JSON array) —
-- used by summary-report.py to confirm a case made it all the way through
-- the pipeline and to cross-check hearing counts against stagingdlrm's own
-- values.
--
-- Parameter (psql -v, quoted-literal substitution):
--   :'case_ids' — comma-separated case_id UUIDs, e.g.
--                 '58957522-...,e6105f12-...,...'
--                 (built by listing-report.sh from Script 2's output CSV's
--                 case_id column, skipping blank values — same convention
--                 as pcfdlrm-report.sh/pcfdlrm-report.sql).
--
-- A case_id with no matching row in listed_cases (never reached Listing) simply
-- produces no output row at all — this IS the "missing from listing" signal
-- downstream in summary-report.py (STUCK_AT_LISTING).
--
-- Query logic as supplied by the Listing team's own export script — only the
-- input/parameterization and output mechanism were adapted to fit this
-- pipeline's conventions (case_ids passed the same way pcfdlrm-report.sql
-- takes them, via unnest(string_to_array(:'case_ids', ',')); plain SELECT +
-- --csv from the .sh wrapper instead of an embedded COPY ... TO STDOUT).

SELECT
    properties::jsonb #>> '{listedCases,0,id}'                           AS case_id,
    properties::jsonb #>> '{listedCases,0,caseIdentifier,caseReference}' AS case_reference,
    count(*)                                                             AS hearing_count,
    jsonb_agg(
        jsonb_build_object(
            'hearing_id',                 id,
            'unscheduled',                unscheduled,
            'allocated',                  allocated,
            'week_commencing_start_date', week_commencing_start_date,
            'week_commencing_end_date',   week_commencing_end_date,
            'start_date',                 start_date,
            'end_date',                   end_date,
            'estimated_minutes',          estimated_minutes
        )
    ) AS hearings
FROM public.hearing
WHERE id IN (
    SELECT hearing_id
    FROM public.listed_cases
    WHERE case_id IN (
        SELECT trim(case_id)::uuid
        FROM unnest(string_to_array(:'case_ids', ',')) AS case_id
        WHERE trim(case_id) <> ''
    )
)
GROUP BY
    properties::jsonb #>> '{listedCases,0,id}',
    properties::jsonb #>> '{listedCases,0,caseIdentifier,caseReference}'
ORDER BY case_reference;
