package uk.gov.moj.cpp.stagingdlrm.event.processor.convertor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static uk.gov.justice.core.courts.Gender.MALE;
import static uk.gov.justice.core.courts.Gender.NOT_SPECIFIED;
import static uk.gov.moj.cpp.stagingdlrm.event.processor.ObjectBuilder.buildMigratedCase;
import static uk.gov.moj.cpp.stagingdlrm.json.schemas.MigrationSourceSystemName.XHIBIT;

import uk.gov.moj.cpp.prosecution.casefile.dlrm.migrated.json.schemas.MigratedCaseDetails;
import uk.gov.moj.cpp.stagingdlrm.json.schemas.CaseDetails;
import uk.gov.moj.cpp.stagingdlrm.json.schemas.Individual;
import uk.gov.moj.cpp.stagingdlrm.json.schemas.ParentGuardianInformation;
import uk.gov.moj.cpp.stagingdlrm.json.schemas.PersonalInformation;
import uk.gov.moj.cpp.stagingdlrm.json.schemas.SelfDefinedInformation;
import uk.gov.moj.cpp.stagingdlrm.migrated.json.schemas.Defendant;
import uk.gov.moj.cpp.stagingdlrm.migrated.json.schemas.MigratedCase;
import uk.gov.moj.cpp.stagingdlrm.migrated.json.schemas.Offence;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class MigratedCaseConvertorTest {

    @InjectMocks
    private MigratedCaseConvertor migratedCaseConvertor;

    @Test
    void shouldHandleEmptyMigratedCaseDetails() {

        final UUID caseId = UUID.randomUUID();

        final MigratedCase migratedCase = MigratedCase.migratedCase()
                .build();

        final MigratedCaseDetails migratedCaseDetails = migratedCaseConvertor.buildMigratedCasedetails(migratedCase, caseId);

        assertEquals(MigratedCaseDetails.migratedCaseDetails().build(), migratedCaseDetails);
    }

    @Test
    void shouldConvertGenderInformation() {

        final UUID caseId = UUID.randomUUID();

        final MigratedCase migratedCase = MigratedCase.migratedCase()
                .withDefendants(List.of(Defendant.defendant()
                        .withIndividual(Individual.individual()
                                .withParentGuardianInformation(ParentGuardianInformation.parentGuardianInformation()
                                        .withGender(1)
                                        .build())
                                .build())
                        .build()))
                .build();

        final MigratedCaseDetails migratedCaseDetails = migratedCaseConvertor.buildMigratedCasedetails(migratedCase, caseId);

        assertNotNull(migratedCaseDetails.getDefendants().get(0).getIndividual().getParentGuardianInformation().getGender());
    }

    @Test
    void shouldReturnEmptyGenderInformation() {

        final UUID caseId = UUID.randomUUID();

        final MigratedCase migratedCase = MigratedCase.migratedCase()
                .withDefendants(List.of(Defendant.defendant()
                        .withIndividual(Individual.individual()
                                .withParentGuardianInformation(ParentGuardianInformation.parentGuardianInformation()
                                        .build())
                                .build())
                        .build()))
                .build();

        final MigratedCaseDetails migratedCaseDetails = migratedCaseConvertor.buildMigratedCasedetails(migratedCase, caseId);

        assertNull(migratedCaseDetails.getDefendants().get(0).getIndividual().getParentGuardianInformation().getGender());
    }

    @Test
    void shouldConvertMigratedCaseToMigratedCaseDetails() {

        final UUID caseId = UUID.randomUUID();

        final MigratedCase migratedCase = buildMigratedCase(XHIBIT, "C50EX02");

        final MigratedCaseDetails migratedCaseDetails = migratedCaseConvertor.buildMigratedCasedetails(migratedCase, caseId);

        assertEquals(migratedCase.getMigrationSourceSystem().getMigrationSourceSystemName().name(),
                migratedCaseDetails.getMigrationSourceSystem().getMigrationSourceSystemName());
        assertEquals(migratedCase.getHearings().get(0).getListedDefendants().get(0).getListedOffences(),
                migratedCaseDetails.getHearings().get(0).getListedDefendants().get(0).getListedOffences());
        assertEquals(migratedCase.getHearings().get(2).getWeekCommencingDate().getDuration(),
                migratedCaseDetails.getHearings().get(2).getWeekCommencingDate().getDuration());
        assertEquals(MALE.name(), migratedCaseDetails.getDefendants().get(0).getIndividual().getSelfDefinedInformation().getGender());
        assertEquals(NOT_SPECIFIED.name(), migratedCaseDetails.getDefendants().get(1).getIndividual().getSelfDefinedInformation().getGender());
        assertEquals(NOT_SPECIFIED.name(), migratedCaseDetails.getDefendants().get(1).getIndividual().getSelfDefinedInformation().getGender());
        assertEquals(migratedCase.getCaseDetails().getCaseMarkers().get(0).getMarkerTypeCode(), migratedCaseDetails.getCaseDetails().getCaseMarkers().get(0).getMarkerTypeCode());
    }

    @Test
    void shouldPropagateGroupAFieldsWithPcfdlrmNames() {

        final UUID caseId = UUID.randomUUID();

        final MigratedCase migratedCase = MigratedCase.migratedCase()
                .withDefendants(List.of(Defendant.defendant()
                        .withIndividual(Individual.individual()
                                .withDriverNumber("DRV1234567890123")
                                .withNationalInsuranceNumber("SYNTHETIC-NI")
                                .withLicenseCode("A")
                                .withPersonalInformation(PersonalInformation.personalInformation()
                                        .withSurname("Brown")
                                        .withOccupation("Bricklayer")
                                        .withDefendantOccupationCode(12345)
                                        .build())
                                .withSelfDefinedInformation(SelfDefinedInformation.selfDefinedInformation()
                                        .withGender(1)
                                        .withAdditionalNationality("FRA")
                                        .build())
                                .build())
                        .withOffences(List.of(Offence.offence()
                                .withStatementOfFacts("The facts")
                                .withStatementOfFactsWelsh("Y ffeithiau")
                                .withVehicleMake("Ford")
                                .withVehicleCode("L")
                                .withVehicleRegistrationMark("AB12CDE")
                                .build()))
                        .build()))
                .build();

        final MigratedCaseDetails result = migratedCaseConvertor.buildMigratedCasedetails(migratedCase, caseId);

        final var individual = result.getDefendants().get(0).getIndividual();
        assertEquals("DRV1234567890123", individual.getDriverNumber());
        assertEquals("SYNTHETIC-NI", individual.getNationalInsuranceNumber());
        assertEquals("A", individual.getDriverLicenceCode());
        assertEquals("Bricklayer", individual.getPersonalInformation().getOccupation());
        assertEquals(12345, individual.getPersonalInformation().getOccupationCode());
        assertEquals("FRA", individual.getSelfDefinedInformation().getAdditionalNationality());

        final var offence = result.getDefendants().get(0).getOffences().get(0);
        assertEquals("The facts", offence.getStatementOfFacts());
        assertEquals("Y ffeithiau", offence.getStatementOfFactsWelsh());
        assertEquals("Ford", offence.getVehicleMake());
        assertNotNull(offence.getVehicleRelatedOffence());
        assertEquals("L", offence.getVehicleRelatedOffence().getVehicleCode());
        assertEquals("AB12CDE", offence.getVehicleRelatedOffence().getVehicleRegistrationMark());
    }

    @Test
    void shouldConvertMigratedCaseWhenSendingCourtIsMissing() {

        final UUID caseId = UUID.randomUUID();

        MigratedCase migratedCase=buildMigratedCase(XHIBIT, "C50EX02");

        final MigratedCase newMigratedCase = MigratedCase.migratedCase()
                .withValuesFrom(migratedCase)
                .withCaseDetails(CaseDetails.caseDetails()
                        .withValuesFrom(migratedCase.getCaseDetails())
                        .withSendingCourt(null)
                        .build())
                .build();

        final MigratedCaseDetails migratedCaseDetails = migratedCaseConvertor.buildMigratedCasedetails(newMigratedCase, caseId);

        assertNull(migratedCaseDetails.getCaseDetails().getSendingCourt());
    }
}