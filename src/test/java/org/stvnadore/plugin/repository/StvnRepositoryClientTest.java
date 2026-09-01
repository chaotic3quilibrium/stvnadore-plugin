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
    void testPublishSuccess_201() throws Exception {
        server.createContext("/api/v1/schemas/user_schema", exchange -> {
            assertEquals("POST", exchange.getRequestMethod());
            assertEquals("application/stvn", exchange.getRequestHeaders().getFirst("Content-Type"));
            byte[] response = "{\"schemaName\":\"user_schema\",\"shapeSignature\":\"0x1234\",\"casHash\":\"e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855\"}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(201, response.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(response);
            }
        });

        PublishResponse response = StvnRepositoryClient.publishSchemaAsync(
                "http://localhost:" + port,
                "user_schema",
                ":type :Int32",
                2000
        ).get(5, TimeUnit.SECONDS);

        assertTrue(response instanceof PublishResponse.Success);
        SchemaMetadataDto metadata = ((PublishResponse.Success) response).metadata();
        assertEquals("user_schema", metadata.schemaName());
        assertEquals("0x1234", metadata.shapeSignature());
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", metadata.casHash());
    }

    @Test
    void testPublishIdempotent_200() throws Exception {
        server.createContext("/api/v1/schemas/existing_schema", exchange -> {
            byte[] response = "{\"schemaName\":\"existing_schema\",\"shapeSignature\":\"0x5678\",\"casHash\":\"abcdef123456\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(response);
            }
        });

        PublishResponse response = StvnRepositoryClient.publishSchemaAsync(
                "http://localhost:" + port,
                "existing_schema",
                ":type :String",
                2000
        ).get(5, TimeUnit.SECONDS);

        assertTrue(response instanceof PublishResponse.Idempotent);
        SchemaMetadataDto metadata = ((PublishResponse.Idempotent) response).metadata();
        assertEquals("existing_schema", metadata.schemaName());
    }

    @Test
    void testPublishConflict_409() throws Exception {
        server.createContext("/api/v1/schemas/locked_schema", exchange -> {
            byte[] response = "{\"error\":\"Conflict\",\"message\":\"Schema name 'locked_schema' already exists with hash abc. Mutations are prohibited.\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(409, response.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(response);
            }
        });

        PublishResponse response = StvnRepositoryClient.publishSchemaAsync(
                "http://localhost:" + port,
                "locked_schema",
                ":type :Float64",
                2000
        ).get(5, TimeUnit.SECONDS);

        assertTrue(response instanceof PublishResponse.Conflict);
        assertTrue(((PublishResponse.Conflict) response).message().contains("Mutations are prohibited"));
    }

    @Test
    void testPublishValidationError_422() throws Exception {
        server.createContext("/api/v1/schemas/bad_schema", exchange -> {
            byte[] response = "[{\"message\":\"Undefined type :BadType\",\"line\":12,\"column\":4}]".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(422, response.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(response);
            }
        });

        PublishResponse response = StvnRepositoryClient.publishSchemaAsync(
                "http://localhost:" + port,
                "bad_schema",
                ":type :BadType",
                2000
        ).get(5, TimeUnit.SECONDS);

        assertTrue(response instanceof PublishResponse.ValidationError);
        var diags = ((PublishResponse.ValidationError) response).diagnostics();
        assertEquals(1, diags.size());
        CompileDiagnosticDto diag = diags.get(0);
        assertEquals("Undefined type :BadType", diag.message());
        assertEquals(12, diag.line());
        assertEquals(4, diag.column());
    }

    @Test
    void testPublishTimeout_TransportError() throws Exception {
        server.createContext("/api/v1/schemas/slow_schema", exchange -> {
            try {
                Thread.sleep(500);
            } catch (InterruptedException ignored) {}
            exchange.sendResponseHeaders(201, -1);
        });

        PublishResponse response = StvnRepositoryClient.publishSchemaAsync(
                "http://localhost:" + port,
                "slow_schema",
                ":type :Int32",
                100 // 100ms timeout
        ).get(5, TimeUnit.SECONDS);

        assertTrue(response instanceof PublishResponse.TransportError);
    }
}