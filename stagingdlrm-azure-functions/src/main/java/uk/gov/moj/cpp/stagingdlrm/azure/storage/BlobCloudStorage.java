package uk.gov.moj.cpp.stagingdlrm.azure.storage;

import uk.gov.moj.cpp.stagingdlrm.azure.exception.CloudBlobException;
import uk.gov.moj.cpp.stagingdlrm.azure.rest.LoggerHelper;

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
    private final LoggerHelper loggerHelper;

    public BlobCloudStorage(final ExecutionContext context, final String connectionString, final String containerReference) {
        this.context = context;
        this.loggerHelper = new LoggerHelper();
        loggerHelper.logInfo(context, "Initialising BlobCloudStorage for container: {0}", new Object[]{containerReference});
        try {
            final CloudStorageAccount cloudStorageAccount = CloudStorageAccount.parse(connectionString);
            final CloudBlobClient cloudBlobClient = cloudStorageAccount.createCloudBlobClient();
            this.cloudBlobContainer = cloudBlobClient.getContainerReference(containerReference);
            loggerHelper.logInfo(context, "Successfully connected to blob container: {0}", new Object[]{containerReference});
        } catch (URISyntaxException | StorageException | InvalidKeyException e) {
            loggerHelper.logSevere(context, "Failed to connect to blob container: {0}", new Object[]{containerReference});
            throw new CloudBlobException(CANNOT_CONNECT_TO_STORAGE_TO_UPLOAD_FILE, e);
        }
    }

    public void uploadToStorage(final InputStream documentContent, final Long sizeOfDocument, final String file) {
        loggerHelper.logInfo(context, "Uploading file: {0}, size: {1} bytes", new Object[]{file, sizeOfDocument});
        try {
            final CloudBlockBlob blockBlobReference = cloudBlobContainer.getBlockBlobReference(file);
            blockBlobReference.upload(documentContent, sizeOfDocument);
            loggerHelper.logInfo(context, "Successfully uploaded file: {0}", new Object[]{file});
        } catch (URISyntaxException | StorageException | IOException e) {
            loggerHelper.logSevere(context, "Failed to upload file: {0}", new Object[]{file});
            throw new CloudBlobException(CANNOT_CONNECT_TO_STORAGE_TO_UPLOAD_FILE, e);
        }
    }

}