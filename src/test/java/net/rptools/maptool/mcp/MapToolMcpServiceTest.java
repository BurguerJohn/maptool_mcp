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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.awt.Rectangle;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import javax.swing.SwingUtilities;
import net.rptools.maptool.client.MapTool;
import net.rptools.maptool.client.MapToolClient;
import net.rptools.maptool.client.ui.MapToolFrame;
import net.rptools.maptool.client.ui.zone.renderer.ZoneRenderer;
import net.rptools.maptool.model.Campaign;
import net.rptools.maptool.model.Grid;
import net.rptools.maptool.model.GridFactory;
import net.rptools.maptool.model.Token;
import net.rptools.maptool.model.Zone;
import net.rptools.maptool.model.drawing.Drawable;
import net.rptools.maptool.model.drawing.DrawnElement;
import net.rptools.maptool.model.drawing.Pen;
import net.rptools.maptool.model.player.LocalPlayer;
import net.rptools.maptool.server.ServerCommand;
import net.rptools.maptool.server.ServerPolicy;
import org.junit.jupiter.api.Test;

class MapToolMcpServiceTest {
  private static final String ID = "00112233445566778899AABBCCDDEEFF";

  @Test
  void schemasRejectCoercionsUnknownFieldsAndReservedMetadata() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            validate(
                "maptool_move_token",
                "{\"mapId\":\"" + ID + "\",\"tokenId\":\"" + ID + "\",\"x\":1.5,\"y\":0}"));
    assertThrows(
        IllegalArgumentException.class,
        () -> validate("maptool_create_map", "{\"name\":\"A\",\"visible\":\"true\"}"));
    assertThrows(
        IllegalArgumentException.class, () -> validate("maptool_get_session", "{\"role\":\"GM\"}"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            validate(
                "maptool_update_token",
                "{\"mapId\":\""
                    + ID
                    + "\",\"tokenId\":\""
                    + ID
                    + "\",\"properties\":{\"__MAPTOOL_MCP_door_open\":\"true\"}}"));
    assertThrows(
        IllegalArgumentException.class,
        () -> validate("maptool_create_map", "{\"name\":\"A\",\"gridSize\":100000}"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            validate(
                "maptool_update_token",
                "{\"mapId\":\""
                    + ID
                    + "\",\"tokenId\":\""
                    + ID
                    + "\",\"properties\":{\"nested\":{\"macro\":\"x\"}}}"));
    assertThrows(
        IllegalArgumentException.class, () -> validate("maptool_create_map", "{\"name\":null}"));
    assertDoesNotThrow(
        () ->
            validate(
                "maptool_update_token",
                "{\"mapId\":\""
                    + ID
                    + "\",\"tokenId\":\""
                    + ID
                    + "\",\"properties\":{\"HP\":12,\"Conscious\":true,\"Notes\":\"\"}}"));
  }

  @Test
  void schemasAreDetachedAndReadOnlyToolsAreAnnotated() {
    var service = new MapToolMcpService();
    var first = service.listTools();
    var names = new java.util.HashSet<String>();
    first.forEach(tool -> names.add(tool.getAsJsonObject().get("name").getAsString()));
    assertEquals(first.size(), names.size(), "Every tool has a unique dispatch name");
    assertTrue(
        names.containsAll(
            java.util.Set.of(
                "maptool_get_session",
                "maptool_move_token",
                "maptool_create_door",
                "maptool_get_content_options",
                "maptool_create_npc",
                "maptool_advance_world")));
    var read = first.get(0).getAsJsonObject();
    assertTrue(read.getAsJsonObject("annotations").get("readOnlyHint").getAsBoolean());
    assertFalse(read.getAsJsonObject("annotations").get("destructiveHint").getAsBoolean());
    read.addProperty("name", "tampered");
    assertEquals(
        "maptool_get_session",
        service.listTools().get(0).getAsJsonObject().get("name").getAsString());
  }

  @Test
  void playerReadsOmitHiddenMapsEnemyTokensAndAllTokenSecrets() throws Exception {
    withSession(
        false,
        context -> {
          Zone hidden = zone("Secret basement", false);
          context.campaign.putZone(hidden);
          context.zone.setPlayerAlias("Town square");
          Token owned = context.addToken("Hero", "Alice", Zone.Layer.TOKEN, true);
          owned.setProperty("GMSecret", "secret password");
          owned.setGMNotes("secret trap");
          context.addToken("Hidden owned", "Alice", Zone.Layer.TOKEN, false);
          context.addToken("Enemy", "Bob", Zone.Layer.TOKEN, true);
          context.addToken("GM layer", "Alice", Zone.Layer.GM, true);

          var maps =
              context
                  .service
                  .callTool("maptool_list_maps", new JsonObject())
                  .getAsJsonArray("maps");
          assertEquals(1, maps.size());
          assertEquals("Town square", maps.get(0).getAsJsonObject().get("name").getAsString());
          var response = context.service.callTool("maptool_get_map", context.args());
          assertEquals(1, response.getAsJsonArray("tokens").size());
          assertFalse(response.toString().contains("secret"));
          assertFalse(response.toString().contains("properties"));
          JsonObject hiddenArgs = new JsonObject();
          hiddenArgs.addProperty("mapId", hidden.getId().toString());
          assertThrows(
              IllegalArgumentException.class,
              () -> context.service.callTool("maptool_get_map", hiddenArgs));
          verifyNoInteractions(context.server);
        });
  }

  @Test
  void playerCannotCreateMapsOrEditAnotherTokenOrProperties() throws Exception {
    withSession(
        false,
        context -> {
          JsonObject create = new JsonObject();
          create.addProperty("name", "Unauthorized");
          assertThrows(
              SecurityException.class,
              () -> context.service.callTool("maptool_create_map", create));
          Token enemy = context.addToken("Enemy", "Bob", Zone.Layer.TOKEN, true);
          JsonObject update = context.args();
          update.addProperty("tokenId", enemy.getId().toString());
          update.addProperty("name", "Changed");
          assertThrows(
              IllegalArgumentException.class,
              () -> context.service.callTool("maptool_update_token", update));
          Token own = context.addToken("Hero", "Alice", Zone.Layer.TOKEN, true);
          update.addProperty("tokenId", own.getId().toString());
          update.add("properties", JsonParser.parseString("{\"HP\":999}").getAsJsonObject());
          assertThrows(
              SecurityException.class,
              () -> context.service.callTool("maptool_update_token", update));
          assertEquals("Hero", own.getName());
          verifyNoInteractions(context.server);
        });
  }

  @Test
  void ownedTokenUpdateHonorsLocksAndPublishesStableId() throws Exception {
    withSession(
        false,
        context -> {
          Token own = context.addToken("Hero", "Alice", Zone.Layer.TOKEN, true);
          JsonObject args = context.args();
          args.addProperty("tokenId", own.getId().toString());
          args.addProperty("facing", 90);
          context.policy.setIsTokenEditorLocked(true);
          assertThrows(
              SecurityException.class,
              () -> context.service.callTool("maptool_update_token", args));
          verifyNoInteractions(context.server);
          context.policy.setIsTokenEditorLocked(false);
          var result = context.service.callTool("maptool_update_token", args);
          assertEquals(90, result.get("facing").getAsInt());
          assertEquals(own.getId().toString(), result.get("tokenId").getAsString());
          verify(context.server)
              .putToken(
                  eq(context.zone.getId()),
                  argThat(t -> t.getId().equals(own.getId()) && t.getFacing() == 90));
          assertFalse(
              own.hasFacing(), "Validation/copy must not mutate the original token before publish");
        });
  }

  @Test
  void gmCanDisablePlayerMapDiscoveryAndSceneSwitching() throws Exception {
    withSession(
        false,
        context -> {
          Zone another = zone("Other scene", true);
          context.campaign.putZone(another);
          context.policy.setHiddenMapSelectUI(true);
          var listed = context.service.callTool("maptool_list_maps", new JsonObject());
          assertEquals(1, listed.getAsJsonArray("maps").size());
          assertEquals(
              context.zone.getId().toString(),
              listed.getAsJsonArray("maps").get(0).getAsJsonObject().get("mapId").getAsString());
          JsonObject target = new JsonObject();
          target.addProperty("mapId", another.getId().toString());
          assertThrows(
              IllegalArgumentException.class,
              () -> context.service.callTool("maptool_get_map", target));
          assertThrows(
              SecurityException.class,
              () -> context.service.callTool("maptool_switch_scene", target));
          verify(context.frame, never()).setCurrentZoneRenderer(any(ZoneRenderer.class));
        });
  }

  @Test
  void gmMapUpdateAndDrawingUseNativeSynchronizationOnce() throws Exception {
    withSession(
        true,
        context -> {
          JsonObject args = context.args();
          args.addProperty("name", "Castle");
          args.addProperty("visible", false);
          args.addProperty("fog", true);
          context.service.callTool("maptool_update_map", args);
          assertEquals("Castle", context.zone.getName());
          assertFalse(context.zone.isVisible());
          assertTrue(context.zone.hasFog());
          verify(context.server).renameZone(context.zone.getId(), "Castle");
          verify(context.server).setZoneVisibility(context.zone.getId(), false);
          verify(context.server).setZoneHasFoW(context.zone.getId(), true);
          JsonObject draw = context.args();
          draw.addProperty("x", 10);
          draw.addProperty("y", 20);
          draw.addProperty("width", 100);
          draw.addProperty("height", 50);
          // Undo registration touches Swing AppActions/menu accelerators, unavailable headlessly.
          // Keep model insertion real, and verify the separate undo registration call below.
          doNothing().when(context.zone).addDrawable(any(Pen.class), any(Drawable.class));
          // Native DRAW_MSG is echoed to the originating client. Simulate that delivery,
          // so an accidental local insertion would make the assertion below see two drawings.
          var zoneId = context.zone.getId();
          doAnswer(
                  invocation -> {
                    context.zone.addDrawable(
                        new DrawnElement(invocation.getArgument(2), invocation.getArgument(1)));
                    return null;
                  })
              .when(context.server)
              .draw(eq(zoneId), any(Pen.class), any(Drawable.class));
          context.service.callTool("maptool_draw_shape", draw);
          assertEquals(1, context.zone.getDrawnElements(Zone.Layer.BACKGROUND).size());
          verify(context.server, times(1))
              .draw(eq(context.zone.getId()), any(Pen.class), any(Drawable.class));
          verify(context.zone, times(1)).addDrawable(any(Pen.class), any(Drawable.class));
        });
  }

  @Test
  void openingDoorOnlyChangesItsOwnTopologyAndCanBeClosedAgain() {
    Token door = new Token();
    door.setWidth(60);
    door.setHeight(10);
    Token otherDoor = new Token();
    otherDoor.setWidth(60);
    otherDoor.setHeight(10);
    MapToolMcpService.applyDoorTopology(door, false);
    MapToolMcpService.applyDoorTopology(otherDoor, false);
    assertTrue(door.getMaskTopology(Zone.TopologyType.WALL_VBL).contains(30, 5));
    assertNotNull(door.getMaskTopology(Zone.TopologyType.MBL));
    MapToolMcpService.applyDoorTopology(door, true);
    assertNull(door.getMaskTopology(Zone.TopologyType.WALL_VBL));
    assertNull(door.getMaskTopology(Zone.TopologyType.MBL));
    assertNotNull(otherDoor.getMaskTopology(Zone.TopologyType.WALL_VBL));
    assertEquals("true", door.getProperty("__maptool_mcp_door_open"));
    MapToolMcpService.applyDoorTopology(door, false);
    assertNotNull(door.getMaskTopology(Zone.TopologyType.MBL));
  }

  @Test
  void doorReachUsesFootprintEdgesAndRejectsDiagonalBeyondOneCell() {
    assertTrue(
        MapToolMcpService.withinReach(
            new Rectangle(0, 0, 50, 50), new Rectangle(100, 0, 5, 50), 50));
    assertFalse(
        MapToolMcpService.withinReach(
            new Rectangle(0, 0, 50, 50), new Rectangle(100, 100, 5, 50), 50));
  }

  private static void validate(String tool, String json) {
    MapToolMcpService.validateArguments(tool, JsonParser.parseString(json).getAsJsonObject());
  }

  private static Zone zone(String name, boolean visible) {
    Zone zone = new Zone();
    zone.setName(name);
    zone.setVisible(visible);
    zone.setHasFog(false);
    zone.setVisionType(Zone.VisionType.OFF);
    zone.setGrid(GridFactory.createGrid(Grid.GridType.Square));
    zone.getGrid().setSize(50);
    return zone;
  }

  private static void withSession(boolean gm, Consumer<Context> test) throws Exception {
    AtomicReference<Throwable> failure = new AtomicReference<>();
    SwingUtilities.invokeAndWait(
        () -> {
          try (var mapTool = mockStatic(MapTool.class)) {
            Context context = new Context();
            LocalPlayer player = mock(LocalPlayer.class);
            when(player.getName()).thenReturn("Alice");
            when(player.isGM()).thenReturn(gm);
            mapTool.when(MapTool::getPlayer).thenReturn(player);
            mapTool.when(MapTool::getCampaign).thenReturn(context.campaign);
            mapTool.when(MapTool::getFrame).thenReturn(context.frame);
            mapTool.when(MapTool::getClient).thenReturn(mock(MapToolClient.class));
            mapTool.when(MapTool::getServerPolicy).thenReturn(context.policy);
            mapTool.when(MapTool::serverCommand).thenReturn(context.server);
            ZoneRenderer renderer = mock(ZoneRenderer.class);
            when(renderer.getZone()).thenReturn(context.zone);
            when(context.frame.getCurrentZoneRenderer()).thenReturn(renderer);
            when(context.frame.getZoneRenderer(context.zone)).thenReturn(renderer);
            context.campaign.putZone(context.zone);
            test.accept(context);
          } catch (Throwable exception) {
            failure.set(exception);
          }
        });
    if (failure.get() != null) throw new AssertionError("EDT service test failed", failure.get());
  }

  private static class Context {
    final MapToolMcpService service = new MapToolMcpService();
    final Campaign campaign = new Campaign();
    final Zone zone = spy(zone("Internal GM name", true));
    final MapToolFrame frame = mock(MapToolFrame.class, RETURNS_DEEP_STUBS);
    final ServerCommand server = mock(ServerCommand.class);
    final ServerPolicy policy = new ServerPolicy();

    JsonObject args() {
      JsonObject args = new JsonObject();
      args.addProperty("mapId", zone.getId().toString());
      return args;
    }

    Token addToken(String name, String owner, Zone.Layer layer, boolean visible) {
      Token token = new Token();
      token.setName(name);
      token.addOwner(owner);
      token.setLayer(layer);
      token.setVisible(visible);
      token.setWidth(50);
      token.setHeight(50);
      token.setSnapToGrid(false);
      token.setSnapToScale(false);
      zone.putToken(token);
      return token;
    }
  }
}
