# CSC v2.0 Wire Format Update — pdf-signing-service

**Date**: 2026-05-18
**Service**: `pdf-signing-service` (Spring Boot 3.4.13, Java 21)
**Trigger**: `eidasremotesigning` updated to CSC API v2.0.0.2 compliance (see `eidasremotesigning/docs/superpowers/specs/2026-05-18-csc-compliance-fix-design.md`)
**Approach**: Keep deferred `signHash` architecture; add `CredentialInfoCache`; fix all CSC wire format fields.

## Scope

Update the CSC adapter layer to match the new wire format exposed by the updated `eidasremotesigning` service. No changes to PAdES construction (PDFBox, BouncyCastle), storage, or Saga orchestration.

Breaking changes in eidasremotesigning that affect this service:
- `clientId` removed from all request bodies (JWT carries identity)
- `hashAlgo` → `hashAlgorithmOID` (OID string, not JCA name)
- `hash[]` → `hashes[]` (renamed array field)
- `numSignatures` type: `String` → `Integer`
- PIN delivery moved from `signHash.credentials.pin` → `authorize.authData[{id:"PIN",value:"..."}]`
- `authorize` response: `transactionID` removed, field is now `handle` (but not used by this service for polling)
- `signHash` request: `signatureData` wrapper removed; `hashes[]` now flat at request root
- `signHash` response: `certificate` and `timestampData` removed; `operationID` → `responseID`

---

## Section 1 — DTO Changes

### `CSCAuthorizeRequest`

| Field | Change |
|-------|--------|
| `clientId` | **Remove** |
| `hashAlgo` | Rename → `hashAlgorithmOID` |
| `hash: String[]` | Rename → `hashes: String[]` |
| `numSignatures: String` | Change type → `Integer` |
| `description` | Keep (optional, informational) |
| *(new)* `authData: List<AuthDataEntry>` | **Add** — replaces `signHash.credentials.pin` |

**New static inner class `AuthDataEntry`:**
```java
@Data @Builder @NoArgsConstructor @AllArgsConstructor @JsonInclude(NON_NULL)
public static class AuthDataEntry {
    @JsonProperty("id")    private String id;    // e.g. "PIN"
    @JsonProperty("value") private String value; // PIN value
}
```

### `CSCAuthorizeResponse`

| Field | Change |
|-------|--------|
| `transactionID` | **Remove** (not used — this service does synchronous signing only) |
| `authMode` | **Remove** (not consumed) |
| `SAD` | Keep |
| `expiresIn` | Keep |

### `CSCSignatureRequest`

| Field | Change |
|-------|--------|
| `clientId` | **Remove** |
| `hashAlgo` | Rename → `hashAlgorithmOID` |
| `signatureData: SignatureData` | **Remove** wrapper class entirely |
| *(new)* `hashes: String[]` | **Add** at top level (was `signatureData.hashToSign`) |
| `credentials: Credentials` | **Remove** (PIN moves to `authorize.authData`) |
| `async` | **Remove** (not used) |
| `credentialID`, `SAD` | Keep |

### `CSCSignatureResponse`

| Field | Change |
|-------|--------|
| `certificate` | **Remove** (cert now comes from `CredentialInfoCache`) |
| `timestampData` | **Remove** |
| `operationID` | Rename → `responseID` |
| `signatures[]`, `signatureAlgorithm` | Keep |

### New DTOs for `credentials/info`

**`CSCCredentialsInfoRequest`:**
```java
@Data @Builder @NoArgsConstructor @AllArgsConstructor @JsonInclude(NON_NULL)
public class CSCCredentialsInfoRequest {
    @JsonProperty("credentialID") private String credentialID;
}
```

**`CSCCredentialsInfoResponse`** (minimal — only fields consumed by this service):
```java
@Data @NoArgsConstructor @AllArgsConstructor @JsonInclude(NON_NULL)
public class CSCCredentialsInfoResponse {
    @JsonProperty("cert") private CertInfo cert;

    @Data @NoArgsConstructor @AllArgsConstructor @JsonInclude(NON_NULL)
    public static class CertInfo {
        @JsonProperty("certificates") private String[] certificates; // Base64-encoded DER
    }
}
```

---

## Section 2 — Certificate Caching and New Feign Client

### New Feign client: `CSCCredentialsInfoClient`

**Location**: `infrastructure/adapter/out/csc/client/CSCCredentialsInfoClient.java`

```java
@FeignClient(
    name = "csc-credentials-info-client",
    url = "${app.csc.service-url}"
)
public interface CSCCredentialsInfoClient {
    @PostMapping("${app.csc.credentials-info-endpoint}")
    CSCCredentialsInfoResponse getCredentialInfo(@RequestBody CSCCredentialsInfoRequest request);
}
```

Uses the default `FeignConfig` (same Bearer token auth as `CSCApiClient`).

### New component: `CredentialInfoCache`

**Location**: `infrastructure/adapter/out/csc/CredentialInfoCache.java`

Fetches `credentials/info` on `@PostConstruct` (fail-fast if CSC unavailable at startup), caches the parsed `X509Certificate[]` chain in a `volatile` field.

```java
@Component
public class CredentialInfoCache {
    private final CSCCredentialsInfoClient credentialsInfoClient;
    private final CertificateParser certificateParser;
    private final CscProperties cscProperties;
    private volatile X509Certificate[] certChain;

    @PostConstruct
    public void init() {
        refresh();
    }

    public X509Certificate[] getCertChain() {
        return certChain;
    }

    public void refresh() {
        CSCCredentialsInfoResponse response = credentialsInfoClient.getCredentialInfo(
            new CSCCredentialsInfoRequest(cscProperties.getCredentialId())
        );
        certChain = certificateParser.parseDerCertificates(
            response.getCert().getCertificates()
        );
    }
}
```

If `@PostConstruct` throws, Spring context fails to start — acceptable since the service cannot sign without a cert.

### `CertificateParser` extension

Add `parseDerCertificates(String[] base64DerCerts)` alongside the existing `parseCertificateChain(String pemChain)`:

```java
public X509Certificate[] parseDerCertificates(String[] base64DerCerts) {
    // For each entry: Base64.getDecoder().decode(entry) → DER bytes
    // CertificateFactory.generateCertificate(new ByteArrayInputStream(derBytes))
    // Return as X509Certificate[]
}
```

The standard `CertificateFactory` (type "X.509") accepts both PEM and DER input — no dependency change needed.

### Config changes

**`application.yml`**:
```yaml
app:
  csc:
    service-url: ${CSC_SERVICE_URL:http://localhost:9000}
    auth-endpoint: /csc/v2/credentials/authorize
    sign-hash-endpoint: /csc/v2/signatures/signHash
    credentials-info-endpoint: /csc/v2/credentials/info    # new
    credential-id: ${CSC_CREDENTIAL_ID:default-credential}
    hash-algorithm-oid: 2.16.840.1.101.3.4.2.1             # replaces hash-algo: SHA-256
    pin: ${CSC_PIN:}
    # remove: client-id
```

**`CscProperties`**:
- Rename field `hashAlgo → hashAlgorithmOid`
- Remove field `clientId`
- Add field `credentialsInfoEndpoint`
- Update `@Pattern` on `hashAlgorithmOid` to validate OID format: `"^[0-9]+(\.[0-9]+)+$"`

---

## Section 3 — Updated `CscSigningAdapter` Flow

`CscSigningAdapter` adds `CredentialInfoCache` as a constructor dependency.

`signPdfWithCertChain()` new sequence:

1. **Get cert chain** — `credentialInfoCache.getCertChain()` (no network, volatile read)
2. **Compute Base64 digest** — unchanged
3. **Authorize** — `CSCAuthClient.authorize()` with:
   - `credentialID` from config
   - `numSignatures: 1` (Integer)
   - `hashes: [base64Digest]`
   - `hashAlgorithmOID` from config
   - `authData: [{id:"PIN", value:pin}]` if `pin` is non-blank; omit `authData` field entirely otherwise
4. **Validate SAD token** — `sadTokenValidator.validate(...)` unchanged
5. **Check SAD expiry** — `sadTokenValidator.isExpired(...)` unchanged
6. **Sign** — `CSCApiClient.signHash()` with:
   - `credentialID`
   - `SAD`
   - `hashes: [base64Digest]` (flat array)
   - `hashAlgorithmOID`
   - *(no `clientId`, no `signatureData` wrapper, no `credentials.pin`)*
7. **Use cached cert** — cert chain from step 1; ignore `signHash` response cert fields (removed)
8. **Build CMS** — `cmsBuilder.buildCmsSignature(rawSig, certChain, digest)` unchanged
9. **Embed signature** — unchanged
10. **Return result** — use `signResponse.getResponseID()` for audit traceability

`validateCertificateChain()` — unchanged.

Remove `cscProperties.getClientId()` calls throughout.

---

## Section 4 — Test Updates

### `CSCDtoTest`

Update field-name assertions for all four DTO classes:

- **`CSCAuthorizeRequest`**: assert `hashAlgorithmOID`, `hashes` (not `hashAlgo`/`hash`); assert no `clientId`; assert `authData` serializes as array; assert `numSignatures` is JSON integer (not string)
- **`CSCAuthorizeResponse`**: assert `sad` field; remove any `transactionID`/`authMode` assertions
- **`CSCSignatureRequest`**: assert flat `hashes` at root (not `signatureData.hashToSign`); assert `hashAlgorithmOID`; assert no `clientId`, no `credentials` wrapper, no `signatureData` wrapper
- **`CSCSignatureResponse`**: assert `responseID` (not `operationID`); assert absence of `certificate` and `timestampData` in JSON

### `CscSigningAdapterTest`

- Add `@Mock CredentialInfoCache mockCredentialInfoCache` — passed to adapter constructor
- Remove `lenient().when(mockCscProperties.getClientId())...` stub
- Mock `mockCredentialInfoCache.getCertChain()` to return a `new X509Certificate[0]`
- `CSCSignatureResponse` no longer sets `.certificate(...)` — verify `mockCertificateParser.parseCertificateChain()` is **not** called during the signing path (cert comes from `mockCredentialInfoCache.getCertChain()`)
- Verify `authorize` arg: `hashes` present, `hashAlgorithmOID` present, no `clientId`, `authData` present when pin non-blank
- Verify `signHash` arg: flat `hashes` present, no `signatureData` wrapper, no `clientId`
- Verify result uses `responseID` (not `operationID`) for the `operationId` field in `SigningResult`

### New: `CredentialInfoCacheTest`

**Location**: `infrastructure/adapter/out/csc/CredentialInfoCacheTest.java`

- Happy path: `@PostConstruct` calls `credentialsInfoClient.getCredentialInfo()`, parses certs, stores chain
- `getCertChain()` returns same instance without additional client call
- `refresh()` fetches again and updates `certChain`
- Startup failure: if client throws, exception propagates from `init()`

### `CertificateParserTest` extension

Add test for `parseDerCertificates(String[])` using a real self-signed DER cert (Base64-encoded). Assert returned `X509Certificate[]` has correct subject DN.

---

## Cross-Cutting Notes

- **No Saga or outbox changes** — all Saga topology, Kafka topics, and outbox pattern remain unchanged
- **No storage changes** — local and S3 backends unaffected
- **No PAdES logic changes** — PDFBox digest, BouncyCastle CMS, embedder all unchanged
- **`CscProperties.clientId` removal**: the `app.csc.client-id` env var and YAML key are removed. OAuth2 token acquisition is handled by `AuthFeignConfig` (client_credentials grant) separately from request body fields — that config is unaffected
- **`SadTokenValidator`**: unchanged — still validates expiry from `expiresIn` on the authorize response

---

## File Change Summary

| File | Change type |
|------|-------------|
| `CSCAuthorizeRequest.java` | Modify (rename fields, add `AuthDataEntry`) |
| `CSCAuthorizeResponse.java` | Modify (remove unused fields) |
| `CSCSignatureRequest.java` | Modify (remove wrappers, rename fields) |
| `CSCSignatureResponse.java` | Modify (rename `operationID`, remove cert/timestamp) |
| `CSCCredentialsInfoRequest.java` | **New** |
| `CSCCredentialsInfoResponse.java` | **New** |
| `CSCCredentialsInfoClient.java` | **New** |
| `CredentialInfoCache.java` | **New** |
| `CscSigningAdapter.java` | Modify (new dependency, updated flow) |
| `CertificateParser.java` | Modify (add DER parsing method) |
| `CscProperties.java` | Modify (rename/remove fields) |
| `application.yml` | Modify (rename/add keys) |
| `CSCDtoTest.java` | Modify (update field assertions) |
| `CscSigningAdapterTest.java` | Modify (add mock, update assertions) |
| `CredentialInfoCacheTest.java` | **New** |
| `CertificateParserTest.java` | Modify (add DER parsing test) |
