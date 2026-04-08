package com.wpanther.pdfsigning.infrastructure.persistence;

import com.wpanther.pdfsigning.domain.model.SigningStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Spring Data JPA repository for SignedPdfDocumentEntity.
 *
 * Provides database access operations for the signed_pdf_documents table.
 */
@Repository
public interface JpaSignedPdfDocumentRepository extends JpaRepository<SignedPdfDocumentEntity, UUID> {

    /**
     * Finds a SignedPdfDocumentEntity by document ID.
     *
     * @param documentId the document ID
     * @return Optional containing the entity if found
     */
    Optional<SignedPdfDocumentEntity> findByDocumentId(String documentId);

    /**
     * Finds all SignedPdfDocumentEntities with a specific status.
     *
     * @param status the signing status
     * @return list of entities with the given status
     */
    List<SignedPdfDocumentEntity> findByStatus(SigningStatus status);

    /**
     * Checks if a SignedPdfDocumentEntity exists for the given document ID.
     *
     * @param documentId the document ID
     * @return true if an entity exists, false otherwise
     */
    boolean existsByDocumentId(String documentId);
}
