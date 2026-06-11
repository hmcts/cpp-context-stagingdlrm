package uk.gov.moj.cpp.stagingdlrm.testharness.storage;

import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.security.InvalidKeyException;

import com.microsoft.azure.storage.CloudStorageAccount;
import com.microsoft.azure.storage.StorageException;
import com.microsoft.azure.storage.blob.CloudBlobClient;
import com.microsoft.azure.storage.blob.CloudBlobContainer;
import com.microsoft.azure.storage.blob.CloudBlockBlob;

public class BlobCloudStorage {

    private static final String CANNOT_CONNECT_TO_STORAGE_TO_UPLOAD_FILE = "Cannot connect to storage to upload file ";

    private final CloudBlobContainer containerReference;

    public BlobCloudStorage(final String connectionString, final String containerReference) {
        try {
            final CloudStorageAccount cloudStorageAccount = CloudStorageAccount.parse(connectionString);
            final CloudBlobClient cloudBlobClient = cloudStorageAccount.createCloudBlobClient();
            this.containerReference = cloudBlobClient.getContainerReference(containerReference);
        } catch (URISyntaxException | StorageException | InvalidKeyException e) {
            throw new RuntimeException(CANNOT_CONNECT_TO_STORAGE_TO_UPLOAD_FILE, e);
        }
    }

    public void uploadToStorage(final InputStream documentContent, final Long sizeOfDocument, final String file) {
        try {
            final CloudBlockBlob blockBlobReference = containerReference.getBlockBlobReference(file);
            if (file.endsWith(".pdf")) {
                blockBlobReference.getProperties().setContentType("application/pdf");
            }
            blockBlobReference.upload(documentContent, sizeOfDocument);
        } catch (URISyntaxException | StorageException | IOException e) {
            throw new RuntimeException(CANNOT_CONNECT_TO_STORAGE_TO_UPLOAD_FILE, e);
        }
    }

}
