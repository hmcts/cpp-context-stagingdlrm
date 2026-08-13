package uk.gov.moj.cpp.stagingdlrm.azure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.params.provider.Arguments.arguments;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static uk.gov.justice.services.test.utils.common.reflection.ReflectionUtils.setField;

import uk.gov.moj.cpp.stagingdlrm.azure.event.EventGridSchema;
import uk.gov.moj.cpp.stagingdlrm.azure.storage.StorageCloudClient;

import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Stream;

import java.util.logging.Logger;

import com.azure.core.http.rest.Response;
import com.azure.storage.queue.models.SendMessageResult;
import com.microsoft.azure.functions.ExecutionContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
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

/**
 * DD-43078 FR3/FR9 — the Function App's blob-path gate as scenario rows of
 * ({@code dlrm_folder_name}, {@code dlrm_batch_name}, blob path, accepted?), so a new source system
 * or folder/batch configuration is one {@code arguments(...)} and never a new test method
 * (DD-43078 AC2, T2 AC8).
 *
 * <p>Verified against {@code EventGridTriggerJava.run}: {@code dlrm_batch_name} is a comma-separated
 * list, trimmed, matched case-insensitively, with a leading {@code *} meaning "match anything".
 * {@code dlrm_folder_name} was none of those — a single {@code trim().equalsIgnoreCase()}.
 *
 * <p>DD-43086 LIBRA01 (FR1/FR7) flips that: the folder gate now accepts a comma-separated
 * source-system list too, routed through the same {@code validateConfiguredNames} helper the batch
 * gate uses — but with {@code wildcardAllowed=false}, because the folder name <i>is</i> the
 * source-system gate and a {@code *} must never widen it (AC2). The row that previously pinned the
 * gap ("a comma-separated folder list is NOT supported yet") is now the AC1 acceptance row, and
 * new rows pin the AC2 wildcard rejection. Every batch-name row stays exactly as it was — the batch
 * gate's {@code *} behaviour is unchanged (AC9 regression).
 */
@ExtendWith(MockitoExtension.class)
class EventGridTriggerJavaTest {

    private static final String CONTAINER = "dlrmcontainer";
    private static final String CASE_ID = "a1b2c3d4-0000-0000-0000-000000000001";
    private static final String SUBMISSION_ID = "a1b2c3d4-0000-0000-0000-000000000002";

    /** Leading whitespace is deliberate: it pins that the env var is trimmed. */
    private static final String FOLDER_ENV = " XHIBIT";
    private static final String BATCH_ENV = " batch0001,batch0002";

    @Mock
    private static StorageCloudClient storageCloudClient;

    @Mock
    private ExecutionContext context;

    @Mock
    private Response<SendMessageResult> response;

    @Mock
    private SendMessageResult sendMessageResult;

    @Captor
    private ArgumentCaptor<String> stringArgumentCaptor;

    @InjectMocks
    private EventGridTriggerJava eventGridTriggerJava;

    private final Logger logger = Logger.getLogger(EventGridTriggerJava.class.getName());

    @BeforeEach
    public void setup() {
        setField(eventGridTriggerJava, "storageCloudClient", storageCloudClient);
        setField(eventGridTriggerJava, "folderName", FOLDER_ENV);
        setField(eventGridTriggerJava, "batchName", BATCH_ENV);
    }

    private static String blobUrl(final String path) {
        return "https://stedlrmsa.blob.core.windows.net/" + CONTAINER + "/" + path;
    }

    private static String submissionPath(final String folder, final String batch) {
        return "%s/%s/%s/%s/test1.json".formatted(folder, batch, CASE_ID, SUBMISSION_ID);
    }

    private static EventGridSchema event(final String url) {
        final Map<String, Object> data = new HashMap<>();
        data.put("url", url);
        return new EventGridSchema(new Date(), data);
    }

    static Stream<Arguments> pathValidationScenarios() {
        return Stream.of(
                arguments("FR9 an XHIBIT submission in the first configured batch is enqueued (XHIBIT)",
                        FOLDER_ENV, BATCH_ENV, submissionPath("XHIBIT", "Batch0001"), true),
                arguments("FR9 a comma-separated batch list matches its second member (XHIBIT)",
                        FOLDER_ENV, BATCH_ENV, submissionPath("XHIBIT", "Batch0002"), true),
                arguments("FR9 a batch outside the configured list is dropped (XHIBIT)",
                        FOLDER_ENV, BATCH_ENV, submissionPath("XHIBIT", "Batch0003"), false),
                arguments("FR9 batch matching is case-insensitive (XHIBIT)",
                        FOLDER_ENV, "BATCH0001", submissionPath("XHIBIT", "batch0001"), true),
                arguments("FR9 a leading wildcard batch matches anything (XHIBIT)",
                        FOLDER_ENV, "*", submissionPath("XHIBIT", "Batch0009"), true),
                arguments("FR9 a wildcard first entry short-circuits the rest of the list (XHIBIT)",
                        FOLDER_ENV, "*,batch0001", submissionPath("XHIBIT", "anything-at-all"), true),
                arguments("FR9 folder matching is case-insensitive and trimmed (XHIBIT)",
                        "  xhibit  ", BATCH_ENV, submissionPath("XHIBIT", "Batch0001"), true),
                arguments("FR9 a folder other than the configured one is dropped",
                        FOLDER_ENV, BATCH_ENV, submissionPath("XHIBIT1", "Batch0001"), false),
                // DD-43086 LIBRA01/AC1 — the folder gate now accepts a comma-separated source-system list.
                // This row was the "NOT supported yet" gap-pinning row; it flips to accepted here.
                arguments("LIBRA01/AC1 a comma-separated folder list accepts its first member (XHIBIT)",
                        "XHIBIT,LIBRA", BATCH_ENV, submissionPath("XHIBIT", "Batch0001"), true),
                arguments("LIBRA01/AC1 a comma-separated folder list accepts its second member (LIBRA)",
                        "XHIBIT,LIBRA", BATCH_ENV, submissionPath("LIBRA", "Batch0001"), true),
                arguments("LIBRA01/AC1 a folder outside the configured source-system list is dropped (COMPASS)",
                        "XHIBIT,LIBRA", BATCH_ENV, submissionPath("COMPASS", "Batch0001"), false),
                arguments("LIBRA01/AC1 the folder list is trimmed and matched case-insensitively (LIBRA)",
                        " xhibit , libra ", BATCH_ENV, submissionPath("LIBRA", "Batch0001"), true),
                // DD-43086 LIBRA01/AC2 — the wildcard must NOT widen the source-system gate, unlike batch.
                arguments("LIBRA01/AC2 dlrm_folder_name=* must NOT widen the folder gate — a literal * is not a configured folder (XHIBIT)",
                        "*", BATCH_ENV, submissionPath("XHIBIT", "Batch0001"), false),
                arguments("LIBRA01/AC2 dlrm_folder_name=* rejects a LIBRA folder too — the wildcard never applies to the folder gate",
                        "*", BATCH_ENV, submissionPath("LIBRA", "Batch0001"), false),
                arguments("LIBRA01/AC2 a wildcard entry inside the folder list still does not widen the folder gate",
                        "XHIBIT,*", BATCH_ENV, submissionPath("COMPASS", "Batch0001"), false),
                arguments("FR9 a path with fewer than four tokens is dropped",
                        FOLDER_ENV, BATCH_ENV, "test1.json", false));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("pathValidationScenarios")
    void shouldValidateBlobPath(final String scenario,
                                final String folderNameEnv,
                                final String batchNameEnv,
                                final String blobPath,
                                final boolean enqueued) {

        setField(eventGridTriggerJava, "folderName", folderNameEnv);
        setField(eventGridTriggerJava, "batchName", batchNameEnv);

        when(context.getLogger()).thenReturn(logger);
        when(storageCloudClient.getDlrmContainer()).thenReturn(CONTAINER);

        if (enqueued) {
            when(storageCloudClient.sendMessageToTheQueue(eq(SUBMISSION_ID), stringArgumentCaptor.capture()))
                    .thenReturn(response);
            when(response.getValue()).thenReturn(sendMessageResult);
        }

        eventGridTriggerJava.run(event(blobUrl(blobPath)), context, "test".getBytes());

        if (enqueued) {
            final String[] tokens = blobPath.split("/");
            assertEquals("%s/%s/%s/%s".formatted(tokens[0], tokens[1], tokens[2], tokens[3]),
                    stringArgumentCaptor.getValue(),
                    () -> scenario + " — the queue message is the first four path tokens");
        } else {
            verify(storageCloudClient, never()).sendMessageToTheQueue(anyString(), anyString());
        }
    }

    /** Not a row: {@code run} returns before the storage client is touched, so the stubbing differs. */
    @Test
    @DisplayName("FR9 null content is dropped before the blob path is examined")
    void shouldReturnWhenContentIsNull() {
        when(context.getLogger()).thenReturn(logger);

        eventGridTriggerJava.run(event(blobUrl(submissionPath("XHIBIT", "Batch0001"))), context, null);

        verify(storageCloudClient, never()).sendMessageToTheQueue(anyString(), anyString());
    }

    @Test
    @DisplayName("FR9 empty content is dropped before the blob path is examined")
    void shouldReturnWhenContentIsEmpty() {
        when(context.getLogger()).thenReturn(logger);

        eventGridTriggerJava.run(event(blobUrl(submissionPath("XHIBIT", "Batch0001"))), context, new byte[]{});

        verify(storageCloudClient, never()).sendMessageToTheQueue(anyString(), anyString());
    }
}
