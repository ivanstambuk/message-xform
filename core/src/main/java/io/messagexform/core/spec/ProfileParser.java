package io.messagexform.core.spec;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.messagexform.core.error.ProfileResolveException;
import io.messagexform.core.model.Direction;
import io.messagexform.core.model.ProfileEntry;
import io.messagexform.core.model.TransformProfile;
import io.messagexform.core.model.TransformSpec;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Parses YAML profile files into {@link TransformProfile} instances
 * (FR-001-05).
 * Resolves {@code spec: id@version} references against a pre-loaded spec
 * registry.
 *
 * <p>
 * Thread-safe if the underlying YAML parser and spec registry are thread-safe.
 */
public final class ProfileParser {

    private static final ObjectMapper YAML_MAPPER = new ObjectMapper(new YAMLFactory());

    private final Map<String, TransformSpec> specRegistry;

    /**
     * Creates a profile parser that resolves spec references from the given
     * registry.
     *
     * @param specRegistry map of "id@version" → loaded TransformSpec. The key
     *                     format
     *                     MUST be "specId@version" for versioned references, or
     *                     just
     *                     "specId" for unversioned (latest) resolution.
     */
    public ProfileParser(Map<String, TransformSpec> specRegistry) {
        this.specRegistry = Objects.requireNonNull(specRegistry, "specRegistry must not be null");
    }

    /**
     * Parses the YAML file at the given path into a {@link TransformProfile}.
     *
     * @param path path to the profile YAML file
     * @return a fully parsed profile with resolved spec references
     * @throws ProfileResolveException if the YAML is invalid, required fields are
     *                                 missing, or spec references cannot be
     *                                 resolved
     */
    public TransformProfile parse(Path path) {
        String source = path.toString();
        JsonNode root = readYaml(path, source);

        String profileId = requireString(root, "profile", source);
        String version = requireString(root, "version", source);
        String description = optionalString(root, "description");

        JsonNode transformsNode = root.get("transforms");
        if (transformsNode == null || !transformsNode.isArray() || transformsNode.isEmpty()) {
            throw new ProfileResolveException(
                    "Profile '" + profileId + "' must contain a non-empty 'transforms' array", null, source);
        }

        List<ProfileEntry> entries = new ArrayList<>();
        for (int i = 0; i < transformsNode.size(); i++) {
            entries.add(parseEntry(transformsNode.get(i), profileId, source, i));
        }

        return new TransformProfile(profileId, description, version, entries);
    }

    // --- Private helpers ---

    private ProfileEntry parseEntry(JsonNode entryNode, String profileId, String source, int index) {
        // Parse spec reference (required)
        String specRef = requireEntryString(entryNode, "spec", profileId, source, index);
        TransformSpec resolvedSpec = resolveSpec(specRef, profileId, source, index);

        // Parse direction (required per ADR-0016)
        String directionStr = requireEntryString(entryNode, "direction", profileId, source, index);
        Direction direction = parseDirection(directionStr, profileId, source, index);

        // Parse match block (required)
        JsonNode matchNode = entryNode.get("match");
        if (matchNode == null || !matchNode.isObject()) {
            throw new ProfileResolveException(
                    String.format("Profile '%s' entry[%d]: 'match' block is required", profileId, index), null, source);
        }

        String pathPattern = requireMatchString(matchNode, "path", profileId, source, index);
        String method = optionalString(matchNode, "method");
        String contentType = optionalString(matchNode, "content-type");

        // Normalize method to uppercase for consistent matching
        if (method != null) {
            method = method.toUpperCase();
        }

        return new ProfileEntry(resolvedSpec, direction, pathPattern, method, contentType);
    }

    /**
     * Resolves a spec reference of the form "id@version" or "id" (latest).
     * If the reference includes a version, looks up "id@version" in the registry.
     * If no version, finds the latest loaded version of the spec (highest semver).
     */
    private TransformSpec resolveSpec(String specRef, String profileId, String source, int index) {
        // Check for versioned reference (id@version)
        TransformSpec spec = specRegistry.get(specRef);
        if (spec != null) {
            return spec;
        }

        // If specRef has no @, try to find latest version
        if (!specRef.contains("@")) {
            TransformSpec latest = findLatestVersion(specRef);
            if (latest != null) {
                return latest;
            }
        }

        throw new ProfileResolveException(
                String.format(
                        "Profile '%s' entry[%d]: spec reference '%s' not found in registry", profileId, index, specRef),
                null,
                source);
    }

    /**
     * Finds the latest version of a spec by id (highest semver).
     * Scans the registry for all entries matching "id@*".
     */
    private TransformSpec findLatestVersion(String specId) {
        TransformSpec latest = null;
        String latestVersion = null;

        for (var entry : specRegistry.entrySet()) {
            String key = entry.getKey();
            if (key.startsWith(specId + "@")) {
                String version = key.substring(specId.length() + 1);
                if (latestVersion == null || compareVersions(version, latestVersion) > 0) {
                    latestVersion = version;
                    latest = entry.getValue();
                }
            }
        }

        // Also check for exact "id" key (unversioned)
        if (latest == null) {
            latest = specRegistry.get(specId);
        }

        return latest;
    }

    /**
     * Simple semver comparison for "major.minor.patch" strings.
     * Returns positive if v1 > v2, negative if v1 < v2, 0 if equal.
     */
    static int compareVersions(String v1, String v2) {
        String[] parts1 = v1.split("\\.");
        String[] parts2 = v2.split("\\.");
        int len = Math.max(parts1.length, parts2.length);
        for (int i = 0; i < len; i++) {
            int p1 = i < parts1.length ? Integer.parseInt(parts1[i]) : 0;
            int p2 = i < parts2.length ? Integer.parseInt(parts2[i]) : 0;
            if (p1 != p2) {
                return Integer.compare(p1, p2);
            }
        }
        return 0;
    }

    private Direction parseDirection(String directionStr, String profileId, String source, int index) {
        return switch (directionStr.toLowerCase()) {
            case "request" -> Direction.REQUEST;
            case "response" -> Direction.RESPONSE;
            default ->
                throw new ProfileResolveException(
                        String.format(
                                "Profile '%s' entry[%d]: invalid direction '%s' — must be 'request' or 'response'",
                                profileId, index, directionStr),
                        null,
                        source);
        };
    }

    private JsonNode readYaml(Path path, String source) {
        try {
            return YAML_MAPPER.readTree(path.toFile());
        } catch (IOException e) {
            throw new ProfileResolveException("Failed to read profile YAML: " + e.getMessage(), e, null, source);
        }
    }

    private String requireString(JsonNode root, String field, String source) {
        JsonNode node = root.get(field);
        if (node == null || !node.isTextual() || node.asText().isBlank()) {
            throw new ProfileResolveException("Profile is missing required field '" + field + "'", null, source);
        }
        return node.asText();
    }

    private String requireEntryString(JsonNode entryNode, String field, String profileId, String source, int index) {
        JsonNode node = entryNode.get(field);
        if (node == null || !node.isTextual() || node.asText().isBlank()) {
            throw new ProfileResolveException(
                    String.format("Profile '%s' entry[%d]: missing required field '%s'", profileId, index, field),
                    null,
                    source);
        }
        return node.asText();
    }

    private String requireMatchString(JsonNode matchNode, String field, String profileId, String source, int index) {
        JsonNode node = matchNode.get(field);
        if (node == null || !node.isTextual() || node.asText().isBlank()) {
            throw new ProfileResolveException(
                    String.format(
                            "Profile '%s' entry[%d]: match block missing required field '%s'", profileId, index, field),
                    null,
                    source);
        }
        return node.asText();
    }

    private String optionalString(JsonNode node, String field) {
        JsonNode child = node.get(field);
        return (child != null && child.isTextual()) ? child.asText() : null;
    }
}
