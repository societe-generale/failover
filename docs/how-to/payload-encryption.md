---
icon: material/lock-outline
---

# Payload Encryption (JDBC store)

Encrypt the serialized payload **at rest** in the JDBC failover store, so the `PAYLOAD` column holds
ciphertext instead of readable JSON. Two ciphers ship out of the box: a no-dependency **Base64** encoder
(`b64`, not encryption) and a production-grade **AES-GCM** cipher (`aesgcm`, real confidentiality — just
supply a key). For anything more specialised (KMS/Jasypt-backed, HSM, …) declare your own `PayloadCipher`
bean.

!!! warning "JDBC store only"
    Encryption applies **only** to the JDBC store — it is the only store that persists the payload as
    a string. In-memory and Caffeine stores keep live objects and are never encrypted. That is why the
    configuration lives under `failover.store.jdbc.encryption.*`, not the generic `failover.store.*`.

---

## How it works

```
WRITE  payload --serialize--> JSON --cipher.encrypt--> ENC(<id>:<ciphertext>) --> PAYLOAD column
READ   PAYLOAD --> ENC(<id>:<ciphertext>) --cipher[id].decrypt--> JSON --deserialize--> payload
```

Every encrypted value is wrapped in a self-describing envelope **`ENC(<cipherId>:<ciphertext>)`**.
The `cipherId` records which cipher produced the row, so a single store can hold a mix of plaintext,
`ENC(b64:…)` and `ENC(aesgcm:…)` rows and still read each one correctly.

* **`PAYLOAD_CLASS` is never encrypted.** Only the data column is. The class name stays plaintext so
  the [deserialization allowlist](../support/security.md) keeps gating class loading on the real name.

See the [Payload Encryption concept](../concepts/payload-encryption.md) for the design rationale and
[ADR 56](../adr/adr.md).

---

## Configuration

```yaml
failover:
  store:
    type: jdbc
    jdbc:
      encryption:
        enabled: false     # (default) write plaintext; reads still decrypt any ENC(...) row
        cipher: b64         # (default) id of the registered PayloadCipher used for new writes
```

| Property | Default | Meaning |
|---|---|---|
| `failover.store.jdbc.encryption.enabled` | `false` | Gates the **write** side only. `true` → new rows are encrypted. Reads always honour the `ENC(...)` marker regardless. |
| `failover.store.jdbc.encryption.cipher` | `b64` | The `id()` of the registered `PayloadCipher` to encrypt **new writes** with (e.g. `aesgcm`). |
| `failover.store.jdbc.encryption.key` | `""` | Base64-encoded AES key for the built-in `aesgcm` cipher (decodes to 16/24/32 bytes). When set, an `AesGcmPayloadCipher` is auto-registered. **A secret — inject from a secret manager / env var, never commit it.** |

---

## Real encryption out of the box: AES-GCM (recommended)

The built-in **`aesgcm`** cipher is AES-GCM authenticated encryption (random IV per write, 128-bit auth
tag). It needs only a key — no custom bean:

```yaml
failover:
  store:
    type: jdbc
    jdbc:
      encryption:
        enabled: true
        cipher: aesgcm
        key: ${FAILOVER_STORE_JDBC_ENCRYPTION_KEY}   # Base64, 16/24/32 bytes — from a secret store
```

```bash
# generate a 256-bit key
openssl rand -base64 32
```

When `failover.store.jdbc.encryption.key` is non-empty, the framework auto-registers an
`AesGcmPayloadCipher` (id `aesgcm`) for both reads and writes. An invalid key (bad Base64 or wrong length)
fails startup fast.

!!! danger "The key is a secret"
    Never commit a real key to source or `application.yml`. Inject it from a secret manager, KMS, or an
    environment variable (e.g. `FAILOVER_STORE_JDBC_ENCRYPTION_KEY`). Anyone with the key **and** store
    access can read the payloads; losing the key makes existing `ENC(aesgcm:…)` rows unreadable (they are
    a regenerable cache, so this only costs a cold start).

!!! note "Authenticated — fails loud"
    AES-GCM verifies an authentication tag on read: a tampered row, the wrong key, or a non-AES-GCM value
    throws rather than returning corrupted data.

### Enable / disable is always safe

`enabled` only controls **writes**. The read path always decrypts any `ENC(...)` row (as long as the
naming cipher is on the classpath) and passes any non-enveloped value through as plaintext. So:

| Toggle | Existing `ENC(…)` rows | Existing plaintext rows | New writes |
|---|---|---|---|
| turn **on** | still decrypt | still read | encrypted |
| turn **off** | still decrypt | still read | plaintext |

---

## The default: Base64 is **not** encryption

When `enabled=true` with no custom cipher, the built-in `Base64PayloadCipher` (id `b64`) is used. It is
**encoding only** — anyone who can read the database can trivially decode it. The auto-configuration
logs a loud `WARN` when Base64 is the active write cipher:

```
Failover JDBC payload encryption is ENABLED with the Base64 cipher ('b64') — this is ENCODING,
NOT ENCRYPTION, and provides no confidentiality. Declare a PayloadCipher bean with a real algorithm…
```

Use it for obfuscation/demos only. For confidentiality, provide a real cipher (below).

---

## Provide your own cipher

For most needs the built-in `aesgcm` cipher (above) is enough. Implement `PayloadCipher` only for a
specialised backend — a KMS/HSM-managed key, an envelope-encryption scheme, Jasypt, etc. Declare it as a
bean; the decorator owns the `ENC(...)` envelope, so your cipher deals in **raw** ciphertext only — no
envelope parsing.

```java
public interface PayloadCipher {
    String id();                                  // short, stable, unique; persisted in ENC(<id>:…)
    @Nullable String encrypt(@Nullable String plaintext);
    @Nullable String decrypt(@Nullable String ciphertext);
}
```

Example: a KMS-backed cipher selected for writes:

```java
@Bean
public PayloadCipher kmsPayloadCipher(KmsClient kms) {
    return new KmsPayloadCipher(kms);   // your implementation; id() e.g. "kms"
}
```

```yaml
failover:
  store:
    jdbc:
      encryption:
        enabled: true
        cipher: kms     # must match your cipher's id()
```

!!! tip "Contract"
    *`id()` must be **unique** across all cipher beans and contain neither `:` nor `)` — it is persisted
      in every encrypted row, so changing it strands existing rows.
    * `encrypt`/`decrypt` must round-trip and be null-safe (`null` → `null`).
    * `decrypt` must **throw** (not return garbage) on input it did not produce, so a misconfiguration
      surfaces loudly. (This is why `b64` carries an id: a Base64 decoder would otherwise silently
      "decode" AES bytes into junk.)

The built-in `b64` cipher remains registered for **reads** unless you declare your own
`Base64PayloadCipher`. This lets old `ENC(b64:…)` rows keep decrypting while new writes use AES.

---

## Key / algorithm rotation

Because each row records its cipher id, rotation is a rolling operation:

1. Add the new cipher bean (e.g. `aesgcm`) **alongside** the old one (e.g. `b64`).
2. Point writes at the new cipher: `failover.store.jdbc.encryption.cipher: aesgcm`.
3. New rows are written `ENC(aesgcm:…)`; old `ENC(b64:…)` rows still decrypt via the retained bean.
4. Once all old rows have **expired** from the cache, drop the old cipher bean.

If a row references a cipher id that is **no longer registered**, the read throws a
`FailoverStoreException` — and since the failover store is a *cache*, that recovery simply fails and the
row is reclaimed on expiry/cleanup. No data is lost that wasn't already disposable.

---

## Operational notes

* **Column sizing.** Ciphertext is larger than plaintext (Base64 ≈ +33 %; AES + IV + Base64 more). Size
  the `PAYLOAD VARCHAR(n)` column accordingly.
* **Lookups are unaffected.** The store key is `FAILOVER_NAME` + `FAILOVER_KEY`, not the payload, so a
  non-deterministic (random-IV) ciphertext does not break `find`.
* **Data minimisation still applies.** Encryption protects the payload column; prefer not storing PII you
  do not need in the first place.
