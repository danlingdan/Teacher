package com.sqlteacher.infrastructure.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sqlteacher.application.ai.*;
import com.sqlteacher.infrastructure.security.WindowsDpapiSecretStore;

import java.io.*;
import java.net.URI;
import java.nio.file.*;
import java.util.*;
import java.util.function.Function;

/** Stores non-sensitive profiles in JSON and each credential in a separate CurrentUser DPAPI blob. */
public final class PersistentNetworkAiSettingsService
    implements NetworkAiSettingsService, AiProviderProfileService, AutoCloseable {
    private static final int FORMAT_VERSION = 2;
    private static final String DEFAULT_ID = "default-network";

    private final Path profileFile;
    private final ObjectMapper mapper;
    private final Function<String, WindowsDpapiSecretStore> secretStores;
    private final Runnable clearAllSecrets;
    private final List<AiProviderProfile> profiles = new ArrayList<>();
    private String activeProfileId = "";

    public PersistentNetworkAiSettingsService(Path profileFile, Path credentialDirectory) {
        this(profileFile, reference -> new WindowsDpapiSecretStore(
                credentialDirectory.toAbsolutePath().normalize().resolve(reference + ".dat")),
            () -> clearCredentialDirectory(credentialDirectory.toAbsolutePath().normalize()), new ObjectMapper());
    }

    /** Compatibility constructor retained for focused storage tests and single-profile migration. */
    public PersistentNetworkAiSettingsService(Path profileFile, WindowsDpapiSecretStore secretStore) {
        this(profileFile, ignored -> secretStore, secretStore::clear, new ObjectMapper());
    }

    PersistentNetworkAiSettingsService(Path profileFile,
            Function<String, WindowsDpapiSecretStore> secretStores, ObjectMapper mapper) {
        this(profileFile, secretStores, () -> { }, mapper);
    }

    private PersistentNetworkAiSettingsService(Path profileFile,
            Function<String, WindowsDpapiSecretStore> secretStores, Runnable clearAllSecrets, ObjectMapper mapper) {
        this.profileFile = Objects.requireNonNull(profileFile).toAbsolutePath().normalize();
        this.secretStores = Objects.requireNonNull(secretStores);
        this.clearAllSecrets = Objects.requireNonNull(clearAllSecrets);
        this.mapper = Objects.requireNonNull(mapper);
        load();
    }

    @Override public synchronized void configure(URI endpoint, String model, char[] apiKey) {
        save(new AiProviderProfileDraft(DEFAULT_ID, "OpenAI-compatible", AiProviderKind.OPENAI_COMPATIBLE,
            endpoint, model, true), apiKey);
        activate(DEFAULT_ID);
    }

    @Override public synchronized Optional<OpenAiCompatibleConfiguration> current() {
        return configuration(activeProfileId);
    }

    @Override public synchronized List<AiProviderProfile> profiles() { return List.copyOf(profiles); }

    @Override public synchronized Optional<AiProviderProfile> activeProfile() {
        return profiles.stream().filter(profile -> profile.id().equals(activeProfileId)).findFirst();
    }

    @Override public synchronized void save(AiProviderProfileDraft draft, char[] credential) {
        Objects.requireNonNull(draft);
        Objects.requireNonNull(credential);
        String credentialReference = profiles.stream().filter(item -> item.id().equals(draft.id()))
            .map(AiProviderProfile::credentialReference).findFirst().orElseGet(() -> UUID.randomUUID().toString());
        AiProviderProfile replacement = new AiProviderProfile(draft.id(), draft.displayName(), draft.kind(),
            draft.endpoint(), draft.model(), draft.enabled(), credentialReference);
        try {
            if (credential.length > 0) {
                byte[] encoded = encodeSecret(credentialReference, credential);
                try { secretStores.apply(credentialReference).save(encoded); }
                finally { Arrays.fill(encoded, (byte) 0); }
            } else if (profiles.stream().noneMatch(item -> item.id().equals(draft.id()))) {
                throw new IllegalArgumentException("credential must not be empty for a new profile");
            }
            profiles.removeIf(item -> item.id().equals(draft.id()));
            profiles.add(replacement);
            if (activeProfileId.isBlank()) activeProfileId = replacement.id();
            persist();
        } finally {
            Arrays.fill(credential, '\0');
        }
    }

    @Override public synchronized void activate(String profileId) {
        AiProviderProfile profile = profiles.stream().filter(item -> item.id().equals(profileId))
            .findFirst().orElseThrow(() -> new IllegalArgumentException("Unknown AI provider profile"));
        if (!profile.enabled()) throw new IllegalStateException("AI provider profile is disabled");
        activeProfileId = profile.id();
        persist();
    }

    @Override public synchronized void deactivate() {
        activeProfileId = "";
        persist();
    }

    @Override public synchronized void remove(String profileId) {
        AiProviderProfile profile = profiles.stream().filter(item -> item.id().equals(profileId)).findFirst().orElse(null);
        if (profile == null) return;
        secretStores.apply(profile.credentialReference()).clear();
        profiles.remove(profile);
        if (profileId.equals(activeProfileId)) activeProfileId = profiles.stream().filter(AiProviderProfile::enabled)
            .map(AiProviderProfile::id).findFirst().orElse("");
        persist();
    }

    @Override public synchronized Optional<OpenAiCompatibleConfiguration> configuration(String profileId) {
        AiProviderProfile profile = profiles.stream().filter(item -> item.id().equals(profileId) && item.enabled())
            .findFirst().orElse(null);
        if (profile == null || profile.kind() != AiProviderKind.OPENAI_COMPATIBLE) return Optional.empty();
        Optional<byte[]> stored = secretStores.apply(profile.credentialReference()).load();
        if (stored.isEmpty()) return Optional.empty();
        byte[] bytes = stored.get();
        char[] key = null;
        try {
            key = decodeSecret(profile.credentialReference(), bytes);
            return Optional.of(new OpenAiCompatibleConfiguration(profile.endpoint(), profile.model(), key));
        } catch (IOException error) {
            secretStores.apply(profile.credentialReference()).clear();
            return Optional.empty();
        } finally {
            Arrays.fill(bytes, (byte) 0);
            if (key != null) Arrays.fill(key, '\0');
        }
    }

    @Override public synchronized void clear() {
        for (AiProviderProfile profile : List.copyOf(profiles)) secretStores.apply(profile.credentialReference()).clear();
        profiles.clear();
        activeProfileId = "";
        try { Files.deleteIfExists(profileFile); }
        catch (IOException error) { throw new IllegalStateException("Unable to clear network AI provider settings", error); }
    }

    @Override public void close() { /* credentials are decrypted only for individual calls */ }

    private void load() {
        if (Files.notExists(profileFile)) return;
        try {
            StoredState state = mapper.readValue(profileFile.toFile(), StoredState.class);
            if (state.formatVersion() != FORMAT_VERSION) throw new IOException("Unsupported AI profile format");
            for (StoredProfile item : state.profiles()) {
                try { profiles.add(item.toProfile()); } catch (RuntimeException ignored) { /* isolate damaged profile */ }
            }
            activeProfileId = profiles.stream().anyMatch(item -> item.id().equals(state.activeProfileId()))
                ? state.activeProfileId() : profiles.stream().filter(AiProviderProfile::enabled).map(AiProviderProfile::id).findFirst().orElse("");
        } catch (Exception error) {
            profiles.clear();
            activeProfileId = "";
            clearAllSecrets.run();
            try { Files.deleteIfExists(profileFile); } catch (IOException ignored) { }
        }
    }

    private void persist() {
        Path temporary = profileFile.resolveSibling(profileFile.getFileName() + ".tmp");
        try {
            if (profileFile.getParent() != null) Files.createDirectories(profileFile.getParent());
            mapper.writerWithDefaultPrettyPrinter().writeValue(temporary.toFile(), new StoredState(FORMAT_VERSION,
                activeProfileId, profiles.stream().map(StoredProfile::from).toList()));
            moveReplacing(temporary, profileFile);
        } catch (IOException error) {
            throw new IllegalStateException("Unable to persist network AI provider settings", error);
        } finally {
            try { Files.deleteIfExists(temporary); } catch (IOException ignored) { }
        }
    }

    private static byte[] encodeSecret(String reference, char[] key) {
        try (ByteArrayOutputStream bytes = new ByteArrayOutputStream(); DataOutputStream output = new DataOutputStream(bytes)) {
            output.writeUTF(reference); output.writeInt(key.length);
            for (char character : key) output.writeChar(character);
            return bytes.toByteArray();
        } catch (IOException error) { throw new IllegalStateException("Unable to encode network AI credential", error); }
    }

    private static char[] decodeSecret(String reference, byte[] encoded) throws IOException {
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(encoded))) {
            if (!reference.equals(input.readUTF())) throw new IOException("Credential reference mismatch");
            int length = input.readInt();
            if (length < 1 || length > 16_384) throw new IOException("Invalid credential length");
            char[] key = new char[length];
            for (int i = 0; i < length; i++) key[i] = input.readChar();
            if (input.available() != 0) { Arrays.fill(key, '\0'); throw new IOException("Unexpected credential data"); }
            return key;
        }
    }

    private static void moveReplacing(Path source, Path target) throws IOException {
        try { Files.move(source, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
        catch (AtomicMoveNotSupportedException ignored) { Files.move(source, target, StandardCopyOption.REPLACE_EXISTING); }
    }

    private static void clearCredentialDirectory(Path directory) {
        if (Files.notExists(directory)) return;
        try (var paths = Files.list(directory)) {
            paths.filter(path -> Files.isRegularFile(path) && path.getFileName().toString().endsWith(".dat"))
                .forEach(path -> new WindowsDpapiSecretStore(path).clear());
        } catch (IOException ignored) { }
    }

    private record StoredState(int formatVersion, String activeProfileId, List<StoredProfile> profiles) {
        private StoredState { profiles = profiles == null ? List.of() : List.copyOf(profiles); activeProfileId = activeProfileId == null ? "" : activeProfileId; }
    }
    private record StoredProfile(String id, String displayName, String kind, String endpoint, String model,
                                 boolean enabled, String credentialReference) {
        static StoredProfile from(AiProviderProfile profile) { return new StoredProfile(profile.id(), profile.displayName(),
            profile.kind().name(), profile.endpoint().toString(), profile.model(), profile.enabled(), profile.credentialReference()); }
        AiProviderProfile toProfile() { return new AiProviderProfile(id, displayName, AiProviderKind.valueOf(kind),
            URI.create(endpoint), model, enabled, credentialReference); }
    }
}
