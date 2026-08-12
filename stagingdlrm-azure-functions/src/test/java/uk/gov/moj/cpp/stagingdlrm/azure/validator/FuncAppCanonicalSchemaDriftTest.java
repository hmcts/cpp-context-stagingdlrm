package uk.gov.moj.cpp.stagingdlrm.azure.validator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.TreeSet;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

/**
 * DD-43086 FR6 — fails the build when the Function App's own schema is <b>more lenient</b> than
 * canonical for a field both declare (the direction that produces a terminal 4xx once stagingDLRM
 * rejects what the Function App's gate accepted — 00-input-brief.md). This is a proposed
 * mitigation the team confirmed keeping; see that doc's "Decisions taken with the requester" for
 * why the check exists instead of coupling the two schemas.
 *
 * <p>Deliberately scoped to {@code required} and {@code additionalProperties}, not full constraint
 * parity — the gate carries zero patterns/lengths/enums against canonical's, so a naive
 * "same constraints" check reports 100+ findings on day one (01-requirements.md FR6). Comparison
 * runs against the two flattened schema documents `tools/schema-gen/flatten-canonical-schema.py`
 * already produces and commits — this test does not regenerate them (that stays a manual/CI-adjacent
 * step); if either file is missing or stale, re-run that script first.
 *
 * <p><b>This is a ratchet, not a pass/fail gate on zero drift.</b> The Function App's XHIBIT schema
 * already drifts from canonical today (00-input-brief.md's original finding, predating this
 * story) — FR6 is explicitly "detection... not elimination", so this test pins that known drift as
 * a baseline rather than failing the build over a pre-existing condition nobody asked this story to
 * fix. Any <em>new</em> finding beyond the baseline fails the build. If a baseline finding is fixed,
 * this test starts failing too (actual no longer equals expected) — that is intentional: remove the
 * fixed entry from {@link #KNOWN_FINDINGS} explicitly, so the baseline never silently goes stale.
 */
class FuncAppCanonicalSchemaDriftTest {

    private static final Path CANONICAL_FLATTENED = Path.of(
            "..", "docs", "analysis", "libra-ingestion", "schema", "canonical", "staging-dlrm-canonical-flattened.json");

    private static final Path FUNCAPP_FLATTENED = Path.of(
            "..", "docs", "analysis", "libra-ingestion", "schema", "canonical", "staging-dlrm-funcapp-flattened.json");

    /**
     * The Function App's XHIBIT schema today, exactly as diffed against canonical
     * (00-input-brief.md's "Current state" table). Not LIBRA's schema (DD-43086 FR3) — canonical
     * has no LIBRA-specific relaxation yet (DD-43081), so a LIBRA-vs-canonical comparison at this
     * scope isn't meaningful until that lands.
     */
    private static final Set<String> KNOWN_FINDINGS = Set.of(
            "caseDetails: additionalProperties is true/absent, canonical is false",
            "migratedCase: additionalProperties is true/absent, canonical is false",
            "prosecutor: additionalProperties is true/absent, canonical is false",
            "prosecutor.prosecutingAuthority: required in canonical, not required in func-app",
            "migrationSourceSystem.migrationSourceSystemName: required in canonical, not required in func-app",
            "migrationSourceSystem.migrationSourceSystemCaseIdentifier: required in canonical, not required in func-app");

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void funcAppSchemaDriftFromCanonicalMustNotExceedTheKnownBaseline() throws IOException {
        final JsonNode canonicalDefinitions = readDefinitions(CANONICAL_FLATTENED);
        final JsonNode funcAppDefinitions = readDefinitions(FUNCAPP_FLATTENED);

        final Set<String> findings = new TreeSet<>();
        final Iterator<String> definitionNames = funcAppDefinitions.fieldNames();
        while (definitionNames.hasNext()) {
            final String definitionName = definitionNames.next();
            if (canonicalDefinitions.has(definitionName)) {
                findings.addAll(compareDefinition(
                        definitionName, funcAppDefinitions.get(definitionName), canonicalDefinitions.get(definitionName)));
            }
        }

        assertEquals(new TreeSet<>(KNOWN_FINDINGS), findings, () ->
                "Function App vs. canonical schema drift changed. If this added a NEW finding, the "
                        + "Function App schema just became more lenient than canonical for a field both "
                        + "declare — fix the schema (add the missing required/additionalProperties "
                        + "constraint), don't widen KNOWN_FINDINGS to silence it. If a KNOWN_FINDINGS "
                        + "entry is gone because someone fixed it, remove that entry from the baseline.\n"
                        + "Actual: " + findings);
    }

    /** required/additionalProperties only (FR6) — see class Javadoc for why. */
    private static Set<String> compareDefinition(final String definitionName, final JsonNode funcApp, final JsonNode canonical) {
        final Set<String> findings = new LinkedHashSet<>();

        final boolean funcAppOpen = funcApp.path("additionalProperties").asBoolean(true);
        final boolean canonicalClosed = !canonical.path("additionalProperties").asBoolean(true);
        if (funcAppOpen && canonicalClosed) {
            findings.add(definitionName + ": additionalProperties is true/absent, canonical is false");
        }

        final Set<String> funcAppProperties = fieldNames(funcApp.path("properties"));
        final Set<String> funcAppRequired = toStringSet(funcApp.path("required"));
        final Set<String> canonicalRequired = toStringSet(canonical.path("required"));

        for (final String field : canonicalRequired) {
            if (funcAppProperties.contains(field) && !funcAppRequired.contains(field)) {
                findings.add(definitionName + "." + field + ": required in canonical, not required in func-app");
            }
        }

        return findings;
    }

    private static JsonNode readDefinitions(final Path path) throws IOException {
        assertTrue(Files.exists(path), () -> "Expected flattened schema at " + path.toAbsolutePath()
                + " — run tools/schema-gen/flatten-canonical-schema.py (see its --out examples) if it's missing.");
        return MAPPER.readTree(path.toFile()).path("definitions");
    }

    private static Set<String> fieldNames(final JsonNode objectNode) {
        final Set<String> names = new LinkedHashSet<>();
        objectNode.fieldNames().forEachRemaining(names::add);
        return names;
    }

    private static Set<String> toStringSet(final JsonNode arrayNode) {
        final Set<String> values = new LinkedHashSet<>();
        arrayNode.forEach(node -> values.add(node.asText()));
        return values;
    }
}