package com.wpanther.pdfsigning.application.port.out;

import com.wpanther.pdfsigning.domain.model.DocumentType;
import com.wpanther.pdfsigning.domain.model.StorageException;

/**
 * Port for document storage operations.
 * <p>
 * Abstracts storage backend details (local filesystem, S3, etc.)
 * from the domain. Infrastructure provides implementations.
 * </p>
 */
public interface DocumentStoragePort {

    /**
     * Store document data and return storage URL.
     *
     * @param documentData The document bytes to store
     * @param documentType Type of document (e.g., UNSIGNED_PDF, SIGNED_PDF)
     * @param documentId   Unique identifier used to name the stored file
     * @return Storage URL that can be used to retrieve the document
     * @throws StorageException if storage fails
     */
    String store(byte[] documentData, DocumentType documentType, String documentId) throws StorageException;

    /**
     * Retrieve document data from storage.
     *
     * @param storageUrl URL returned from {@link #store(byte[], String, SignedPdfDocument)}
     * @return Document bytes
     * @throws StorageException if retrieval fails
     */
    byte[] retrieve(String storageUrl) throws StorageException;

    /**
     * Delete document from storage.
     *
     * @param storageUrl URL of document to delete
     * @throws StorageException if deletion fails
     */
    void delete(String storageUrl) throws StorageException;
}
