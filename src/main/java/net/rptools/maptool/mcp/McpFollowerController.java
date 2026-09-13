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

import com.google.common.eventbus.Subscribe;
import com.google.gson.JsonObject;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import net.rptools.maptool.client.MapTool;
import net.rptools.maptool.client.events.PlayerConnected;
import net.rptools.maptool.client.events.PlayerStatusChanged;
import net.rptools.maptool.client.events.ServerDisconnected;
import net.rptools.maptool.client.events.ZoneLoaded;
import net.rptools.maptool.events.MapToolEventBus;
import net.rptools.maptool.model.Campaign;
import net.rptools.maptool.model.GUID;
import net.rptools.maptool.model.Token;
import net.rptools.maptool.model.Zone;
import net.rptools.maptool.model.zones.TokensAdded;
import net.rptools.maptool.model.zones.TokensChanged;
import net.rptools.maptool.model.zones.TokensRemoved;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/** Observes native movement on the GM host, then moves configured NPC followers on the EDT. */
public final class McpFollowerController implements AutoCloseable {
  private static final Logger log = LogManager.getLogger(McpFollowerController.class);
  private static volatile McpFollowerController instance;
  private static volatile boolean requested;

  @FunctionalInterface
  interface FollowerMover {
    JsonObject move(Zone zone, Token original, Token updated);
  }

  private record Position(Zone zone, GUID tokenId, int x, int y) {
    static Position of(Zone zone, Token token) {
      return new Position(zone, token.getId(), token.getX(), token.getY());
    }
  }

  private final FollowerMover mover;
  private final Map<GUID, Position> positions = new HashMap<>();
  private final Map<GUID, Position> pending = new LinkedHashMap<>();
  private final Timer timer;
  private Campaign campaign;
  private Object client;
  private volatile boolean closed;
  private volatile boolean disconnected;
  private boolean active;
  private boolean applying;
  private boolean scheduled;
  private long generation;
  private JsonObject lastResult;

  public static void start() {
    requested = true;
    onEdt(
        () -> {
          if (requested && (instance == null || instance.closed)) {
            instance = new McpFollowerController(McpWorldService::afterLeaderMoved, true);
          }
        });
  }

  public static void stop() {
    requested = false;
    McpFollowerController current = instance;
    if (current != null) current.close();
  }

  /** Must be called on the EDT, like the MCP domain service itself. */
  public static boolean isAuthoritativeActive() {
    requireEdt();
    McpFollowerController current = instance;
    return current != null && current.reconcile();
  }

  /** The last automatic result is only returned to a GM; it may contain hidden NPC identifiers. */
  public static JsonObject status() {
    requireEdt();
    JsonObject result = new JsonObject();
    result.addProperty("active", isAuthoritativeActive());
    if (MapTool.getPlayer() != null && MapTool.getPlayer().isGM()) {
      result.addProperty("mode", "nativeMovementOnGmHost");
      McpFollowerController current = instance;
      if (current != null && current.lastResult != null) {
        result.add("lastResult", current.lastResult.deepCopy());
      }
    }
    return result;
  }

  McpFollowerController(FollowerMover mover, boolean startTimer) {
    requireEdt();
    this.mover = mover;
    timer = new Timer(1000, event -> reconcile());
    timer.setCoalesce(true);
    new MapToolEventBus().getMainEventBus().register(this);
    reconcile();
    if (startTimer) timer.start();
  }

  /** Reconcile object identity, not campaign GUID: reopening a save must discard old positions. */
  boolean reconcile() {
    requireEdt();
    if (closed) return false;
    Campaign currentCampaign = MapTool.getCampaign();
    Object currentClient = MapTool.getClient();
    boolean changed = campaign != currentCampaign || client != currentClient;
    if (changed) disconnected = false;
    boolean authorized =
        !disconnected
            && currentCampaign != null
            && MapTool.getPlayer() != null
            && MapTool.getPlayer().isGM()
            && (MapTool.isHostingServer() || MapTool.isPersonalServer());
    if (changed || active != authorized) {
      reset();
      campaign = currentCampaign;
      client = currentClient;
      active = authorized;
      if (active) campaign.getZones().forEach(this::seedZone);
    }
    return active;
  }

  private void reset() {
    positions.clear();
    pending.clear();
    scheduled = false;
    lastResult = null;
    generation++;
  }

  private boolean currentZone(Zone zone) {
    return active && campaign != null && campaign.getZone(zone.getId()) == zone;
  }

  private void seedZone(Zone zone) {
    if (!currentZone(zone)) return;
    for (Token token : zone.getAllTokens()) {
      if (token.getLayer() == Zone.Layer.TOKEN) {
        positions.put(token.getId(), Position.of(zone, token));
      }
    }
  }

  @Subscribe
  public void tokensChanged(TokensChanged event) {
    // Event tokens can be mutable or arrive on a network thread. Carry only stable identifiers,
    // then inspect the live model on the EDT. Repeated notifications coalesce to its final
    // position.
    List<GUID> ids = event.tokens().stream().map(Token::getId).toList();
    onEdt(() -> changed(event.zone(), ids));
  }

  private void changed(Zone zone, List<GUID> ids) {
    if (!reconcile() || !currentZone(zone)) return;
    for (GUID id : ids) {
      Token token = zone.getToken(id);
      if (token == null || token.getLayer() != Zone.Layer.TOKEN) continue;
      Position next = Position.of(zone, token);
      Position before = positions.put(id, next);
      if (applying) {
        // The world service already follows the complete chain, so its own token writes must not
        // trigger another traversal. Also discard any older queued move superseded by this write.
        pending.remove(id);
      } else if (before != null
          && before.zone() == zone
          && (before.x() != next.x() || before.y() != next.y())) {
        pending.putIfAbsent(id, before);
      }
    }
    if (!pending.isEmpty() && !scheduled) {
      scheduled = true;
      long scheduledGeneration = generation;
      SwingUtilities.invokeLater(
          () -> {
            if (scheduledGeneration == generation) flushPending();
          });
    }
  }

  /**
   * Deferred until after native leader publication; package visibility permits deterministic tests.
   */
  void flushPending() {
    requireEdt();
    scheduled = false;
    if (!reconcile()) return;
    while (reconcile() && !pending.isEmpty()) {
      GUID id = pending.keySet().iterator().next();
      Position before = pending.remove(id);
      Zone zone = before.zone();
      if (!currentZone(zone)) continue;
      Token current = zone.getToken(id);
      Position recorded = positions.get(id);
      // Portal transfers use native add/remove in different zones. Never translate their jump.
      if (current == null
          || recorded == null
          || recorded.zone() != zone
          || current.getLayer() != Zone.Layer.TOKEN
          || (before.x() == current.getX() && before.y() == current.getY())) continue;
      Token original = new Token(current, true);
      original.setX(before.x());
      original.setY(before.y());
      Token updated = new Token(current, true);
      applying = true;
      try {
        JsonObject result = mover.move(zone, original, updated);
        lastResult = result.deepCopy();
        lastResult.addProperty("leaderId", id.toString());
        lastResult.addProperty("mapId", zone.getId().toString());
        if (result.has("blocked") && !result.getAsJsonArray("blocked").isEmpty()) {
          log.info("MapTool MCP followers blocked: {}", lastResult);
        }
      } catch (RuntimeException exception) {
        lastResult = new JsonObject();
        lastResult.addProperty("leaderId", id.toString());
        lastResult.addProperty("mapId", zone.getId().toString());
        lastResult.addProperty(
            "error", "Following failed; inspect token positions before retrying.");
        lastResult.addProperty("mayHaveApplied", true);
        log.error(
            "MapTool MCP automatic following failed; some followers may have moved", exception);
        // Rebaseline after a partial native publication; do not repeat an uncertain mutation.
        pending.clear();
        seedZone(zone);
      } finally {
        applying = false;
      }
    }
  }

  @Subscribe
  public void tokensAdded(TokensAdded event) {
    List<GUID> ids = event.tokens().stream().map(Token::getId).toList();
    onEdt(
        () -> {
          if (!reconcile() || !currentZone(event.zone())) return;
          for (GUID id : ids) {
            Token current = event.zone().getToken(id);
            if (current != null && current.getLayer() == Zone.Layer.TOKEN) {
              positions.put(id, Position.of(event.zone(), current));
              pending.remove(id);
            }
          }
        });
  }

  @Subscribe
  public void tokensRemoved(TokensRemoved event) {
    List<GUID> ids = event.tokens().stream().map(Token::getId).toList();
    onEdt(
        () -> {
          if (!reconcile()) return;
          for (GUID id : ids) {
            Position recorded = positions.get(id);
            if (recorded != null && recorded.zone() == event.zone()) positions.remove(id);
            Position queued = pending.get(id);
            if (queued != null && queued.zone() == event.zone()) pending.remove(id);
          }
        });
  }

  @Subscribe
  public void zoneLoaded(ZoneLoaded event) {
    onEdt(
        () -> {
          if (reconcile()) seedZone(event.zone());
        });
  }

  @Subscribe
  public void playerStatusChanged(PlayerStatusChanged event) {
    // Native status also reports remote players loading maps. Only an actual authority/session
    // transition should discard queued movement; unrelated loading must leave it intact.
    onEdt(this::reconcile);
  }

  @Subscribe
  public void serverDisconnected(ServerDisconnected event) {
    disconnected = true;
    onEdt(this::reconcile);
  }

  @Subscribe
  public void playerConnected(PlayerConnected event) {
    if (event.isLocal())
      onEdt(
          () -> {
            disconnected = false;
            if (closed) return;
            reset();
            active = false;
            reconcile();
          });
  }

  @Override
  public void close() {
    if (closed) return;
    closed = true;
    onEdt(
        () -> {
          timer.stop();
          new MapToolEventBus().getMainEventBus().unregister(this);
          reset();
          active = false;
        });
  }

  private static void onEdt(Runnable action) {
    if (SwingUtilities.isEventDispatchThread()) action.run();
    else SwingUtilities.invokeLater(action);
  }

  private static void requireEdt() {
    if (!SwingUtilities.isEventDispatchThread()) {
      throw new IllegalStateException("NPC following must access campaign state on the Swing EDT");
    }
  }
}
