package uk.gov.moj.cpp.stagingdlrm.testharness;

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
import java.time.LocalDate;
import java.util.Properties;
import java.util.Random;
import java.util.Scanner;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.Set;

public class StagingDlrmTestHarness {


    public static void main(String[] args) throws IOException, URISyntaxException {

        final String config = "config.properties";

        try (final InputStream input = StagingDlrmTestHarness.class.getClassLoader().getResourceAsStream(config)) {

            final Properties prop = new Properties();

            if (input == null) {
                return;
            }

            //load a properties file from classpath, inside static method
            prop.load(input);

            // Get user input for system choice
            Scanner scanner = new Scanner(System.in);
            promptln("Please choose the system:");
            promptln("0 for Libra");
            promptln("1 for Exhibit");
            prompt("Enter your choice (0 or 1): ");
            
            int systemChoice = -1;
            while (systemChoice != 0 && systemChoice != 1) {
                try {
                    systemChoice = Integer.parseInt(scanner.nextLine().trim());
                    if (systemChoice != 0 && systemChoice != 1) {
                        prompt("Invalid choice. Please enter 0 for Libra or 1 for Exhibit: ");
                    }
                } catch (NumberFormatException e) {
                    prompt("Invalid input. Please enter 0 for Libra or 1 for Exhibit: ");
                }
            }

            String resourcePath = "materials"; // Default path for Libra (existing behavior)
            
            if (systemChoice == 1) {
                // Exhibit system - prompt for directory choice
                promptln("Please choose the directory:");
                promptln("1. fixeddate");
                promptln("2. weekcommencing");
                promptln("3. unscheduled");
                promptln("4. fixeddatenomaterial");
                prompt("Enter your choice (1, 2,3 or 4): ");
                
                int directoryChoice = -1;
                while (directoryChoice < 1 || directoryChoice > 4) {
                    try {
                        directoryChoice = Integer.parseInt(scanner.nextLine().trim());
                        if (directoryChoice < 1 || directoryChoice > 4) {
                            prompt("Invalid choice. Please enter 1, 2, 3 or 4: ");
                        }
                    } catch (NumberFormatException e) {
                        prompt("Invalid choice. Please enter 1, 2, 3 or 4: ");
                    }
                }
                
                // Set the appropriate directory path based on user choice
                resourcePath = switch (directoryChoice) {
                    case 1 -> "fixeddate";
                    case 2 -> "weekcommencing";
                    case 3 -> "unscheduled";
                    case 4 -> "fixeddatenomaterial";
                    default -> resourcePath;
                };
            }
            
            scanner.close();

            Random generator = new Random();
            generator.setSeed(System.currentTimeMillis());

            int randomId = generator.nextInt(9999999) + 1000000;
            final String caseUrn = "28DI" + randomId;
            final String libraId = "LIB-" + randomId;
            final String caseId = UUID.randomUUID().toString();

            // Generate random personal information for the defendant only
            final String[] firstNames = {"John", "Jane", "Michael", "Sarah", "David", "Emma", "James", "Lisa", "Robert", "Anna", 
                                        "William", "Mary", "Richard", "Patricia", "Joseph", "Jennifer", "Thomas", "Linda", "Christopher", "Elizabeth"};
            final String[] lastNames = {"Smith", "Johnson", "Williams", "Brown", "Jones", "Garcia", "Miller", "Davis", "Rodriguez", "Martinez",
                                       "Hernandez", "Lopez", "Gonzalez", "Wilson", "Anderson", "Thomas", "Taylor", "Moore", "Jackson", "Martin"};
            
            final String randomFirstName = firstNames[generator.nextInt(firstNames.length)];
            final String randomLastName = lastNames[generator.nextInt(lastNames.length)];

            final URL resource = StagingDlrmTestHarness.class.getClassLoader().getResource(resourcePath);

            if (resource == null) {
                throw new IllegalArgumentException("Directory not found: " + resourcePath);
            }

            final String migrationSourceSystemName = "XHIBIT-TH";

            final String batchIdentifier = LocalDate.now().toString();

            final String migrationSourceSystemCaseIdentifier = UUID.randomUUID().toString();

            final String submissionId = UUID.randomUUID().toString();

            final String azureLocation = "%s/%s/%s/%s".formatted(migrationSourceSystemName, batchIdentifier, migrationSourceSystemCaseIdentifier, submissionId);

            try (Stream<Path> stream = Files.list(Paths.get(resource.toURI()))) {
                final Set<Path> paths = stream
                        .filter(file -> !Files.isDirectory(file))
                        .collect(Collectors.toSet());

                for (Path path : paths) {
                    if (path.toString().endsWith("case.json")) {
                        replaceCaseUrn(path, caseUrn, "CASE-URN");
                        replaceCaseUrn(path, caseId, "CASE_ID");
                        // Replace defendant personal information placeholders
                        replaceCaseUrn(path, randomFirstName, "DEFENDANT_FIRST_NAME");
                        replaceCaseUrn(path, randomLastName, "DEFENDANT_LAST_NAME");
                    }

                    if (path.toString().endsWith("manifest.json")) {
                        replaceCaseUrn(path, libraId, "LIBRA-IDENTIFIER");
                    }

                    processPath(path, azureLocation, prop);

                    if (path.toString().endsWith("case.json")) {
                        replaceCaseUrn(path, "CASE-URN", caseUrn);
                        replaceCaseUrn(path, "CASE_ID", caseId);
                        // Restore defendant personal information placeholders
                        replaceCaseUrn(path, "DEFENDANT_FIRST_NAME", randomFirstName);
                        replaceCaseUrn(path, "DEFENDANT_LAST_NAME", randomLastName);
                    }

                    if (path.toString().endsWith("manifest.json")) {
                        replaceCaseUrn(path, "LIBRA-IDENTIFIER", libraId);
                    }
                }
            }
        }
    }

    private static void prompt(String message) {
        System.out.print(message);
    }

    private static void promptln(String message) {
        System.out.println(message+"\n");
    }

    private static void replaceCaseUrn(final Path path, final String caseUrn, final String replaceStr) throws IOException {
        Charset charset = StandardCharsets.UTF_8;
        String content = Files.readString(path, charset);
        content = content.replaceAll(replaceStr, caseUrn);
        Files.writeString(path, content, charset);
    }

    private static void processPath(final Path path, final String azureLocation, final Properties prop) throws IOException {
        long bytes = Files.size(path);
        try (final InputStream inputStream = Files.newInputStream(path)) {
            final BlobCloudStorage containerReference = getCaseStorageActiveBlobContainer(prop);
            containerReference.uploadToStorage(inputStream, bytes, azureLocation + File.separator + path.getFileName());
            System.out.println(azureLocation + "/" + path.getFileName());
        }
    }

    private static BlobCloudStorage getCaseStorageActiveBlobContainer(final Properties prop) {
        return new BlobCloudStorage(prop.getProperty("connection.string"), prop.getProperty("container_name"));
    }
}
