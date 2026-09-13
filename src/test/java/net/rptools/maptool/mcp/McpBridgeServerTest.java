/*
 * This software Copyright by the RPTools.net development team, and
 * licensed under the Affero GPL Version 3 or, at your option, any later
 * version.
 *
 * MapTool Source Code is distributed in the hope that it will be
 * useful, but WITHOUT ANY WARRANTY; without even the implied warranty
 * of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 *
 * You should have received a copy of the GNU Affero General Public
 * License * along with this source Code.  If not, please visit
 * <http://www.gnu.org/licenses/> and specifically the Affero license
 * text at <http://www.gnu.org/licenses/agpl.html>.
 */
package net.rptools.maptool.mcp;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class McpBridgeServerTest {
  private static final String TOKEN = "test-credential-for-local-bridge-123456789";
  private McpBridgeServer server;
  private final AtomicInteger calls = new AtomicInteger();
  private final HttpClient client = HttpClient.newHttpClient();

  @BeforeEach
  void start() throws Exception {
    server =
        new McpBridgeServer(
            new McpConfig(0, TOKEN),
            new McpBridgeServer.ToolHandler() {
              @Override
              public JsonArray listTools() {
                assertTrue(SwingUtilities.isEventDispatchThread());
                JsonArray tools = new JsonArray();
                for (String name : new String[] {"read", "forbidden", "invalid", "crash"}) {
                  JsonObject tool = new JsonObject();
                  tool.addProperty("name", name);
                  tools.add(tool);
                }
                return tools;
              }

              @Override
              public JsonObject callTool(String name, JsonObject arguments) {
                assertTrue(SwingUtilities.isEventDispatchThread());
                calls.incrementAndGet();
                switch (name) {
                  case "forbidden" -> throw new SecurityException("Only the GM can change maps");
                  case "invalid" -> throw new IllegalArgumentException("Coordinates are required");
                  case "crash" ->
                      throw new IllegalStateException("internal secret should never leave bridge");
                  default -> {
                    JsonObject result = new JsonObject();
                    result.addProperty("name", "Forest");
                    return result;
                  }
                }
              }
            });
    server.start();
  }

  @AfterEach
  void stop() {
    server.close();
    client.close();
  }

  private HttpRequest.Builder request(String path, String body) {
    return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + path))
        .header("Content-Type", "application/json")
        .header("Authorization", "Bearer " + TOKEN)
        .POST(HttpRequest.BodyPublishers.ofString(body));
  }

  private HttpResponse<String> send(HttpRequest.Builder request) throws Exception {
    return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
  }

  private HttpResponse<String> call(String name) throws Exception {
    return send(
        request(
            "/mcp-bridge",
            "{\"method\":\"tools/call\",\"params\":{\"name\":\"" + name + "\",\"arguments\":{}}}"));
  }

  @Test
  void disabledUnlessExplicitlyEnabledAndSecretsNeverAppearInConfigDiagnostics() {
    assertTrue(McpConfig.fromEnvironment(Map.of()).isEmpty());
    assertTrue(McpConfig.fromEnvironment(Map.of("MAPTOOL_MCP_ENABLED", "false")).isEmpty());
    var error =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                McpConfig.fromEnvironment(
                    Map.of("MAPTOOL_MCP_ENABLED", "true", "MAPTOOL_MCP_TOKEN", "secret")));
    assertFalse(error.getMessage().contains("secret"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            McpConfig.fromEnvironment(
                Map.of(
                    "MAPTOOL_MCP_ENABLED",
                    "true",
                    "MAPTOOL_MCP_TOKEN",
                    TOKEN,
                    "MAPTOOL_MCP_PORT",
                    "0")));
    assertEquals(
        27182,
        McpConfig.fromEnvironment(Map.of("MAPTOOL_MCP_ENABLED", "true", "MAPTOOL_MCP_TOKEN", TOKEN))
            .orElseThrow()
            .port());
  }

  @Test
  void authenticatedDiscoveryAndCallUseTheSwingThread() throws Exception {
    var listed = send(request("/mcp-bridge", "{\"method\":\"tools/list\"}"));
    assertEquals(200, listed.statusCode());
    assertEquals(
        4, JsonParser.parseString(listed.body()).getAsJsonObject().getAsJsonArray("tools").size());
    var response = call("read");
    assertEquals(200, response.statusCode());
    var result = JsonParser.parseString(response.body()).getAsJsonObject();
    assertFalse(result.get("isError").getAsBoolean());
    assertEquals("Forest", result.getAsJsonObject("structuredContent").get("name").getAsString());
    assertEquals(1, calls.get());
  }

  @Test
  void rejectsBadCredentialsAndBrowserRequestsBeforeDispatch() throws Exception {
    var bad =
        send(
            request("/mcp-bridge", "{\"method\":\"tools/list\"}")
                .setHeader("Authorization", "Bearer wrong"));
    assertEquals(401, bad.statusCode());
    var origin =
        send(
            request("/mcp-bridge", "{\"method\":\"tools/list\"}")
                .header("Origin", "https://example.com"));
    assertEquals(403, origin.statusCode());
    assertEquals(0, calls.get());
    assertFalse(bad.body().contains(TOKEN));
  }

  @Test
  void rejectsMalformedAndUnsupportedRequests() throws Exception {
    assertEquals(400, send(request("/mcp-bridge", "[]")).statusCode());
    assertEquals(400, send(request("/mcp-bridge", "{broken")).statusCode());
    assertEquals(400, send(request("/mcp-bridge", "{'method':'tools/list'}")).statusCode());
    assertEquals(
        400, send(request("/mcp-bridge", "{/* comment */\"method\":\"tools/list\"}")).statusCode());
    assertEquals(
        400,
        send(request("/mcp-bridge", "{\"method\":\"tools/list\",\"method\":\"tools/list\"}"))
            .statusCode());
    assertEquals(
        400,
        send(request(
                "/mcp-bridge",
                "{\"method\":\"tools/call\",\"params\":{\"name\":\"read\",\"arguments\":{\"x\":1,\"x\":2}}}"))
            .statusCode());
    assertEquals(
        400,
        send(request(
                "/mcp-bridge",
                "{\"method\":\"tools/call\",\"params\":{\"name\":\"read\",\"arguments\":[]}}"))
            .statusCode());
    assertEquals(400, send(request("/mcp-bridge", "{\"method\":\"macro/evaluate\"}")).statusCode());
    assertEquals(
        415,
        send(request("/mcp-bridge", "{}").setHeader("Content-Type", "text/plain")).statusCode());
    assertEquals(404, send(request("/mcp-bridge/other", "{}")).statusCode());
    assertEquals(405, send(request("/mcp-bridge", "{}").GET()).statusCode());
    assertEquals(0, calls.get());
  }

  @Test
  void rejectsOversizedRequestsBeforeReadingTheirBody() throws Exception {
    // Send headers first: the bridge must reject without waiting for or draining the body.
    // An eager client still uploading after rejection may instead observe a TCP reset.
    try (Socket socket = new Socket("127.0.0.1", server.port())) {
      socket.setSoTimeout(3000);
      String headers =
          "POST /mcp-bridge HTTP/1.1\r\nHost: 127.0.0.1:"
              + server.port()
              + "\r\nAuthorization: Bearer "
              + TOKEN
              + "\r\nContent-Type: application/json\r\nContent-Length: "
              + (McpBridgeServer.MAX_REQUEST_BYTES + 1)
              + "\r\n\r\n";
      socket.getOutputStream().write(headers.getBytes(StandardCharsets.US_ASCII));
      socket.getOutputStream().flush();
      String response = new String(socket.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
      assertTrue(response.startsWith("HTTP/1.1 413"));
      assertTrue(response.contains("TOO_LARGE"));
    }
    assertEquals(0, calls.get());
    assertEquals(200, call("read").statusCode());
  }

  @Test
  void timedOutQueuedActionNeverExecutesLater() throws Exception {
    CountDownLatch entered = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    SwingUtilities.invokeLater(
        () -> {
          entered.countDown();
          try {
            release.await(15, TimeUnit.SECONDS);
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          }
        });
    assertTrue(entered.await(3, TimeUnit.SECONDS));
    try {
      var response = call("read");
      assertEquals(504, response.statusCode());
      assertTrue(response.body().contains("before retrying"));
    } finally {
      release.countDown();
      SwingUtilities.invokeAndWait(() -> {});
    }
    assertEquals(0, calls.get());
  }

  @Test
  void rejectsInvalidUtf8AndClosesIncompleteBodies() throws Exception {
    var malformed =
        request("/mcp-bridge", "{}")
            .POST(HttpRequest.BodyPublishers.ofByteArray(new byte[] {(byte) 0xc3, 0x28}));
    assertEquals(400, send(malformed).statusCode());
    try (Socket socket = new Socket("127.0.0.1", server.port())) {
      socket.setSoTimeout(10000);
      String headers =
          "POST /mcp-bridge HTTP/1.1\r\nHost: 127.0.0.1:"
              + server.port()
              + "\r\nAuthorization: Bearer "
              + TOKEN
              + "\r\nContent-Type: application/json\r\nContent-Length: 100\r\n\r\n{";
      socket.getOutputStream().write(headers.getBytes(StandardCharsets.US_ASCII));
      socket.getOutputStream().flush();
      assertEquals(-1, socket.getInputStream().read());
    }
    assertEquals(0, calls.get());
    assertEquals(200, call("read").statusCode());
  }

  @Test
  void domainErrorsAreToolErrorsAndUnknownToolsDoNotExecute() throws Exception {
    for (String name : new String[] {"forbidden", "invalid"}) {
      var response = call(name);
      assertEquals(200, response.statusCode());
      assertTrue(
          JsonParser.parseString(response.body()).getAsJsonObject().get("isError").getAsBoolean());
    }
    var unknown = call("unknown");
    assertEquals(400, unknown.statusCode());
    assertTrue(unknown.body().contains("UNKNOWN_TOOL"));
    assertEquals(2, calls.get());
    var crash = call("crash");
    assertEquals(500, crash.statusCode());
    assertFalse(crash.body().contains("internal secret"));
  }
}
