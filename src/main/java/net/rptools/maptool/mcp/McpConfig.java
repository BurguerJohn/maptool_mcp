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

import java.util.Map;
import java.util.Optional;

/** Explicit opt-in configuration. The bearer credential is never included in diagnostics. */
public final class McpConfig {
  private final int port;
  private final String token;

  McpConfig(int port, String token) {
    if (port < 0 || port > 65535) {
      throw new IllegalArgumentException("MAPTOOL_MCP_PORT must be between 1 and 65535");
    }
    if (token == null
        || token.length() < 32
        || token.length() > 512
        || !token.matches("[A-Za-z0-9._~+/\\-]+=*")) {
      throw new IllegalArgumentException(
          "MAPTOOL_MCP_TOKEN must contain 32 to 512 bearer-token characters; use a random hex"
              + " value");
    }
    this.port = port;
    this.token = token;
  }

  public static Optional<McpConfig> fromEnvironment(Map<String, String> environment) {
    if (!"true".equalsIgnoreCase(environment.get("MAPTOOL_MCP_ENABLED"))) {
      return Optional.empty();
    }
    int port;
    try {
      port = Integer.parseInt(environment.getOrDefault("MAPTOOL_MCP_PORT", "27182"));
    } catch (NumberFormatException e) {
      throw new IllegalArgumentException("MAPTOOL_MCP_PORT must be between 1 and 65535");
    }
    if (port == 0) {
      throw new IllegalArgumentException("MAPTOOL_MCP_PORT must be between 1 and 65535");
    }
    return Optional.of(new McpConfig(port, environment.get("MAPTOOL_MCP_TOKEN")));
  }

  int port() {
    return port;
  }

  String token() {
    return token;
  }
}
