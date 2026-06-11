package uk.gov.moj.cpp.stagingdlrm.event.processor.counter;

import static org.mockito.Mockito.verify;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.composite.CompositeMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class MigratedCaseSubmissionReceivedCounterTest {

    @InjectMocks
    private MigratedCaseSubmissionReceivedCounter migratedCaseSubmissionReceivedCounter;

    @Spy
    private CompositeMeterRegistry registry;

    @BeforeEach
    void setUp() {
        migratedCaseSubmissionReceivedCounter.init();
    }

    @Mock
    private Counter counter;

    @Test
    void shouldHandleMigratedCaseSubmissionReceivedCounterIncrement() {
        migratedCaseSubmissionReceivedCounter.increment();

        verify(registry).isClosed();
    }
}