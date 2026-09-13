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
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.google.gson.JsonObject;
import java.awt.Rectangle;
import java.awt.geom.Area;
import java.util.Set;
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
import net.rptools.maptool.model.ZoneFactory;
import net.rptools.maptool.model.drawing.Drawable;
import net.rptools.maptool.model.drawing.DrawnElement;
import net.rptools.maptool.model.drawing.Pen;
import net.rptools.maptool.model.player.LocalPlayer;
import net.rptools.maptool.server.ServerCommand;
import net.rptools.maptool.server.ServerPolicy;
import org.junit.jupiter.api.Test;

class McpWorldServiceTest {
  @Test
  void portalApproachCannotCrossWallUnlessGmExplicitlyOverridesReach() throws Exception {
    withSession(
        c -> {
          JsonObject source = c.create("Town", "city", c.zone, null);
          JsonObject target = c.create("Home", "room", newZoneAdded(c, "Home map"), source);
          JsonObject portal =
              c.call(
                  "maptool_create_portal",
                  args(
                      "sourceLocationId",
                      id(source),
                      "targetLocationId",
                      id(target),
                      "x",
                      100,
                      "y",
                      0));
          Token hero = c.character(c.zone, "Hero", Token.Type.PC, 0, 0);
          c.zone.updateMaskTopology(
              new Area(new Rectangle(75, -50, 5, 200)), false, Zone.TopologyType.MBL);
          JsonObject enter = args("portalId", id(portal), "tokenId", hero.getId().toString());
          assertThrows(
              IllegalArgumentException.class, () -> c.call("maptool_enter_location", enter));
          assertNotNull(c.zone.getToken(hero.getId()));
          enter.addProperty("requireReach", false);
          assertEquals(
              1,
              c.call("maptool_enter_location", enter).getAsJsonArray("transferredTokenIds").size());
        });
  }

  @Test
  void guidCaseDoesNotChangeParentsPortalLookupOrFollowerChains() throws Exception {
    withSession(
        c -> {
          JsonObject source = c.create("Town", "city", c.zone, null);
          JsonObject target =
              c.call(
                  "maptool_create_location",
                  args(
                      "name",
                      "Home",
                      "kind",
                      "room",
                      "parentId",
                      id(source).toLowerCase(java.util.Locale.ROOT),
                      "template",
                      "blank"));
          assertEquals(id(source), target.get("parentId").getAsString());
          assertEquals(
              1,
              c.call(
                      "maptool_list_locations",
                      args("parentId", id(source).toLowerCase(java.util.Locale.ROOT)))
                  .getAsJsonArray("locations")
                  .size());
          JsonObject portal =
              c.call(
                  "maptool_create_portal",
                  args(
                      "sourceLocationId",
                      id(source).toLowerCase(java.util.Locale.ROOT),
                      "targetLocationId",
                      id(target).toLowerCase(java.util.Locale.ROOT),
                      "x",
                      0,
                      "y",
                      0));
          Token hero = c.character(c.zone, "Hero", Token.Type.PC, 0, 0);
          Token npc = c.character(c.zone, "Guide", Token.Type.NPC, 50, 0);
          c.call(
              "maptool_set_npc_follow",
              args(
                  "npcId",
                  npc.getId().toString().toLowerCase(java.util.Locale.ROOT),
                  "leaderId",
                  hero.getId().toString().toLowerCase(java.util.Locale.ROOT)));
          JsonObject moved =
              c.call(
                  "maptool_enter_location",
                  args(
                      "portalId",
                      id(portal).toLowerCase(java.util.Locale.ROOT),
                      "tokenId",
                      hero.getId().toString().toLowerCase(java.util.Locale.ROOT)));
          assertEquals(2, moved.getAsJsonArray("transferredTokenIds").size());
        });
  }

  @Test
  void registrySurvivesNativeTokenSerializationAndNeverAliasesReadResults() throws Exception {
    withSession(
        c -> {
          JsonObject document = new JsonObject();
          document.add("settings", args("npc", false));
          document.add("world", args("note", "persistent city state"));
          McpCampaignStore.write(document);
          McpCampaignStore.write(document);
          Token registry = c.zone.getAllTokens().getFirst();
          assertFalse(registry.isVisible());
          assertEquals(Zone.Layer.GM, registry.getLayer());
          Token restored = Token.fromDto(registry.toDto());
          c.zone.putToken(restored);
          JsonObject read = McpCampaignStore.read();
          assertEquals(document, read);
          read.getAsJsonObject("settings").addProperty("npc", true);
          assertFalse(
              McpCampaignStore.read().getAsJsonObject("settings").get("npc").getAsBoolean());
          c.campaign.removeZone(c.zone.getId());
          c.campaign.putZone(newZone("Different campaign map"));
          assertEquals(new JsonObject(), McpCampaignStore.read());
        });
  }

  @Test
  void registryRejectsOversizeDuplicateAndPlayerWritesWithoutMutation() throws Exception {
    withSession(
        c -> {
          JsonObject document = args("tooLarge", "x".repeat(McpCampaignStore.MAX_BYTES));
          assertThrows(IllegalArgumentException.class, () -> McpCampaignStore.write(document));
          assertTrue(c.zone.getAllTokens().isEmpty());
          when(c.player.isGM()).thenReturn(false);
          assertThrows(SecurityException.class, () -> McpCampaignStore.write(new JsonObject()));
          when(c.player.isGM()).thenReturn(true);
          McpCampaignStore.write(new JsonObject());
          c.zone.putToken(new Token(c.zone.getAllTokens().getFirst()));
          assertThrows(IllegalStateException.class, McpCampaignStore::read);
        });
  }

  @Test
  void plannedInteriorMaterializesOnceWithRealWallsDoorAndSavedState() throws Exception {
    withSession(
        c -> {
          JsonObject place = c.create("Home", "building", null, null);
          assertEquals("planned", place.get("status").getAsString());
          JsonObject materialized =
              c.call(
                  "maptool_materialize_location",
                  args("locationId", place.get("id").getAsString()));
          Zone inside = c.campaign.getZone(GUID.valueOf(materialized.get("mapId").getAsString()));
          assertNotNull(inside);
          assertFalse(inside.getDrawnElements(Zone.Layer.BACKGROUND).isEmpty());
          assertTrue(inside.getMaskTopology(Zone.TopologyType.MBL).contains(-399, -599));
          assertFalse(
              inside.getMaskTopology(Zone.TopologyType.MBL).contains(0, 95),
              "Doorway must remain a gap in static topology");
          Token door =
              inside.getAllTokens().stream()
                  .filter(t -> "1".equals(t.getProperty("__maptool_mcp_door_v1")))
                  .findFirst()
                  .orElseThrow();
          assertEquals("true", door.getProperty("__maptool_mcp_door_open"));
          assertNull(door.getMaskTopology(Zone.TopologyType.MBL));
          Token chest = c.character(inside, "Chest", Token.Type.NPC, 100, 100);
          chest.setProperty("Opened", true);
          int maps = c.campaign.getZones().size();
          var again =
              c.call(
                  "maptool_materialize_location",
                  args("locationId", place.get("id").getAsString()));
          assertEquals(materialized.get("mapId"), again.get("mapId"));
          assertEquals(maps, c.campaign.getZones().size());
          assertEquals(true, inside.getToken(chest.getId()).getProperty("Opened"));
        });
  }

  @Test
  void portalRoundTripPreservesCharacterIdentityOwnershipPropertiesAndNpcFollowChain()
      throws Exception {
    withSession(
        c -> {
          JsonObject outside = c.create("Town", "city", c.zone, null);
          JsonObject house = c.create("Inn", "building", null, outside);
          JsonObject portal =
              c.call(
                  "maptool_create_portal",
                  args(
                      "sourceLocationId",
                      id(outside),
                      "targetLocationId",
                      id(house),
                      "x",
                      0,
                      "y",
                      0,
                      "bidirectional",
                      true));
          Token hero = c.character(c.zone, "Hero", Token.Type.PC, 0, 0);
          hero.addOwner("Alice");
          hero.setProperty("HP", 17);
          Token npc = c.character(c.zone, "Guide", Token.Type.NPC, 50, 0);
          c.call(
              "maptool_set_npc_follow",
              args("npcId", npc.getId().toString(), "leaderId", hero.getId().toString()));
          JsonObject result =
              c.call(
                  "maptool_enter_location",
                  args("portalId", id(portal), "tokenId", hero.getId().toString()));
          assertEquals(2, result.getAsJsonArray("transferredTokenIds").size());
          Zone inside = c.campaign.getZone(GUID.valueOf(result.get("mapId").getAsString()));
          assertNull(c.zone.getToken(hero.getId()));
          assertEquals(17, inside.getToken(hero.getId()).getProperty("HP"));
          assertTrue(inside.getToken(hero.getId()).isOwner("Alice"));
          assertNotNull(inside.getToken(npc.getId()));
          c.call(
              "maptool_enter_location",
              args(
                  "portalId",
                  portal.get("returnPortalId").getAsString(),
                  "tokenId",
                  hero.getId().toString()));
          assertNotNull(c.zone.getToken(hero.getId()));
          assertNull(inside.getToken(hero.getId()));
          assertEquals(17, c.zone.getToken(hero.getId()).getProperty("HP"));
        });
  }

  @Test
  void distantFollowersStayAndCannotBeTeleportedWithTheirChildren() throws Exception {
    withSession(
        c -> {
          JsonObject outside = c.create("Town", "city", c.zone, null);
          JsonObject inside = c.create("House", "room", newZoneAdded(c, "House map"), outside);
          JsonObject portal =
              c.call(
                  "maptool_create_portal",
                  args(
                      "sourceLocationId",
                      id(outside),
                      "targetLocationId",
                      id(inside),
                      "x",
                      0,
                      "y",
                      0));
          Token hero = c.character(c.zone, "Hero", Token.Type.PC, 0, 0);
          Token distant = c.character(c.zone, "Far guide", Token.Type.NPC, 1000, 0);
          Token child = c.character(c.zone, "Guide helper", Token.Type.NPC, 50, 0);
          c.call(
              "maptool_set_npc_follow",
              args("npcId", distant.getId().toString(), "leaderId", hero.getId().toString()));
          c.call(
              "maptool_set_npc_follow",
              args("npcId", child.getId().toString(), "leaderId", distant.getId().toString()));
          JsonObject result =
              c.call(
                  "maptool_enter_location",
                  args("portalId", id(portal), "tokenId", hero.getId().toString()));
          assertEquals(1, result.getAsJsonArray("transferredTokenIds").size());
          assertEquals(2, result.getAsJsonArray("stayedTokenIds").size());
          assertNotNull(c.zone.getToken(distant.getId()));
          assertNotNull(c.zone.getToken(child.getId()));
        });
  }

  @Test
  void followerMovesUseNativeBlockingTopologyAndPreventCycles() throws Exception {
    withSession(
        c -> {
          Token hero = c.character(c.zone, "Hero", Token.Type.PC, 0, 0);
          Token npc = c.character(c.zone, "Guide", Token.Type.NPC, 0, 100);
          Token other = c.character(c.zone, "Helper", Token.Type.NPC, 0, 200);
          c.call(
              "maptool_set_npc_follow",
              args("npcId", npc.getId().toString(), "leaderId", hero.getId().toString()));
          c.call(
              "maptool_set_npc_follow",
              args("npcId", other.getId().toString(), "leaderId", npc.getId().toString()));
          assertThrows(
              IllegalArgumentException.class,
              () ->
                  c.call(
                      "maptool_set_npc_follow",
                      args("npcId", npc.getId().toString(), "leaderId", other.getId().toString())));
          c.zone.updateMaskTopology(
              new Area(new Rectangle(75, 90, 2, 70)), false, Zone.TopologyType.MBL);
          Token moved = new Token(hero, true);
          moved.setX(150);
          JsonObject blocked = McpWorldService.afterLeaderMoved(c.zone, hero, moved);
          assertEquals(2, blocked.getAsJsonArray("blocked").size());
          assertEquals(0, c.zone.getToken(npc.getId()).getX());
          c.zone.updateMaskTopology(
              new Area(new Rectangle(75, 90, 2, 70)), true, Zone.TopologyType.MBL);
          JsonObject success = McpWorldService.afterLeaderMoved(c.zone, hero, moved);
          assertEquals(2, success.getAsJsonArray("moved").size());
          assertEquals(150, c.zone.getToken(npc.getId()).getX());
          assertEquals(150, c.zone.getToken(other.getId()).getX());
        });
  }

  @Test
  void destroyedDestinationCannotBeRecreatedOrEnteredAndMissingMapsNeverReset() throws Exception {
    withSession(
        c -> {
          JsonObject outside = c.create("Town", "city", c.zone, null);
          JsonObject target = c.create("House", "room", null, outside);
          JsonObject portal =
              c.call(
                  "maptool_create_portal",
                  args(
                      "sourceLocationId",
                      id(outside),
                      "targetLocationId",
                      id(target),
                      "x",
                      0,
                      "y",
                      0));
          Token hero = c.character(c.zone, "Hero", Token.Type.PC, 0, 0);
          JsonObject root = McpCampaignStore.read();
          McpWorldService.location(McpWorldService.world(root), id(target))
              .addProperty("status", "destroyed");
          McpCampaignStore.write(root);
          int maps = c.campaign.getZones().size();
          assertThrows(
              IllegalArgumentException.class,
              () -> c.call("maptool_materialize_location", args("locationId", id(target))));
          assertThrows(
              IllegalArgumentException.class,
              () ->
                  c.call(
                      "maptool_enter_location",
                      args("portalId", id(portal), "tokenId", hero.getId().toString())));
          assertEquals(maps, c.campaign.getZones().size());
          assertNotNull(c.zone.getToken(hero.getId()));
          assertEquals(
              1,
              c.call("maptool_list_locations", new JsonObject())
                  .getAsJsonArray("locations")
                  .size());
          assertEquals(
              1,
              c.call("maptool_list_locations", args("status", "destroyed"))
                  .getAsJsonArray("locations")
                  .size());
          JsonObject surviving = c.create("Lost map", "other", newZoneAdded(c, "Temporary"), null);
          c.campaign.removeZone(GUID.valueOf(surviving.get("mapId").getAsString()));
          assertThrows(
              IllegalArgumentException.class,
              () -> c.call("maptool_materialize_location", args("locationId", id(surviving))));
        });
  }

  @Test
  void playerCatalogUsesDisplayNamesAndCannotDiscoverHiddenOrPlannedInteriors() throws Exception {
    withSession(
        c -> {
          c.zone.setPlayerAlias("Market square");
          JsonObject visible = c.create("GM secret title", "city", c.zone, null);
          c.create("Hidden room", "room", null, visible);
          Zone second = newZoneAdded(c, "Other town");
          c.create("Other town", "city", second, null);
          when(c.player.isGM()).thenReturn(false);
          c.policy.setHiddenMapSelectUI(true);
          JsonObject listed = c.call("maptool_list_locations", new JsonObject());
          assertEquals(1, listed.getAsJsonArray("locations").size());
          assertFalse(listed.toString().contains("secret"));
          assertFalse(listed.toString().contains("Hidden room"));
          assertEquals(
              0,
              c.call("maptool_list_locations", args("query", "secret"))
                  .getAsJsonArray("locations")
                  .size());
          JsonObject detail = c.call("maptool_get_location", args("locationId", id(visible)));
          assertFalse(detail.has("blueprint"));
          assertFalse(detail.has("occupants"));
          assertThrows(
              SecurityException.class,
              () -> c.call("maptool_create_location", args("name", "Not allowed", "kind", "room")));
        });
  }

  @Test
  void locationValidationRejectsInvalidParentsDuplicateMapsAndOversizedBlueprints()
      throws Exception {
    withSession(
        c -> {
          JsonObject parent = c.create("Root", "world", c.zone, null);
          assertThrows(
              IllegalArgumentException.class, () -> c.create("Duplicate", "city", c.zone, null));
          assertThrows(
              IllegalArgumentException.class,
              () ->
                  c.call(
                      "maptool_create_location",
                      args("name", "Orphan", "kind", "room", "parentId", new GUID().toString())));
          assertThrows(
              IllegalArgumentException.class,
              () ->
                  c.call(
                      "maptool_create_location",
                      args(
                          "name", "Too large", "kind", "room", "gridSize", 500, "widthCells", 60)));
          JsonObject root = McpCampaignStore.read();
          McpWorldService.location(McpWorldService.world(root), id(parent))
              .addProperty("status", "burning");
          McpCampaignStore.write(root);
          assertThrows(
              IllegalArgumentException.class,
              () ->
                  c.call(
                      "maptool_update_location",
                      args("locationId", id(parent), "flammable", false)));
        });
  }

  private static String id(JsonObject object) {
    return object.get("id").getAsString();
  }

  private static JsonObject args(Object... entries) {
    JsonObject args = new JsonObject();
    for (int i = 0; i < entries.length; i += 2) {
      Object value = entries[i + 1];
      if (value instanceof Boolean b) args.addProperty((String) entries[i], b);
      else if (value instanceof Number n) args.addProperty((String) entries[i], n);
      else args.addProperty((String) entries[i], value.toString());
    }
    return args;
  }

  private static Zone newZone(String name) {
    Zone zone = spy(new Zone());
    zone.setName(name);
    zone.setVisible(true);
    zone.setHasFog(false);
    zone.setVisionType(Zone.VisionType.OFF);
    zone.setGrid(GridFactory.createGrid(Grid.GridType.Square));
    zone.getGrid().setSize(50);
    doNothing().when(zone).addDrawable(any(Pen.class), any(Drawable.class));
    return zone;
  }

  private static Zone newZoneAdded(Context c, String name) {
    Zone zone = newZone(name);
    c.campaign.putZone(zone);
    return zone;
  }

  private static void withSession(Consumer<Context> test) throws Exception {
    AtomicReference<Throwable> failure = new AtomicReference<>();
    SwingUtilities.invokeAndWait(
        () -> {
          try (var mt = mockStatic(MapTool.class);
              var factory = mockStatic(ZoneFactory.class)) {
            Context c = new Context();
            mt.when(MapTool::getPlayer).thenReturn(c.player);
            mt.when(MapTool::getCampaign).thenReturn(c.campaign);
            mt.when(MapTool::getFrame).thenReturn(c.frame);
            mt.when(MapTool::getClient).thenReturn(mock(MapToolClient.class));
            mt.when(MapTool::getServerPolicy).thenReturn(c.policy);
            mt.when(MapTool::serverCommand).thenReturn(c.server);
            mt.when(() -> MapTool.addZone(any(Zone.class), eq(false)))
                .thenAnswer(
                    invocation -> {
                      c.campaign.putZone(invocation.getArgument(0));
                      return null;
                    });
            factory.when(ZoneFactory::createZone).thenAnswer(invocation -> newZone("Generated"));
            test.accept(c);
          } catch (Throwable exception) {
            failure.set(exception);
          }
        });
    if (failure.get() != null) throw new AssertionError("World test failed on EDT", failure.get());
  }

  private static class Context {
    final Campaign campaign = new Campaign();
    final Zone zone = newZone("Town map");
    final LocalPlayer player = mock(LocalPlayer.class);
    final ServerPolicy policy = new ServerPolicy();
    final MapToolFrame frame = mock(MapToolFrame.class, RETURNS_DEEP_STUBS);
    final ServerCommand server = mock(ServerCommand.class);
    final MapToolMcpService service = new MapToolMcpService();

    Context() {
      when(player.getName()).thenReturn("Alice");
      when(player.isGM()).thenReturn(true);
      campaign.putZone(zone);
      ZoneRenderer renderer = mock(ZoneRenderer.class);
      when(renderer.getZone()).thenReturn(zone);
      when(frame.getCurrentZoneRenderer()).thenReturn(renderer);
      when(frame.getZoneRenderer(any(Zone.class)))
          .thenAnswer(
              invocation -> {
                ZoneRenderer r = mock(ZoneRenderer.class);
                when(r.getZone()).thenReturn(invocation.getArgument(0));
                return r;
              });
      doAnswer(
              invocation -> {
                campaign.getZone(invocation.getArgument(0)).putToken(invocation.getArgument(1));
                return null;
              })
          .when(server)
          .putToken(any(GUID.class), any(Token.class));
      doAnswer(
              invocation -> {
                campaign.getZone(invocation.getArgument(0)).removeToken(invocation.getArgument(1));
                return null;
              })
          .when(server)
          .removeToken(any(GUID.class), any(GUID.class));
      doAnswer(
              invocation -> {
                campaign
                    .getZone(invocation.getArgument(0))
                    .addDrawable(
                        new DrawnElement(invocation.getArgument(2), invocation.getArgument(1)));
                return null;
              })
          .when(server)
          .draw(any(GUID.class), any(Pen.class), any(Drawable.class));
      doAnswer(
              invocation -> {
                Zone target = invocation.getArgument(0);
                for (Zone.TopologyType type : (Set<Zone.TopologyType>) invocation.getArgument(3)) {
                  target.updateMaskTopology(
                      invocation.getArgument(1), invocation.getArgument(2), type);
                }
                return null;
              })
          .when(server)
          .updateMaskTopology(any(Zone.class), any(Area.class), anyBoolean(), anySet());
    }

    JsonObject call(String tool, JsonObject args) {
      return service.callTool(tool, args);
    }

    JsonObject create(String name, String kind, Zone map, JsonObject parent) {
      JsonObject args = args("name", name, "kind", kind, "visible", true);
      if (map != null) args.addProperty("mapId", map.getId().toString());
      if (parent != null) args.addProperty("parentId", id(parent));
      return call("maptool_create_location", args);
    }

    Token character(Zone map, String name, Token.Type type, int x, int y) {
      Token token = new Token();
      token.setName(name);
      token.setType(type);
      token.setLayer(Zone.Layer.TOKEN);
      token.setVisible(true);
      token.setWidth(50);
      token.setHeight(50);
      token.setSnapToGrid(false);
      token.setSnapToScale(false);
      token.setX(x);
      token.setY(y);
      map.putToken(token);
      return token;
    }
  }
}
