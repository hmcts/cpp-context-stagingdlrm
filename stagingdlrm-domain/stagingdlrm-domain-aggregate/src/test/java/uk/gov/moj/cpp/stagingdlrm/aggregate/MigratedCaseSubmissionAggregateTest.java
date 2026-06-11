package uk.gov.moj.cpp.stagingdlrm.aggregate;

import static java.util.UUID.fromString;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Answers.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import uk.gov.moj.stagingdlrm.domain.event.DuplicatedMigratedCaseSubmissionReceived;
import uk.gov.moj.stagingdlrm.domain.event.MigratedCaseSubmissionProcessed;

import uk.gov.moj.cpp.stagingdlrm.migrated.json.schemas.ErrorMigratedCaseSubmission;
import uk.gov.moj.cpp.stagingdlrm.migrated.json.schemas.MigratedCaseSubmission;
import uk.gov.moj.cpp.stagingdlrm.migrated.json.schemas.MigratedCaseSubmissionProcessedOutput;
import uk.gov.moj.cpp.stagingdlrm.migrated.json.schemas.MigratedMaterial;
import uk.gov.moj.stagingdlrm.domain.event.ErrorMigratedCaseSubmissionReceived;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class MigratedCaseSubmissionAggregateTest {


    @Test
    void shouldRaiseMigratedCaseSubmissionReceived() {

        final String migrationSourceSystemName = "XHIBIT";

        final String batchIdentifier = "20082025";

        final String migrationSourceSystemCaseIdentifier = UUID.randomUUID().toString();

        final String submissionId = UUID.randomUUID().toString();

        final String path = "%s/%s/%s/%s".formatted(migrationSourceSystemName, batchIdentifier, migrationSourceSystemCaseIdentifier, submissionId);

        final String testFile = path + "/test.pdf";

        final String test1File = path + "/test1.pdf";

        MigratedCaseSubmission migratedCaseSubmission = mock(MigratedCaseSubmission.class, RETURNS_DEEP_STUBS);

        when(migratedCaseSubmission.getSubmissionId()).thenReturn(fromString(submissionId));

        when(migratedCaseSubmission.getMaterials()).thenReturn(List.of(
                MigratedMaterial.migratedMaterial()
                        .withId(UUID.randomUUID())
                        .withAzureLocation(testFile)
                        .build(),
                MigratedMaterial.migratedMaterial()
                        .withId(UUID.randomUUID())
                        .withAzureLocation(test1File)
                        .build()));

        MigratedCaseSubmissionAggregate migratedCaseSubmissionAggregate = new MigratedCaseSubmissionAggregate();

        migratedCaseSubmissionAggregate.receiveMigratedCaseSubmission(migratedCaseSubmission);

        assertEquals(submissionId, migratedCaseSubmissionAggregate.getSubmissionId().toString(), "Caseid should match");

    }

    @Test
    void shouldRaiseMigratedCaseSubmissionReceivedWhenMaterialIsEmpty() {

        final String submissionId = UUID.randomUUID().toString();

        MigratedCaseSubmission migratedCaseSubmission = mock(MigratedCaseSubmission.class, RETURNS_DEEP_STUBS);

        when(migratedCaseSubmission.getSubmissionId()).thenReturn(fromString(submissionId));

        when(migratedCaseSubmission.getMaterials()).thenReturn(List.of());

        MigratedCaseSubmissionAggregate migratedCaseSubmissionAggregate = new MigratedCaseSubmissionAggregate();

        migratedCaseSubmissionAggregate.receiveMigratedCaseSubmission(migratedCaseSubmission);

        assertEquals(submissionId, migratedCaseSubmissionAggregate.getSubmissionId().toString(), "Caseid should match");

    }

    @Test
    void shouldRaiseMigratedCaseSubmissionReceivedWhenMaterialIsNull() {

        final String submissionId = UUID.randomUUID().toString();

        final MigratedCaseSubmission migratedCaseSubmission = mock(MigratedCaseSubmission.class, RETURNS_DEEP_STUBS);

        when(migratedCaseSubmission.getSubmissionId()).thenReturn(fromString(submissionId));

        when(migratedCaseSubmission.getMaterials()).thenReturn(null);

        MigratedCaseSubmissionAggregate migratedCaseSubmissionAggregate = new MigratedCaseSubmissionAggregate();

        migratedCaseSubmissionAggregate.receiveMigratedCaseSubmission(migratedCaseSubmission);

        assertEquals(submissionId, migratedCaseSubmissionAggregate.getSubmissionId().toString(), "Caseid should match");

    }

    @Test
    void shouldRaiseDuplicateMigratedCaseSubmissionReceived() {
        final String uuid = UUID.randomUUID().toString();

        final String migrationSourceSystemName = "XHIBIT";

        final String batchIdentifier = "20082025";

        final String migrationSourceSystemCaseIdentifier = UUID.randomUUID().toString();

        final String submissionId = UUID.randomUUID().toString();

        final String path = "%s/%s/%s/%s".formatted(migrationSourceSystemName, batchIdentifier, migrationSourceSystemCaseIdentifier, submissionId);

        final String testFile = path + "/test.pdf";

        final String test1File = path + "/test1.pdf";

        MigratedCaseSubmission migratedCaseSubmission = mock(MigratedCaseSubmission.class, RETURNS_DEEP_STUBS);

        when(migratedCaseSubmission.getSubmissionId()).thenReturn(fromString(uuid));
        when(migratedCaseSubmission.getMigratedCase().getCaseDetails().getProsecutorCaseReference()).thenReturn("T20000001");

        when(migratedCaseSubmission.getMaterials()).thenReturn(List.of(
                MigratedMaterial.migratedMaterial()
                        .withId(UUID.randomUUID())
                        .withAzureLocation(testFile)
                        .build(),
                MigratedMaterial.migratedMaterial()
                        .withId(UUID.randomUUID())
                        .withAzureLocation(test1File)
                        .build()));

        MigratedCaseSubmissionAggregate migratedCaseSubmissionAggregate = new MigratedCaseSubmissionAggregate();

        migratedCaseSubmissionAggregate.receiveMigratedCaseSubmission(migratedCaseSubmission);

        final MigratedCaseSubmissionProcessedOutput processedOutput = MigratedCaseSubmissionProcessedOutput.migratedCaseSubmissionProcessedOutput()
                .withCaseId(UUID.fromString(uuid))
                .withSubmissionId(UUID.fromString(uuid))
                .withCaseUrn("T20000001")
                .withProcessingIsSuccessful(true)
                .withDescription("Processed")
                .build();
        migratedCaseSubmissionAggregate.recordMigratedCaseSubmissionOutput(processedOutput);

        List<Object> eventStream =  migratedCaseSubmissionAggregate.receiveMigratedCaseSubmission(migratedCaseSubmission).toList();

        assertEquals(uuid, migratedCaseSubmissionAggregate.getSubmissionId().toString(), "Caseid should match");
        assertThat(eventStream.size(), is(2));
        assertTrue(eventStream.get(0) instanceof DuplicatedMigratedCaseSubmissionReceived, "First event should be DuplicatedMigratedCaseSubmissionReceived");
        assertTrue(eventStream.get(1) instanceof MigratedCaseSubmissionProcessed, "Second event should be MigratedCaseSubmissionProcessed");
        assertTrue(migratedCaseSubmissionAggregate.isCaseSubmissionDuplicated());
        assertNotNull(migratedCaseSubmissionAggregate.getMigratedCaseSubmissionProcessedOutput(),
                "migratedCaseSubmissionProcessedOutput should be set when duplicate is detected");
        assertNotNull(migratedCaseSubmissionAggregate.getMigratedCaseSubmissionProcessedOutput().getCaseId(),
                "caseId must be non-null to satisfy JSON schema required constraint");
    }


    @Test
    void shouldRaiseMigratedCaseSubmissionProcessed() {
        final UUID uuid = UUID.fromString("a4391788-f829-4514-a344-61f1d5d9690c");
        final MigratedCaseSubmissionProcessedOutput output = MigratedCaseSubmissionProcessedOutput.migratedCaseSubmissionProcessedOutput().withCaseId(uuid).withCaseUrn("caseURN").withSubmissionId(uuid).withProcessingIsSuccessful(true).build();

        MigratedCaseSubmissionAggregate migratedCaseSubmissionAggregate = new MigratedCaseSubmissionAggregate();

        migratedCaseSubmissionAggregate.recordMigratedCaseSubmissionOutput(output);

        assertEquals(uuid, migratedCaseSubmissionAggregate.getMigratedCaseSubmissionProcessedOutput().getCaseId(), "Caseid should match");

    }

    @Test
    void receiveErrorMigratedCaseSubmission() {
        UUID submissionId = UUID.randomUUID();

        ErrorMigratedCaseSubmission errorMigratedCaseSubmission = ErrorMigratedCaseSubmission
                .errorMigratedCaseSubmission().withSubmissionId(submissionId)
                .withPayload("sample text")
                .build();

        MigratedCaseSubmissionAggregate migratedCaseSubmissionAggregate = new MigratedCaseSubmissionAggregate();

        final List<Object> response = migratedCaseSubmissionAggregate.receiveErrorMigratedCaseSubmission(errorMigratedCaseSubmission).toList();

        assertThat(response.size(), is(1));
        assertThat(response.get(0).getClass().toString(), is(ErrorMigratedCaseSubmissionReceived.class.toString()));

        final ErrorMigratedCaseSubmission actual = ((ErrorMigratedCaseSubmissionReceived) response.get(0)).getErrorMigratedCaseSubmission();

        assertThat(actual.getSubmissionId(), is(submissionId));
        assertThat(actual.getPayload(), is("sample text"));
    }
}