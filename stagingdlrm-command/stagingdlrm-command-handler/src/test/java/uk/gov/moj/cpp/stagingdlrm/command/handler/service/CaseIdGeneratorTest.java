package uk.gov.moj.cpp.stagingdlrm.command.handler.service;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
public class CaseIdGeneratorTest {

    @InjectMocks
    private CaseIdGenerator caseIdGenerator;

    @Test
    public void shouldReturnCaseIdwithURNAndSubmissionId() {
        final String urn = "theURN";
        final UUID submissionId = UUID.randomUUID();

        final UUID uuid = caseIdGenerator.generateCaseIdFromCaseUrnAndSubmissionId(urn, submissionId);

        assertEquals(UUID.nameUUIDFromBytes(urn.concat(submissionId.toString()).getBytes()), uuid);
    }

}
