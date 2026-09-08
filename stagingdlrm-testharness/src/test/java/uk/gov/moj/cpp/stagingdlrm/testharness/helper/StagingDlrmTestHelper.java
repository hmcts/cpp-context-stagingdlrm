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

public class StagingDlrmTestHelper {

    private final Properties properties;

    public StagingDlrmTestHelper(final Properties properties) {
        this.properties = properties;
    }

    public void createXhibitCase() throws URISyntaxException, IOException {

        final String noOfCasesStr = properties.getProperty("no_of_cases");

        final int noOfCases = noOfCasesStr == null ? 1: Integer.parseInt(noOfCasesStr);

        for (int i = 0; i < noOfCases; i++) {

            String resourcePath = generateRandomTestScenario();

            final String migrationSourceSystemCaseIdentifier = UUID.randomUUID().toString();

            String azureLocation = generateAzureLocation(migrationSourceSystemCaseIdentifier);

            System.out.printf("Creating \"%s\" test case\n", resourcePath);

            //Generate random personal information for the defendant only
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
                        replaceCaseUrn(path, migrationSourceSystemCaseIdentifier, "XHIBIT-HEARINGS");
                    }

                    processPath(path, azureLocation);

                    if (path.toString().endsWith("case.json")) {
                        replaceCaseUrn(path, "CASE-URN", caseUrn);
                        replaceCaseUrn(path, "CASE_ID", caseId);

                        // Restore defendant personal information placeholders
                        replaceCaseUrn(path, "DEFENDANT_FIRST_NAME", randomFirstName);
                        replaceCaseUrn(path, "DEFENDANT_LAST_NAME", randomLastName);
                    }

                    if (path.toString().endsWith("manifest.json")) {
                        replaceCaseUrn(path, "XHIBIT-HEARINGS", migrationSourceSystemCaseIdentifier);
                    }
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

            String azureLocation = generateAzureLocation(migrationSourceSystemCaseIdentifier);

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
        String[] testScenarios = {"fixeddate", "weekcommencing", "unscheduled", "fixeddatenomaterial"};
        int index = ThreadLocalRandom.current().nextInt(0, 4);
        return testScenarios[index];
    }

    private String generateAzureLocation(final String migrationSourceSystemCaseIdentifier) {

        final String migrationSourceSystemName = properties.getProperty("folder_name");

        final String batchIdentifier = LocalDate.now().toString();

        final String submissionId = UUID.randomUUID().toString();

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
}
