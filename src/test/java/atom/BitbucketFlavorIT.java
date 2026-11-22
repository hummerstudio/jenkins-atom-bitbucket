package atom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jenkinschina.atom.sdk.testing.Atom;
import com.jenkinschina.atom.sdk.testing.AtomResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The one thing the merge adds: the {@code flavor} select decides which deployment is spoken to.
 *
 * <p>The two ports are dispatched, not unified, so the risk of the merge is the dispatch itself — a job that says
 * {@code cloud} must never reach the Server API and vice versa, an empty {@code flavor} must keep the old
 * {@code bitbucket-status} behaviour, and a value that is neither must be refused by name instead of being guessed at.
 */
class BitbucketFlavorIT {

    private static final String SHA = "a1b2c3d4e5f60718293a4b5c6d7e8f9012345678";
    private static final Path MODULE = Path.of(System.getProperty("atom.module", System.getProperty("user.dir")));

    private Path work;

    @BeforeEach
    void setUp() throws Exception {
        work = Files.createTempDirectory(Path.of(System.getProperty("atom.scratch")), "bitbucket-flavor-");
    }

    @Test
    @DisplayName("an unknown flavor is refused, and the message names the two it knows")
    void unknownFlavorIsRefused() throws Exception {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("flavor", "bitbucket");
        params.put("sha", SHA);
        params.put("BUILD_STATUS", "SUCCESS");

        AtomResult run = run(params, Map.of());

        run.assertRefused();
        String output = run.file("output.json");
        assertTrue(output.contains("40000"), "the dispatch error has no code of its own:\n" + output);
        assertTrue(output.contains("'bitbucket'"), "the refused value is not echoed:\n" + output);
        assertTrue(output.contains("server") && output.contains("cloud"),
                "the message does not name the two flavours:\n" + output);
    }

    @Test
    @DisplayName("no flavor at all speaks Bitbucket Server, exactly as bitbucket-status did")
    void anEmptyFlavorIsServer() throws Exception {
        try (FakeBitbucket app = FakeBitbucket.start(MODULE, work, "normal", "bearer", "bb-token-123", "", "PROJ/parcel/1")) {
            Map<String, String> params = new LinkedHashMap<>();
            params.put("serverUrl", app.base());
            params.put("sha", SHA);
            params.put("BUILD_STATUS", "SUCCESS");
            params.put("JOB_NAME", "parcel");
            params.put("BUILD_NUMBER", "42");
            params.put("BUILD_URL", "http://jenkins/job/parcel/42/display/redirect");

            AtomResult run = run(params, Map.of("token", "bb-token-123"));

            run.assertOk();
            assertEquals("SUCCESSFUL", run.field("state"));
            assertEquals(app.base() + "/rest/build-status/1.0/commits/" + SHA, run.field("apiUrl"));
            assertTrue(app.logText().contains("POST"), "the Server API was never called:\n" + app.logText());
            assertFalse(run.file("output.json").contains("tokenSource"),
                    "the cloud flavour answered for an unset flavor");
        }
    }

    @Test
    @DisplayName("flavor is read case-insensitively: Server still speaks the Server API")
    void flavorIsCaseInsensitive() throws Exception {
        try (FakeBitbucket app = FakeBitbucket.start(MODULE, work, "normal", "bearer", "bb-token-123", "", "PROJ/parcel/1")) {
            Map<String, String> params = new LinkedHashMap<>();
            params.put("flavor", " Server ");
            params.put("serverUrl", app.base());
            params.put("sha", SHA);
            params.put("BUILD_STATUS", "SUCCESS");
            params.put("JOB_NAME", "parcel");
            params.put("BUILD_NUMBER", "42");
            params.put("BUILD_URL", "http://jenkins/job/parcel/42/display/redirect");

            AtomResult run = run(params, Map.of("token", "bb-token-123"));

            run.assertOk();
            assertEquals("SUCCESSFUL", run.field("state"));
        }
    }

    @Test
    @DisplayName("flavor=CLOUD goes to the Cloud API even when a Server URL is filled in")
    void cloudIgnoresTheServerFlavourInputs() throws Exception {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("flavor", "CLOUD");
        params.put("serverUrl", "https://bitbucket.example.com");
        params.put("workspace", "acme");
        params.put("repoSlug", "parcel");
        params.put("sha", SHA);
        params.put("BUILD_STATUS", "SUCCESS");
        params.put("BUILD_URL", "http://jenkins/job/parcel/42/display/redirect");
        AtomResult run = run(params, Map.of());

        run.assertRefused();
        String output = run.file("output.json");
        assertTrue(output.contains("40002"), "the cloud credential error is not the cloud one:\n" + output);
        assertTrue(output.contains("OAuth consumer"), "the cloud flavour did not answer:\n" + output);
    }

    private static AtomResult run(Map<String, String> params, Map<String, String> secrets) throws Exception {
        return Atom.of()
                .file(".secrets.json", json(secrets))
                .input(json(params))
                .run();
    }

    private static String json(Map<String, String> values) {
        StringBuilder out = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, String> entry : values.entrySet()) {
            if (!first) {
                out.append(", ");
            }
            first = false;
            out.append('"').append(entry.getKey()).append("\": \"")
                    .append(entry.getValue().replace("\\", "\\\\").replace("\"", "\\\""))
                    .append('"');
        }
        return out.append('}').toString();
    }
}
