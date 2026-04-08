package com.wpanther.pdfsigning.domain.repository;

import com.wpanther.pdfsigning.domain.model.SignedPdfDocument;
import com.wpanther.pdfsigning.domain.model.SignedPdfDocumentId;
import com.wpanther.pdfsigning.domain.model.SigningStatus;

import java.util.List;
import java.util.Optional;

/**
 * Repository interface for SignedPdfDocument aggregate.
 *
 * This is a domain-level interface that defines persistence operations
 * without exposing infrastructure concerns. Implementation is in the
 * infrastructure layer.
 */
public interface SignedPdfDocumentRepository {

    /**
     * Saves a SignedPdfDocument (insert or update).
     *
     * @param document the document to save
     * @return the saved document
     */
    SignedPdfDocument save(SignedPdfDocument document);

    /**
     * Finds a SignedPdfDocument by its ID.
     *
     * @param id the document ID
     * @return Optional containing the document if found
     */
    Optional<SignedPdfDocument> findById(SignedPdfDocumentId id);

    /**
     * Finds a SignedPdfDocument by document ID.
     * Used for idempotency checks.
     *
     * @param documentId the document ID
     * @return Optional containing the document if found
     */
    Optional<SignedPdfDocument> findByDocumentId(String documentId);

    /**
     * Finds all SignedPdfDocuments with a specific status.
     *
     * @param status the signing status
     * @return list of documents with the given status
     */
    List<SignedPdfDocument> findByStatus(SigningStatus status);

    /**
     * Checks if a SignedPdfDocument exists for the given document ID.
     * Used for idempotency checks before creating new documents.
     *
     * @param documentId the document ID
     * @return true if a document exists, false otherwise
     */
    boolean existsByDocumentId(String documentId);

    /**
     * Deletes a SignedPdfDocument by its ID.
     *
     * @param id the document ID
     */
    void deleteById(SignedPdfDocumentId id);

    /**
     * Counts all SignedPdfDocuments.
     *
     * @return total count of documents
     */
    long count();
}
