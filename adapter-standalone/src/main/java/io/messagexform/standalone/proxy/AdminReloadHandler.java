package io.messagexform.standalone.proxy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.javalin.http.Context;
import io.javalin.http.Handler;
import io.messagexform.core.engine.TransformEngine;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Admin reload endpoint handler (FR-004-20, IMPL-004-04).
 *
 * <p>
 * {@code POST /admin/reload} scans {@code engine.specs-dir} for
 * {@code *.yaml}/{@code *.yml} files and calls
 * {@link TransformEngine#reload(List, Path)} to atomically swap the registry.
 *
 * <p>
 * Response on success:
 *
 * <pre>
 * 200 OK
 * {"status": "reloaded", "specs": N, "profile": "id-or-none"}
 * </pre>
 *
 * <p>
 * Response on failure:
 *
 * <pre>
 * 500 Internal Server Error
 * RFC 9457 application/problem+json body
 * </pre>
 *
 * <p>
 * This handler is NOT subject to transform matching — it is registered as
 * a dedicated Javalin route before the wildcard proxy handler.
 */
public final class AdminReloadHandler implements Handler {

    private static final Logger LOG = LoggerFactory.getLogger(AdminReloadHandler.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final TransformEngine engine;
    private final Path specsDir;
    private final Path profilePath;

    /**
     * Creates a new admin reload handler.
     *
     * @param engine      the transform engine to reload
     * @param specsDir    directory to scan for spec YAML files
     * @param profilePath optional profile path (null for no profile)
     */
    public AdminReloadHandler(TransformEngine engine, Path specsDir, Path profilePath) {
        this.engine = engine;
        this.specsDir = specsDir;
        this.profilePath = profilePath;
    }

    @Override
    public void handle(Context ctx) throws Exception {
        LOG.info("Admin reload triggered via POST {}", ctx.path());

        try {
            List<Path> specPaths = scanSpecFiles(specsDir);
            engine.reload(specPaths, profilePath);

            // Build success response — use specPaths.size() for file count,
            // not engine.specCount() (which counts both id and id@version keys)
            String profileId =
                    engine.activeProfile() != null ? engine.activeProfile().id() : "none";
            int loadedSpecCount = specPaths.size();

            ObjectNode response = MAPPER.createObjectNode();
            response.put("status", "reloaded");
            response.put("specs", loadedSpecCount);
            response.put("profile", profileId);

            ctx.status(200);
            ctx.contentType("application/json");
            ctx.result(response.toString());

            LOG.info("Reload successful: specs={}, profile={}", loadedSpecCount, profileId);
        } catch (Exception e) {
            LOG.error("Reload failed: {}", e.getMessage(), e);

            JsonNode problemDetail = ProblemDetail.internalError("Reload failed: " + e.getMessage(), ctx.path());

            ctx.status(500);
            ctx.contentType("application/problem+json");
            ctx.result(problemDetail.toString());
        }
    }

    /**
     * Scans the given directory for {@code *.yaml} and {@code *.yml} files,
     * returning them sorted for deterministic load order.
     *
     * @param dir the directory to scan
     * @return a sorted list of spec file paths
     * @throws IOException if the directory cannot be read
     */
    static List<Path> scanSpecFiles(Path dir) throws IOException {
        if (dir == null || !Files.exists(dir)) {
            return List.of();
        }

        List<Path> specFiles = new ArrayList<>();
        try (Stream<Path> stream = Files.list(dir)) {
            stream.filter(p -> {
                        String name = p.getFileName().toString();
                        return name.endsWith(".yaml") || name.endsWith(".yml");
                    })
                    .sorted()
                    .forEach(specFiles::add);
        }
        return specFiles;
    }
}
