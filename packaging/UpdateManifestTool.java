import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;

/** Release helper. Private key material is read from an environment variable and is never printed. */
public final class UpdateManifestTool {
    public static void main(String[] args) throws Exception {
        if (args.length == 3 && "generate-key".equals(args[0])) { generate(Path.of(args[1]), Path.of(args[2])); return; }
        if (args.length == 4 && "sign".equals(args[0])) { sign(Path.of(args[1]), Path.of(args[2]), args[3]); return; }
        if (args.length == 5 && "set-rollout".equals(args[0])) { setRollout(Path.of(args[1]), Path.of(args[2]), args[3], args[4]); return; }
        if (args.length == 4 && "pause".equals(args[0])) { setRollout(Path.of(args[1]), Path.of(args[2]), args[3], "paused"); return; }
        throw new IllegalArgumentException("Usage: generate-key <private-file> <public-file> | sign <payload> <envelope> <key-id> | set-rollout <payload> <envelope> <key-id> <percent|paused> | pause <payload> <envelope> <key-id>");
    }

    private static void generate(Path privateFile, Path publicFile) throws Exception {
        if (Files.exists(privateFile) || Files.exists(publicFile)) throw new IllegalStateException("Refusing to overwrite an existing release key");
        Files.createDirectories(privateFile.toAbsolutePath().normalize().getParent());
        Files.createDirectories(publicFile.toAbsolutePath().normalize().getParent());
        var pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        Files.writeString(privateFile, Base64.getEncoder().encodeToString(pair.getPrivate().getEncoded()), StandardCharsets.US_ASCII);
        Files.writeString(publicFile, Base64.getEncoder().encodeToString(pair.getPublic().getEncoded()), StandardCharsets.US_ASCII);
    }

    private static void sign(Path payloadFile, Path envelopeFile, String keyId) throws Exception {
        if (keyId == null || !keyId.matches("[A-Za-z0-9._-]{1,64}")) throw new IllegalArgumentException("key-id is invalid");
        String encodedKey = System.getenv("SQLTEACHER_UPDATE_SIGNING_KEY");
        if (encodedKey == null || encodedKey.isBlank()) throw new IllegalStateException("SQLTEACHER_UPDATE_SIGNING_KEY is not configured");
        byte[] payload = Files.readAllBytes(payloadFile);
        if (payload.length == 0 || payload.length > 64 * 1024) throw new IllegalArgumentException("payload size is invalid");
        var privateKey = KeyFactory.getInstance("Ed25519").generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(encodedKey.strip())));
        Signature signer = Signature.getInstance("Ed25519"); signer.initSign(privateKey); signer.update(payload);
        String envelope = "{\n  \"keyId\": \"" + keyId + "\",\n  \"payload\": \""
            + Base64.getEncoder().encodeToString(payload) + "\",\n  \"signature\": \""
            + Base64.getEncoder().encodeToString(signer.sign()) + "\"\n}\n";
        Path parent = envelopeFile.toAbsolutePath().normalize().getParent();
        if (parent != null) Files.createDirectories(parent);
        Files.writeString(envelopeFile, envelope, StandardCharsets.US_ASCII);
    }

    /**
     * Re-signs a manifest with an adjusted rollout field for controlled release:
     * {@code value} is either a percentage 0-100 or the literal "paused". The
     * signature is recomputed with the same key, so clients keep trusting it.
     */
    private static void setRollout(Path payloadFile, Path envelopeFile, String keyId, String value) throws Exception {
        String payload = Files.readString(payloadFile, StandardCharsets.UTF_8).strip();
        if (payload.isEmpty() || payload.length() > 64 * 1024) throw new IllegalArgumentException("payload size is invalid");
        boolean paused = value.equalsIgnoreCase("paused");
        int percent = 100;
        if (!paused) {
            try { percent = Integer.parseInt(value); } catch (NumberFormatException error) { throw new IllegalArgumentException("percent must be 0-100 or 'paused'"); }
            if (percent < 0 || percent > 100) throw new IllegalArgumentException("percent must be 0-100");
        }
        String rolloutJson = "\"rollout\":{\"percentage\":" + percent + ",\"paused\":" + paused + "}";
        // Drop any existing rollout field (with its leading comma) so no double comma remains.
        payload = payload.replaceAll(",\\s*\"rollout\"\\s*:\\s*\\{[^}]*}", "");
        int closing = payload.lastIndexOf('}');
        if (closing < 0) throw new IllegalArgumentException("payload is not a JSON object");
        String prefix = payload.substring(0, closing).stripTrailing();
        String updated = prefix + (prefix.endsWith("{") || prefix.endsWith(",") ? "" : ",") + rolloutJson + "}";
        Files.writeString(payloadFile, updated, StandardCharsets.US_ASCII);
        sign(payloadFile, envelopeFile, keyId);
        System.out.println("rollout updated to " + (paused ? "paused" : percent + "%") + " and envelope re-signed");
    }
}
