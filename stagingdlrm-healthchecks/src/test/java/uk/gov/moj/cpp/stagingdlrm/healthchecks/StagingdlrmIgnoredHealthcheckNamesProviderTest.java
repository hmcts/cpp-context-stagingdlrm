package uk.gov.moj.cpp.stagingdlrm.healthchecks;

import org.junit.jupiter.api.extension.ExtendWith;

import static org.hamcrest.CoreMatchers.hasItems;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;

import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static uk.gov.justice.services.healthcheck.healthchecks.FileStoreHealthcheck.FILE_STORE_HEALTHCHECK_NAME;
import static uk.gov.justice.services.healthcheck.healthchecks.JobStoreHealthcheck.JOB_STORE_HEALTHCHECK_NAME;

import java.util.List;

@ExtendWith(MockitoExtension.class)
public class StagingdlrmIgnoredHealthcheckNamesProviderTest {

    @InjectMocks
    private uk.gov.moj.cpp.stagingdlrm.healthchecks.StagingdlrmIgnoredHealthcheckNamesProvider ignoredHealthcheckNamesProvider;

    @Test
    public void shouldIgnoreFileStoreAndJobStoreHealthchecks() throws Exception {

        final List<String> namesOfIgnoredHealthChecks = ignoredHealthcheckNamesProvider.getNamesOfIgnoredHealthChecks();

        assertThat(namesOfIgnoredHealthChecks.size(), is(2));
        assertThat(namesOfIgnoredHealthChecks, hasItems(JOB_STORE_HEALTHCHECK_NAME, FILE_STORE_HEALTHCHECK_NAME));
    }
}