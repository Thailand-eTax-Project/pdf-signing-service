# InvoiceId → DocumentId Refactoring Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Complete the invoiceId→documentId refactoring in pdf-signing-service by updating persistence layer to align with already-refactored domain model.

**Architecture:** Rename columns in database schema, entity, JPA repository, adapter, and mapper to use documentId/documentNumber terminology. Update SagaCommandHandler to use correct repository method name.

**Tech Stack:** Java 21, Spring Boot, JPA/Hibernate, Flyway migrations, MapStruct

---

## Files to Modify

| File | Change |
|------|--------|
| `src/main/resources/db/migration/V1__create_schema.sql` | Rename `invoice_id`→`document_id`, `invoice_number`→`document_number`, update indexes/comments |
| `infrastructure/persistence/SignedPdfDocumentEntity.java` | Rename fields and @Column annotations |
| `infrastructure/persistence/JpaSignedPdfDocumentRepository.java` | Rename methods |
| `infrastructure/persistence/SignedPdfDocumentRepositoryAdapter.java` | Implement correct interface methods |
| `infrastructure/persistence/SignedPdfDocumentMapper.java` | Add explicit @Mapping annotations |
| `application/usecase/SagaCommandHandler.java` | Change 4 calls from `findByInvoiceId()`→`findByDocumentId()` |

---

## Task 1: Update Database Migration V1

**Files:**
- Modify: `src/main/resources/db/migration/V1__create_schema.sql`

- [ ] **Step 1: Update V1 migration column names**

```sql
-- In signed_pdf_documents table definition:
invoice_id          VARCHAR(100)    NOT NULL  →  document_id          VARCHAR(100)    NOT NULL
invoice_number      VARCHAR(50)     NOT NULL  →  document_number      VARCHAR(50)     NOT NULL

-- In indexes:
idx_signed_pdf_invoice_id     →  idx_signed_pdf_document_id
idx_signed_pdf_invoice_number →  idx_signed_pdf_document_number

-- In comments:
'invoice_id' reference to invoice → 'document_id' reference to document
'invoice_number' human-readable invoice identifier → 'document_number' human-readable document identifier
```

---

## Task 2: Update JPA Entity

**Files:**
- Modify: `infrastructure/persistence/SignedPdfDocumentEntity.java`

- [ ] **Step 1: Rename fields from invoiceId/invoiceNumber to documentId/documentNumber**

Change field declarations:
```java
@Column(name = "invoice_id", nullable = false, unique = true, length = 100)
private String invoiceId;  →  @Column(name = "document_id", nullable = false, unique = true, length = 100)
                              private String documentId;

@Column(name = "invoice_number", nullable = false, length = 50)
private String invoiceNumber;  →  @Column(name = "document_number", nullable = false, length = 50)
                                  private String documentNumber;
```

---

## Task 3: Update JPA Repository Interface

**Files:**
- Modify: `infrastructure/persistence/JpaSignedPdfDocumentRepository.java`

- [ ] **Step 1: Rename JPA repository methods**

```java
Optional<SignedPdfDocumentEntity> findByInvoiceId(String invoiceId);  →  Optional<SignedPdfDocumentEntity> findByDocumentId(String documentId);

boolean existsByInvoiceId(String invoiceId);  →  boolean existsByDocumentId(String documentId);
```

Also update Javadoc comments to reflect new parameter names.

---

## Task 4: Update Repository Adapter

**Files:**
- Modify: `infrastructure/persistence/SignedPdfDocumentRepositoryAdapter.java`

- [ ] **Step 1: Replace findByInvoiceId implementation with findByDocumentId**

```java
@Override
public Optional<SignedPdfDocument> findByInvoiceId(String invoiceId) {  →  @Override
                                                                      public Optional<SignedPdfDocument> findByDocumentId(String documentId) {
    return jpaRepository.findByInvoiceId(invoiceId)                           return jpaRepository.findByDocumentId(documentId)
            .map(mapper::toDomain);                                                .map(mapper::toDomain);
}                                                                              }
```

- [ ] **Step 2: Replace existsByInvoiceId implementation with existsByDocumentId**

```java
@Override
public boolean existsByInvoiceId(String invoiceId) {  →  @Override
                                                      public boolean existsByDocumentId(String documentId) {
    return jpaRepository.existsByInvoiceId(invoiceId);                           return jpaRepository.existsByDocumentId(documentId);
}                                                                              }
```

---

## Task 5: Update Mapper with Explicit Mappings

**Files:**
- Modify: `infrastructure/persistence/SignedPdfDocumentMapper.java`

- [ ] **Step 1: Add explicit @Mapping annotations to toEntity method**

```java
@Mapping(source = "id", target = "id")
@Mapping(source = "documentId", target = "documentId")
@Mapping(source = "documentNumber", target = "documentNumber")
SignedPdfDocumentEntity toEntity(SignedPdfDocument domain);
```

- [ ] **Step 2: Add explicit @Mapping annotations to toDomain method**

```java
@Mapping(source = "id", target = "id")
@Mapping(source = "documentId", target = "documentId")
@Mapping(source = "documentNumber", target = "documentNumber")
SignedPdfDocument toDomain(SignedPdfDocumentEntity entity);
```

---

## Task 6: Update SagaCommandHandler

**Files:**
- Modify: `application/usecase/SagaCommandHandler.java`

- [ ] **Step 1: Change line 75 - idempotency check in handleProcessCommand()**

```java
Optional<SignedPdfDocument> existing = documentRepository.findByInvoiceId(command.getDocumentId());
                                                                      → findByDocumentId
```

- [ ] **Step 2: Change line 176 - error handling in catch block**

```java
documentRepository.findByInvoiceId(command.getDocumentId()).ifPresent(document -> {
                                            → findByDocumentId
```

- [ ] **Step 3: Change line 203 - error handling in second catch block**

```java
documentRepository.findByInvoiceId(command.getDocumentId()).ifPresent(document -> {
                                            → findByDocumentId
```

- [ ] **Step 4: Change line 247 - compensation handler**

```java
Optional<SignedPdfDocument> existing = documentRepository.findByInvoiceId(command.getDocumentId());
                                                                      → findByDocumentId
```

---

## Task 7: Verify with mvn clean test

**Files:**
- Test: All test files

- [ ] **Step 1: Run mvn clean test**

```bash
mvn clean test
```

Expected: BUILD SUCCESS with all tests passing.

- [ ] **Step 2: Run mvn verify for JaCoCo coverage**

```bash
mvn verify
```

Expected: BUILD SUCCESS with 90%+ coverage maintained.

---

## Spec Coverage Check

| Spec Requirement | Task |
|------------------|------|
| V1 migration updated with document_id/document_number | Task 1 |
| Entity renamed to documentId/documentNumber | Task 2 |
| JPA repository methods renamed | Task 3 |
| Adapter implements findByDocumentId/existsByDocumentId | Task 4 |
| Mapper explicit @Mapping annotations | Task 5 |
| SagaCommandHandler 4 method calls updated | Task 6 |
| Tests pass with mvn clean test | Task 7 |

---

## Type Consistency Check

| Location | Method/Field | Type |
|----------|--------------|------|
| Domain interface | `findByDocumentId(String)` | `Optional<SignedPdfDocument>` |
| Adapter | `findByDocumentId()` implements interface | `Optional<SignedPdfDocument>` |
| JPA repository | `findByDocumentId(String)` | `Optional<SignedPdfDocumentEntity>` |
| SagaCommandHandler | `documentRepository.findByDocumentId()` | `Optional<SignedPdfDocument>` |

All types consistent across layers.
