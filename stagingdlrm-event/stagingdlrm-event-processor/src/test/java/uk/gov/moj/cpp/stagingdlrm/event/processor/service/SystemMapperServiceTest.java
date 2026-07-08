package uk.gov.moj.cpp.stagingdlrm.event.processor.service;

import static java.lang.String.format;
import static java.util.UUID.randomUUID;
import static javax.json.Json.createObjectBuilder;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.Is.is;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import uk.gov.justice.services.core.dispatcher.SystemUserProvider;
import uk.gov.moj.cpp.systemidmapper.client.AdditionResponse;
import uk.gov.moj.cpp.systemidmapper.client.SystemIdMap;
import uk.gov.moj.cpp.systemidmapper.client.SystemIdMapperClient;
import uk.gov.moj.cpp.systemidmapper.client.SystemIdMapping;

import java.util.Optional;
import java.util.UUID;

import javax.json.JsonObject;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class SystemMapperServiceTest {

    private static final String MOCK_URN = "Mock URN";

    @InjectMocks
    private SystemMapperService systemMapperService;

    @Mock
    private SystemUserProvider systemUserProvider;

    @Mock
    private SystemIdMapperClient systemIdMapperClient;

    @Mock
    private SystemIdMapping systemIdMapping;

    @Mock
    private AdditionResponse additionResponse;

    @Mock
    private ProgressionService progressionService;

    @Captor
    private ArgumentCaptor<SystemIdMap> argumentCaptor;

    @Test
    void shouldReturnCorrectCaseIdWhenMappingExists() {
        final UUID expectedCaseId = randomUUID();

        when(systemUserProvider.getContextSystemUserId()).thenReturn(Optional.of(randomUUID()));
        when(systemIdMapperClient.findBy(eq(MOCK_URN), any(), any(), any())).thenReturn(Optional.of(systemIdMapping));
        when(systemIdMapping.getTargetId()).thenReturn(expectedCaseId);
        when(progressionService.getProsecutionCaseDetails(expectedCaseId)).thenReturn(Optional.empty());

        final SystemMapperService.CaseIdLookupResult result = systemMapperService.getCaseIdForPtiURN(MOCK_URN);

        assertThat(result.getCaseId(), is(expectedCaseId));
        assertThat(result.isCaseAlreadyProcessedAndExistsInProgression(), is(true));
        verify(systemIdMapperClient, never()).remap(any(), any(), any());
    }

    @Test
    void shouldRemapAndCreateNewCaseIdWhenCaseIsEjected() {
        final UUID existingCaseId = randomUUID();
        final UUID existingMappingId = randomUUID();
        final UUID systemUserId = randomUUID();
        final JsonObject caseDetailsResponse = createObjectBuilder()
                .add("prosecutionCase", createObjectBuilder().add("caseStatus", "EJECTED").build())
                .build();

        when(systemUserProvider.getContextSystemUserId()).thenReturn(Optional.of(systemUserId));
        when(systemIdMapperClient.findBy(eq(MOCK_URN), any(), any(), any())).thenReturn(Optional.of(systemIdMapping));
        when(systemIdMapping.getTargetId()).thenReturn(existingCaseId);
        when(systemIdMapping.getMappingId()).thenReturn(existingMappingId);
        when(progressionService.getProsecutionCaseDetails(existingCaseId)).thenReturn(Optional.of(caseDetailsResponse));
        when(systemIdMapperClient.add(any(), any())).thenReturn(additionResponse);
        when(additionResponse.isSuccess()).thenReturn(true);

        final SystemMapperService.CaseIdLookupResult result = systemMapperService.getCaseIdForPtiURN(MOCK_URN);

        verify(systemIdMapperClient).remap(eq(MOCK_URN + "_Ejected"), eq(existingMappingId), eq(systemUserId));
        verify(systemIdMapperClient).add(argumentCaptor.capture(), any());
        assertThat(result.getCaseId(), is(argumentCaptor.getValue().getTargetId()));
        assertThat(result.isCaseAlreadyProcessedAndExistsInProgression(), is(false));
    }

    @Test
    void shouldReturnCaseIdAndLogStatusWhenProgressionCaseFound() {
        final UUID expectedCaseId = randomUUID();
        final JsonObject caseDetailsResponse = createObjectBuilder()
                .add("prosecutionCase", createObjectBuilder().add("caseStatus", "ACTIVE").build())
                .build();

        when(systemUserProvider.getContextSystemUserId()).thenReturn(Optional.of(randomUUID()));
        when(systemIdMapperClient.findBy(eq(MOCK_URN), any(), any(), any())).thenReturn(Optional.of(systemIdMapping));
        when(systemIdMapping.getTargetId()).thenReturn(expectedCaseId);
        when(progressionService.getProsecutionCaseDetails(expectedCaseId)).thenReturn(Optional.of(caseDetailsResponse));

        final SystemMapperService.CaseIdLookupResult result = systemMapperService.getCaseIdForPtiURN(MOCK_URN);

        assertThat(result.getCaseId(), is(expectedCaseId));
        assertThat(result.isCaseAlreadyProcessedAndExistsInProgression(), is(true));
        verify(progressionService).getProsecutionCaseDetails(expectedCaseId);
    }

    @Test
    void shouldReturnCaseIdWhenProgressionResponseMissingProsecutionCaseField() {
        final UUID expectedCaseId = randomUUID();

        when(systemUserProvider.getContextSystemUserId()).thenReturn(Optional.of(randomUUID()));
        when(systemIdMapperClient.findBy(eq(MOCK_URN), any(), any(), any())).thenReturn(Optional.of(systemIdMapping));
        when(systemIdMapping.getTargetId()).thenReturn(expectedCaseId);
        when(progressionService.getProsecutionCaseDetails(expectedCaseId)).thenReturn(Optional.of(createObjectBuilder().build()));

        final SystemMapperService.CaseIdLookupResult result = systemMapperService.getCaseIdForPtiURN(MOCK_URN);

        assertThat(result.getCaseId(), is(expectedCaseId));
        assertThat(result.isCaseAlreadyProcessedAndExistsInProgression(), is(true));
    }

    @Test
    void shouldReturnCaseIdWhenProgressionReturnsNoCase() {
        final UUID expectedCaseId = randomUUID();

        when(systemUserProvider.getContextSystemUserId()).thenReturn(Optional.of(randomUUID()));
        when(systemIdMapperClient.findBy(eq(MOCK_URN), any(), any(), any())).thenReturn(Optional.of(systemIdMapping));
        when(systemIdMapping.getTargetId()).thenReturn(expectedCaseId);
        when(progressionService.getProsecutionCaseDetails(expectedCaseId)).thenReturn(Optional.empty());

        final SystemMapperService.CaseIdLookupResult result = systemMapperService.getCaseIdForPtiURN(MOCK_URN);

        assertThat(result.getCaseId(), is(expectedCaseId));
        assertThat(result.isCaseAlreadyProcessedAndExistsInProgression(), is(true));
    }

    @Test
    void shouldCreateAndReturnNewCaseIdWhenMappingDoesNotExist() {
        when(systemUserProvider.getContextSystemUserId()).thenReturn(Optional.of(randomUUID()));
        when(systemIdMapperClient.findBy(eq(MOCK_URN), any(), any(), any())).thenReturn(Optional.empty());
        when(systemIdMapperClient.add(any(), any())).thenReturn(additionResponse);
        when(additionResponse.isSuccess()).thenReturn(true);

        final SystemMapperService.CaseIdLookupResult result = systemMapperService.getCaseIdForPtiURN(MOCK_URN);

        verify(systemIdMapperClient).add(argumentCaptor.capture(), any());
        final SystemIdMap systemIdMap = argumentCaptor.getValue();

        assertThat(systemIdMap.getSourceId(), is(MOCK_URN));
        assertThat(result.getCaseId(), is(systemIdMap.getTargetId()));
        assertThat(result.isCaseAlreadyProcessedAndExistsInProgression(), is(false));
    }

    @Test
    void shouldThrowExceptionWhenAddingNewMappingFails() {
        when(systemUserProvider.getContextSystemUserId()).thenReturn(Optional.of(randomUUID()));
        when(systemIdMapperClient.findBy(eq(MOCK_URN), any(), any(), any())).thenReturn(Optional.empty());
        when(systemIdMapperClient.add(any(), any())).thenReturn(additionResponse);
        when(additionResponse.isSuccess()).thenReturn(false);

        final Exception e = assertThrows(Exception.class,
                () -> systemMapperService.getCaseIdForPtiURN(MOCK_URN));
        assertThat(e.getMessage(), is(format("Unable to creating mapping for input String %s to a uuid", MOCK_URN)));
    }

    @Test
    void shouldThrowExceptionWhenContextSystemUserIdNotPresent() {
        when(systemUserProvider.getContextSystemUserId()).thenReturn(Optional.empty());

        final Exception e = assertThrows(Exception.class,
                () -> systemMapperService.getCaseIdForPtiURN(MOCK_URN));
        assertThat(e.getMessage(), is(SystemMapperService.CONTEXT_SYSTEM_USER_ID_IS_NOT_PRESENT));
    }
}
