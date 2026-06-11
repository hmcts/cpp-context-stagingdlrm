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
class ErrorMigratedCaseSubmissionReceivedCounterTest {

    @InjectMocks
    private ErrorMigratedCaseSubmissionReceivedCounter errorMigratedCaseSubmissionReceivedCounter;

    @Spy
    private CompositeMeterRegistry registry;

    @BeforeEach
    void setUp() {
        errorMigratedCaseSubmissionReceivedCounter.init();
    }

    @Test
    void shouldHandleErrorMigratedCaseSubmissionReceivedCounterIncrement() {
        errorMigratedCaseSubmissionReceivedCounter.increment();

        verify(registry).isClosed();
    }
}