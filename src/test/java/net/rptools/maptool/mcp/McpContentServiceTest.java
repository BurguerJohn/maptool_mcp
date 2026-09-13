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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import javax.imageio.ImageIO;
import javax.swing.SwingUtilities;
import net.rptools.maptool.client.MapTool;
import net.rptools.maptool.client.MapToolClient;
import net.rptools.maptool.client.ui.MapToolFrame;
import net.rptools.maptool.model.Asset;
import net.rptools.maptool.model.Campaign;
import net.rptools.maptool.model.Grid;
import net.rptools.maptool.model.GridFactory;
import net.rptools.maptool.model.Token;
import net.rptools.maptool.model.Zone;
import net.rptools.maptool.model.player.LocalPlayer;
import net.rptools.maptool.server.ServerCommand;
import net.rptools.maptool.server.ServerPolicy;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class McpContentServiceTest {
  @Test
  void defaultsAndCheckboxesReflectSavedValuesWithoutExposingWorldData() throws Exception {
    withSession(
        true,
        context -> {
          context.root.set(json("{\"world\":{\"secret\":\"do not expose\"}}"));
          JsonObject defaults = context.call("maptool_get_content_options", new JsonObject());
          assertEquals(
              json("{\"imagegen\":false,\"npc\":true,\"scenery\":true,\"misc\":true}"), defaults);
          defaults.addProperty("npc", false);
          assertTrue(McpContentService.options().get("npc").getAsBoolean());
          JsonObject saved =
              context.call(
                  "maptool_set_content_options", json("{\"imagegen\":true,\"misc\":false}"));
          assertTrue(context.root.get().has("world"));
          assertTrue(saved.get("imagegen").getAsBoolean());
          assertFalse(saved.get("misc").getAsBoolean());
          var editable = McpOptionsDialog.createCheckboxes(saved, true);
          assertEquals(4, editable.size());
          assertTrue(editable.get("imagegen").isSelected());
          assertFalse(editable.get("misc").isSelected());
          assertTrue(editable.values().stream().allMatch(box -> box.isEnabled()));
          assertTrue(
              McpOptionsDialog.createCheckboxes(saved, false).values().stream()
                  .noneMatch(box -> box.isEnabled()));
        });
  }

  @Test
  void playerCanReadOptionsButCannotEnableCreateOrUpload() throws Exception {
    withSession(
        false,
        context -> {
          assertTrue(
              context
                  .call("maptool_get_content_options", new JsonObject())
                  .get("npc")
                  .getAsBoolean());
          assertThrows(
              SecurityException.class,
              () -> context.call("maptool_set_content_options", json("{\"npc\":true}")));
          assertThrows(
              SecurityException.class,
              () -> context.call("maptool_create_npc", context.creation()));
          JsonObject image = context.image("existing", png());
          assertThrows(SecurityException.class, () -> context.call("maptool_import_image", image));
          assertThrows(
              SecurityException.class,
              () -> context.call("maptool_image_upload", begin("existing", 2)));
          verifyNoInteractions(context.server);
        });
  }

  @Test
  void disabledCategoriesGateSpecializedAndBaseCreationBeforePublication() throws Exception {
    withSession(
        true,
        context -> {
          context.root.set(json("{\"settings\":{\"npc\":false,\"scenery\":false,\"misc\":false}}"));
          for (String tool :
              new String[] {"maptool_create_npc", "maptool_create_scenery", "maptool_create_misc"})
            assertThrows(SecurityException.class, () -> context.call(tool, context.creation()));
          for (String layer : new String[] {"TOKEN", "BACKGROUND", "OBJECT"}) {
            JsonObject args = context.creation();
            args.addProperty("type", "NPC");
            args.addProperty("layer", layer);
            assertThrows(SecurityException.class, () -> context.call("maptool_create_token", args));
          }
          verifyNoInteractions(context.server);
        });
  }

  @Test
  void specializedCreationPublishesNativeLayersDimensionsAndNpcProperties() throws Exception {
    withSession(
        true,
        context -> {
          JsonObject npc = context.creation();
          npc.addProperty("role", "Innkeeper");
          npc.addProperty("hp", 8);
          npc.addProperty("maxHp", 10);
          context.call("maptool_create_npc", npc);
          JsonObject scenery = context.creation();
          scenery.addProperty("width", 640);
          scenery.addProperty("height", 320);
          context.call("maptool_create_scenery", scenery);
          context.call("maptool_create_misc", context.creation());
          ArgumentCaptor<Token> tokens = ArgumentCaptor.forClass(Token.class);
          verify(context.server, times(3)).putToken(any(), tokens.capture());
          assertEquals(Token.Type.NPC, tokens.getAllValues().get(0).getType());
          assertEquals("Innkeeper", tokens.getAllValues().get(0).getProperty("Role"));
          assertEquals("8", tokens.getAllValues().get(0).getProperty("HP").toString());
          assertEquals(Zone.Layer.BACKGROUND, tokens.getAllValues().get(1).getLayer());
          assertEquals(640, tokens.getAllValues().get(1).getWidth());
          assertEquals(320, tokens.getAllValues().get(1).getHeight());
          assertFalse(tokens.getAllValues().get(1).isSnapToScale());
          assertEquals(Zone.Layer.OBJECT, tokens.getAllValues().get(2).getLayer());
        });
  }

  @Test
  void invalidNpcStatsFailBeforeAnyNativeMutation() throws Exception {
    withSession(
        true,
        context -> {
          JsonObject npc = context.creation();
          npc.addProperty("hp", 20);
          npc.addProperty("maxHp", 10);
          assertThrows(
              IllegalArgumentException.class, () -> context.call("maptool_create_npc", npc));
          verifyNoInteractions(context.server);
        });
  }

  @Test
  void imagegenRequiresCheckboxAndPrepareDoesNotGenerateOrPublish() throws Exception {
    withSession(
        true,
        context -> {
          JsonObject prepare =
              json("{\"category\":\"npc\",\"name\":\"Guard\",\"prompt\":\"A town guard\"}");
          assertThrows(
              SecurityException.class, () -> context.call("maptool_prepare_image", prepare));
          assertThrows(
              SecurityException.class,
              () -> context.call("maptool_import_image", context.image("generated", png())));
          context.root.set(json("{\"settings\":{\"imagegen\":true}}"));
          assertEquals(
              "generation_required",
              context.call("maptool_prepare_image", prepare).get("status").getAsString());
          verifyNoInteractions(context.server);
          JsonObject result =
              context.call("maptool_import_image", context.image("generated", png()));
          assertEquals(3, result.get("width").getAsInt());
          assertEquals(2, result.get("height").getAsInt());
          assertTrue(result.get("imageAssetId").getAsString().matches("[0-9a-fA-F]{32}"));
          verify(context.server).putAsset(any(Asset.class));
        });
  }

  @Test
  void imageValidationRejectsMalformedWrongFormatOversizeAndHeaderBombs() throws Exception {
    assertThrows(
        IllegalArgumentException.class,
        () -> McpContentService.decodeChunk("data:image/png;base64,AAAA"));
    assertThrows(
        IllegalArgumentException.class, () -> McpContentService.decodeChunk("A".repeat(819204)));
    assertThrows(IllegalArgumentException.class, () -> McpContentService.decodeImage(new byte[0]));
    assertThrows(
        IllegalArgumentException.class,
        () -> McpContentService.decodeImage(new byte[McpContentService.MAX_IMAGE_BYTES + 1]));
    assertThrows(
        IllegalArgumentException.class,
        () -> McpContentService.decodeImage("not an image".getBytes()));
    byte[] oversized = png();
    ByteBuffer.wrap(oversized).putInt(16, 4097);
    assertThrows(IllegalArgumentException.class, () -> McpContentService.decodeImage(oversized));
    byte[] pixelBomb = png();
    ByteBuffer.wrap(pixelBomb).putInt(16, 4096).putInt(20, 4096);
    assertThrows(IllegalArgumentException.class, () -> McpContentService.decodeImage(pixelBomb));
    ByteArrayOutputStream gif = new ByteArrayOutputStream();
    ImageIO.write(new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB), "gif", gif);
    assertThrows(
        IllegalArgumentException.class, () -> McpContentService.decodeImage(gif.toByteArray()));
    BufferedImage valid = McpContentService.decodeImage(png());
    assertEquals(3, valid.getWidth());
    valid.flush();
  }

  @Test
  void chunkUploadRejectsDuplicatesAndReturnsReceiptAfterFinish() throws Exception {
    withSession(
        true,
        context -> {
          byte[] image = png();
          String uploadId =
              context
                  .call("maptool_image_upload", begin("existing", image.length))
                  .get("uploadId")
                  .getAsString();
          JsonObject first = append(uploadId, 0, Arrays.copyOfRange(image, 0, 12));
          assertEquals(1, context.call("maptool_image_upload", first).get("nextIndex").getAsInt());
          assertThrows(
              IllegalArgumentException.class, () -> context.call("maptool_image_upload", first));
          assertThrows(
              IllegalArgumentException.class,
              () -> context.call("maptool_image_upload", action("finish", uploadId)));
          context.call(
              "maptool_image_upload",
              append(uploadId, 1, Arrays.copyOfRange(image, 12, image.length)));
          JsonObject complete = context.call("maptool_image_upload", action("finish", uploadId));
          assertEquals(complete, context.call("maptool_image_upload", action("finish", uploadId)));
          JsonObject status = context.call("maptool_image_upload", action("status", uploadId));
          assertEquals("complete", status.get("status").getAsString());
          assertEquals(complete, status.getAsJsonObject("image"));
          verify(context.server, times(1)).putAsset(any(Asset.class));
        });
  }

  @Test
  void realImageLargerThanOneBridgeRequestCanBeImportedInChunks() throws Exception {
    BufferedImage image = new BufferedImage(512, 512, BufferedImage.TYPE_INT_RGB);
    var random = new java.util.Random(42);
    for (int y = 0; y < image.getHeight(); y++)
      for (int x = 0; x < image.getWidth(); x++) image.setRGB(x, y, random.nextInt());
    ByteArrayOutputStream encoded = new ByteArrayOutputStream();
    ImageIO.write(image, "png", encoded);
    image.flush();
    byte[] bytes = encoded.toByteArray();
    assertTrue(bytes.length > McpContentService.MAX_CHUNK_BYTES);
    withSession(
        true,
        context -> {
          String id =
              context
                  .call("maptool_image_upload", begin("existing", bytes.length))
                  .get("uploadId")
                  .getAsString();
          int index = 0;
          for (int offset = 0; offset < bytes.length; offset += McpContentService.MAX_CHUNK_BYTES) {
            byte[] chunk =
                Arrays.copyOfRange(
                    bytes,
                    offset,
                    Math.min(bytes.length, offset + McpContentService.MAX_CHUNK_BYTES));
            context.call("maptool_image_upload", append(id, index++, chunk));
          }
          JsonObject result = context.call("maptool_image_upload", action("finish", id));
          assertEquals(512, result.get("width").getAsInt());
          assertEquals(512, result.get("height").getAsInt());
          verify(context.server).putAsset(any(Asset.class));
        });
  }

  @Test
  void uploadBoundsAndWrongActionFieldsFailWithoutPublishing() throws Exception {
    withSession(
        true,
        context -> {
          String first =
              context
                  .call("maptool_image_upload", begin("existing", 2))
                  .get("uploadId")
                  .getAsString();
          context.call("maptool_image_upload", begin("existing", 2));
          assertThrows(
              IllegalStateException.class,
              () -> context.call("maptool_image_upload", begin("existing", 2)));
          assertThrows(
              IllegalArgumentException.class,
              () -> context.call("maptool_image_upload", append(first, 0, new byte[3])));
          JsonObject missing = json("{\"action\":\"append\"}");
          assertThrows(
              IllegalArgumentException.class, () -> context.call("maptool_image_upload", missing));
          context.call("maptool_image_upload", append(first, 0, new byte[2]));
          assertThrows(
              IllegalArgumentException.class,
              () -> context.call("maptool_image_upload", action("finish", first)));
          context.call("maptool_image_upload", action("cancel", first));
          assertDoesNotThrow(() -> context.call("maptool_image_upload", begin("existing", 2)));
          verifyNoInteractions(context.server);
        });
  }

  @Test
  void uploadsAreBoundToCampaignAndGmIdentity() throws Exception {
    withSession(
        true,
        context -> {
          String id =
              context
                  .call("maptool_image_upload", begin("existing", 2))
                  .get("uploadId")
                  .getAsString();
          when(context.player.getName()).thenReturn("Bob");
          assertThrows(
              IllegalArgumentException.class,
              () -> context.call("maptool_image_upload", action("status", id)));
          when(context.player.getName()).thenReturn("Alice");
          when(context.player.isGM()).thenReturn(false);
          assertThrows(
              SecurityException.class,
              () -> context.call("maptool_image_upload", action("status", id)));
          when(context.player.isGM()).thenReturn(true);
          context.activeCampaign.set(new Campaign());
          assertThrows(
              IllegalArgumentException.class,
              () -> context.call("maptool_image_upload", action("status", id)));
          context.activeCampaign.set(context.campaign);
          assertThrows(
              IllegalArgumentException.class,
              () -> context.call("maptool_image_upload", action("status", id)));
          verifyNoInteractions(context.server);
        });
  }

  @Test
  void finishingUploadRechecksGenerationAndCategoryOptions() throws Exception {
    withSession(
        true,
        context -> {
          context.root.set(json("{\"settings\":{\"imagegen\":true}}"));
          byte[] image = png();
          String id =
              context
                  .call("maptool_image_upload", begin("generated", image.length))
                  .get("uploadId")
                  .getAsString();
          context.call("maptool_image_upload", append(id, 0, image));
          context.root.set(json("{\"settings\":{\"imagegen\":false}}"));
          assertThrows(
              SecurityException.class,
              () -> context.call("maptool_image_upload", action("finish", id)));
          context.root.set(json("{\"settings\":{\"imagegen\":true,\"npc\":false}}"));
          assertThrows(
              SecurityException.class,
              () -> context.call("maptool_image_upload", action("finish", id)));
          verifyNoInteractions(context.server);
        });
  }

  private static JsonObject json(String text) {
    return JsonParser.parseString(text).getAsJsonObject();
  }

  private static byte[] png() {
    try {
      ByteArrayOutputStream output = new ByteArrayOutputStream();
      ImageIO.write(new BufferedImage(3, 2, BufferedImage.TYPE_INT_ARGB), "png", output);
      return output.toByteArray();
    } catch (Exception e) {
      throw new AssertionError(e);
    }
  }

  private static JsonObject begin(String source, int total) {
    JsonObject result = json("{\"action\":\"begin\",\"name\":\"Test\",\"category\":\"npc\"}");
    result.addProperty("source", source);
    result.addProperty("totalBytes", total);
    return result;
  }

  private static JsonObject action(String action, String id) {
    JsonObject args = new JsonObject();
    args.addProperty("action", action);
    args.addProperty("uploadId", id);
    return args;
  }

  private static JsonObject append(String id, int index, byte[] bytes) {
    JsonObject args = action("append", id);
    args.addProperty("index", index);
    args.addProperty("dataBase64", Base64.getEncoder().encodeToString(bytes));
    return args;
  }

  private static void withSession(boolean gm, Consumer<Context> test) throws Exception {
    AtomicReference<Throwable> failure = new AtomicReference<>();
    SwingUtilities.invokeAndWait(
        () -> {
          try (var mapTool = mockStatic(MapTool.class);
              var store = mockStatic(McpCampaignStore.class)) {
            Context context = new Context();
            when(context.player.getName()).thenReturn("Alice");
            when(context.player.isGM()).thenReturn(gm);
            mapTool.when(MapTool::getPlayer).thenReturn(context.player);
            mapTool
                .when(MapTool::getCampaign)
                .thenAnswer(invocation -> context.activeCampaign.get());
            mapTool.when(MapTool::getFrame).thenReturn(context.frame);
            mapTool.when(MapTool::getClient).thenReturn(mock(MapToolClient.class));
            mapTool.when(MapTool::getServerPolicy).thenReturn(new ServerPolicy());
            mapTool.when(MapTool::serverCommand).thenReturn(context.server);
            store.when(McpCampaignStore::requireGM).thenCallRealMethod();
            store
                .when(McpCampaignStore::read)
                .thenAnswer(invocation -> context.root.get().deepCopy());
            store
                .when(() -> McpCampaignStore.write(any(JsonObject.class)))
                .thenAnswer(
                    invocation -> {
                      context.root.set(((JsonObject) invocation.getArgument(0)).deepCopy());
                      return null;
                    });
            context.zone.setName("Test map");
            context.zone.setVisible(true);
            context.zone.setGrid(GridFactory.createGrid(Grid.GridType.Square));
            context.zone.getGrid().setSize(50);
            context.campaign.putZone(context.zone);
            test.accept(context);
          } catch (Throwable exception) {
            failure.set(exception);
          }
        });
    if (failure.get() != null) throw new AssertionError("EDT content test failed", failure.get());
  }

  private static final class Context {
    final Campaign campaign = new Campaign();
    final AtomicReference<Campaign> activeCampaign = new AtomicReference<>(campaign);
    final AtomicReference<JsonObject> root = new AtomicReference<>(new JsonObject());
    final Zone zone = new Zone();
    final MapToolFrame frame = mock(MapToolFrame.class, RETURNS_DEEP_STUBS);
    final LocalPlayer player = mock(LocalPlayer.class);
    final ServerCommand server = mock(ServerCommand.class);

    JsonObject call(String name, JsonObject args) {
      return new MapToolMcpService().callTool(name, args);
    }

    JsonObject creation() {
      JsonObject args = json("{\"name\":\"Test\",\"x\":0,\"y\":0}");
      args.addProperty("mapId", zone.getId().toString());
      return args;
    }

    JsonObject image(String source, byte[] bytes) {
      JsonObject args = json("{\"name\":\"Test\",\"category\":\"npc\"}");
      args.addProperty("source", source);
      args.addProperty("dataBase64", Base64.getEncoder().encodeToString(bytes));
      return args;
    }
  }
}
