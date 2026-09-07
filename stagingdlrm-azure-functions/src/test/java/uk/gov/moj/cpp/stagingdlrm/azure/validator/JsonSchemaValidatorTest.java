package uk.gov.moj.cpp.stagingdlrm.azure.validator;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Set;
import java.util.UUID;

import com.microsoft.azure.functions.ExecutionContext;
import com.networknt.schema.ValidationMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class JsonSchemaValidatorTest {

    @Mock
    private ExecutionContext context;

    private JsonSchemaValidator caseValidator;

    private JsonSchemaValidator manifestValidator;

    @BeforeEach
    public void setup() {
        final String caseJsonSchema = "stagingdlrm.case-submission.json";
        caseValidator = new JsonSchemaValidator(context, caseJsonSchema);

        final String manifestJsonSchema = "stagingdlrm.manifest.json";
        manifestValidator = new JsonSchemaValidator(context, manifestJsonSchema);
    }

    @Test
    void validatePayloadSuccessfully() {

        String payload = """
                {
                    "migratedCase": {
                      "caseDetails": {
                        "originatingOrganisation": "G94DV00",
                        "summonsCode": "summonsCode",
                        "initiationCode": "O",
                        "prosecutorCaseReference": "TVL55117DFXXV",
                        "dateReceived": "2024-01-15",
                        "prosecutor": {
                          "prosecutingAuthority": "GAEAA01"
                        },
                        "sendingCourt": "C50EX00",
                        "receivingCourt": "B01LY00",
                        "dateOfSending": "2024-08-23",
                        "dateOfCommittal": "2024-01-15",
                        "receiptType": "Commital for sentence",
                        "retrialIndicator": false
                      },
                      "hearings": [
                        {
                          "courtHearingLocation": "B01LY01",
                          "dateOfHearing": "2024-11-10",
                          "timeOfHearing": "10:05:01",
                          "durationMinutes": 60,
                          "courtRoomId": 60,
                          "hearingType": "",
                          "listedDefendants": [
                            {
                              "prosecutorDefendantId": "LIBRA-defendant-id-1",
                              "listedOffences": [
                                "LIBRA-offence-id-1"
                              ]
                            },
                            {
                              "prosecutorDefendantId": "LIBRA-defendant-id-2",
                              "listedOffences": [
                                "LIBRA-offence-id-2"
                              ]
                            }
                          ]
                        }
                      ],
                      "defendants": [
                        {
                          "prosecutorDefendantId": "LIBRA-defendant-id-1",
                          "documentationLanguage": "W",
                          "hearingLanguage": "W",
                          "individual": {
                            "personalInformation": {
                              "title": "Baron",
                              "forename": "John",
                              "surname": "Smith",
                              "address": {
                                "address1": "armagaddon house",
                                "postcode": "M60 1NW"
                              },
                              "contactDetails": {}
                            },
                            "selfDefinedInformation": {
                              "ethnicity": "W1",
                              "gender": 1
                            }
                          },
                          "offences": [
                            {
                              "prosecutorOffenceId": "LIBRA-offence-id-1",
                              "offenceCode": "CA03013",
                              "offenceSequenceNumber": 1,
                              "offenceCommittedDate": "2018-09-10",
                              "offenceDateCode": 1,
                              "offenceLocation": "Croydon",
                              "offenceWording": "TV Licence not paid",
                              "chargeDate": "2015-04-04",
                              "laidDate": "2015-04-04"
                            }
                          ],
                          "postingDate": "2018-09-10"
                        },
                        {
                          "prosecutorDefendantId": "LIBRA-defendant-id-2",
                          "documentationLanguage": "W",
                          "hearingLanguage": "W",
                          "individual": {
                            "personalInformation": {
                              "title": "Baron",
                              "forename": "John",
                              "surname": "Smith",
                              "address": {
                                "address1": "armagaddon house",
                                "postcode": "M60 1NW"
                              },
                              "contactDetails": {}
                            },
                            "selfDefinedInformation": {
                              "ethnicity": "W1",
                              "gender": 2
                            }
                          },
                          "offences": [
                            {
                              "prosecutorOffenceId": "LIBRA-offence-id-2",
                              "offenceCode": "DA03014",
                              "offenceSequenceNumber": 1,
                              "offenceCommittedDate": "2018-09-10",
                              "offenceDateCode": 1,
                              "offenceLocation": "Croydon",
                              "offenceWording": "TV Licence not paid",
                              "chargeDate": "2015-04-04"
                            }
                          ],
                          "postingDate": "2018-09-10"
                        }
                      ],
                      "migrationSourceSystem": {
                        "migrationSourceSystemName": "LIBRA",
                        "migrationSourceSystemCaseIdentifier": "This is from Libra"
                      }
                    }
                  }
                """;

        final Set<ValidationMessage> validationMessages = caseValidator.validate(UUID.randomUUID().toString(), payload);
        assertEquals(0, validationMessages.size());

    }

    @Test
    void validateEmptyCasePayload() {
        String payload = """
                {
                   }
                """;

        final Set<ValidationMessage> validationMessages = caseValidator.validate(UUID.randomUUID().toString(), payload);
        assertEquals(1, validationMessages.size());
    }

    @Test
    void validateEmptyManifestPayload() {
        String payload = """
                {
                   }
                """;

        final Set<ValidationMessage> validationMessages = manifestValidator.validate(UUID.randomUUID().toString(), payload);
        assertEquals(1, validationMessages.size());
    }

    @Test
    void validateManifestPayloadWithFiles() {
        String payload = """
                {
                      "files" : [ {
                        "fileName" : "WitnessStatementDocument_1.pdf",
                        "fileType" : "8",
                        "documentType" : 1
                      }, {
                        "fileName" : "WitnessStatementDocument_2.pdf",
                        "fileType" : "8",
                        "documentType" : 1
                      } ],
                      "migrationSourceSystem" : {
                        "migrationSourceSystemName" : "XHIBIT",
                        "migrationSourceSystemCaseIdentifier" : "XHIBIT-HEARINGS"
                      }
                    }
                """;

        final Set<ValidationMessage> validationMessages = manifestValidator.validate(UUID.randomUUID().toString(), payload);
        assertEquals(0, validationMessages.size());
    }

    @Test
    void validateManifestPayloadWithoutFiles() {
        String payload = """
                {
                      "migrationSourceSystem" : {
                        "migrationSourceSystemName" : "XHIBIT",
                        "migrationSourceSystemCaseIdentifier" : "XHIBIT-HEARINGS"
                      }
                    }
                """;

        final Set<ValidationMessage> validationMessages = manifestValidator.validate(UUID.randomUUID().toString(), payload);
        assertEquals(0, validationMessages.size());
    }
}