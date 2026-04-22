package com.wpanther.pdfsigning.infrastructure.persistence;

import com.wpanther.pdfsigning.domain.model.SignedPdfDocument;
import com.wpanther.pdfsigning.domain.model.SignedPdfDocumentId;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingConstants;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

/**
 * MapStruct mapper for converting between SignedPdfDocument domain model
 * and SignedPdfDocumentEntity JPA entity.
 *
 * This mapper handles the impedance mismatch between the domain layer
 * (value objects, immutability) and the persistence layer (JPA entities).
 */
@Mapper(componentModel = MappingConstants.ComponentModel.SPRING)
public interface SignedPdfDocumentMapper {

    /**
     * Converts a domain model to a JPA entity.
     *
     * @param domain the domain model
     * @return the JPA entity
     */
    @Mapping(source = "id", target = "id")
    @Mapping(source = "documentId", target = "documentId")
    @Mapping(source = "documentNumber", target = "documentNumber")
    SignedPdfDocumentEntity toEntity(SignedPdfDocument domain);

    /**
     * Converts a JPA entity to a domain model.
     *
     * @param entity the JPA entity
     * @return the domain model
     */
    @Mapping(source = "id", target = "id")
    @Mapping(source = "documentId", target = "documentId")
    @Mapping(source = "documentNumber", target = "documentNumber")
    SignedPdfDocument toDomain(SignedPdfDocumentEntity entity);

    /**
     * Maps SignedPdfDocumentId to UUID.
     *
     * @param id the SignedPdfDocumentId
     * @return the UUID value
     */
    default UUID map(SignedPdfDocumentId id) {
        return id != null ? id.getValue() : null;
    }

    /**
     * Maps UUID to SignedPdfDocumentId.
     *
     * @param uuid the UUID
     * @return the SignedPdfDocumentId
     */
    default SignedPdfDocumentId map(UUID uuid) {
        return uuid != null ? SignedPdfDocumentId.of(uuid) : null;
    }

    /**
     * Maps Instant (domain) to OffsetDateTime (entity / TIMESTAMPTZ column).
     */
    default OffsetDateTime map(Instant instant) {
        return instant != null ? instant.atOffset(ZoneOffset.UTC) : null;
    }

    /**
     * Maps OffsetDateTime (entity) back to Instant (domain).
     */
    default Instant map(OffsetDateTime offsetDateTime) {
        return offsetDateTime != null ? offsetDateTime.toInstant() : null;
    }
}
