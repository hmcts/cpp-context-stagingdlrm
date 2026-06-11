package uk.gov.moj.cpp.stagingdlrm.event.processor.counter;

import javax.annotation.PostConstruct;
import javax.inject.Inject;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.composite.CompositeMeterRegistry;

public class ErrorMigratedCaseSubmissionReceivedCounter {

    @Inject
    private CompositeMeterRegistry registry;

    private Counter counter;

    @PostConstruct
    public void init() {
        counter = Counter.builder("error-migrated-case-submission-received")
                .description("The counter for error migrated case submissions received")
                .tag("component", "aggregate")
                .register(registry);
    }

    public void increment() {
        counter.increment();
    }
}
