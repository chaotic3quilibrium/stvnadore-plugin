package org.stvnadore.plugin.repository;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.stvnadore.plugin.repository.dto.CompileDiagnosticDto;
import org.stvnadore.plugin.repository.dto.SchemaMetadataDto;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit test suite verifying STVN 2.0.0 Schema Repository wire contracts,
 * {@code .stvn_d} schema name derivation, {@code Content-Type: application/stvn; charset=utf-8},
 * 5-field {@link SchemaMetadataDto}, and 7-field {@link CompileDiagnosticDto}.
 */
public class StvnRepositoryClientTest {

    private HttpServer server;
    private int port;

    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        port = server.getAddress().getPort();
        server.start();
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void testDeriveSchemaNamePreservesCanonicalStvnD() {
        assertEquals("user.billing.stvn_d", StvnRepositoryClient.deriveSchemaName("user.billing.stvn_d"));
        assertEquals("user.billing.stvn_d", StvnRepositoryClient.deriveSchemaName("user.billing.stvn_di"));
        assertEquals("archive.billing.stvn_bd", StvnRepositoryClient.deriveSchemaName("archive.billing.stvn_bd"));
        assertEquals("standalone.stvn_d", StvnRepositoryClient.deriveSchemaName("standalone"));
    }

    @Test
    void testPublishSuccess_201() throws Exception {
        String schemaName = StvnRepositoryClient.deriveSchemaName("user.billing.stvn_d");
        assertEquals("user.billing.stvn_d", schemaName);

        server.createContext("/api/v1/schemas/user.billing.stvn_d", exchange -> {
            assertEquals("POST", exchange.getRequestMethod());
            assertEquals("application/stvn; charset=utf-8", exchange.getRequestHeaders().getFirst("Content-Type"));
            byte[] response = ("{\"schemaName\":\"user.billing.stvn_d\",\"domain\":\"billing\","
                + "\"shapeSignature\":\"0x1234\","
                + "\"casHash\":\"e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855\","
                + "\"warningsCount\":1}").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(201, response.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(response);
            }
        });

        PublishResponse response = StvnRepositoryClient.publishSchemaAsync(
                "http://localhost:" + port,
                schemaName,
                "{ :meta { #name \"user\" #domain \"billing\" #kind #DEFS } :defs { :Port { #unsigned #size 16 } :Int } }",
                2000
        ).get(5, TimeUnit.SECONDS);

        assertTrue(response instanceof PublishResponse.Success);
        SchemaMetadataDto metadata = ((PublishResponse.Success) response).metadata();
        assertEquals("user.billing.stvn_d", metadata.schemaName());
        assertEquals("billing", metadata.domain());
        assertEquals("0x1234", metadata.shapeSignature());
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", metadata.casHash());
        assertEquals(1, metadata.warningsCount());
    }

    @Test
    void testPublishIdempotent_200() throws Exception {
        server.createContext("/api/v1/schemas/existing.stvn_d", exchange -> {
            assertEquals("application/stvn; charset=utf-8", exchange.getRequestHeaders().getFirst("Content-Type"));
            byte[] response = "{\"schemaName\":\"existing.stvn_d\",\"domain\":null,\"shapeSignature\":\"0x5678\",\"casHash\":\"abcdef123456\",\"warningsCount\":0}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(response);
            }
        });

        PublishResponse response = StvnRepositoryClient.publishSchemaAsync(
                "http://localhost:" + port,
                "existing.stvn_d",
                "{ :meta { #name \"existing\" #kind #DEFS } :defs { :Label :String } }",
                2000
        ).get(5, TimeUnit.SECONDS);

        assertTrue(response instanceof PublishResponse.Idempotent);
        SchemaMetadataDto metadata = ((PublishResponse.Idempotent) response).metadata();
        assertEquals("existing.stvn_d", metadata.schemaName());
        assertNull(metadata.domain());
        assertEquals(0, metadata.warningsCount());
    }

    @Test
    void testPublishConflict_409() throws Exception {
        server.createContext("/api/v1/schemas/locked.stvn_d", exchange -> {
            byte[] response = "{\"error\":\"Conflict\",\"message\":\"Schema name 'locked.stvn_d' already exists with hash abc. Mutations are prohibited.\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(409, response.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(response);
            }
        });

        PublishResponse response = StvnRepositoryClient.publishSchemaAsync(
                "http://localhost:" + port,
                "locked.stvn_d",
                "{ :meta { #name \"locked\" #kind #DEFS } :defs { :Ratio :Float } }",
                2000
        ).get(5, TimeUnit.SECONDS);

        assertTrue(response instanceof PublishResponse.Conflict);
        assertTrue(((PublishResponse.Conflict) response).message().contains("Mutations are prohibited"));
    }

    @Test
    void testPublishValidationError_422() throws Exception {
        server.createContext("/api/v1/schemas/bad.stvn_d", exchange -> {
            byte[] response = ("["
                + "{\"message\":\"Undefined type: :BadType\",\"line\":12,\"column\":4,"
                + "\"startOffset\":140,\"endOffset\":148,"
                + "\"canonicalCode\":\"E018\",\"symbolicId\":\"ERR_UNDEFINED_TYPE\"}"
                + "]").getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(422, response.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(response);
            }
        });

        PublishResponse response = StvnRepositoryClient.publishSchemaAsync(
                "http://localhost:" + port,
                "bad.stvn_d",
                "{ :meta { #name \"bad\" #kind #DEFS } :defs { :Broken :BadType } }",
                2000
        ).get(5, TimeUnit.SECONDS);

        assertTrue(response instanceof PublishResponse.ValidationError);
        var diags = ((PublishResponse.ValidationError) response).diagnostics();
        assertEquals(1, diags.size());
        CompileDiagnosticDto diag = diags.get(0);
        assertEquals("Undefined type: :BadType", diag.message());
        assertEquals(12, diag.line());
        assertEquals(4, diag.column());
        assertEquals(140, diag.startOffset());
        assertEquals(148, diag.endOffset());
        assertEquals("E018", diag.canonicalCode());
        assertEquals("ERR_UNDEFINED_TYPE", diag.symbolicId());
    }

    @Test
    void testPublishTimeout_TransportError() throws Exception {
        server.createContext("/api/v1/schemas/slow.stvn_d", exchange -> {
            try {
                Thread.sleep(500);
            } catch (InterruptedException ignored) {}
            exchange.sendResponseHeaders(201, -1);
        });

        PublishResponse response = StvnRepositoryClient.publishSchemaAsync(
                "http://localhost:" + port,
                "slow.stvn_d",
                "{ :meta { #name \"slow\" #kind #DEFS } :defs { :Count :Int } }",
                100
        ).get(5, TimeUnit.SECONDS);

        assertTrue(response instanceof PublishResponse.TransportError);
    }
}
