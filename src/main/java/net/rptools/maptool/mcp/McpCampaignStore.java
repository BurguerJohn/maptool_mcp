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

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.awt.image.BufferedImage;
import java.nio.charset.StandardCharsets;
import net.rptools.maptool.client.MapTool;
import net.rptools.maptool.model.Asset;
import net.rptools.maptool.model.AssetManager;
import net.rptools.maptool.model.Token;
import net.rptools.maptool.model.Zone;

/** Campaign-owned metadata, persisted and synchronized by MapTool's native token protocol. */
public final class McpCampaignStore {
  static final String REGISTRY = "__maptool_mcp_registry_v1";
  static final String PAYLOAD = "__maptool_mcp_campaign_json";
  static final int MAX_BYTES = 1_048_576;

  private McpCampaignStore() {}

  /** A fresh, detached document on every call; switching campaigns never reuses cached state. */
  public static JsonObject read() {
    Registry registry = find();
    if (registry == null) return new JsonObject();
    Object value = registry.token().getProperty(PAYLOAD);
    if (!(value instanceof String text)) {
      throw new IllegalStateException("MCP campaign metadata is missing or corrupt");
    }
    try {
      if (text.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) {
        throw new IllegalArgumentException("Metadata too large");
      }
      JsonObject document = JsonParser.parseString(text).getAsJsonObject();
      validate(document);
      return document;
    } catch (RuntimeException exception) {
      throw new IllegalStateException(
          "MCP campaign metadata is corrupt; restore a campaign backup", exception);
    }
  }

  public static void write(JsonObject document) {
    requireGM();
    validate(document);
    Registry registry = find();
    Zone zone;
    Token token;
    if (registry == null) {
      zone =
          MapTool.getCampaign().getZones().stream()
              .findFirst()
              .orElseThrow(
                  () -> new IllegalStateException("Create a map before saving MCP settings"));
      token = registryToken();
      token.setLayer(Zone.Layer.GM);
      token.setType(Token.Type.NPC);
      token.setVisible(false);
      token.setHasSight(false);
      token.setProperty(REGISTRY, "1");
    } else {
      zone = registry.zone();
      if (registry.token().getImageAssetId() == null) {
        // Repair registries created before image-backed serialization was required.
        token = registryToken();
        token.setId(registry.token().getId());
        token.setLayer(Zone.Layer.GM);
        token.setType(Token.Type.NPC);
        token.setVisible(false);
        token.setHasSight(false);
        token.setProperty(REGISTRY, "1");
      } else token = new Token(registry.token(), true);
    }
    token.setProperty(PAYLOAD, document.toString());
    // Native putToken both updates the local campaign and publishes to connected clients.
    MapTool.serverCommand().putToken(zone.getId(), token);
  }

  private static Token registryToken() {
    Asset asset =
        Asset.createImageAsset(
            "MCP campaign registry", new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB));
    AssetManager.putAsset(asset);
    MapTool.serverCommand().putAsset(asset);
    return new Token("MapTool MCP — Campaign Registry", asset.getMD5Key());
  }

  public static void validate(JsonObject document) {
    if (document == null
        || document.toString().getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) {
      throw new IllegalArgumentException("MCP campaign metadata exceeds the 1 MiB limit");
    }
    validateDepth(document, 0);
    if ((document.has("settings") && !document.get("settings").isJsonObject())
        || (document.has("world") && !document.get("world").isJsonObject())) {
      throw new IllegalArgumentException("MCP settings and world must be JSON objects");
    }
  }

  private static void validateDepth(JsonElement value, int depth) {
    if (depth > 24) throw new IllegalArgumentException("MCP metadata nesting is too deep");
    if (value.isJsonObject())
      value.getAsJsonObject().entrySet().forEach(e -> validateDepth(e.getValue(), depth + 1));
    else if (value.isJsonArray()) value.getAsJsonArray().forEach(e -> validateDepth(e, depth + 1));
  }

  public static void requireGM() {
    if (MapTool.getPlayer() == null || !MapTool.getPlayer().isGM()) {
      throw new SecurityException("This operation requires the connected MapTool player to be GM");
    }
  }

  private static Registry find() {
    Registry found = null;
    if (MapTool.getCampaign() == null) throw new IllegalStateException("Open a campaign first");
    for (Zone zone : MapTool.getCampaign().getZones()) {
      for (Token token : zone.getAllTokens()) {
        if (!"1".equals(token.getProperty(REGISTRY))) continue;
        if (found != null)
          throw new IllegalStateException(
              "Duplicate MCP campaign registries; restore a campaign backup");
        if (token.getLayer() != Zone.Layer.GM || token.isVisible()) {
          throw new IllegalStateException("MCP registry must remain invisible on the GM layer");
        }
        found = new Registry(zone, token);
      }
    }
    return found;
  }

  private record Registry(Zone zone, Token token) {}
}
