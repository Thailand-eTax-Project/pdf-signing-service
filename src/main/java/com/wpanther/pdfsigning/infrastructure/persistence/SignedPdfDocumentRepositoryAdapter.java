package com.wpanther.pdfsigning.infrastructure.persistence;

import com.wpanther.pdfsigning.domain.model.SignedPdfDocument;
import com.wpanther.pdfsigning.domain.model.SignedPdfDocumentId;
import com.wpanther.pdfsigning.domain.model.SigningStatus;
import com.wpanther.pdfsigning.domain.repository.SignedPdfDocumentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * Adapter that implements the domain repository interface using JPA.
 *
 * This adapter translates between the domain model (SignedPdfDocument)
 * and the persistence model (SignedPdfDocumentEntity) using MapStruct.
 *
 * Part of the Hexagonal Architecture pattern - this is a secondary adapter
 * that implements a domain port (SignedPdfDocumentRepository).
 */
@Component
@RequiredArgsConstructor
public class SignedPdfDocumentRepositoryAdapter implements SignedPdfDocumentRepository {

    private final JpaSignedPdfDocumentRepository jpaRepository;
    private final SignedPdfDocumentMapper mapper;

    @Override
    public SignedPdfDocument save(SignedPdfDocument document) {
        SignedPdfDocumentEntity entity = mapper.toEntity(document);
        SignedPdfDocumentEntity savedEntity = jpaRepository.save(entity);
        return mapper.toDomain(savedEntity);
    }

    @Override
    public Optional<SignedPdfDocument> findById(SignedPdfDocumentId id) {
        return jpaRepository.findById(id.getValue())
                .map(mapper::toDomain);
    }

    @Override
    public Optional<SignedPdfDocument> findByDocumentId(String documentId) {
        return jpaRepository.findByDocumentId(documentId)
                .map(mapper::toDomain);
    }

    @Override
    public List<SignedPdfDocument> findByStatus(SigningStatus status) {
        return jpaRepository.findByStatus(status).stream()
                .map(mapper::toDomain)
                .toList();
    }

    @Override
    public boolean existsByDocumentId(String documentId) {
        return jpaRepository.existsByDocumentId(documentId);
    }

    @Override
    public void deleteById(SignedPdfDocumentId id) {
        jpaRepository.deleteById(id.getValue());
    }

    @Override
    public long count() {
        return jpaRepository.count();
    }
}
