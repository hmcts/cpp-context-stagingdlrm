package uk.gov.moj.cpp.stagingdlrm.testharness;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.InvalidKeyException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;

import com.azure.core.util.BinaryData;
import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.BlobContainerClientBuilder;
import com.azure.storage.blob.models.BlobItem;
import com.azure.storage.blob.models.ListBlobsOptions;
import com.microsoft.azure.storage.CloudStorageAccount;
import com.microsoft.azure.storage.StorageException;
import com.microsoft.azure.storage.blob.CloudBlobClient;
import com.microsoft.azure.storage.blob.CloudBlobContainer;
import com.microsoft.azure.storage.blob.CloudBlockBlob;

public class StagingDlrmTestHarness2 {

    public static void main(String[] args) throws IOException {
        final String sourceConfig = "source.properties";
        final String destinationConfig = "destination.properties";

        try (final InputStream input = StagingDlrmTestHarness.class.getClassLoader().getResourceAsStream(sourceConfig);
             final InputStream output = StagingDlrmTestHarness.class.getClassLoader().getResourceAsStream(destinationConfig)) {

            if (input == null || output == null) {
                return;
            }

            final Properties sourceProp = new Properties();
            final Properties destProp = new Properties();
            sourceProp.load(input);
            destProp.load(output);

            final BlobContainerClient blobContainerClient = new BlobContainerClientBuilder()
                    .connectionString(sourceProp.getProperty("connection.string"))
                    .containerName(sourceProp.getProperty("container_name"))
                    .buildClient();

            final Set<String> folderNames = getFolderNames(blobContainerClient);

            folderNames.forEach(folderName -> {
                final List<String> files = listFiles(blobContainerClient, folderName);
                downloadFromStorage(files, blobContainerClient);
                final String submissionId = UUID.randomUUID().toString();
                uploadToStorage(destProp, files, submissionId);
            });
        }
    }

    private static Set<String> getFolderNames(final BlobContainerClient blobContainerClient) {
        final Set<String> folderNames = new HashSet<>();
        for (final BlobItem blobItem : blobContainerClient.listBlobs()) {
            if (!blobItem.getName().startsWith("XHIBIT") || blobItem.getName().contains("outcome")) {
                continue;
            }
            final List<String> tokens = List.of(blobItem.getName().split("/"));
            if (tokens.size() < 5) {
                continue;
            }
            folderNames.add("%s/%s/%s/%s".formatted(tokens.get(0), tokens.get(1), tokens.get(2), tokens.get(3)));
        }
        return folderNames;
    }

    private static void downloadFromStorage(final List<String> files, final BlobContainerClient blobContainerClient) {
        files.forEach(file -> {
            final BinaryData fileContent = downloadBlobContents(blobContainerClient, file);
            final Path localPath = Path.of(file);
            try {
                Files.createDirectories(localPath.getParent());
                Files.write(localPath, fileContent.toBytes());
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });
    }

    private static void uploadToStorage(final Properties destProp, final List<String> files, final String submissionId) {
        files.forEach(file -> upload(file, destProp, submissionId));
    }

    private static void upload(final String file, final Properties destProp, final String submissionId) {
        try {
            final CloudStorageAccount cloudStorageAccount = CloudStorageAccount.parse(destProp.getProperty("connection.string"));
            final CloudBlobClient cloudBlobClient = cloudStorageAccount.createCloudBlobClient();
            final CloudBlobContainer containerReference = cloudBlobClient.getContainerReference(destProp.getProperty("container_name"));

            final List<String> tokens = List.of(file.split("/"));
            final String newFileName = "%s/%s/%s/%s/%s".formatted(tokens.get(0), "testDatafromdev4", tokens.get(2), submissionId, tokens.get(4));

            System.out.println(newFileName);

            final CloudBlockBlob blockBlobReference = containerReference.getBlockBlobReference(newFileName);
            final Path localPath = Path.of(file);
            final long bytes = Files.size(localPath);

            System.out.println("Uploading: " + file + " (" + bytes + " bytes)");
            try (final InputStream inputStream = Files.newInputStream(localPath)) {
                if (newFileName.endsWith(".pdf")) {
                    blockBlobReference.getProperties().setContentType("application/pdf");
                }
                blockBlobReference.upload(inputStream, bytes);
            }
        } catch (URISyntaxException | StorageException | IOException e) {
            System.out.println("Failed to upload: " + file);
            throw new RuntimeException(e);
        } catch (InvalidKeyException e) {
            throw new RuntimeException(e);
        }
    }

    private static List<String> listFiles(final BlobContainerClient blobContainerClient, final String prefix) {
        final List<String> blobNames = new ArrayList<>();
        for (final BlobItem blobItem : blobContainerClient.listBlobsByHierarchy("/", new ListBlobsOptions().setPrefix(prefix), null)) {
            if (blobItem.isPrefix()) {
                return listFiles(blobContainerClient, blobItem.getName());
            }
            if (!blobItem.getName().contains("outcome")) {
                blobNames.add(blobItem.getName());
            }
        }
        return blobNames;
    }

    private static BinaryData downloadBlobContents(final BlobContainerClient blobContainerClient, final String blobName) {
        return blobContainerClient.getBlobClient(blobName).downloadContent();
    }
}
