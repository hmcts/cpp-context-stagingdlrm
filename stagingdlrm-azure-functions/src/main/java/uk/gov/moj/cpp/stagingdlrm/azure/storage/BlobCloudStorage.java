package uk.gov.moj.cpp.stagingdlrm.azure.storage;

import static java.util.logging.Level.INFO;
import static java.util.logging.Level.SEVERE;

import uk.gov.moj.cpp.stagingdlrm.azure.exception.CloudBlobException;

import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.security.InvalidKeyException;

import com.microsoft.azure.functions.ExecutionContext;
import com.microsoft.azure.storage.CloudStorageAccount;
import com.microsoft.azure.storage.StorageException;
import com.microsoft.azure.storage.blob.CloudBlobClient;
import com.microsoft.azure.storage.blob.CloudBlobContainer;
import com.microsoft.azure.storage.blob.CloudBlockBlob;

public class BlobCloudStorage {

    private static final String CANNOT_CONNECT_TO_STORAGE_TO_UPLOAD_FILE = "Cannot connect to storage to upload file ";

    private final CloudBlobContainer cloudBlobContainer;

    private final ExecutionContext context;

    public BlobCloudStorage(final ExecutionContext context, final String connectionString, final String containerReference) {
        this.context = context;
        context.getLogger().log(INFO, "Initialising BlobCloudStorage for container: {0}", containerReference);
        try {
            final CloudStorageAccount cloudStorageAccount = CloudStorageAccount.parse(connectionString);
            final CloudBlobClient cloudBlobClient = cloudStorageAccount.createCloudBlobClient();
            this.cloudBlobContainer = cloudBlobClient.getContainerReference(containerReference);
            context.getLogger().log(INFO, "Successfully connected to blob container: {0}", containerReference);
        } catch (URISyntaxException | StorageException | InvalidKeyException e) {
            context.getLogger().log(SEVERE, "Failed to connect to blob container: {0}", containerReference);
            throw new CloudBlobException(CANNOT_CONNECT_TO_STORAGE_TO_UPLOAD_FILE, e);
        }
    }

    public void uploadToStorage(final InputStream documentContent, final Long sizeOfDocument, final String file) {
        context.getLogger().log(INFO, "Uploading file: {0}, size: {1} bytes", new Object[]{file, sizeOfDocument});
        try {
            final CloudBlockBlob blockBlobReference = cloudBlobContainer.getBlockBlobReference(file);
            blockBlobReference.upload(documentContent, sizeOfDocument);
            context.getLogger().log(INFO, "Successfully uploaded file: {0}", file);
        } catch (URISyntaxException | StorageException | IOException e) {
            context.getLogger().log(SEVERE, "Failed to upload file: {0}", file);
            throw new CloudBlobException(CANNOT_CONNECT_TO_STORAGE_TO_UPLOAD_FILE, e);
        }
    }

}
