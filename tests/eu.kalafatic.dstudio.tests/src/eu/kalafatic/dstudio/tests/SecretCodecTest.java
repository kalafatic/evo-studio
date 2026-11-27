package eu.kalafatic.dstudio.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.kalafatic.dstudio.infrastructure.persistence.SchemaMigrationService;
import eu.kalafatic.dstudio.infrastructure.persistence.SecretCodec;
import eu.kalafatic.dstudio.infrastructure.persistence.SqliteDatabaseManager;
import eu.kalafatic.dstudio.infrastructure.persistence.SqliteHarnessStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Phase 7 (S4): secrets must never reach SQLite or files in plaintext — encryption at
 * rest via a per-installation AES-GCM master key with owner-only permissions.
 */
class SecretCodecTest {

    @TempDir
    Path tempDir;

    @Test
    void roundTripWithKeyFile() throws Exception {
        SecretCodec codec = new SecretCodec(tempDir);
        String encrypted = codec.encrypt("sk-secret-value");
        assertTrue(encrypted.startsWith("enc:"));
        assertNotEquals("sk-secret-value", encrypted);
        assertEquals("sk-secret-value", codec.decrypt(encrypted));
        Path keyFile = tempDir.resolve(SecretCodec.KEY_FILE_NAME);
        assertTrue(Files.isRegularFile(keyFile));
        try {
            var perms = Files.getPosixFilePermissions(keyFile);
            assertFalse(perms.contains(PosixFilePermission.GROUP_READ));
            assertFalse(perms.contains(PosixFilePermission.GROUP_WRITE));
            assertFalse(perms.contains(PosixFilePermission.OTHERS_READ));
            assertFalse(perms.contains(PosixFilePermission.OTHERS_WRITE));
        } catch (UnsupportedOperationException e) {
            // non-POSIX filesystem: platform permissions apply
        }
    }

    @Test
    void keyIsStableAcrossInstances() {
        SecretCodec first = new SecretCodec(tempDir);
        String encrypted = first.encrypt("stable");
        SecretCodec second = new SecretCodec(tempDir);
        assertEquals("stable", second.decrypt(encrypted));
    }

    @Test
    void ivIsUniquePerEncryption() {
        SecretCodec codec = new SecretCodec(tempDir);
        assertNotEquals(codec.encrypt("same"), codec.encrypt("same"));
    }

    @Test
    void wrongKeyFailsLoudly() throws Exception {
        SecretCodec first = new SecretCodec(tempDir);
        String encrypted = first.encrypt("guarded");
        Files.delete(tempDir.resolve(SecretCodec.KEY_FILE_NAME));
        SecretCodec second = new SecretCodec(tempDir);
        assertThrows(IllegalStateException.class, () -> second.decrypt(encrypted));
    }

    @Test
    void sensitiveKeyDetection() {
        assertTrue(SecretCodec.isSensitiveKey("authToken"));
        assertTrue(SecretCodec.isSensitiveKey("apiKey"));
        assertTrue(SecretCodec.isSensitiveKey("openai_secret"));
        assertTrue(SecretCodec.isSensitiveKey("password"));
        assertTrue(SecretCodec.isSensitiveKey("credential"));
        assertTrue(SecretCodec.isSensitiveKey("DCODE_AUTH_TOKEN"));
        assertFalse(SecretCodec.isSensitiveKey("model"));
        assertFalse(SecretCodec.isSensitiveKey("endpoint"));
        assertFalse(SecretCodec.isSensitiveKey("binary"));
        assertFalse(SecretCodec.isSensitiveKey("agent"));
    }

    @Test
    void mapRoundTripEncryptsOnlySensitiveValues() {
        SecretCodec codec = new SecretCodec(tempDir);
        Map<String, String> values = new java.util.LinkedHashMap<>();
        values.put("model", "ollama/qwen3:latest");
        values.put("authToken", "");
        values.put("endpoint", "http://localhost:28780");
        Map<String, String> stored = codec.encodeMap(values);
        // blank secret is not encrypted (nothing to protect)
        assertEquals("", stored.get("authToken"));
        assertEquals("ollama/qwen3:latest", stored.get("model"));
        stored = codec.encodeMap(Map.of("authToken", "tok-1"));
        assertTrue(codec.isEncrypted(stored.get("authToken")));
        Map<String, String> decoded = codec.decodeMap(stored);
        assertEquals("tok-1", decoded.get("authToken"));
        // plaintext values pass through decode untouched
        assertEquals("build", codec.decodeFromStorage("build"));
    }

    @Test
    void harnessConfigIsEncryptedAtRestInDatabase() throws Exception {
        SqliteDatabaseManager db = new SqliteDatabaseManager(tempDir.resolve("codec.db"));
        try {
            new SchemaMigrationService().migrate(db.connection());
            SqliteHarnessStore store = new SqliteHarnessStore(db.connection(),
                    new SecretCodec(tempDir));
            store.saveConfiguration("dcode", Map.of("endpoint", "http://localhost:48080",
                    "authToken", "super-secret-token", "model", "family-model"));
            try (PreparedStatement ps = db.connection().prepareStatement(
                    "SELECT config_json FROM harness_config WHERE harness_id = 'dcode'");
                    ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                String raw = rs.getString(1);
                assertFalse(raw.contains("super-secret-token"),
                        "plaintext secret must never reach the database");
                assertTrue(raw.contains("enc:"));
                assertTrue(raw.contains("http://localhost:48080"));
            }
            Map<String, String> loaded = store.findConfiguration("dcode");
            assertEquals("super-secret-token", loaded.get("authToken"));
            assertEquals("family-model", loaded.get("model"));
        } finally {
            db.close();
        }
    }
}
