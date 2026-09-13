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
import java.util.ArrayList;
import net.rptools.maptool.client.MapTool;
import net.rptools.maptool.model.AssetManager;
import net.rptools.maptool.model.Campaign;
import net.rptools.maptool.model.Token;
import net.rptools.maptool.model.Zone;
import net.rptools.maptool.model.player.LocalPlayer;
import net.rptools.maptool.server.ServerCommand;
import org.junit.jupiter.api.Test;

class McpWorldEventsTest {
  private static final String WORLD = "00000000000000000000000000000001";
  private static final String CITY = "00000000000000000000000000000002";
  private static final String HOUSE = "00000000000000000000000000000003";
  private static final String ROOM = "00000000000000000000000000000004";
  private static final String EXIT = "00000000000000000000000000000005";
  private static final String ENTRY = "00000000000000000000000000000006";

  @Test
  void cityFireDestroysUnbuiltInteriorsAndPersistsAcrossSerialization() {
    JsonObject world = fixture();
    McpWorldEvents.ignite(world, CITY, 50);
    assertEquals("planned", at(world, HOUSE).get("status").getAsString());
    McpWorldEvents.advance(world, 1);
    assertEquals("burning", at(world, ROOM).get("status").getAsString());
    assertEquals(50, at(world, HOUSE).get("integrity").getAsInt());
    assertFalse(at(world, HOUSE).has("mapId"));
    JsonObject restored = JsonParser.parseString(world.toString()).getAsJsonObject();
    McpWorldEvents.advance(restored, 1);
    for (String id : new String[] {CITY, HOUSE, ROOM}) {
      assertEquals("destroyed", at(restored, id).get("status").getAsString());
      assertEquals(0, at(restored, id).get("integrity").getAsInt());
      assertFalse(at(restored, id).has("fire"));
      assertThrows(
          IllegalArgumentException.class, () -> McpWorldService.requireEnterable(at(restored, id)));
    }
    assertEquals("active", at(restored, WORLD).get("status").getAsString());
    assertEquals(2, restored.get("tick").getAsInt());
  }

  @Test
  void nonflammableBuildingProtectsItsInteriorButNotOtherBurningLocations() {
    JsonObject world = fixture();
    at(world, HOUSE).addProperty("flammable", false);
    McpWorldEvents.ignite(world, CITY, 100);
    McpWorldEvents.advance(world, 1);
    assertEquals("destroyed", at(world, CITY).get("status").getAsString());
    assertEquals("planned", at(world, HOUSE).get("status").getAsString());
    assertEquals("planned", at(world, ROOM).get("status").getAsString());
    assertThrows(IllegalArgumentException.class, () -> McpWorldEvents.ignite(world, HOUSE, 10));
    McpWorldEvents.ignite(world, ROOM, 10);
    McpWorldEvents.advance(world, 1);
    assertEquals(90, at(world, ROOM).get("integrity").getAsInt());
  }

  @Test
  void spreadBarrierStillBurnsItselfAndProtectsChildren() {
    JsonObject world = fixture();
    at(world, HOUSE).addProperty("fireSpread", false);
    McpWorldEvents.ignite(world, CITY, 10);
    McpWorldEvents.advance(world, 1);
    assertEquals("burning", at(world, HOUSE).get("status").getAsString());
    assertEquals("planned", at(world, ROOM).get("status").getAsString());
  }

  @Test
  void extinguishingCascadeStopsDescendantsWithoutRepairOrResurrection() {
    JsonObject world = fixture();
    McpWorldEvents.ignite(world, CITY, 20);
    McpWorldEvents.advance(world, 1);
    McpWorldEvents.extinguish(world, CITY, false);
    assertEquals("planned", at(world, CITY).get("status").getAsString());
    assertEquals("burning", at(world, HOUSE).get("status").getAsString());
    McpWorldEvents.extinguish(world, CITY, true);
    McpWorldEvents.advance(world, 2);
    assertEquals(80, at(world, HOUSE).get("integrity").getAsInt());
    assertEquals("planned", at(world, HOUSE).get("status").getAsString());
    McpWorldEvents.ignite(world, CITY, 100);
    McpWorldEvents.advance(world, 1);
    McpWorldEvents.extinguish(world, CITY, true);
    assertEquals("destroyed", at(world, HOUSE).get("status").getAsString());
    assertThrows(IllegalArgumentException.class, () -> McpWorldEvents.ignite(world, HOUSE, 1));
    JsonObject repair = new JsonObject();
    repair.addProperty("locationId", HOUSE);
    repair.addProperty("integrity", 100);
    assertThrows(IllegalArgumentException.class, () -> McpWorldEvents.configure(world, repair));
  }

  @Test
  void destructionDisablesInboundPortalButPreservesOutboundEvacuation() {
    JsonObject world = fixture();
    portal(world, ENTRY, WORLD, CITY);
    portal(world, EXIT, CITY, WORLD);
    McpWorldEvents.ignite(world, CITY, 100);
    McpWorldEvents.advance(world, 1);
    assertFalse(
        world.getAsJsonObject("portals").getAsJsonObject(ENTRY).get("enabled").getAsBoolean());
    assertTrue(
        world.getAsJsonObject("portals").getAsJsonObject(EXIT).get("enabled").getAsBoolean());
  }

  @Test
  void engineIsDeterministicAndRepeatedIgnitionDoesNotResetDuration() {
    JsonObject one = fixture();
    JsonObject two = one.deepCopy();
    McpWorldEvents.ignite(one, CITY, 3);
    McpWorldEvents.ignite(two, CITY, 3);
    assertEquals(McpWorldEvents.advance(one, 4), McpWorldEvents.advance(two, 4));
    JsonObject before = one.deepCopy();
    McpWorldEvents.ignite(one, CITY, 3);
    assertEquals(before, one);
    assertEquals(4, at(one, CITY).getAsJsonObject("fire").get("ticks").getAsInt());
  }

  @Test
  void retainedEventLogIsBoundedAndSequenceContinuesAcrossReload() {
    JsonObject world = fixture();
    McpWorldEvents.ignite(world, CITY, 1);
    for (int i = 0; i < 4; i++) McpWorldEvents.advance(world, 20);
    assertEquals(100, world.getAsJsonArray("events").size());
    long previous = world.get("eventSequence").getAsLong();
    assertTrue(previous > 100);
    JsonObject restored = JsonParser.parseString(world.toString()).getAsJsonObject();
    McpWorldEvents.advance(restored, 1);
    assertEquals(previous + 3, restored.get("eventSequence").getAsLong());
    assertEquals(100, restored.getAsJsonArray("events").size());
  }

  @Test
  void dryRunPreviewsConsequencesWithoutWritingCampaignOrRendering() {
    JsonObject world = fixture();
    McpWorldEvents.ignite(world, CITY, 100);
    JsonObject root = new JsonObject();
    root.add("world", world);
    JsonObject saved = root.deepCopy();
    Campaign campaign = new Campaign();
    try (var store = mockStatic(McpCampaignStore.class);
        var maptool = mockStatic(MapTool.class)) {
      store.when(McpCampaignStore::read).thenReturn(root);
      maptool.when(MapTool::getCampaign).thenReturn(campaign);
      JsonObject args = new JsonObject();
      args.addProperty("dryRun", true);
      JsonObject report = McpWorldEvents.callTool("maptool_advance_world", args);
      assertEquals(
          saved, root, "Even a store returning its live object must not be mutated by a preview");
      assertEquals(3, report.getAsJsonArray("affectedLocations").size());
      assertEquals(
          "destroyed",
          report
              .getAsJsonArray("affectedLocations")
              .get(0)
              .getAsJsonObject()
              .get("status")
              .getAsString());
      assertTrue(report.get("dryRun").getAsBoolean());
      store.verify(() -> McpCampaignStore.write(any()), never());
      maptool.verify(MapTool::serverCommand, never());
    }
  }

  @Test
  void hazardToolsRequireGmEvenWhenPreviewingOrReadingEvents() {
    LocalPlayer player = mock(LocalPlayer.class);
    when(player.isGM()).thenReturn(false);
    try (var maptool = mockStatic(MapTool.class)) {
      maptool.when(MapTool::getPlayer).thenReturn(player);
      for (String tool : new String[] {"maptool_advance_world", "maptool_get_world_events"}) {
        assertThrows(
            SecurityException.class, () -> McpWorldEvents.callTool(tool, new JsonObject()));
      }
      maptool.verify(MapTool::getCampaign, never());
    }
  }

  @Test
  void evacuatedCharacterExposureClearsWithoutAlteringHpOrIdentity() {
    JsonObject world = fixture();
    Zone safe = new Zone();
    at(world, WORLD).addProperty("mapId", safe.getId().toString());
    Token hero = new Token();
    hero.setName("Hero");
    hero.setType(Token.Type.PC);
    hero.setLayer(Zone.Layer.TOKEN);
    hero.setProperty("HP", 12);
    hero.setProperty(McpWorldEvents.EXPOSURE, "{\"hazard\":\"burning\"}");
    safe.putToken(hero);
    Campaign campaign = new Campaign();
    campaign.putZone(safe);
    ServerCommand server = mock(ServerCommand.class);
    ArrayList<Token> updates = new ArrayList<>();
    doAnswer(
            invocation -> {
              updates.add(invocation.getArgument(1));
              return null;
            })
        .when(server)
        .putToken(any(), any());
    try (var maptool = mockStatic(MapTool.class)) {
      maptool.when(MapTool::getCampaign).thenReturn(campaign);
      maptool.when(MapTool::serverCommand).thenReturn(server);
      McpWorldEvents.syncLocationVisuals(world);
      assertEquals(1, updates.size());
      Token updated = updates.get(0);
      assertEquals(hero.getId(), updated.getId());
      assertEquals(12, updated.getProperty("HP"));
      assertNull(updated.getProperty(McpWorldEvents.EXPOSURE));
      assertNotNull(
          hero.getProperty(McpWorldEvents.EXPOSURE), "Only a detached copy may be published");
    }
  }

  @Test
  void nativeHazardMarkerShowsFireThenRuinsAndArchivesOnlyAfterEvacuation() {
    JsonObject world = fixture();
    Zone zone = new Zone();
    zone.setVisible(true);
    at(world, CITY).addProperty("mapId", zone.getId().toString());
    Token npc = new Token();
    npc.setType(Token.Type.NPC);
    npc.setLayer(Zone.Layer.TOKEN);
    npc.setProperty("HP", 8);
    zone.putToken(npc);
    Campaign campaign = new Campaign();
    campaign.putZone(zone);
    ServerCommand server = mock(ServerCommand.class);
    doAnswer(
            invocation -> {
              zone.putToken(invocation.getArgument(1));
              return null;
            })
        .when(server)
        .putToken(any(), any());
    try (var maptool = mockStatic(MapTool.class);
        var assets = mockStatic(AssetManager.class)) {
      maptool.when(MapTool::getCampaign).thenReturn(campaign);
      maptool.when(MapTool::serverCommand).thenReturn(server);
      McpWorldEvents.ignite(world, CITY, 100);
      McpWorldEvents.syncLocationVisuals(world);
      Token marker =
          zone.getAllTokens().stream()
              .filter(t -> t.getProperty(McpWorldEvents.HAZARD_MARKER) != null)
              .findFirst()
              .orElseThrow();
      assertTrue(marker.getLabel().startsWith("Fire"));
      assertEquals(Zone.Layer.OBJECT, marker.getLayer());
      assertNotNull(marker.getImageAssetId());
      assertEquals(8, zone.getToken(npc.getId()).getProperty("HP"));
      assertTrue(
          zone.getToken(npc.getId())
              .getProperty(McpWorldEvents.EXPOSURE)
              .toString()
              .contains("burning"));
      McpWorldEvents.advance(world, 1);
      McpWorldEvents.syncLocationVisuals(world);
      assertTrue(zone.getToken(marker.getId()).getLabel().startsWith("Ruins"));
      assertTrue(zone.isVisible(), "An occupied ruined map must remain available for evacuation");
      assertEquals(8, zone.getToken(npc.getId()).getProperty("HP"));
      zone.removeToken(npc.getId());
      McpWorldEvents.syncLocationVisuals(world);
      assertFalse(zone.isVisible());
      verify(server).setZoneVisibility(zone.getId(), false);
      assets.verify(() -> AssetManager.putAsset(any()), times(2));
    }
  }

  @Test
  void invalidTickAndIntensityRequestsDoNotChangeState() {
    JsonObject world = fixture();
    JsonObject before = world.deepCopy();
    for (int value : new int[] {-1, 0, 21}) {
      assertThrows(IllegalArgumentException.class, () -> McpWorldEvents.advance(world, value));
    }
    for (int value : new int[] {0, 101}) {
      assertThrows(IllegalArgumentException.class, () -> McpWorldEvents.ignite(world, CITY, value));
    }
    assertEquals(before, world);
  }

  private static JsonObject fixture() {
    JsonObject world = new JsonObject();
    world.addProperty("schemaVersion", 1);
    world.add("locations", new JsonObject());
    world.add("portals", new JsonObject());
    world.add("followers", new JsonObject());
    add(world, WORLD, "World", "world", null);
    add(world, CITY, "City", "city", WORLD);
    add(world, HOUSE, "House", "building", CITY);
    add(world, ROOM, "Room", "room", HOUSE);
    at(world, WORLD).addProperty("status", "active");
    return world;
  }

  private static void add(JsonObject world, String id, String name, String kind, String parentId) {
    JsonObject location = new JsonObject();
    location.addProperty("id", id);
    location.addProperty("name", name);
    location.addProperty("kind", kind);
    location.addProperty("status", "planned");
    location.addProperty("visible", true);
    location.addProperty("flammable", true);
    location.addProperty("entryX", 0);
    location.addProperty("entryY", 0);
    if (parentId != null) location.addProperty("parentId", parentId);
    location.add("blueprint", new JsonObject());
    world.getAsJsonObject("locations").add(id, location);
  }

  private static JsonObject at(JsonObject world, String id) {
    return world.getAsJsonObject("locations").getAsJsonObject(id);
  }

  private static void portal(JsonObject world, String id, String source, String target) {
    JsonObject portal = new JsonObject();
    portal.addProperty("id", id);
    portal.addProperty("sourceLocationId", source);
    portal.addProperty("targetLocationId", target);
    portal.addProperty("enabled", true);
    world.getAsJsonObject("portals").add(id, portal);
  }
}
