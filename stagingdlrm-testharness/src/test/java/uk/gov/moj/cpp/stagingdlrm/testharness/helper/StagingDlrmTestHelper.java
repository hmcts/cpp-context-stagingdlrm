package uk.gov.moj.cpp.stagingdlrm.testharness.helper;

import uk.gov.moj.cpp.stagingdlrm.testharness.StagingDlrmTestHarness;
import uk.gov.moj.cpp.stagingdlrm.testharness.storage.BlobCloudStorage;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.Properties;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.checkerframework.checker.nullness.qual.NonNull;

public class StagingDlrmTestHelper {

    private final Properties properties;

    public StagingDlrmTestHelper(final Properties properties) {
        this.properties = properties;
    }

    public void submitAllXhibitScenarioCase() throws URISyntaxException, IOException {

        final String[] testScenarios = {"invalidGender", "invalidWeekCommencing","invalidHearingType","InvalidDateOfHearingPastDate","invalidCourtRoom","invalidCourtHearingLocation", "invalidCTL", "invalidCustodyStatus", "invalidPleaCode","invalidVerdictCode","invalidCaseMarker", "fixeddate", "duplicateSubmission", "caseAlreadyExists", "schemaValidation", "caseWithoutHearings"};

        for (final String resourcePath : testScenarios) {

            final String submissionId = UUID.randomUUID().toString();

            final String migrationSourceSystemCaseIdentifier = UUID.randomUUID().toString();

            final String azureLocation = generateAzureLocation(migrationSourceSystemCaseIdentifier, submissionId);

            System.out.printf("Creating \"%s\" test case\n", resourcePath);

            final Random generator = getRandomGenerator();

            final String caseUrn = "28DI" + (generator.nextInt(9999999) + 1000000);

            final String randomFirstName = generateFirstName(generator);

            final String randomLastName = generateLastName(generator);

            final URL resource = StagingDlrmTestHarness.class.getClassLoader().getResource(resourcePath);

            if (resource == null) {
                throw new IllegalArgumentException("Directory not found: " + resourcePath);
            }

            if ("multi_hearing".equalsIgnoreCase(resourcePath)) {
                caseSubmissionMultiHearing(resource, caseUrn, generator, migrationSourceSystemCaseIdentifier, azureLocation);
            } else {
                caseSubmission(resource, caseUrn, generator, migrationSourceSystemCaseIdentifier, azureLocation);
            }

            System.out.println("caseUrn : " + caseUrn);

            if ("duplicateSubmission".equalsIgnoreCase(resourcePath)) {
                duplicateCaseSubmission(submissionId, resource);
            }

            if ("caseAlreadyExists".equalsIgnoreCase(resourcePath)) {
                caseAlreadyExists(resource, caseUrn);
            }
        }
    }

    public void createXhibitCase() throws URISyntaxException, IOException {

        final String noOfCasesStr = properties.getProperty("no_of_cases");

        final int noOfCases = noOfCasesStr == null ? 1: Integer.parseInt(noOfCasesStr);

        for (int i = 0; i < noOfCases; i++) {

            final String resourcePath = generateRandomTestScenario();

            final String submissionId = UUID.randomUUID().toString();

            final String migrationSourceSystemCaseIdentifier = UUID.randomUUID().toString();

            final String azureLocation = generateAzureLocation(migrationSourceSystemCaseIdentifier, submissionId);

            System.out.printf("Creating \"%s\" test case\n", resourcePath);

            final Random generator = getRandomGenerator();

            final String caseUrn = "28DI" + (generator.nextInt(9999999) + 1000000);

            final String randomFirstName = generateFirstName(generator);

            final String randomLastName = generateLastName(generator);

            final URL resource = StagingDlrmTestHarness.class.getClassLoader().getResource(resourcePath);

            if (resource == null) {
                throw new IllegalArgumentException("Directory not found: " + resourcePath);
            }

            if ("multi_hearing".equalsIgnoreCase(resourcePath)) {
                caseSubmissionMultiHearing(resource, caseUrn, generator, migrationSourceSystemCaseIdentifier, azureLocation);
            } else {
                caseSubmission(resource, caseUrn, generator, migrationSourceSystemCaseIdentifier, azureLocation);
            }

            System.out.println("caseUrn : " + caseUrn);

            if ("duplicateSubmission".equalsIgnoreCase(resourcePath)) {
                duplicateCaseSubmission(submissionId, resource);
            }

            if ("caseAlreadyExists".equalsIgnoreCase(resourcePath)) {
                caseAlreadyExists(resource, caseUrn);
            }
        }
    }

    private void caseAlreadyExists(final URL resource, final String caseUrn) throws IOException, URISyntaxException {

        final String migrationSourceSystemCaseIdentifier = UUID.randomUUID().toString();

        final String submissionId = UUID.randomUUID().toString();

        String azureLocation = generateAzureLocation(migrationSourceSystemCaseIdentifier, submissionId);

        final Random generator = getRandomGenerator();

        final String randomFirstName = generateFirstName(generator);

        final String randomLastName = generateLastName(generator);

        caseSubmission(resource, caseUrn, generator, migrationSourceSystemCaseIdentifier, azureLocation);

        System.out.println("caseUrn : " + caseUrn);
    }

    private void duplicateCaseSubmission(final String submissionId, final URL resource) throws IOException, URISyntaxException {

        final String migrationSourceSystemCaseIdentifier = UUID.randomUUID().toString();

        String azureLocation = generateAzureLocation(migrationSourceSystemCaseIdentifier, submissionId);

        final Random generator = getRandomGenerator();

        final String caseUrn = "28DI" + (generator.nextInt(9999999) + 1000000);

        final String randomFirstName = generateFirstName(generator);

        final String randomLastName = generateLastName(generator);

        caseSubmission(resource, caseUrn, generator, migrationSourceSystemCaseIdentifier, azureLocation);

        System.out.println("caseUrn : " + caseUrn);
    }

    private static @NonNull Random getRandomGenerator() {
        final Random generator = new Random();

        generator.setSeed(System.nanoTime());
        return generator;
    }

    private static String generateFirstName(final Random generator) {
        //Generate random personal information for the defendant only
        final String[] firstNames = {"John", "Jane", "Michael", "Sarah", "David", "Emma", "James", "Lisa", "Robert", "Anna",
                "William", "Mary", "Richard", "Patricia", "Joseph", "Jennifer", "Thomas", "Linda", "Christopher", "Elizabeth"};

        return firstNames[generator.nextInt(firstNames.length)];
    }

    private static String generateLastName(final Random generator) {
        //Generate random personal information for the defendant only
        final String[] lastNames = {"Smith", "Johnson", "Williams", "Brown", "Jones", "Garcia", "Miller", "Davis", "Rodriguez", "Martinez",
                "Hernandez", "Lopez", "Gonzalez", "Wilson", "Anderson", "Thomas", "Taylor", "Moore", "Jackson", "Martin"};

        return lastNames[generator.nextInt(lastNames.length)];
    }

    private void waitForSec(long seconds) {
        try {
            Thread.sleep(seconds * 1000);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    private void caseSubmissionMultiHearing(final URL resource, final String caseUrn, final Random generator, final String migrationSourceSystemCaseIdentifier, final String azureLocation) throws IOException, URISyntaxException {
        try (Stream<Path> stream = Files.list(Paths.get(resource.toURI()))) {
            final Set<Path> paths = stream
                    .filter(file -> !Files.isDirectory(file))
                    .collect(Collectors.toSet());

            for (Path path : paths) {
                final String dateOfBirth1 = randomDob();
                final String dateOfHearing1 = LocalDate.now().plusDays(1).toString();
                final String prosecutorDefendantId1 = UUID.randomUUID().toString();
                final String randomFirstName1 = generateFirstName(generator);
                final String randomLastName1 = generateLastName(generator);

                final String dateOfBirth2 = randomDob();
                final String dateOfHearing2 = LocalDate.now().plusDays(2).toString();
                final String prosecutorDefendantId2 = UUID.randomUUID().toString();
                final String randomFirstName2 = generateFirstName(generator);
                final String randomLastName2 = generateLastName(generator);

                final String dateOfBirth3 = randomDob();
                final String dateOfHearing3 = LocalDate.now().plusDays(3).toString();
                final String prosecutorDefendantId3 = UUID.randomUUID().toString();
                final String randomFirstName3 = generateFirstName(generator);
                final String randomLastName3 = generateLastName(generator);

                if (path.toString().endsWith("case.json")) {
                    replaceCaseUrn(path, caseUrn, "CASE-URN");


                    replaceCaseUrn(path, dateOfHearing1, "DATE_OF_HEARING_1");
                    replaceCaseUrn(path, prosecutorDefendantId1, "PROSECUTOR_DEFENDANT_ID_1");
                    replaceCaseUrn(path, dateOfBirth1, "DATE_OF_BIRTH_1");
                    replaceCaseUrn(path, randomFirstName1, "DEFENDANT_FIRST_NAME_1");
                    replaceCaseUrn(path, randomLastName1, "DEFENDANT_LAST_NAME_1");

                    replaceCaseUrn(path, dateOfHearing2, "DATE_OF_HEARING_2");
                    replaceCaseUrn(path, prosecutorDefendantId2, "PROSECUTOR_DEFENDANT_ID_2");
                    replaceCaseUrn(path, dateOfBirth2, "DATE_OF_BIRTH_2");
                    replaceCaseUrn(path, randomFirstName2, "DEFENDANT_FIRST_NAME_2");
                    replaceCaseUrn(path, randomLastName2, "DEFENDANT_LAST_NAME_2");

                    replaceCaseUrn(path, dateOfHearing3, "DATE_OF_HEARING_3");
                    replaceCaseUrn(path, prosecutorDefendantId3, "PROSECUTOR_DEFENDANT_ID_3");
                    replaceCaseUrn(path, dateOfBirth3, "DATE_OF_BIRTH_3");
                    replaceCaseUrn(path, randomFirstName3, "DEFENDANT_FIRST_NAME_3");
                    replaceCaseUrn(path, randomLastName3, "DEFENDANT_LAST_NAME_3");
                }

                if (path.toString().endsWith("manifest.json")) {
                    replaceCaseUrn(path, migrationSourceSystemCaseIdentifier, "XHIBIT-HEARINGS");
                }

                processPath(path, azureLocation);

                if (path.toString().endsWith("case.json")) {
                    replaceCaseUrn(path, "CASE-URN", caseUrn);

                    replaceCaseUrn(path, "DATE_OF_HEARING_1", dateOfHearing1);
                    replaceCaseUrn(path, "PROSECUTOR_DEFENDANT_ID_1", prosecutorDefendantId1);
                    replaceCaseUrn(path, "DATE_OF_BIRTH_1", dateOfBirth1);
                    replaceCaseUrn(path, "DEFENDANT_FIRST_NAME_1", randomFirstName1);
                    replaceCaseUrn(path, "DEFENDANT_LAST_NAME_1", randomLastName1);

                    replaceCaseUrn(path, "DATE_OF_HEARING_2", dateOfHearing2);
                    replaceCaseUrn(path, "PROSECUTOR_DEFENDANT_ID_2", prosecutorDefendantId2);
                    replaceCaseUrn(path, "DATE_OF_BIRTH_2", dateOfBirth2);
                    replaceCaseUrn(path, "DEFENDANT_FIRST_NAME_2", randomFirstName2);
                    replaceCaseUrn(path, "DEFENDANT_LAST_NAME_2", randomLastName2);

                    replaceCaseUrn(path, "DATE_OF_HEARING_3", dateOfHearing3);
                    replaceCaseUrn(path, "PROSECUTOR_DEFENDANT_ID_3", prosecutorDefendantId3);
                    replaceCaseUrn(path, "DATE_OF_BIRTH_3", dateOfBirth3);
                    replaceCaseUrn(path, "DEFENDANT_FIRST_NAME_3", randomFirstName3);
                    replaceCaseUrn(path, "DEFENDANT_LAST_NAME_3", randomLastName3);
                }

                if (path.toString().endsWith("manifest.json")) {
                    replaceCaseUrn(path, "XHIBIT-HEARINGS", migrationSourceSystemCaseIdentifier);
                }
            }
        }
    }

    private void caseSubmission(final URL resource, final String caseUrn, final Random generator, final String migrationSourceSystemCaseIdentifier, final String azureLocation) throws IOException, URISyntaxException {
        try (Stream<Path> stream = Files.list(Paths.get(resource.toURI()))) {
            final Set<Path> paths = stream
                    .filter(file -> !Files.isDirectory(file))
                    .collect(Collectors.toSet());

            for (Path path : paths) {
                final String dateOfBirth = randomDob();
                final String dateOfHearing = LocalDate.now().plusDays(1).toString();
                final String prosecutorDefendantId = UUID.randomUUID().toString();
                final String randomFirstName = generateFirstName(generator);
                final String randomLastName = generateLastName(generator);

                if (path.toString().endsWith("case.json")) {
                    replaceCaseUrn(path, caseUrn, "CASE-URN");
                    replaceCaseUrn(path, dateOfHearing, "DATE_OF_HEARING");
                    replaceCaseUrn(path, prosecutorDefendantId, "PROSECUTOR_DEFENDANT_ID");
                    replaceCaseUrn(path, dateOfBirth, "DATE_OF_BIRTH");

                    //Replace defendant personal information placeholders
                    replaceCaseUrn(path, randomFirstName, "DEFENDANT_FIRST_NAME");
                    replaceCaseUrn(path, randomLastName, "DEFENDANT_LAST_NAME");
                }

                if (path.toString().endsWith("manifest.json")) {
                    replaceCaseUrn(path, migrationSourceSystemCaseIdentifier, "XHIBIT-HEARINGS");
                }

                processPath(path, azureLocation);

                if (path.toString().endsWith("case.json")) {
                    replaceCaseUrn(path, "CASE-URN", caseUrn);
                    replaceCaseUrn(path, "DATE_OF_HEARING", dateOfHearing);
                    replaceCaseUrn(path, "PROSECUTOR_DEFENDANT_ID", prosecutorDefendantId);
                    replaceCaseUrn(path, "DATE_OF_BIRTH", dateOfBirth);

                    // Restore defendant personal information placeholders
                    replaceCaseUrn(path, "DEFENDANT_FIRST_NAME", randomFirstName);
                    replaceCaseUrn(path, "DEFENDANT_LAST_NAME", randomLastName);
                }

                if (path.toString().endsWith("manifest.json")) {
                    replaceCaseUrn(path, "XHIBIT-HEARINGS", migrationSourceSystemCaseIdentifier);
                }
            }

            System.out.println("caseUrn : " + caseUrn);
        }
    }

    public void createXhibitCaseWithoutManifestFile() throws URISyntaxException, IOException {

        final String noOfCasesStr = properties.getProperty("no_of_cases");

        final int noOfCases = noOfCasesStr == null ? 1: Integer.parseInt(noOfCasesStr);

        for (int i = 0; i < noOfCases; i++) {

            String resourcePath = generateRandomTestScenario();

            final String migrationSourceSystemCaseIdentifier = UUID.randomUUID().toString();

            final String submissionId = UUID.randomUUID().toString();

            String azureLocation = generateAzureLocation(migrationSourceSystemCaseIdentifier, submissionId);

            System.out.printf("Creating \"%s\" test case\n", resourcePath);

            //         Generate random personal information for the defendant only
            final String[] firstNames = {"John", "Jane", "Michael", "Sarah", "David", "Emma", "James", "Lisa", "Robert", "Anna",
                    "William", "Mary", "Richard", "Patricia", "Joseph", "Jennifer", "Thomas", "Linda", "Christopher", "Elizabeth"};

            final String[] lastNames = {"Smith", "Johnson", "Williams", "Brown", "Jones", "Garcia", "Miller", "Davis", "Rodriguez", "Martinez",
                    "Hernandez", "Lopez", "Gonzalez", "Wilson", "Anderson", "Thomas", "Taylor", "Moore", "Jackson", "Martin"};

            final Random generator = new Random();

            generator.setSeed(System.nanoTime());

            int randomId = generator.nextInt(9999999) + 1000000;

            final String caseUrn = "28DI" + randomId;

            final String caseId = UUID.randomUUID().toString();

            final String randomFirstName = firstNames[generator.nextInt(firstNames.length)];

            final String randomLastName = lastNames[generator.nextInt(lastNames.length)];

            final URL resource = StagingDlrmTestHarness.class.getClassLoader().getResource(resourcePath);

            if (resource == null) {
                throw new IllegalArgumentException("Directory not found: " + resourcePath);
            }

            try (Stream<Path> stream = Files.list(Paths.get(resource.toURI()))) {
                final Set<Path> paths = stream
                        .filter(file -> !Files.isDirectory(file))
                        .collect(Collectors.toSet());

                for (Path path : paths) {
                    if (path.toString().endsWith("case.json")) {
                        replaceCaseUrn(path, caseUrn, "CASE-URN");
                        replaceCaseUrn(path, caseId, "CASE_ID");

                        //Replace defendant personal information placeholders
                        replaceCaseUrn(path, randomFirstName, "DEFENDANT_FIRST_NAME");
                        replaceCaseUrn(path, randomFirstName, "DEFENDANT_LAST_NAME");
                    }

                    if (path.toString().endsWith("manifest.json")) {
                        continue;
                    }

                    processPath(path, azureLocation);

                    if (path.toString().endsWith("case.json")) {
                        replaceCaseUrn(path, "CASE-URN", caseUrn);
                        replaceCaseUrn(path, "CASE_ID", caseId);

                        //Restore defendant personal information placeholders
                        replaceCaseUrn(path, "DEFENDANT_FIRST_NAME", randomFirstName);
                        replaceCaseUrn(path, "DEFENDANT_LAST_NAME", randomLastName);
                    }
                }

                saveAzureLocation(resourcePath, azureLocation);

            }

            System.out.println("caseUrn : " + caseUrn);
        }
    }

    public void updateExistingXhibitCase() throws IOException, URISyntaxException {

        final URL url = StagingDlrmTestHarness.class
                .getClassLoader()
                .getResource("azure-location.txt");

        if (url == null) {
            throw new IllegalStateException("Resource not found");
        }

        Path path = Paths.get(url.toURI());

        List<String> lines = Files.readAllLines(path);

        for (String line : lines) {

            if (!Objects.isNull(line) && !line.isBlank()) {

                uploadManifestFile(line);
            }
        }

        emptyAzureLocationFile();
    }

    private void uploadManifestFile(String line) throws URISyntaxException, IOException {

        final String resourcePath = line.split(":")[0];

        final String azureLocation = line.split(":")[1];

        final String migrationSourceSystemCaseIdentifier = azureLocation.split("/")[2];

        final URL resource = StagingDlrmTestHarness.class.getClassLoader().getResource(resourcePath);

        if (resource == null) {
            throw new IllegalArgumentException("Directory not found: " + resourcePath);
        }

        try (Stream<Path> stream = Files.list(Paths.get(resource.toURI()))) {
            final Set<Path> paths = stream
                    .filter(file -> !Files.isDirectory(file))
                    .collect(Collectors.toSet());

            for (Path path : paths) {
                if (path.toString().endsWith("manifest.json")) {
                    replaceCaseUrn(path, migrationSourceSystemCaseIdentifier, "XHIBIT-HEARINGS");
                    processPath(path, azureLocation);
                    replaceCaseUrn(path, "XHIBIT-HEARINGS", migrationSourceSystemCaseIdentifier);
                }
            }
        }
    }

    private String generateRandomTestScenario() {
        String[] testScenarios = {"fixeddate", "weekcommencing", "unscheduled", "fixeddatenomaterial", "duplicateSubmission", "caseAlreadyExists", "schemaValidation", "LIBRA"};
        int index = ThreadLocalRandom.current().nextInt(0, testScenarios.length);
        return testScenarios[index];
    }

    private String generateAzureLocation(final String migrationSourceSystemCaseIdentifier, final String submissionId) {

        final String migrationSourceSystemName = properties.getProperty("folder_name");

        final String batchIdentifier = LocalDate.now().toString();

        return "%s/%s/%s/%s".formatted(migrationSourceSystemName, batchIdentifier, migrationSourceSystemCaseIdentifier, submissionId);
    }

    private void saveAzureLocation(final String resourcePath, final String azureLocation) throws IOException, URISyntaxException {

        URL url = StagingDlrmTestHarness.class
                .getClassLoader()
                .getResource("azure-location.txt");

        if (url == null) {
            throw new IllegalStateException("Resource not found");
        }

        Path path = Paths.get(url.toURI());

        Files.writeString(
                path,
                resourcePath+":"+azureLocation+"\n",
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND
        );
    }

    private void emptyAzureLocationFile() throws IOException, URISyntaxException {
        URL url = StagingDlrmTestHarness.class
                .getClassLoader()
                .getResource("azure-location.txt");

        if (url == null) {
            throw new IllegalStateException("Resource not found");
        }

        Path path = Paths.get(url.toURI());

        Files.write(path, new byte[0]);
    }

    private static void replaceCaseUrn(final Path path, final String caseUrn,
                                       final String replaceStr) throws IOException {
        Charset charset = StandardCharsets.UTF_8;
        String content = Files.readString(path, charset);
        content = content.replaceAll(replaceStr, caseUrn);
        Files.writeString(path, content, charset);
    }

    private void processPath(final Path path, final String azureLocation) throws IOException {
        long bytes = Files.size(path);
        try (final InputStream inputStream = Files.newInputStream(path)) {
            final BlobCloudStorage containerReference = getCaseStorageActiveBlobContainer();
            containerReference.uploadToStorage(inputStream, bytes, azureLocation + File.separator + path.getFileName());
            System.out.println(azureLocation + "/" + path.getFileName());
        }
    }

    private BlobCloudStorage getCaseStorageActiveBlobContainer() {
        return new BlobCloudStorage(properties.getProperty("connection.string"), properties.getProperty("container_name"));
    }

    private String randomDob() {
        long minDay = LocalDate.of(1900, 1, 1).toEpochDay();
        long maxDay = LocalDate.now().toEpochDay() - 1; // yesterday or earlier
        long day = ThreadLocalRandom.current().nextLong(minDay, maxDay + 1);
        return LocalDate.ofEpochDay(day).toString();
    }
}
