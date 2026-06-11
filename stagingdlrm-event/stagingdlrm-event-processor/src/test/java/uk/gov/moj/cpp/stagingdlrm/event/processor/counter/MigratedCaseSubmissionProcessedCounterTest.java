package uk.gov.moj.cpp.stagingdlrm.event.processor.counter;

import static org.mockito.Mockito.verify;

import io.micrometer.core.instrument.composite.CompositeMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class MigratedCaseSubmissionProcessedCounterTest {

    @InjectMocks
    private MigratedCaseSubmissionProcessedCounter migratedCaseSubmissionProcessedCounter;

    @Spy
    private CompositeMeterRegistry registry;

    @BeforeEach
    void setUp() {
        migratedCaseSubmissionProcessedCounter.init();
    }

    @Test
    void shouldHandleMigratedCaseSubmissionProcessedCounterIncrement() {
        migratedCaseSubmissionProcessedCounter.increment();

        verify(registry).isClosed();
    }
}