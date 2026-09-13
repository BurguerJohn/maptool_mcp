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

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonPrimitive;
import com.google.gson.Strictness;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.StringReader;
import java.math.BigDecimal;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.FutureTask;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import javax.swing.SwingUtilities;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/** Local authenticated adapter used by the stdio MCP process, not a public MCP HTTP endpoint. */
public final class McpBridgeServer implements AutoCloseable {
  private static final Logger log = LogManager.getLogger(McpBridgeServer.class);
  private static final Gson GSON = new Gson();
  static final int MAX_REQUEST_BYTES = 1024 * 1024;
  private final HttpServer server;
  private final ThreadPoolExecutor executor;
  private final ScheduledExecutorService deadlines;
  private final byte[] authorization;
  private final ToolHandler handler;
  private boolean followerControllerStarted;

  private static final class UnknownToolException extends RuntimeException {}

  interface ToolHandler {
    JsonArray listTools();

    JsonObject callTool(String name, JsonObject arguments);
  }

  McpBridgeServer(McpConfig config, ToolHandler handler) throws IOException {
    this.handler = handler;
    authorization = ("Bearer " + config.token()).getBytes(StandardCharsets.US_ASCII);
    // The JDK HTTP server otherwise drains an unread request when rejecting it, which can block
    // a worker on a client that never finishes uploading. Configure before the provider starts.
    System.getProperties().putIfAbsent("sun.net.httpserver.drainAmount", "0");
    System.getProperties().putIfAbsent("sun.net.httpserver.maxReqTime", "5");
    server =
        HttpServer.create(
            new InetSocketAddress(
                InetAddress.getByAddress(new byte[] {127, 0, 0, 1}), config.port()),
            16);
    executor =
        new ThreadPoolExecutor(
            2,
            2,
            0,
            TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(16),
            runnable -> {
              Thread thread = new Thread(runnable, "maptool-mcp-bridge");
              thread.setDaemon(true);
              return thread;
            },
            new ThreadPoolExecutor.AbortPolicy());
    server.setExecutor(executor);
    deadlines =
        Executors.newSingleThreadScheduledExecutor(
            runnable -> {
              Thread thread = new Thread(runnable, "maptool-mcp-request-deadline");
              thread.setDaemon(true);
              return thread;
            });
    server.createContext("/mcp-bridge", this::handle);
  }

  public static void startFromEnvironment() {
    try {
      var config = McpConfig.fromEnvironment(System.getenv());
      if (config.isEmpty()) {
        return;
      }
      var service = new MapToolMcpService();
      var bridge =
          new McpBridgeServer(
              config.get(),
              new ToolHandler() {
                @Override
                public JsonArray listTools() {
                  return service.listTools();
                }

                @Override
                public JsonObject callTool(String name, JsonObject arguments) {
                  return service.callTool(name, arguments);
                }
              });
      bridge.start();
      McpFollowerController.start();
      bridge.followerControllerStarted = true;
      Runtime.getRuntime().addShutdownHook(new Thread(bridge::close, "maptool-mcp-shutdown"));
      log.info("MapTool MCP bridge listening on 127.0.0.1:{}", bridge.port());
    } catch (IllegalArgumentException e) {
      log.error("MapTool MCP bridge configuration rejected: {}", e.getMessage());
    } catch (IOException e) {
      log.error("MapTool MCP bridge could not bind its local port; check MAPTOOL_MCP_PORT");
    }
  }

  void start() {
    server.start();
  }

  int port() {
    return server.getAddress().getPort();
  }

  @Override
  public void close() {
    if (followerControllerStarted) McpFollowerController.stop();
    server.stop(0);
    executor.shutdownNow();
    deadlines.shutdownNow();
  }

  private void handle(HttpExchange exchange) throws IOException {
    try (exchange) {
      // No browser origins or hostnames: prevent browser CSRF and DNS rebinding.
      String host = exchange.getRequestHeaders().getFirst("Host");
      if (!exchange.getRemoteAddress().getAddress().isLoopbackAddress()
          || exchange.getRequestHeaders().containsKey("Origin")
          || !("127.0.0.1:" + port()).equals(host)) {
        rejectBeforeBody(
            exchange, 403, "FORBIDDEN", "Only the local MCP process may access this bridge");
        return;
      }
      String supplied = exchange.getRequestHeaders().getFirst("Authorization");
      if (supplied == null
          || !MessageDigest.isEqual(authorization, supplied.getBytes(StandardCharsets.UTF_8))) {
        rejectBeforeBody(
            exchange, 401, "UNAUTHORIZED", "Check MAPTOOL_MCP_TOKEN in both processes");
        return;
      }
      if (!"/mcp-bridge".equals(exchange.getRequestURI().getPath())
          || exchange.getRequestURI().getRawQuery() != null) {
        rejectBeforeBody(exchange, 404, "NOT_FOUND", "Unknown bridge endpoint");
        return;
      }
      if (!"POST".equals(exchange.getRequestMethod())) {
        exchange.getResponseHeaders().set("Allow", "POST");
        rejectBeforeBody(exchange, 405, "METHOD_NOT_ALLOWED", "Use POST with application/json");
        return;
      }
      String contentType = exchange.getRequestHeaders().getFirst("Content-Type");
      if (contentType == null
          || !"application/json"
              .equals(contentType.split(";", 2)[0].strip().toLowerCase(Locale.ROOT))) {
        rejectBeforeBody(exchange, 415, "INVALID_REQUEST", "Content-Type must be application/json");
        return;
      }
      // The stdio adapter always sends a fixed length. Reject streaming bodies before opening
      // their input stream, so oversized or unfinished requests cannot hold a worker while
      // draining.
      String contentLength = exchange.getRequestHeaders().getFirst("Content-Length");
      long declaredLength;
      try {
        if (exchange.getRequestHeaders().containsKey("Transfer-Encoding")
            || contentLength == null
            || !contentLength.matches("[0-9]{1,10}")) {
          throw new IllegalArgumentException();
        }
        declaredLength = Long.parseLong(contentLength);
      } catch (IllegalArgumentException e) {
        rejectBeforeBody(exchange, 411, "INVALID_REQUEST", "A fixed Content-Length is required");
        return;
      }
      if (declaredLength > MAX_REQUEST_BYTES) {
        rejectBeforeBody(exchange, 413, "TOO_LARGE", "Request exceeds one MiB");
        return;
      }
      // Bound incomplete request bodies as well as waiting for the Swing thread.
      var bodyDeadline = deadlines.schedule(exchange::close, 5, TimeUnit.SECONDS);
      byte[] bytes;
      try {
        bytes = exchange.getRequestBody().readNBytes(MAX_REQUEST_BYTES + 1);
      } finally {
        bodyDeadline.cancel(false);
      }
      if (bytes.length > MAX_REQUEST_BYTES) {
        sendError(exchange, 413, "TOO_LARGE", "Request exceeds one MiB");
        return;
      }
      JsonObject request;
      try {
        var parsed = parseStrict(bytes);
        if (!parsed.isJsonObject()) {
          throw new IllegalArgumentException();
        }
        request = parsed.getAsJsonObject();
        if (!Set.of("method", "params").containsAll(request.keySet())
            || !isString(request, "method")) {
          throw new IllegalArgumentException();
        }
      } catch (JsonParseException | IllegalArgumentException | IOException e) {
        sendError(
            exchange, 400, "INVALID_REQUEST", "Expected a JSON object with method and params");
        return;
      }
      String method = request.get("method").getAsString();
      Callable<JsonObject> operation;
      if ("tools/list".equals(method)) {
        operation =
            () -> {
              JsonObject result = new JsonObject();
              result.add("tools", handler.listTools());
              return result;
            };
      } else if ("tools/call".equals(method)) {
        if (!request.has("params") || !request.get("params").isJsonObject()) {
          sendError(exchange, 400, "INVALID_REQUEST", "tools/call requires params");
          return;
        }
        JsonObject params = request.getAsJsonObject("params");
        if (!Set.of("name", "arguments").containsAll(params.keySet())
            || !isString(params, "name")
            || params.get("name").getAsString().length() > 128
            || (params.has("arguments") && !params.get("arguments").isJsonObject())) {
          sendError(exchange, 400, "INVALID_REQUEST", "Expected name and object arguments");
          return;
        }
        String name = params.get("name").getAsString();
        JsonObject arguments =
            params.has("arguments") ? params.getAsJsonObject("arguments") : new JsonObject();
        operation =
            () -> {
              boolean known = false;
              for (var tool : handler.listTools()) {
                if (tool.getAsJsonObject().get("name").getAsString().equals(name)) {
                  known = true;
                  break;
                }
              }
              if (!known) {
                throw new UnknownToolException();
              }
              try {
                return toolResult(handler.callTool(name, arguments), false);
              } catch (IllegalArgumentException | SecurityException e) {
                JsonObject failure = new JsonObject();
                failure.addProperty(
                    "error", e instanceof SecurityException ? "FORBIDDEN" : "INVALID_ARGUMENT");
                failure.addProperty("message", e.getMessage());
                return toolResult(failure, true);
              }
            };
      } else {
        sendError(
            exchange, 400, "METHOD_NOT_FOUND", "Only tools/list and tools/call are supported");
        return;
      }

      // Every model read and write runs on the UI thread. A timed-out queued task is cancelled.
      FutureTask<JsonObject> task = new FutureTask<>(operation);
      SwingUtilities.invokeLater(task);
      try {
        send(exchange, 200, task.get(8, TimeUnit.SECONDS));
      } catch (TimeoutException e) {
        task.cancel(false);
        sendError(
            exchange,
            504,
            "TIMEOUT",
            "MapTool did not respond; inspect the current map before retrying an action");
      } catch (InterruptedException e) {
        task.cancel(false);
        Thread.currentThread().interrupt();
        sendError(exchange, 503, "UNAVAILABLE", "MapTool is shutting down");
      } catch (ExecutionException e) {
        if (e.getCause() instanceof UnknownToolException) {
          sendError(
              exchange,
              400,
              "UNKNOWN_TOOL",
              "Unknown tool; call tools/list to discover available tools");
          return;
        }
        // Do not disclose macro values, credentials, or internal model details.
        sendError(
            exchange,
            500,
            "INTERNAL_ERROR",
            "MapTool could not complete the operation; inspect the map before retrying");
      }
    }
  }

  private static boolean isString(JsonObject object, String key) {
    return object.has(key)
        && object.get(key).isJsonPrimitive()
        && object.getAsJsonPrimitive(key).isString();
  }

  /** JSON transport must not reinterpret duplicate fields, malformed UTF-8 or lenient syntax. */
  private static JsonElement parseStrict(byte[] bytes) throws IOException {
    String text =
        StandardCharsets.UTF_8
            .newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString();
    try (JsonReader reader = new JsonReader(new StringReader(text))) {
      reader.setStrictness(Strictness.STRICT);
      JsonElement value = readJson(reader, 0);
      if (reader.peek() != JsonToken.END_DOCUMENT) throw new IllegalArgumentException();
      return value;
    }
  }

  private static JsonElement readJson(JsonReader reader, int depth) throws IOException {
    if (depth > 32) throw new IllegalArgumentException("JSON nesting exceeds the allowed limit");
    switch (reader.peek()) {
      case BEGIN_OBJECT -> {
        JsonObject object = new JsonObject();
        reader.beginObject();
        while (reader.hasNext()) {
          String key = reader.nextName();
          if (object.has(key)) throw new IllegalArgumentException("Duplicate JSON field");
          object.add(key, readJson(reader, depth + 1));
        }
        reader.endObject();
        return object;
      }
      case BEGIN_ARRAY -> {
        JsonArray array = new JsonArray();
        reader.beginArray();
        while (reader.hasNext()) array.add(readJson(reader, depth + 1));
        reader.endArray();
        return array;
      }
      case STRING -> {
        return new JsonPrimitive(reader.nextString());
      }
      case NUMBER -> {
        return new JsonPrimitive(new BigDecimal(reader.nextString()));
      }
      case BOOLEAN -> {
        return new JsonPrimitive(reader.nextBoolean());
      }
      case NULL -> {
        reader.nextNull();
        return JsonNull.INSTANCE;
      }
      default -> throw new IllegalArgumentException("Unexpected JSON token");
    }
  }

  private static JsonObject toolResult(JsonObject data, boolean error) {
    JsonObject text = new JsonObject();
    text.addProperty("type", "text");
    text.addProperty("text", GSON.toJson(data));
    JsonArray content = new JsonArray();
    content.add(text);
    JsonObject result = new JsonObject();
    result.add("content", content);
    result.add("structuredContent", data);
    result.addProperty("isError", error);
    return result;
  }

  private static void rejectBeforeBody(
      HttpExchange exchange, int status, String code, String message) throws IOException {
    // An early response must not drain a body which the client may never finish sending.
    // With draining disabled above, a bodyless response completes the exchange immediately.
    exchange.getResponseHeaders().set("Connection", "close");
    exchange.getResponseHeaders().set("Cache-Control", "no-store");
    exchange.getResponseHeaders().set("X-MapTool-Error", code);
    exchange.sendResponseHeaders(status, -1);
  }

  private static void sendError(HttpExchange exchange, int status, String code, String message)
      throws IOException {
    JsonObject error = new JsonObject();
    error.addProperty("code", code);
    error.addProperty("message", message);
    JsonObject result = new JsonObject();
    result.add("error", error);
    send(exchange, status, result);
  }

  private static void send(HttpExchange exchange, int status, JsonObject result)
      throws IOException {
    byte[] body = GSON.toJson(result).getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
    exchange.getResponseHeaders().set("Cache-Control", "no-store");
    exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
    exchange.sendResponseHeaders(status, body.length);
    exchange.getResponseBody().write(body);
    // Flush before closing an exchange whose request body was deliberately never opened.
    exchange.getResponseBody().flush();
  }
}
