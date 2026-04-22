# Design: Complete InvoiceId → DocumentId Refactoring in pdf-signing-service

## Context

The domain model (`SignedPdfDocument`) was refactored to use `documentId`/`documentNumber` (commits `343dcf9` and `8747107`), but the persistence layer was not updated. The application fails to compile because:
- `SagaCommandHandler` calls `findByInvoiceId()` which no longer exists in the repository interface
- Entity, JPA repository, and adapter still use `invoiceId`/`invoiceNumber`
- Mapper has unmapped property warnings

## Decision

Since pdf-signing-service will be freshly deployed, update V1 migration directly to use `document_id`/`document_number` column names for clean alignment between domain and persistence layers.

## Files to Change

### 1. `src/main/resources/db/migration/V1__create_schema.sql`

Rename columns in table definition and indexes:
- `invoice_id` → `document_id`
- `invoice_number` → `document_number`
- Update index names: `idx_signed_pdf_invoice_id` → `idx_signed_pdf_document_id`
- Update column comments to reflect `documentId`/`documentNumber` terminology

### 2. `SignedPdfDocumentEntity.java`

- Field `invoiceId` → `documentId`
- Field `invoiceNumber` → `documentNumber`
- `@Column(name = "invoice_id")` → `@Column(name = "document_id")`
- `@Column(name = "invoice_number")` → `@Column(name = "document_number")`

### 3. `JpaSignedPdfDocumentRepository.java`

- Method `findByInvoiceId(String invoiceId)` → `findByDocumentId(String documentId)`
- Method `existsByInvoiceId(String invoiceId)` → `existsByDocumentId(String documentId)`

### 4. `SignedPdfDocumentRepositoryAdapter.java`

- Remove `findByInvoiceId()` implementation
- Remove `existsByInvoiceId()` implementation
- Add `findByDocumentId()` calling `jpaRepository.findByDocumentId()`
- Add `existsByDocumentId()` calling `jpaRepository.existsByDocumentId()`

### 5. `SignedPdfDocumentMapper.java`

Add explicit mappings to eliminate unmapped property warnings:
```java
@Mapping(source = "documentId", target = "documentId")
@Mapping(source = "documentNumber", target = "documentNumber")
SignedPdfDocumentEntity toEntity(SignedPdfDocument domain);

@Mapping(source = "documentId", target = "documentId")
@Mapping(source = "documentNumber", target = "documentNumber")
SignedPdfDocument toDomain(SignedPdfDocumentEntity entity);
```

### 6. `SagaCommandHandler.java`

Change 4 calls from `findByInvoiceId()` → `findByDocumentId()`:
- Line 75: idempotency check in `handleProcessCommand()`
- Line 176: error handling in catch block
- Line 203: error handling in catch block
- Line 247: compensation handler

## Files with No Changes Needed

- `SignedPdfDocument.java` (domain model) - already uses `documentId`/`documentNumber`
- `ProcessPdfSigningCommand.java` - already uses `documentId`
- `PdfSigningReplyEvent.java` - already correct
- `CompensatePdfSigningCommand.java` - already correct
- All test files - already written against domain model

## Change Order

1. Migration SQL (foundation)
2. Entity (depends on migration)
3. JPA Repository (depends on entity column names)
4. Adapter (depends on JPA repository interface)
5. Mapper (no runtime dependencies)
6. SagaCommandHandler (depends on repository interface)

## Verification

After changes, run `mvn clean test` to confirm:
- No compilation errors
- All tests pass
- JaCoCo coverage maintained at 90%+
