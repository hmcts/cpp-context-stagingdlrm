package uk.gov.moj.cpp.stagingdlrm.event.processor.convertor;

import static java.time.LocalDate.parse;
import static java.util.Objects.isNull;
import static java.util.Objects.nonNull;
import static java.util.UUID.randomUUID;
import static uk.gov.moj.cpp.stagingdlrm.event.processor.domain.MigratedGender.getValueFromCode;

import uk.gov.moj.cpp.prosecution.casefile.dlrm.json.schemas.AlcoholRelatedOffence;
import uk.gov.moj.cpp.prosecution.casefile.dlrm.json.schemas.CaseDetails;
import uk.gov.moj.cpp.prosecution.casefile.dlrm.json.schemas.CaseMarker;
import uk.gov.moj.cpp.prosecution.casefile.dlrm.json.schemas.Individual;
import uk.gov.moj.cpp.prosecution.casefile.dlrm.json.schemas.IndividualAlias;
import uk.gov.moj.cpp.prosecution.casefile.dlrm.json.schemas.ParentGuardianInformation;
import uk.gov.moj.cpp.prosecution.casefile.dlrm.json.schemas.SelfDefinedInformation;
import uk.gov.moj.cpp.prosecution.casefile.dlrm.json.schemas.VehicleRelatedOffence;
import uk.gov.moj.cpp.prosecution.casefile.dlrm.migrated.json.schemas.ListedDefendant;
import uk.gov.moj.cpp.prosecution.casefile.dlrm.migrated.json.schemas.MigratedAllocationDecision;
import uk.gov.moj.cpp.prosecution.casefile.dlrm.migrated.json.schemas.MigratedCaseDetails;
import uk.gov.moj.cpp.prosecution.casefile.dlrm.migrated.json.schemas.MigratedDefendant;
import uk.gov.moj.cpp.prosecution.casefile.dlrm.migrated.json.schemas.MigratedHearing;
import uk.gov.moj.cpp.prosecution.casefile.dlrm.migrated.json.schemas.MigratedOffence;
import uk.gov.moj.cpp.prosecution.casefile.dlrm.migrated.json.schemas.MigratedPlea;
import uk.gov.moj.cpp.prosecution.casefile.dlrm.migrated.json.schemas.MigratedVerdict;
import uk.gov.moj.cpp.prosecution.casefile.dlrm.migrated.json.schemas.MigratedWeekCommencingDate;
import uk.gov.moj.cpp.prosecution.casefile.dlrm.migrated.json.schemas.MigrationSourceSystem;
import uk.gov.moj.cpp.stagingdlrm.json.schemas.AllocationDecision;
import uk.gov.moj.cpp.stagingdlrm.json.schemas.Plea;
import uk.gov.moj.cpp.stagingdlrm.json.schemas.Verdict;
import uk.gov.moj.cpp.stagingdlrm.migrated.json.schemas.Defendant;
import uk.gov.moj.cpp.stagingdlrm.migrated.json.schemas.Hearing;
import uk.gov.moj.cpp.stagingdlrm.migrated.json.schemas.MigratedCase;
import uk.gov.moj.cpp.stagingdlrm.migrated.json.schemas.MigratedMaterial;
import uk.gov.moj.cpp.stagingdlrm.migrated.json.schemas.Offence;
import uk.gov.moj.cpp.stagingdlrm.migrated.json.schemas.WeekCommencingDate;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class MigratedCaseConvertor implements Serializable {

    public MigratedCaseDetails buildMigratedCasedetails(final MigratedCase migratedCase, final UUID caseId) {
        return MigratedCaseDetails.migratedCaseDetails()
                .withMigrationSourceSystem(buildMigrationSourceSystem(migratedCase.getMigrationSourceSystem()))
                .withCaseDetails(buildCaseDetails(migratedCase.getCaseDetails(), caseId))
                .withHearings(buildMigratedHearings(migratedCase.getHearings()))
                .withDefendants(buildMigratedDefendants(migratedCase.getDefendants()))
                .build();
    }

    public static List<MigratedDefendant> buildMigratedDefendants(final List<Defendant> defendants) {
        if (isNull(defendants)) return null;
        List<MigratedDefendant> migratedDefendants = new ArrayList<>();
        defendants.forEach(e -> migratedDefendants.add(MigratedDefendant.migratedDefendant()
                .withId(randomUUID())
                .withAddress(buildAddress(e.getAddress()))
                .withProsecutorDefendantId(e.getProsecutorDefendantId())
                .withAsn(e.getAsn())
                .withDocumentationLanguage(e.getDocumentationLanguage())
                .withHearingLanguage(e.getHearingLanguage())
                .withLanguageRequirement(e.getLanguageRequirement())
                .withSpecificRequirements(e.getSpecificRequirements())
                .withIndividual(buildIndividual(e.getIndividual()))
                .withOffences(buildOffences(e.getOffences()))
                .withEmailAddress1(e.getEmailAddress1())
                .withEmailAddress2(e.getEmailAddress2())
                .withOrganisationName(e.getOrganisationName())
                .withPncIdentifier(e.getPncIdentifier())
                .withTelephoneNumberBusiness(e.getTelephoneNumberBusiness())
                .withCroNumber(e.getCroNumber())
                .withIndividualAliases(buildIndividualAliases(e.getIndividualAliases()))
                .withAliasForCorporate(e.getAliasForCorporate())
                .build()));

        return migratedDefendants;
    }

    public static List<IndividualAlias> buildIndividualAliases(final List<uk.gov.moj.cpp.stagingdlrm.json.schemas.IndividualAlias> individualAliases) {
        if (isNull(individualAliases)) return null;
        List<IndividualAlias> pcfIndividualAliases = new ArrayList<>();
        individualAliases.forEach(e -> pcfIndividualAliases.add(
                IndividualAlias.individualAlias()
                        .withTitle(e.getTitle())
                        .withFirstName(e.getFirstName())
                        .withGivenName2(e.getGivenName2())
                        .withGivenName3(e.getGivenName3())
                        .withLastName(e.getLastName())
                        .build()
        ));
        return pcfIndividualAliases;
    }

    public static List<MigratedOffence> buildOffences(final List<Offence> offences) {
        if (isNull(offences)) return null;
        List<MigratedOffence> migratedOffences = new ArrayList<>();
        offences.forEach(e -> migratedOffences.add(
                MigratedOffence.migratedOffence()
                        .withOffenceId(randomUUID())
                        .withProsecutorOffenceId(e.getProsecutorOffenceId())
                        .withArrestDate(e.getArrestDate())
                        .withOffenceCode(e.getOffenceCode())
                        .withOffenceSequenceNumber(e.getOffenceSequenceNumber())
                        .withChargeDate(e.getChargeDate())
                        .withOffenceDateCode(e.getOffenceDateCode())
                        .withOffenceCommittedDate(e.getOffenceCommittedDate())
                        .withOffenceCommittedEndDate(e.getOffenceCommittedEndDate())
                        .withOffenceLocation(e.getOffenceLocation())
                        .withOffenceWording(e.getOffenceWording())
                        .withOffenceWordingWelsh(e.getOffenceWordingWelsh())
                        .withAlcoholRelatedOffence(buildAlcoholRelatedOffence(e.getAlcoholRelatedOffence()))
                        .withStatementOfFacts(e.getStatementOfFacts())
                        .withStatementOfFactsWelsh(e.getStatementOfFactsWelsh())
                        .withVehicleMake(e.getVehicleMake())
                        .withVehicleRelatedOffence(buildVehicleRelatedOffence(e))
                        .withPlea(buildPlea(e.getPlea()))
                        .withVerdict(buildVerdict(e.getVerdict()))
                        .withAllocationDecision(buildAllocationDecision(e.getAllocationDecision()))
                        .withConvictingCourtCode(e.getConvictingCourtCode())
                        .withCount(e.getCount())
                        .build()
        ));
        return migratedOffences;
    }

    public static MigratedAllocationDecision buildAllocationDecision(final AllocationDecision allocationDecision) {
        if (isNull(allocationDecision)) return null;
        return MigratedAllocationDecision.migratedAllocationDecision()
                .withMotReasonId(allocationDecision.getMotReasonId())
                .withAllocationDecisionDate(allocationDecision.getAllocationDecisionDate())
                .build();
    }


    public static MigratedVerdict buildVerdict(final Verdict verdict) {
        if (isNull(verdict)) return null;
        return MigratedVerdict.migratedVerdict()
                .withId(verdict.getId())
                .withVerdictDate(verdict.getVerdictDate())
                .build();
    }


    public static MigratedPlea buildPlea(final Plea plea) {
        if (isNull(plea)) return null;
        return MigratedPlea.migratedPlea()
                .withId(plea.getId())
                .withPleaDate(plea.getPleaDate())
                .build();
    }

    public static AlcoholRelatedOffence buildAlcoholRelatedOffence(final uk.gov.moj.cpp.stagingdlrm.json.schemas.AlcoholRelatedOffence alcoholRelatedOffence) {
        if (isNull(alcoholRelatedOffence)) return null;
        return AlcoholRelatedOffence.alcoholRelatedOffence()
                .withAlcoholLevelAmount(alcoholRelatedOffence.getAlcoholOrDrugLevelAmount())
                .withAlcoholLevelMethod(alcoholRelatedOffence.getAlcoholOrDrugLevelMethod())
                .build();
    }



    public static VehicleRelatedOffence buildVehicleRelatedOffence(final Offence offence) {
        if (isNull(offence.getVehicleCode()) && isNull(offence.getVehicleRegistrationMark())) return null;
        return VehicleRelatedOffence.vehicleRelatedOffence()
                .withVehicleCode(offence.getVehicleCode())
                .withVehicleRegistrationMark(offence.getVehicleRegistrationMark())
                .build();
    }

    public static Individual buildIndividual(final uk.gov.moj.cpp.stagingdlrm.json.schemas.Individual individual) {
        if (isNull(individual)) return null;
        return Individual.individual()
                .withBailConditions(individual.getBailConditions())
                .withSelfDefinedInformation(buildSelfDefinedInformation(individual.getSelfDefinedInformation()))
                .withPersonalInformation(buildPersonalInformation(individual.getPersonalInformation()))
                .withParentGuardianInformation(buildParentGuardianInformation(individual.getParentGuardianInformation()))
                .withCustodyStatus(individual.getCustodyStatus())
                .withCustodyTimeLimit(individual.getCustodyTimeLimit())
                .withDriverNumber(individual.getDriverNumber())
                .withNationalInsuranceNumber(individual.getNationalInsuranceNumber())
                .withDriverLicenceCode(individual.getLicenseCode())
                .build();
    }

    public static ParentGuardianInformation buildParentGuardianInformation(final uk.gov.moj.cpp.stagingdlrm.json.schemas.ParentGuardianInformation parentGuardianInformation) {
        if (isNull(parentGuardianInformation)) return null;

        final Integer gender = parentGuardianInformation.getGender();

        final ParentGuardianInformation.Builder parentGuardianInformationBuilder = ParentGuardianInformation.parentGuardianInformation()
                .withPersonalInformation(buildPersonalInformation(parentGuardianInformation.getPersonalInformation()))
                .withAddress(buildAddress(parentGuardianInformation.getAddress()))
                .withDateOfBirth(parentGuardianInformation.getDateOfBirth())
                .withSelfDefinedEthnicity(parentGuardianInformation.getSelfDefinedEthnicity())
                .withOrganisationName(parentGuardianInformation.getOrganisationName())
                .withCompanyTelephoneNumber(parentGuardianInformation.getCompanyTelephoneNumber());

        if (gender != null) {
            parentGuardianInformationBuilder.withGender(getValueFromCode(parentGuardianInformation.getGender()));
        }

        return parentGuardianInformationBuilder.build();
    }

    public static SelfDefinedInformation buildSelfDefinedInformation(final uk.gov.moj.cpp.stagingdlrm.json.schemas.SelfDefinedInformation selfDefinedInformation) {
        if (isNull(selfDefinedInformation)) return null;
        return SelfDefinedInformation.selfDefinedInformation()
                .withNationality(selfDefinedInformation.getNationality())
                .withEthnicity(selfDefinedInformation.getEthnicity())
                .withDateOfBirth(selfDefinedInformation.getDateOfBirth())
                .withGender(getValueFromCode(selfDefinedInformation.getGender()))
                .withAdditionalNationality(selfDefinedInformation.getAdditionalNationality())
                .build();
    }

    public static List<MigratedHearing> buildMigratedHearings(final List<Hearing> hearings) {
        if (isNull(hearings)) return null;
        List<MigratedHearing> migratedHearings = new ArrayList<>();
        hearings.forEach(e -> migratedHearings.add(buildMigratedHearing(e)));
        return migratedHearings;
    }

    private static MigratedHearing buildMigratedHearing(final Hearing e) {
        return MigratedHearing.migratedHearing()
                .withCourtHearingLocation(e.getCourtHearingLocation())
                .withCourtRoomId(e.getCourtRoomId())
                .withDateOfHearing((e.getDateOfHearing() != null)
                        ? String.valueOf(e.getDateOfHearing())
                        : null)
                .withTimeOfHearing(e.getTimeOfHearing())
                .withDurationMinutes(e.getDurationMinutes())
                .withWeekCommencingDate(buildWeekCommencing(e.getWeekCommencingDate()))
                .withHearingType(e.getHearingType())
                .withListedDefendants(buildListedDefendants(e.getListedDefendants()))
                .build();
    }

    public static List<ListedDefendant> buildListedDefendants(final List<uk.gov.moj.cpp.stagingdlrm.migrated.json.schemas.ListedDefendant> listedDefendants) {
        if (isNull(listedDefendants)) return null;
        List<ListedDefendant> listedPCFDefendants = new ArrayList<>();
        listedDefendants.forEach(e -> listedPCFDefendants.add(
                ListedDefendant.listedDefendant()
                        .withProsecutorDefendantId(e.getProsecutorDefendantId())
                        .withListedOffences(e.getListedOffences())
                        .build()
        ));
        return listedPCFDefendants;
    }

    public static MigratedWeekCommencingDate buildWeekCommencing(final WeekCommencingDate weekCommencingDate) {
        if (isNull(weekCommencingDate)) return null;
        return MigratedWeekCommencingDate.migratedWeekCommencingDate()
                .withDuration(weekCommencingDate.getDuration())
                .withStartDate(parse(weekCommencingDate.getStartDate()))
                .build();
    }

    public static MigrationSourceSystem buildMigrationSourceSystem(final uk.gov.moj.cpp.stagingdlrm.json.schemas.MigrationSourceSystem migrationSourceSystem) {
        if (isNull(migrationSourceSystem)) return null;
        return MigrationSourceSystem.migrationSourceSystem()
                .withMigrationSourceSystemCaseIdentifier(migrationSourceSystem.getMigrationSourceSystemCaseIdentifier())
                .withMigrationSourceSystemName(migrationSourceSystem.getMigrationSourceSystemName().name())
                .build();
    }

    public static CaseDetails buildCaseDetails(final uk.gov.moj.cpp.stagingdlrm.json.schemas.CaseDetails caseDetails, final UUID caseId) {
        if (isNull(caseDetails)) return null;
        return CaseDetails.caseDetails()
                .withCaseId(caseId)
                .withProsecutorCaseReference(caseDetails.getProsecutorCaseReference())
                .withOriginatingOrganisation(caseDetails.getOriginatingOrganisation())
                .withInitiationCode(caseDetails.getInitiationCode().name())
                .withCpsOrganisation(caseDetails.getCpsOrganisation())
                .withDateReceived(caseDetails.getDateReceived())
                .withDateOfSending(caseDetails.getDateOfSending())
                .withDateOfCommittal(caseDetails.getDateOfCommittal())
                .withReceiptType(caseDetails.getReceiptType())
                .withRetrialIndicator(caseDetails.getRetrialIndicator())
                .withSendingCourt(caseDetails.getSendingCourt())
                .withReceivingCourt(caseDetails.getReceivingCourt())
                .withProsecutor(buildProsecutor(caseDetails.getProsecutor()))
                .withCaseMarkers(nonNull(caseDetails.getCaseMarkers()) ? buildCaseMarkers(caseDetails.getCaseMarkers()) : null)
                .build();
    }

    public static uk.gov.moj.cpp.prosecution.casefile.dlrm.json.schemas.Prosecutor buildProsecutor(final uk.gov.moj.cpp.stagingdlrm.json.schemas.Prosecutor prosecutor) {
        if (isNull(prosecutor)) return null;
        return uk.gov.moj.cpp.prosecution.casefile.dlrm.json.schemas.Prosecutor.prosecutor()
                .withProsecutingAuthority(prosecutor.getProsecutingAuthority())
                .build();
    }


    public static uk.gov.moj.cpp.prosecution.casefile.dlrm.json.schemas.PersonalInformation buildPersonalInformation(final uk.gov.moj.cpp.stagingdlrm.json.schemas.PersonalInformation personalInformation) {
        if (isNull(personalInformation)) return null;
        return uk.gov.moj.cpp.prosecution.casefile.dlrm.json.schemas.PersonalInformation.personalInformation()
                .withTitle(personalInformation.getTitle())
                .withFirstName(personalInformation.getForename())
                .withLastName(personalInformation.getSurname())
                .withGivenName2(personalInformation.getMiddleName())
                .withObservedEthnicity(personalInformation.getObservedEthnicity())
                .withContactDetails(buildContactDetails(personalInformation.getContactDetails()))
                .withAddress(buildAddress(personalInformation.getAddress()))
                .withOccupation(personalInformation.getOccupation())
                .withOccupationCode(personalInformation.getDefendantOccupationCode())
                .build();
    }

    public static uk.gov.moj.cpp.prosecution.casefile.dlrm.json.schemas.Address buildAddress(final uk.gov.moj.cpp.stagingdlrm.json.schemas.Address address) {
        if (isNull(address)) return null;
        return uk.gov.moj.cpp.prosecution.casefile.dlrm.json.schemas.Address.address()
                .withAddress1(address.getAddress1())
                .withAddress2(address.getAddress2())
                .withAddress3(address.getAddress3())
                .withAddress4(address.getAddress4())
                .withAddress5(address.getAddress5())
                .withPostcode(address.getPostcode())
                .build();
    }

    public static uk.gov.moj.cpp.prosecution.casefile.dlrm.json.schemas.ContactDetails buildContactDetails(final uk.gov.moj.cpp.stagingdlrm.json.schemas.ContactDetails contactDetails) {
        if (isNull(contactDetails)) return null;
        return uk.gov.moj.cpp.prosecution.casefile.dlrm.json.schemas.ContactDetails.contactDetails()
                .withWork(contactDetails.getWork())
                .withHome(contactDetails.getHome())
                .withMobile(contactDetails.getMobile())
                .withPrimaryEmail(contactDetails.getPrimaryEmail())
                .withSecondaryEmail(contactDetails.getSecondaryEmail())
                .build();
    }

    public static List<uk.gov.moj.cpp.prosecution.casefile.dlrm.migrated.json.schemas.MigratedMaterial> buildMaterials(final List<MigratedMaterial> materials, final UUID caseId) {
        List<uk.gov.moj.cpp.prosecution.casefile.dlrm.migrated.json.schemas.MigratedMaterial> pcfMaterials = new ArrayList<>();

        materials.forEach(e -> pcfMaterials.add(uk.gov.moj.cpp.prosecution.casefile.dlrm.migrated.json.schemas.MigratedMaterial.migratedMaterial()
                .withCaseId(caseId)
                .withId(e.getId())
                .withFileName(e.getFileName())
                .withFileType(e.getFileType())
                .withAzureLocation(e.getAzureLocation())
                .withReceivedDateTime(e.getReceivedDateTime())
                .withDocumentType(e.getDocumentType())
                .withSectionCode(e.getSectionCode())
                .withDefendantId(e.getDefendantId())
                .withDocumentCategory(e.getDocumentCategory())
                .build())
        );
        return pcfMaterials;
    }

    public static List<CaseMarker> buildCaseMarkers(List<uk.gov.moj.cpp.stagingdlrm.json.schemas.CaseMarker> caseMarkers) {
        return caseMarkers.stream()
                .map(caseMarker -> CaseMarker.caseMarker()
                        .withMarkerTypeCode(caseMarker.getMarkerTypeCode())
                        .build())
                .toList();

    }
}
