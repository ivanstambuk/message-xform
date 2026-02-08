package io.messagexform.core.testkit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Extracts scenario YAML blocks from {@code scenarios.md} (T-001-50).
 *
 * <p>
 * Parses the markdown file, extracts each fenced YAML code block that
 * contains a {@code scenario:} key, and returns them as parsed
 * {@link ScenarioDefinition} records.
 *
 * <p>
 * Scenarios that reference unimplemented engines (JOLT, jq, JSONata) are
 * still loaded — the test harness is responsible for skipping them via
 * JUnit assumptions.
 */
public final class ScenarioLoader {

    private static final ObjectMapper YAML_MAPPER = new ObjectMapper(new YAMLFactory());

    /** Matches fenced YAML blocks: ```yaml ... ``` */
    private static final Pattern YAML_BLOCK = Pattern.compile("```yaml\\s*\\n(.*?)\\n```", Pattern.DOTALL);

    private ScenarioLoader() {}

    /**
     * Loads all scenario definitions from the given scenarios.md file.
     *
     * @param scenariosMdPath path to {@code scenarios.md}
     * @return unmodifiable list of parsed scenario definitions
     */
    public static List<ScenarioDefinition> loadAll(Path scenariosMdPath) throws IOException {
        String content = Files.readString(scenariosMdPath);
        Matcher matcher = YAML_BLOCK.matcher(content);
        List<ScenarioDefinition> scenarios = new ArrayList<>();

        while (matcher.find()) {
            String yamlBlock = matcher.group(1);
            try {
                JsonNode root = YAML_MAPPER.readTree(yamlBlock);
                if (root == null || !root.has("scenario")) {
                    continue; // not a scenario block (could be format example)
                }
                scenarios.add(parseScenario(root, yamlBlock));
            } catch (Exception e) {
                // Skip unparseable blocks (e.g., format examples with placeholders)
                continue;
            }
        }

        return Collections.unmodifiableList(scenarios);
    }

    private static ScenarioDefinition parseScenario(JsonNode root, String rawYaml) {
        String id = root.path("scenario").asText();
        String name = root.path("name").asText("");
        String description = root.path("description").asText("");

        // Transform block
        JsonNode transformNode = root.path("transform");
        String lang = transformNode.path("lang").asText("jslt");
        String expr = transformNode.path("expr").asText("");

        // Input
        JsonNode input = root.path("input");

        // Expected output (standard scenarios)
        JsonNode expectedOutput = root.has("expected_output") ? root.get("expected_output") : null;

        // Error scenarios
        JsonNode expectedError = root.has("expected_error") ? root.get("expected_error") : null;
        JsonNode expectedErrorResponse =
                root.has("expected_error_response") ? root.get("expected_error_response") : null;

        // Tags
        List<String> tags = new ArrayList<>();
        if (root.has("tags") && root.get("tags").isArray()) {
            root.get("tags").forEach(t -> tags.add(t.asText()));
        }

        // Context variables for scenarios that test $headers, $status, etc.
        JsonNode headers = root.has("headers") ? root.get("headers") : null;
        Integer statusCode = root.has("status_code") ? root.get("status_code").asInt() : null;
        JsonNode queryParams = root.has("query_params") ? root.get("query_params") : null;
        String requestPath =
                root.has("request_path") ? root.path("request_path").asText() : null;
        String requestMethod =
                root.has("request_method") ? root.path("request_method").asText() : null;

        // Direction
        String direction = root.has("direction") ? root.path("direction").asText() : null;

        return new ScenarioDefinition(
                id,
                name,
                description,
                lang,
                expr,
                input,
                expectedOutput,
                expectedError,
                expectedErrorResponse,
                tags,
                headers,
                statusCode,
                queryParams,
                requestPath,
                requestMethod,
                direction,
                rawYaml);
    }

    /**
     * A parsed scenario definition extracted from scenarios.md.
     */
    public record ScenarioDefinition(
            String id,
            String name,
            String description,
            String lang,
            String expr,
            JsonNode input,
            JsonNode expectedOutput,
            JsonNode expectedError,
            JsonNode expectedErrorResponse,
            List<String> tags,
            JsonNode headers,
            Integer statusCode,
            JsonNode queryParams,
            String requestPath,
            String requestMethod,
            String direction,
            String rawYaml) {

        /**
         * Whether this scenario tests a standard transform (input → expected_output).
         */
        public boolean isStandardTransform() {
            return expectedOutput != null && expectedError == null && expectedErrorResponse == null;
        }

        /** Whether this scenario is an error scenario. */
        public boolean isErrorScenario() {
            return expectedError != null || expectedErrorResponse != null;
        }

        /** Whether this scenario requires an engine other than JSLT. */
        public boolean requiresNonJsltEngine() {
            return !lang.equals("jslt");
        }

        /** Display name for JUnit parameterized test. */
        public String displayName() {
            return id + ": " + name;
        }

        @Override
        public String toString() {
            return displayName();
        }
    }
}
