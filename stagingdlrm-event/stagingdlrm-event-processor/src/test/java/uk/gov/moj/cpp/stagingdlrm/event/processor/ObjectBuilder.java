package uk.gov.moj.cpp.stagingdlrm.event.processor;

import static java.time.ZonedDateTime.now;
import static java.util.Arrays.asList;
import static java.util.Collections.singletonList;
import static java.util.UUID.randomUUID;
import static javax.json.Json.createObjectBuilder;
import static uk.gov.justice.services.messaging.JsonEnvelope.envelopeFrom;
import static uk.gov.justice.services.messaging.spi.DefaultJsonMetadata.metadataBuilder;
import static uk.gov.justice.services.test.utils.core.messaging.MetadataBuilderFactory.metadataOf;
import static uk.gov.moj.cpp.stagingdlrm.json.schemas.Individual.individual;
import static uk.gov.moj.cpp.stagingdlrm.json.schemas.SelfDefinedInformation.selfDefinedInformation;

import uk.gov.justice.services.messaging.JsonEnvelope;
import uk.gov.justice.services.messaging.Metadata;
import uk.gov.moj.cpp.stagingdlrm.json.schemas.CaseDetails;
import uk.gov.moj.cpp.stagingdlrm.json.schemas.CaseMarker;
import uk.gov.moj.cpp.stagingdlrm.json.schemas.MigrationSourceSystem;
import uk.gov.moj.cpp.stagingdlrm.json.schemas.MigrationSourceSystemName;
import uk.gov.moj.cpp.stagingdlrm.migrated.json.schemas.Defendant;
import uk.gov.moj.cpp.stagingdlrm.migrated.json.schemas.ErrorMigratedCaseSubmission;
import uk.gov.moj.cpp.stagingdlrm.migrated.json.schemas.Hearing;
import uk.gov.moj.cpp.stagingdlrm.migrated.json.schemas.ListedDefendant;
import uk.gov.moj.cpp.stagingdlrm.migrated.json.schemas.MigratedCase;
import uk.gov.moj.cpp.stagingdlrm.migrated.json.schemas.MigratedCaseSubmission;
import uk.gov.moj.cpp.stagingdlrm.migrated.json.schemas.MigratedCaseSubmissionProcessedOutput;
import uk.gov.moj.cpp.stagingdlrm.migrated.json.schemas.MigratedMaterial;
import uk.gov.moj.cpp.stagingdlrm.migrated.json.schemas.Offence;
import uk.gov.moj.cpp.stagingdlrm.migrated.json.schemas.WeekCommencingDate;
import uk.gov.moj.stagingdlrm.domain.event.ErrorMigratedCaseSubmissionReceived;
import uk.gov.moj.stagingdlrm.domain.event.InitiationCode;
import uk.gov.moj.stagingdlrm.domain.event.MigratedCaseSubmissionProcessed;
import uk.gov.moj.stagingdlrm.domain.event.MigratedCaseSubmissionReceived;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import javax.json.JsonObject;

public class ObjectBuilder {

    public static final UUID CASE_ID = randomUUID();
    public static final String CASE_URN = randomUUID().toString();
    public static final String DESCRIPTION = "Test Description";
    public static final String AZURE_LOCATION = "Azure Location";
    public static final UUID SUBMISSION_ID = randomUUID();

    public static MigratedCaseSubmissionReceived buildMigratedCaseSubmissionReceived(final MigrationSourceSystemName migrationSourceSystemName,
                                                                                     final String courtHearingLocation) {
        return MigratedCaseSubmissionReceived.migratedCaseSubmissionReceived()
                .withMigratedCaseSubmission(buildMigratedCaseSubmission(migrationSourceSystemName, courtHearingLocation))
                .build();

    }

    public static MigratedCaseSubmissionReceived buildMigratedCaseSubmissionReceivedWithMaterial(final MigrationSourceSystemName migrationSourceSystemName,
                                                                                     final String courtHearingLocation) {
        return MigratedCaseSubmissionReceived.migratedCaseSubmissionReceived()
                .withMigratedCaseSubmission(buildMigratedCaseSubmissionWithMaterial(migrationSourceSystemName, courtHearingLocation))
                .build();
    }

    public static MigratedCaseSubmission buildMigratedCaseSubmission(final MigrationSourceSystemName migrationSourceSystemName,
                                                                     final String courtHearingLocation) {
        return MigratedCaseSubmission.migratedCaseSubmission()
                .withMetadata(null)
                .withMigratedCase(buildMigratedCase(migrationSourceSystemName, courtHearingLocation))
                .withSubmissionId(SUBMISSION_ID)
                .build();
    }

    public static MigratedCaseSubmission buildMigratedCaseSubmissionWithMaterial(final MigrationSourceSystemName migrationSourceSystemName,
                                                                     final String courtHearingLocation) {
        return MigratedCaseSubmission.migratedCaseSubmission()
                .withMetadata(null)
                .withMigratedCase(buildMigratedCase(migrationSourceSystemName, courtHearingLocation))
                .withMaterials(List.of(MigratedMaterial.migratedMaterial().build()))
                .withSubmissionId(SUBMISSION_ID)
                .build();
    }

    public static MigratedCase buildMigratedCase(final MigrationSourceSystemName migrationSourceSystemName,
                                                 final String courtHearingLocation) {
        final WeekCommencingDate weekCommencingDate = WeekCommencingDate.weekCommencingDate()
                .withDuration(4)
                .withStartDate("2025-03-02")
                .build();

        final Hearing firstHearing = buildHearing(courtHearingLocation, "TRI", LocalDate.now().plusMonths(1), "10:00", null);
        final Hearing secondHearing = buildHearing(courtHearingLocation, "ADJ", LocalDate.now().plusMonths(2), "11:00", null);
        final Hearing thirdHearing = buildHearing(courtHearingLocation, "FHG", LocalDate.now().plusMonths(3), "12:00", weekCommencingDate);

        return MigratedCase.migratedCase()
                .withHearings(asList(firstHearing, secondHearing, thirdHearing))
                .withCaseDetails(buildCaseDetails())
                .withDefendants(buildMigratedDefendants())
                .withMigrationSourceSystem(buildMigrationSourceSystem(migrationSourceSystemName))
                .build();
    }

    private static List<Defendant> buildMigratedDefendants() {
        List<Defendant> defendantList = new ArrayList<>();
        Defendant firstDefendant = Defendant.defendant()
                .withId(randomUUID())
                .withProsecutorDefendantId(randomUUID().toString())
                .withDocumentationLanguage("W")
                .withHearingLanguage("W")
                .withIndividual(individual()
                        .withSelfDefinedInformation(selfDefinedInformation()
                                .withGender(1)
                                .build())
                        .build())
                .withOffences(singletonList(buildOffence("TVL", "XHIBIT-offence-id-1")))
                .build();
        Defendant secondDefendant = Defendant.defendant()
                .withId(randomUUID())
                .withProsecutorDefendantId(randomUUID().toString())
                .withDocumentationLanguage("E")
                .withHearingLanguage("E")
                .withIndividual(individual()
                        .withSelfDefinedInformation(selfDefinedInformation()
                                .withGender(9)
                                .build())
                        .build())
                .withOffences(singletonList(buildOffence("TFL", "XHIBIT-defendant-id-2")))
                .build();
        defendantList.add(firstDefendant);
        defendantList.add(secondDefendant);
        return defendantList;
    }

    private static Offence buildOffence(final String offenceCode, final String prosecutorOffenceId) {
        return Offence.offence()
                .withProsecutorOffenceId(prosecutorOffenceId)
                .withOffenceCode(offenceCode)
                .withOffenceSequenceNumber(1)
                .withOffenceDateCode(1)
                .withOffenceCommittedDate(LocalDate.now().minusYears(2))
                .withOffenceWording("offence wording")
                .build();
    }

    public static Hearing buildHearing(final String courtHearingLocation, final String hearingType,
                                       final LocalDate dateOfHearing, final String timeOfHearing,
                                       final WeekCommencingDate weekCommencingDate) {
        return Hearing.hearing()
                .withCourtHearingLocation(courtHearingLocation)
                .withHearingType(hearingType)
                .withDateOfHearing(dateOfHearing)
                .withTimeOfHearing(timeOfHearing)
                .withWeekCommencingDate(weekCommencingDate)
                .withListedDefendants(singletonList(buildListedDefendant()))
                .build();
    }

    public static CaseDetails buildCaseDetails() {
        return CaseDetails.caseDetails()
                .withInitiationCode(InitiationCode.O)
                .withDateOfSending(LocalDate.now().minusMonths(3))
                .withDateOfCommittal(LocalDate.now().minusMonths(4))
                .withReceiptType("receiptType")
                .withRetrialIndicator(false)
                .withSendingCourt("B01LY00")
                .withReceivingCourt("C50EX00")
                .withCaseMarkers(singletonList(CaseMarker.caseMarker()
                        .withMarkerTypeCode("CD")
                        .build()))
                .withProsecutorCaseReference(CASE_URN)
                .build();
    }

    public static MigratedCaseSubmissionProcessed buildCaseSubmissionProcessed(final Boolean ProcessingIsSuccessful) {
        return MigratedCaseSubmissionProcessed.migratedCaseSubmissionProcessed()
                .withMigratedCaseSubmissionProcessed(MigratedCaseSubmissionProcessedOutput
                        .migratedCaseSubmissionProcessedOutput()
                        .withCaseId(CASE_ID)
                        .withCaseUrn(CASE_URN)
                        .withSubmissionId(SUBMISSION_ID)
                        .withProcessingIsSuccessful(ProcessingIsSuccessful)
                        .withDescription(DESCRIPTION)
                        .build())
                .build();

    }

    public static MigrationSourceSystem buildMigrationSourceSystem(final MigrationSourceSystemName migrationSourceSystemName) {
        return MigrationSourceSystem.migrationSourceSystem()
                .withMigrationSourceSystemCaseIdentifier("DLRM123")
                .withMigrationSourceSystemName(migrationSourceSystemName)
                .build();


    }

    public static Metadata buildMetaData(final String name) {
        return metadataBuilder()
                .createdAt(now())
                .withCausation(randomUUID())
                .withName(name)
                .withId(randomUUID())
                .build();

    }

    public static JsonEnvelope createMigratedCaseFileProcessedPublicEvent(final UUID submissionId, final UUID caseId, final String caseUrn, final Boolean processingIsSuccessful, final String description) {
        final JsonObject resultPayload = createObjectBuilder()
                .add("submissionId", submissionId.toString())
                .add("caseId", caseId.toString())
                .add("caseUrn", caseUrn)
                .add("processingIsSuccessful", processingIsSuccessful)
                .add("description", description)
                .build();

        return envelopeFrom(metadataOf(randomUUID(), "public.pcfdlrm.migrated-case-file-processed")
                        .withUserId(randomUUID().toString())
                        .build(),
                resultPayload);
    }

    public static ListedDefendant buildListedDefendant() {
        return ListedDefendant.listedDefendant()
                .withProsecutorDefendantId("source-system-defendant-1")
                .withListedOffences(singletonList("source-system-offence-1"))
                .build();
    }

    public static ErrorMigratedCaseSubmissionReceived buildErrorMigratedCaseSubmissionReceived() {
        return ErrorMigratedCaseSubmissionReceived.errorMigratedCaseSubmissionReceived()
                .withErrorMigratedCaseSubmission(ErrorMigratedCaseSubmission
                        .errorMigratedCaseSubmission()
                        .withPayload("{}")
                        .withSubmissionId(SUBMISSION_ID)
                        .withErrorMessage(DESCRIPTION)
                        .withCaseUrn(CASE_URN)
                        .withAzureLocation(AZURE_LOCATION)
                        .build())
                .build();

    }

}
