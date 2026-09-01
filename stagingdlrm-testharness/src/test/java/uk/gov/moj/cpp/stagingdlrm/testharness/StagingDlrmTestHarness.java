package uk.gov.moj.cpp.stagingdlrm.testharness;

import uk.gov.moj.cpp.stagingdlrm.testharness.helper.StagingDlrmTestHelper;

import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.util.Properties;
import java.util.Scanner;

public class StagingDlrmTestHarness {

    public static void main(String[] args) throws IOException, URISyntaxException {

        String config = "config.properties";

        final String env = System.getenv("APP_ENV");

        if (env != null) {
            config = "config-"+env+".properties";
        }

        promptln("setting config as :" +  config);

        try (final InputStream input = StagingDlrmTestHarness.class.getClassLoader().getResourceAsStream(config);
             final Scanner scanner = new Scanner(System.in)) {

            final Properties prop = new Properties();

            if (input == null) {
                return;
            }

            //load a properties file from classpath, inside static method
            prop.load(input);

            final StagingDlrmTestHelper stagingDlrmTestHelper = new StagingDlrmTestHelper(prop);

            promptln("Please choose :");
            promptln("1: create new XHIBIT case without manifest file");
            promptln("2: create new XHIBIT case with manifest file");
            promptln("3: upload manifest for an existing XHIBIT case");
            promptln("4: upload all scenarios XHIBIT case");
            promptln("5: exit");
            prompt("Enter your choice (1, 2, 3 or 4): ");

            int systemChoice = scanner.nextInt();

            switch (systemChoice) {
                case 1:
                    stagingDlrmTestHelper.createXhibitCaseWithoutManifestFile();
                    break;
                case 2:
                    stagingDlrmTestHelper.createXhibitCase();
                    break;
                case 3:
                    stagingDlrmTestHelper.updateExistingXhibitCase();
                    break;
                case 4:
                    stagingDlrmTestHelper.submitAllXhibitScenarioCase();
                    break;
                case 5:
                    System.exit(0);
                default:
                    prompt("Invalid choice. Please enter 0 for Libra or 1 for Exhibit: ");
            }
        }
    }

    private static void prompt(String message) {
        System.out.print(message);
    }

    private static void promptln(String message) {
        System.out.println(message + "\n");
    }
}
