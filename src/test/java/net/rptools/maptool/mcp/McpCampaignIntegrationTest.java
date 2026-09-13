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
import java.awt.Color;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import javax.swing.SwingUtilities;
import net.rptools.maptool.client.MapTool;
import net.rptools.maptool.client.MapToolClient;
import net.rptools.maptool.client.ui.MapToolFrame;
import net.rptools.maptool.client.ui.zone.renderer.ZoneRenderer;
import net.rptools.maptool.model.Campaign;
import net.rptools.maptool.model.GUID;
import net.rptools.maptool.model.Grid;
import net.rptools.maptool.model.GridFactory;
import net.rptools.maptool.model.Token;
import net.rptools.maptool.model.Zone;
import net.rptools.maptool.model.drawing.DrawableColorPaint;
import net.rptools.maptool.model.player.LocalPlayer;
import net.rptools.maptool.server.ServerCommand;
import net.rptools.maptool.server.ServerPolicy;
import org.junit.jupiter.api.Test;

/** Cross-module scenarios using real campaign storage and its native protobuf round trip. */
class McpCampaignIntegrationTest {
  @Test
  void nestedTravelPersistsDoorsFollowersAndFireAcrossCampaignReload() throws Exception {
    withSession(
        c -> {
          Zone worldMap = c.addMap("World");
          Zone cityMap = c.addMap("City");
          Zone houseMap = c.addMap("House");
          String world = c.location("World", "world", worldMap, null);
          String city = c.location("City", "city", cityMap, world);
          String house = c.location("House", "building", houseMap, city);
          JsonObject cityGate = c.portal(world, city);
          JsonObject houseGate = c.portal(city, house);
          Token hero = c.hero(worldMap);
          JsonObject npc =
              c.call(
                  "maptool_create_npc",
                  args(
                      "mapId",
                      worldMap.getId(),
                      "name",
                      "Guide",
                      "x",
                      0,
                      "y",
                      50,
                      "hp",
                      12,
                      "maxHp",
                      12));
          GUID npcId = GUID.valueOf(npc.get("tokenId").getAsString());
          c.call("maptool_set_npc_follow", args("npcId", npcId, "leaderId", hero.getId()));
          assertEquals(
              2,
              c.enter(cityGate.get("id").getAsString(), hero)
                  .getAsJsonArray("transferredTokenIds")
                  .size());
          assertEquals(
              2,
              c.enter(houseGate.get("id").getAsString(), hero)
                  .getAsJsonArray("transferredTokenIds")
                  .size());
          assertNull(worldMap.getToken(hero.getId()));
          assertNull(cityMap.getToken(hero.getId()));
          assertTrue(houseMap.getToken(hero.getId()).isOwner("Alice"));
          JsonObject door =
              c.call(
                  "maptool_create_door",
                  args(
                      "mapId",
                      houseMap.getId(),
                      "name",
                      "Front door",
                      "x",
                      200,
                      "y",
                      0,
                      "width",
                      10,
                      "height",
                      50));
          GUID doorId = GUID.valueOf(door.get("tokenId").getAsString());
          c.call(
              "maptool_set_door", args("mapId", houseMap.getId(), "doorId", doorId, "open", true));
          c.enter(houseGate.get("returnPortalId").getAsString(), hero);
          c.enter(houseGate.get("id").getAsString(), hero);
          assertEquals("true", houseMap.getToken(doorId).getProperty("__maptool_mcp_door_open"));
          assertEquals("12", houseMap.getToken(npcId).getProperty("HP").toString());

          c.call("maptool_configure_hazard", args("locationId", house, "integrity", 30));
          c.call(
              "maptool_ignite_location",
              args("locationId", city.toLowerCase(java.util.Locale.ROOT), "intensity", 20));
          assertFalse(
              c.call(
                      "maptool_get_world_events",
                      args("locationId", city.toLowerCase(java.util.Locale.ROOT)))
                  .getAsJsonArray("events")
                  .isEmpty());
          JsonObject savedBeforePreview = McpCampaignStore.read();
          c.call("maptool_advance_world", args("ticks", 2, "dryRun", true));
          assertEquals(
              savedBeforePreview,
              McpCampaignStore.read(),
              "Preview must leave campaign state untouched");
          c.call("maptool_advance_world", args("ticks", 2));
          assertEquals(
              "destroyed",
              c.call("maptool_get_location", args("locationId", house))
                  .get("status")
                  .getAsString());
          assertEquals(
              "12",
              houseMap.getToken(npcId).getProperty("HP").toString(),
              "Hazards do not invent combat damage");
          assertNotNull(houseMap.getToken(npcId).getProperty(McpWorldEvents.EXPOSURE));
          c.call("maptool_set_content_options", args("imagegen", true, "misc", false));

          // CampaignDto includes native TokenDto properties, maps, doors and stable token IDs.
          c.campaign.set(Campaign.fromDto(c.campaign.get().toDto()));
          assertTrue(c.call("maptool_get_content_options", args()).get("imagegen").getAsBoolean());
          assertFalse(c.call("maptool_get_content_options", args()).get("misc").getAsBoolean());
          assertEquals(
              "destroyed",
              c.call("maptool_get_location", args("locationId", house))
                  .get("status")
                  .getAsString());
          Zone restoredHouse = c.campaign.get().getZone(houseMap.getId());
          assertEquals(
              "true", restoredHouse.getToken(doorId).getProperty("__maptool_mcp_door_open"));
          assertNotNull(restoredHouse.getToken(hero.getId()));
          assertThrows(
              IllegalArgumentException.class,
              () -> c.call("maptool_switch_scene", args("mapId", houseMap.getId())));
          assertThrows(
              IllegalArgumentException.class,
              () -> c.call("maptool_materialize_location", args("locationId", house)));
          assertFalse(
              c.call("maptool_list_maps", args()).toString().contains(houseMap.getId().toString()));

          // An archived interior still permits evacuation into its surviving parent.
          assertEquals(
              2,
              c.enter(houseGate.get("returnPortalId").getAsString(), hero)
                  .getAsJsonArray("transferredTokenIds")
                  .size());
          c.enter(cityGate.get("returnPortalId").getAsString(), hero);
          Token escaped = c.campaign.get().getZone(worldMap.getId()).getToken(npcId);
          assertNotNull(escaped);
          assertNull(escaped.getProperty(McpWorldEvents.EXPOSURE));
          assertThrows(
              IllegalArgumentException.class,
              () -> c.enter(houseGate.get("id").getAsString(), hero));
        });
  }

  @Test
  void baseToolsEnforceCreationFlagsAndProtectRegistryAndEntranceMarkers() throws Exception {
    withSession(
        c -> {
          Zone map = c.addMap("World");
          Zone destination = c.addMap("Destination");
          String world = c.location("World", "world", map, null);
          String city = c.location("Destination", "city", destination, world);
          JsonObject portal = c.portal(world, city);
          c.call(
              "maptool_update_map",
              args("mapId", destination.getId(), "name", "Renamed city", "visible", false));
          JsonObject location = c.call("maptool_get_location", args("locationId", city));
          assertEquals("Renamed city", location.get("name").getAsString());
          assertFalse(location.get("visible").getAsBoolean());
          assertFalse(map.getToken(GUID.valueOf(portal.get("id").getAsString())).isVisible());
          c.call(
              "maptool_set_content_options", args("npc", false, "scenery", false, "misc", false));
          for (String layer : new String[] {"TOKEN", "GM", "BACKGROUND", "OBJECT"}) {
            assertThrows(
                SecurityException.class,
                () ->
                    c.call(
                        "maptool_create_token",
                        args(
                            "mapId",
                            map.getId(),
                            "name",
                            "Blocked",
                            "x",
                            0,
                            "y",
                            0,
                            "type",
                            "NPC",
                            "layer",
                            layer)));
          }
          assertThrows(
              SecurityException.class,
              () ->
                  c.call(
                      "maptool_draw_shape",
                      args("mapId", map.getId(), "x", 0, "y", 0, "width", 50, "height", 50)));
          assertThrows(
              IllegalArgumentException.class,
              () ->
                  c.call(
                      "maptool_move_token",
                      args(
                          "mapId",
                          map.getId(),
                          "tokenId",
                          portal.get("id").getAsString(),
                          "x",
                          50,
                          "y",
                          50)));
          Token registry =
              map.getAllTokens().stream()
                  .filter(t -> "1".equals(t.getProperty(McpCampaignStore.REGISTRY)))
                  .findFirst()
                  .orElseThrow();
          assertThrows(
              IllegalArgumentException.class,
              () ->
                  c.call(
                      "maptool_update_token",
                      args("mapId", map.getId(), "tokenId", registry.getId(), "visible", true)));
          assertFalse(registry.isVisible());
          JsonObject pc =
              c.call(
                  "maptool_create_token",
                  args("mapId", map.getId(), "name", "Player", "x", 0, "y", 0, "type", "PC"));
          assertNotNull(map.getToken(GUID.valueOf(pc.get("tokenId").getAsString())));
        });
  }

  static JsonObject args(Object... pairs) {
    JsonObject result = new JsonObject();
    for (int i = 0; i < pairs.length; i += 2) {
      String key = pairs[i].toString();
      Object value = pairs[i + 1];
      if (value instanceof Boolean b) result.addProperty(key, b);
      else if (value instanceof Number n) result.addProperty(key, n);
      else result.addProperty(key, value.toString());
    }
    return result;
  }

  private static void withSession(Consumer<Session> test) throws Exception {
    AtomicReference<Throwable> failure = new AtomicReference<>();
    SwingUtilities.invokeAndWait(
        () -> {
          try (var mapTool = mockStatic(MapTool.class)) {
            Session c = new Session();
            LocalPlayer player = mock(LocalPlayer.class);
            when(player.getName()).thenReturn("Alice");
            when(player.isGM()).thenReturn(true);
            mapTool.when(MapTool::getPlayer).thenReturn(player);
            mapTool.when(MapTool::getCampaign).thenAnswer(i -> c.campaign.get());
            mapTool.when(MapTool::getFrame).thenReturn(c.frame);
            mapTool.when(MapTool::getClient).thenReturn(mock(MapToolClient.class));
            mapTool.when(MapTool::getServerPolicy).thenReturn(new ServerPolicy());
            mapTool.when(MapTool::serverCommand).thenReturn(c.server);
            doAnswer(
                    i -> {
                      c.campaign.get().getZone(i.getArgument(0)).putToken(i.getArgument(1));
                      return null;
                    })
                .when(c.server)
                .putToken(any(GUID.class), any(Token.class));
            doAnswer(
                    i -> {
                      c.campaign.get().getZone(i.getArgument(0)).removeToken(i.getArgument(1));
                      return null;
                    })
                .when(c.server)
                .removeToken(any(GUID.class), any(GUID.class));
            when(c.frame.getZoneRenderer(any(Zone.class)))
                .thenAnswer(
                    i -> {
                      ZoneRenderer renderer = mock(ZoneRenderer.class);
                      when(renderer.getZone()).thenReturn(i.getArgument(0));
                      return renderer;
                    });
            test.accept(c);
          } catch (Throwable exception) {
            failure.set(exception);
          }
        });
    if (failure.get() != null)
      throw new AssertionError("Campaign integration failed", failure.get());
  }

  private static class Session {
    final AtomicReference<Campaign> campaign = new AtomicReference<>(new Campaign());
    final MapToolFrame frame = mock(MapToolFrame.class, RETURNS_DEEP_STUBS);
    final ServerCommand server = mock(ServerCommand.class);

    Zone addMap(String name) {
      Zone zone = new Zone();
      zone.setName(name);
      zone.setVisible(true);
      zone.setHasFog(false);
      zone.setVisionType(Zone.VisionType.OFF);
      zone.setBackgroundPaint(new DrawableColorPaint(Color.GRAY));
      zone.setFogPaint(new DrawableColorPaint(Color.BLACK));
      zone.setGrid(GridFactory.createGrid(Grid.GridType.Square));
      zone.getGrid().setSize(50);
      campaign.get().putZone(zone);
      return zone;
    }

    String location(String name, String kind, Zone map, String parent) {
      JsonObject input = args("name", name, "kind", kind, "mapId", map.getId(), "visible", true);
      if (parent != null) input.addProperty("parentId", parent);
      return call("maptool_create_location", input).get("id").getAsString();
    }

    JsonObject portal(String from, String to) {
      return call(
          "maptool_create_portal",
          args(
              "sourceLocationId",
              from,
              "targetLocationId",
              to,
              "x",
              0,
              "y",
              0,
              "targetX",
              0,
              "targetY",
              0,
              "bidirectional",
              true));
    }

    Token hero(Zone zone) {
      var asset =
          net.rptools.maptool.model.Asset.createImageAsset(
              "Hero",
              new java.awt.image.BufferedImage(50, 50, java.awt.image.BufferedImage.TYPE_INT_ARGB));
      net.rptools.maptool.model.AssetManager.putAsset(asset);
      Token hero = new Token("Hero", asset.getMD5Key());
      hero.setType(Token.Type.PC);
      hero.setLayer(Zone.Layer.TOKEN);
      hero.setVisible(true);
      hero.setWidth(50);
      hero.setHeight(50);
      hero.setSnapToScale(false);
      hero.setSnapToGrid(false);
      hero.addOwner("Alice");
      zone.putToken(hero);
      return hero;
    }

    JsonObject enter(String portal, Token token) {
      return call("maptool_enter_location", args("portalId", portal, "tokenId", token.getId()));
    }

    JsonObject call(String name, JsonObject input) {
      return new MapToolMcpService().callTool(name, input);
    }
  }
}
