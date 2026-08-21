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
import java.util.stream.Stream;

import javax.json.JsonObject;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
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

    @ParameterizedTest(name = "{0}")
    @MethodSource("standardLookupScenarios")
    void shouldResolveCaseExistenceForStandardLookup(
            final String scenario,
            final JsonObject progressionCaseDetails,
            final boolean expectedExists) {

        final UUID existingCaseId = randomUUID();

        when(systemUserProvider.getContextSystemUserId()).thenReturn(Optional.of(randomUUID()));
        when(systemIdMapperClient.findBy(eq(MOCK_URN), any(), any(), any())).thenReturn(Optional.of(systemIdMapping));
        when(systemIdMapping.getTargetId()).thenReturn(existingCaseId);
        when(progressionService.getProsecutionCaseDetails(existingCaseId)).thenReturn(Optional.ofNullable(progressionCaseDetails));

        final SystemMapperService.CaseIdLookupResult result = systemMapperService.getCaseIdForPtiURN(MOCK_URN);

        verify(progressionService).getProsecutionCaseDetails(existingCaseId);
        assertThat(result.isCaseAlreadyProcessedAndExistsInProgression(), is(expectedExists));
        assertThat(result.getCaseId(), is(existingCaseId));
        verify(systemIdMapperClient, never()).remap(any(), any(), any());
        verify(systemIdMapperClient, never()).add(any(), any());
    }

    @Test
    void shouldRemapAndCreateNewMappingWhenCaseEjected() {
        final UUID existingCaseId = randomUUID();
        final UUID existingMappingId = randomUUID();
        final UUID systemUserId = randomUUID();

        when(systemUserProvider.getContextSystemUserId()).thenReturn(Optional.of(systemUserId));
        when(systemIdMapperClient.findBy(eq(MOCK_URN), any(), any(), any())).thenReturn(Optional.of(systemIdMapping));
        when(systemIdMapping.getTargetId()).thenReturn(existingCaseId);
        when(systemIdMapping.getMappingId()).thenReturn(existingMappingId);
        when(progressionService.getProsecutionCaseDetails(existingCaseId)).thenReturn(Optional.of(caseDetailsWithStatus("EJECTED")));
        when(systemIdMapperClient.add(any(), any())).thenReturn(additionResponse);
        when(additionResponse.isSuccess()).thenReturn(true);

        final SystemMapperService.CaseIdLookupResult result = systemMapperService.getCaseIdForPtiURN(MOCK_URN);

        verify(progressionService).getProsecutionCaseDetails(existingCaseId);
        assertThat(result.isCaseAlreadyProcessedAndExistsInProgression(), is(false));
        verify(systemIdMapperClient).remap(eq(MOCK_URN + "_Ejected"), eq(existingMappingId), eq(systemUserId));
        verify(systemIdMapperClient).add(argumentCaptor.capture(), any());
        assertThat(result.getCaseId(), is(argumentCaptor.getValue().getTargetId()));
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

    private static Stream<Arguments> standardLookupScenarios() {
        return Stream.of(
                Arguments.of("progression has no record of the case at all", null, false),
                Arguments.of("progression response is missing the prosecutionCase field", createObjectBuilder().build(), false),
                Arguments.of("progression case present but caseStatus field missing", caseDetailsWithNoStatus(), true),
                Arguments.of("progression case found with an active status", caseDetailsWithStatus("ACTIVE"), true)
        );
    }

    private static JsonObject caseDetailsWithStatus(final String caseStatus) {
        return createObjectBuilder()
                .add("prosecutionCase", createObjectBuilder().add("caseStatus", caseStatus).build())
                .build();
    }

    private static JsonObject caseDetailsWithNoStatus() {
        return createObjectBuilder().add("prosecutionCase", createObjectBuilder().build()).build();
    }
}
