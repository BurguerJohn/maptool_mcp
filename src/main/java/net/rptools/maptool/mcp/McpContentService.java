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

import static net.rptools.maptool.mcp.MapToolMcpService.*;

import com.google.gson.JsonObject;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import javax.imageio.ImageIO;
import javax.imageio.stream.MemoryCacheImageInputStream;
import net.rptools.maptool.client.MapTool;
import net.rptools.maptool.model.Asset;
import net.rptools.maptool.model.AssetManager;

/** Campaign creation preferences and bounded image ingestion, without a hosted AI dependency. */
public final class McpContentService {
  static final int MAX_CHUNK_BYTES = 600 * 1024;
  static final int MAX_IMAGE_BYTES = 8 * 1024 * 1024;
  static final int MAX_IMAGE_PIXELS = 8 * 1024 * 1024;
  static final int MAX_IMAGE_DIMENSION = 4096;
  private static final Set<String> CATEGORIES = Set.of("npc", "scenery", "misc");
  private static final Set<String> OPTION_NAMES = Set.of("imagegen", "npc", "scenery", "misc");
  private static final Map<String, Upload> UPLOADS = new LinkedHashMap<>();
  private static final long UPLOAD_LIFETIME = TimeUnit.MINUTES.toNanos(5);
  private static Object uploadCampaign;

  private McpContentService() {}

  public static boolean handles(String name) {
    return switch (name) {
      case "maptool_get_content_options",
              "maptool_set_content_options",
              "maptool_prepare_image",
              "maptool_import_image",
              "maptool_image_upload",
              "maptool_create_npc",
              "maptool_create_scenery",
              "maptool_create_misc" ->
          true;
      default -> false;
    };
  }

  public static JsonObject callTool(String name, JsonObject args) {
    return switch (name) {
      case "maptool_get_content_options" -> options();
      case "maptool_set_content_options" -> setOptions(args);
      case "maptool_prepare_image" -> prepareImage(args);
      case "maptool_import_image" -> importImage(args);
      case "maptool_image_upload" -> imageUpload(args);
      case "maptool_create_npc" -> createContent("npc", args);
      case "maptool_create_scenery" -> createContent("scenery", args);
      case "maptool_create_misc" -> createContent("misc", args);
      default -> throw new IllegalArgumentException("Unknown content tool");
    };
  }

  /** A detached, public view: no campaign registry details are exposed to players. */
  public static JsonObject options() {
    JsonObject root = McpCampaignStore.read();
    JsonObject saved = root.has("settings") ? root.getAsJsonObject("settings") : new JsonObject();
    JsonObject result = new JsonObject();
    for (String key : new String[] {"imagegen", "npc", "scenery", "misc"}) {
      boolean enabled = !key.equals("imagegen");
      if (saved.has(key)
          && saved.get(key).isJsonPrimitive()
          && saved.getAsJsonPrimitive(key).isBoolean()) enabled = saved.get(key).getAsBoolean();
      result.addProperty(key, enabled);
    }
    return result;
  }

  public static JsonObject setOptions(JsonObject changes) {
    McpCampaignStore.requireGM();
    for (var entry : changes.entrySet()) {
      if (!OPTION_NAMES.contains(entry.getKey())
          || !entry.getValue().isJsonPrimitive()
          || !entry.getValue().getAsJsonPrimitive().isBoolean()) {
        throw new IllegalArgumentException(
            "Options must be imagegen, npc, scenery or misc booleans");
      }
    }
    JsonObject root = McpCampaignStore.read();
    JsonObject settings =
        root.has("settings") ? root.getAsJsonObject("settings") : new JsonObject();
    changes.entrySet().forEach(e -> settings.add(e.getKey(), e.getValue().deepCopy()));
    root.add("settings", settings);
    McpCampaignStore.write(root);
    return options();
  }

  public static void requireEnabled(String category) {
    if (!OPTION_NAMES.contains(category))
      throw new IllegalArgumentException("Unknown content option");
    if (!options().get(category).getAsBoolean()) {
      throw new SecurityException("MCP creation option is disabled by the GM: " + category);
    }
  }

  private static void requireImageOptions(String category, String source) {
    McpCampaignStore.requireGM();
    if (!CATEGORIES.contains(category))
      throw new IllegalArgumentException("Unknown image category");
    if (!Set.of("generated", "existing").contains(source))
      throw new IllegalArgumentException("Image source must be generated or existing");
    requireEnabled(category);
    if (source.equals("generated")) requireEnabled("imagegen");
  }

  private static JsonObject prepareImage(JsonObject args) {
    requireImageOptions(args.get("category").getAsString(), "generated");
    JsonObject result = args.deepCopy();
    result.addProperty("status", "generation_required");
    result.addProperty("skill", "maptool-imagegen");
    result.addProperty(
        "workflow",
        "Use the ImageGen capability available in Codex, then import its PNG/JPEG output with"
            + " maptool_import_image or maptool_image_upload. This call does not generate an image"
            + " or contact a provider. If ImageGen is unavailable, report that limitation and use"
            + " an existing asset or a colored marker only with the user's agreement.");
    result.addProperty("maxImageBytes", MAX_IMAGE_BYTES);
    result.addProperty("maxDimension", MAX_IMAGE_DIMENSION);
    result.addProperty("maxPixels", MAX_IMAGE_PIXELS);
    return result;
  }

  private static JsonObject importImage(JsonObject args) {
    String category = args.get("category").getAsString();
    String source = args.get("source").getAsString();
    requireImageOptions(category, source);
    return publishImage(
        args.get("name").getAsString(),
        category,
        source,
        decodeChunk(args.get("dataBase64").getAsString()));
  }

  private static JsonObject imageUpload(JsonObject args) {
    McpCampaignStore.requireGM();
    expireUploads();
    String action = args.get("action").getAsString();
    if (action.equals("begin")) {
      exactFields(args, "action", "name", "category", "source", "totalBytes");
      String category = args.get("category").getAsString();
      String source = args.get("source").getAsString();
      requireImageOptions(category, source);
      int total = args.get("totalBytes").getAsInt();
      if (total < 1 || total > MAX_IMAGE_BYTES)
        throw new IllegalArgumentException("An image upload must contain 1 through 8388608 bytes");
      if (UPLOADS.values().stream().filter(upload -> upload.result == null).count() >= 2)
        throw new IllegalStateException(
            "Finish or cancel a pending image upload first (maximum 2)");
      // Receipts make finish/status safe after a lost response without retaining decoded image
      // data.
      if (UPLOADS.size() >= 18) {
        String completed =
            UPLOADS.entrySet().stream()
                .filter(entry -> entry.getValue().result != null)
                .map(Map.Entry::getKey)
                .findFirst()
                .orElseThrow();
        UPLOADS.remove(completed);
      }
      String uploadId = UUID.randomUUID().toString().replace("-", "");
      Upload upload =
          new Upload(
              args.get("name").getAsString(),
              category,
              source,
              total,
              MapTool.getPlayer().getName());
      UPLOADS.put(uploadId, upload);
      return uploadStatus(uploadId, upload);
    }
    if (action.equals("append")) exactFields(args, "action", "uploadId", "index", "dataBase64");
    else exactFields(args, "action", "uploadId");
    String uploadId = args.get("uploadId").getAsString();
    Upload upload = UPLOADS.get(uploadId);
    if (upload == null || !upload.owner.equals(MapTool.getPlayer().getName()))
      throw new IllegalArgumentException(
          "Image upload is unavailable, expired or belongs to another session");
    return switch (action) {
      case "status" -> uploadStatus(uploadId, upload);
      case "cancel" -> {
        UPLOADS.remove(uploadId);
        JsonObject result = new JsonObject();
        result.addProperty("uploadId", uploadId);
        result.addProperty("status", "cancelled");
        yield result;
      }
      case "append" -> {
        if (upload.result != null)
          throw new IllegalArgumentException("Image upload is already complete");
        if (args.get("index").getAsInt() != upload.nextIndex)
          throw new IllegalArgumentException(
              "Unexpected chunk index; query upload status before continuing");
        byte[] chunk = decodeChunk(args.get("dataBase64").getAsString());
        if (upload.data.size() + chunk.length > upload.totalBytes)
          throw new IllegalArgumentException("Image chunks exceed the declared totalBytes");
        upload.data.writeBytes(chunk);
        upload.nextIndex++;
        yield uploadStatus(uploadId, upload);
      }
      case "finish" -> {
        requireImageOptions(upload.category, upload.source);
        if (upload.result == null) {
          if (upload.data.size() != upload.totalBytes)
            throw new IllegalArgumentException(
                "Upload is incomplete; query upload status before continuing");
          upload.result =
              publishImage(upload.name, upload.category, upload.source, upload.data.toByteArray());
          upload.data = null;
        }
        yield upload.result.deepCopy();
      }
      default -> throw new IllegalArgumentException("Unknown upload action");
    };
  }

  private static void expireUploads() {
    Object campaign = MapTool.getCampaign();
    if (campaign != uploadCampaign) {
      UPLOADS.clear();
      uploadCampaign = campaign;
    }
    long now = System.nanoTime();
    UPLOADS.values().removeIf(upload -> now - upload.created > UPLOAD_LIFETIME);
  }

  private static void exactFields(JsonObject args, String... names) {
    Set<String> required = Set.of(names);
    if (!args.keySet().equals(required))
      throw new IllegalArgumentException(
          "This upload action requires exactly: " + String.join(", ", names));
  }

  private static JsonObject uploadStatus(String id, Upload upload) {
    JsonObject result = new JsonObject();
    result.addProperty("uploadId", id);
    result.addProperty("status", upload.result == null ? "pending" : "complete");
    result.addProperty("totalBytes", upload.totalBytes);
    result.addProperty(
        "receivedBytes", upload.data == null ? upload.totalBytes : upload.data.size());
    result.addProperty("nextIndex", upload.nextIndex);
    result.addProperty(
        "expiresInSeconds",
        Math.max(
            0,
            TimeUnit.NANOSECONDS.toSeconds(
                UPLOAD_LIFETIME - (System.nanoTime() - upload.created))));
    if (upload.result != null) result.add("image", upload.result.deepCopy());
    return result;
  }

  static byte[] decodeChunk(String base64) {
    if (base64.length() > (MAX_CHUNK_BYTES / 3) * 4)
      throw new IllegalArgumentException("Image chunk exceeds 600 KiB");
    byte[] bytes;
    try {
      bytes = Base64.getDecoder().decode(base64);
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException("Image data must be plain base64 without a data URL", e);
    }
    if (bytes.length == 0 || bytes.length > MAX_CHUNK_BYTES)
      throw new IllegalArgumentException("Image chunk must contain 1 through 614400 bytes");
    return bytes;
  }

  private static JsonObject publishImage(
      String name, String category, String source, byte[] bytes) {
    BufferedImage image = decodeImage(bytes);
    try {
      Asset asset = Asset.createImageAsset(name, image);
      AssetManager.putAsset(asset);
      MapTool.serverCommand().putAsset(asset);
      JsonObject result = new JsonObject();
      result.addProperty("imageAssetId", asset.getMD5Key().toString());
      result.addProperty("width", image.getWidth());
      result.addProperty("height", image.getHeight());
      result.addProperty("category", category);
      result.addProperty("source", source);
      return result;
    } finally {
      image.flush();
    }
  }

  /** Inspect headers before allocating pixels; ImageIO never uses a temporary filesystem cache. */
  static BufferedImage decodeImage(byte[] bytes) {
    if (bytes.length == 0 || bytes.length > MAX_IMAGE_BYTES)
      throw new IllegalArgumentException("Image exceeds the 8 MiB limit");
    try (var input = new MemoryCacheImageInputStream(new ByteArrayInputStream(bytes))) {
      var readers = ImageIO.getImageReaders(input);
      if (!readers.hasNext())
        throw new IllegalArgumentException("Image must be a valid PNG or JPEG");
      var reader = readers.next();
      try {
        String format = reader.getFormatName();
        if (!format.equalsIgnoreCase("png") && !format.equalsIgnoreCase("jpeg"))
          throw new IllegalArgumentException("Only PNG and JPEG images are accepted");
        reader.setInput(input, true, true);
        int width = reader.getWidth(0);
        int height = reader.getHeight(0);
        if (width < 1
            || height < 1
            || width > MAX_IMAGE_DIMENSION
            || height > MAX_IMAGE_DIMENSION
            || (long) width * height > MAX_IMAGE_PIXELS)
          throw new IllegalArgumentException(
              "Image dimensions exceed 4096 per side or 8388608 pixels");
        BufferedImage image = reader.read(0);
        if (image == null) throw new IllegalArgumentException("Image pixels could not be decoded");
        return image;
      } finally {
        reader.dispose();
      }
    } catch (IOException e) {
      throw new IllegalArgumentException("Image must be a complete, valid PNG or JPEG", e);
    }
  }

  private static JsonObject createContent(String category, JsonObject args) {
    McpCampaignStore.requireGM();
    requireEnabled(category);
    JsonObject request = args.deepCopy();
    request.addProperty("type", "NPC");
    request.addProperty(
        "layer",
        category.equals("npc") ? "TOKEN" : category.equals("scenery") ? "BACKGROUND" : "OBJECT");
    if (category.equals("npc")) {
      int hp = args.has("hp") ? args.get("hp").getAsInt() : -1;
      int maxHp = args.has("maxHp") ? args.get("maxHp").getAsInt() : -1;
      if (hp >= 0 && maxHp >= 0 && hp > maxHp)
        throw new IllegalArgumentException("hp cannot exceed maxHp");
      JsonObject properties =
          request.has("properties") ? request.getAsJsonObject("properties") : new JsonObject();
      if (request.has("role")) properties.add("Role", request.remove("role"));
      if (request.has("hp")) properties.add("HP", request.remove("hp"));
      if (request.has("maxHp")) properties.add("MaxHP", request.remove("maxHp"));
      if (!properties.isEmpty()) request.add("properties", properties);
    }
    JsonObject result = new MapToolMcpService().callTool("maptool_create_token", request);
    result.addProperty("category", category);
    return result;
  }

  public static void registerTools(Map<String, JsonObject> tools) {
    addTool(
        tools,
        "maptool_get_content_options",
        "Read campaign checkboxes for imagegen, npc, scenery and misc creation.",
        true,
        true,
        fields(),
        "");
    addTool(
        tools,
        "maptool_set_content_options",
        "GM: update campaign creation checkboxes. Disabled options are enforced by the server.",
        false,
        true,
        fields(
            "imagegen",
            booleanSchema(),
            "npc",
            booleanSchema(),
            "scenery",
            booleanSchema(),
            "misc",
            booleanSchema()),
        "");
    addTool(
        tools,
        "maptool_prepare_image",
        "GM: prepare an enabled ImageGen task for Codex. Does not invoke a provider or generate"
            + " art; use the available agent image tool then import the actual result.",
        true,
        true,
        fields("category", categorySchema(), "name", text(128), "prompt", text(4000)),
        "category,name,prompt");
    addTool(
        tools,
        "maptool_import_image",
        "GM: import a complete PNG/JPEG up to 600 KiB from base64. Generated source requires the"
            + " ImageGen checkbox; category must be enabled. Returns an imageAssetId for token"
            + " creation. Use image_upload for larger files.",
        false,
        true,
        fields(
            "category",
            categorySchema(),
            "name",
            text(128),
            "source",
            choice("generated", "existing"),
            "dataBase64",
            text(819200)),
        "category,name,source,dataBase64");
    addTool(
        tools,
        "maptool_image_upload",
        "GM: bounded PNG/JPEG upload up to 8 MiB. begin requires name/category/source/totalBytes;"
            + " append requires uploadId/index/dataBase64 (600 KiB chunks, ordered index starting"
            + " 0); status, finish and cancel require uploadId. Max 2 pending uploads, expiry 5"
            + " minutes, tied to the current campaign and GM. Query status after uncertain"
            + " outcomes; complete status includes the asset receipt.",
        false,
        false,
        fields(
            "action",
            choice("begin", "append", "status", "finish", "cancel"),
            "uploadId",
            id(),
            "name",
            text(128),
            "category",
            categorySchema(),
            "source",
            choice("generated", "existing"),
            "totalBytes",
            numeric("integer", 1, MAX_IMAGE_BYTES),
            "index",
            numeric("integer", 0, 100000),
            "dataBase64",
            text(819200)),
        "action");
    JsonObject common =
        fields(
            "mapId",
            id(),
            "name",
            text(128),
            "x",
            coordinate(),
            "y",
            coordinate(),
            "width",
            numeric("integer", 1, 4096),
            "height",
            numeric("integer", 1, 4096),
            "visible",
            booleanSchema(),
            "imageAssetId",
            id(),
            "color",
            colorSchema());
    JsonObject npc = common.deepCopy();
    npc.add("role", text(512));
    npc.add("hp", numeric("integer", 0, 1000000));
    npc.add("maxHp", numeric("integer", 1, 1000000));
    npc.add("properties", dictionary(scalarSchema()));
    addTool(
        tools,
        "maptool_create_npc",
        "GM: create a real NPC token on the TOKEN layer when the NPC checkbox is enabled. Optional"
            + " role, HP and MaxHP are campaign properties; no game-rule automation or invented"
            + " statistics.",
        false,
        false,
        npc,
        "mapId,name,x,y");
    addTool(
        tools,
        "maptool_create_scenery",
        "GM: create a scene graphic on the BACKGROUND layer when the scenery checkbox is enabled."
            + " Use imageAssetId from image import or an existing asset; width/height are map"
            + " pixels. Without art creates a colored marker.",
        false,
        false,
        common.deepCopy(),
        "mapId,name,x,y");
    addTool(
        tools,
        "maptool_create_misc",
        "GM: create a prop or miscellaneous object on the OBJECT layer when the misc checkbox is"
            + " enabled. Use imageAssetId or a colored marker, with optional width/height in map"
            + " pixels.",
        false,
        false,
        common.deepCopy(),
        "mapId,name,x,y");
  }

  private static JsonObject categorySchema() {
    return choice("npc", "scenery", "misc");
  }

  private static final class Upload {
    final String name;
    final String category;
    final String source;
    final int totalBytes;
    final String owner;
    final long created = System.nanoTime();
    ByteArrayOutputStream data;
    int nextIndex;
    JsonObject result;

    Upload(String name, String category, String source, int totalBytes, String owner) {
      this.name = name;
      this.category = category;
      this.source = source;
      this.totalBytes = totalBytes;
      this.owner = owner;
      data = new ByteArrayOutputStream(Math.min(totalBytes, MAX_CHUNK_BYTES));
    }
  }
}
