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
import static org.mockito.Mockito.*;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import javax.swing.SwingUtilities;
import net.rptools.maptool.client.MapTool;
import net.rptools.maptool.client.MapToolClient;
import net.rptools.maptool.client.events.PlayerConnected;
import net.rptools.maptool.client.events.PlayerStatusChanged;
import net.rptools.maptool.client.events.ServerDisconnected;
import net.rptools.maptool.events.MapToolEventBus;
import net.rptools.maptool.model.Campaign;
import net.rptools.maptool.model.Grid;
import net.rptools.maptool.model.GridFactory;
import net.rptools.maptool.model.Token;
import net.rptools.maptool.model.Zone;
import net.rptools.maptool.model.player.LocalPlayer;
import org.junit.jupiter.api.Test;

class McpFollowerControllerTest {
  @Test
  void nativeMutableMovementAndRepeatedNotificationsFollowOnceAfterPublication() throws Exception {
    withSession(
        context -> {
          try (var controller = context.controller()) {
            context.move(context.hero, 40, 60);
            context.zone.putToken(context.hero);
            assertTrue(context.moves.isEmpty(), "Following waits for native leader publication");
            controller.flushPending();
            assertEquals(
                List.of(new Move(context.hero.getId().toString(), 0, 0, 40, 60)), context.moves);
            context.zone.putToken(context.hero);
            controller.flushPending();
            assertEquals(
                1, context.moves.size(), "Same-position server echoes cannot repeat following");
          }
        });
  }

  @Test
  void multipleMovesInOneEdtTurnCoalesceToOneExactDelta() throws Exception {
    withSession(
        context -> {
          try (var controller = context.controller()) {
            context.move(context.hero, 10, 10);
            context.move(context.hero, 75, 25);
            controller.flushPending();
            assertEquals(
                List.of(new Move(context.hero.getId().toString(), 0, 0, 75, 25)), context.moves);
          }
        });
  }

  @Test
  void worldServiceMovingWholeFollowerChainDoesNotRecursivelyFollowAgain() throws Exception {
    withSession(
        context -> {
          Token follower = context.addToken("Follower", 100, 100);
          Token second = context.addToken("Second follower", 200, 200);
          context.afterMove =
              move -> {
                context.move(follower, follower.getX() + 25, follower.getY());
                context.move(second, second.getX() + 25, second.getY());
              };
          try (var controller = context.controller()) {
            context.move(context.hero, 25, 0);
            controller.flushPending();
            controller.flushPending();
            assertEquals(1, context.moves.size());
            assertEquals(125, follower.getX());
            assertEquals(225, second.getX());
          }
        });
  }

  @Test
  void onlyGmHostOrGmPersonalServerHasAutomaticAuthority() throws Exception {
    withSession(
        context -> {
          context.hosting.set(false);
          try (var controller = context.controller()) {
            assertFalse(
                controller.reconcile(), "A connected remote GM must not duplicate host moves");
            context.move(context.hero, 10, 0);
            controller.flushPending();
            assertTrue(context.moves.isEmpty());
            context.personal.set(true);
            assertTrue(controller.reconcile());
            context.move(context.hero, 20, 0);
            controller.flushPending();
            assertEquals(
                List.of(new Move(context.hero.getId().toString(), 10, 0, 20, 0)), context.moves);
            context.gm.set(false);
            assertFalse(controller.reconcile(), "A player never gets automatic NPC authority");
          }
        });
  }

  @Test
  void roleRevocationDropsQueuedMovementAndRestorationStartsFresh() throws Exception {
    withSession(
        context -> {
          try (var controller = context.controller()) {
            context.move(context.hero, 20, 0);
            context.gm.set(false);
            context.post(new PlayerStatusChanged(context.player));
            controller.flushPending();
            assertTrue(context.moves.isEmpty());
            context.gm.set(true);
            context.post(new PlayerStatusChanged(context.player));
            context.move(context.hero, 30, 0);
            controller.flushPending();
            assertEquals(
                List.of(new Move(context.hero.getId().toString(), 20, 0, 30, 0)), context.moves);
          }
        });
  }

  @Test
  void unrelatedPlayerLoadingStatusPreservesQueuedMovement() throws Exception {
    withSession(
        context -> {
          try (var controller = context.controller()) {
            context.move(context.hero, 20, 0);
            LocalPlayer remote = mock(LocalPlayer.class);
            when(remote.getName()).thenReturn("Another player");
            context.post(new PlayerStatusChanged(remote));
            controller.flushPending();
            assertEquals(
                List.of(new Move(context.hero.getId().toString(), 0, 0, 20, 0)), context.moves);
          }
        });
  }

  @Test
  void reopeningSameCampaignIdDiscardsPreviousObjectPositions() throws Exception {
    withSession(
        context -> {
          try (var controller = context.controller()) {
            context.move(context.hero, 80, 0);
            Campaign reopened = new Campaign(context.campaign.get());
            context.campaign.set(reopened);
            controller.flushPending();
            assertTrue(context.moves.isEmpty());
            Zone reloadedZone = reopened.getZone(context.zone.getId());
            Token reloadedHero = reloadedZone.getToken(context.hero.getId());
            reloadedHero.setX(90);
            reloadedZone.putToken(reloadedHero);
            controller.flushPending();
            assertEquals(
                List.of(new Move(context.hero.getId().toString(), 80, 0, 90, 0)), context.moves);
          }
        });
  }

  @Test
  void serverDisconnectCancelsPendingUntilLocalReconnect() throws Exception {
    withSession(
        context -> {
          try (var controller = context.controller()) {
            context.move(context.hero, 25, 0);
            context.post(new ServerDisconnected());
            controller.flushPending();
            assertTrue(context.moves.isEmpty());
            context.move(context.hero, 50, 0);
            controller.flushPending();
            assertTrue(context.moves.isEmpty());
            context.post(new PlayerConnected(context.player, true));
            context.move(context.hero, 75, 0);
            controller.flushPending();
            assertEquals(
                List.of(new Move(context.hero.getId().toString(), 50, 0, 75, 0)), context.moves);
          }
        });
  }

  @Test
  void portalTransferRebaselinesStableTokenIdWithoutFollowingItsCoordinateJump() throws Exception {
    withSession(
        context -> {
          Zone destination = new Zone();
          context.campaign.get().putZone(destination);
          try (var controller = context.controller()) {
            context.move(context.hero, 50, 0);
            Token transferred = new Token(context.hero, true);
            transferred.setX(1000);
            transferred.setY(2000);
            destination.putToken(transferred);
            context.zone.removeToken(context.hero.getId());
            controller.flushPending();
            assertTrue(context.moves.isEmpty());
            transferred.setX(1050);
            destination.putToken(transferred);
            controller.flushPending();
            assertEquals(
                List.of(new Move(context.hero.getId().toString(), 1000, 2000, 1050, 2000)),
                context.moves);
          }
        });
  }

  @Test
  void closingControllerCancelsPendingWorkAndUnsubscribes() throws Exception {
    withSession(
        context -> {
          var controller = context.controller();
          context.move(context.hero, 50, 0);
          controller.close();
          controller.flushPending();
          context.move(context.hero, 100, 0);
          controller.flushPending();
          assertTrue(context.moves.isEmpty());
        });
  }

  @Test
  void propertyUpdatesAndNewTokensDoNotCountAsMovement() throws Exception {
    withSession(
        context -> {
          try (var controller = context.controller()) {
            context.hero.setName("Renamed");
            context.zone.putToken(context.hero);
            context.addToken("Appeared", 100, 500);
            controller.flushPending();
            assertTrue(context.moves.isEmpty());
          }
        });
  }

  private record Move(String id, int oldX, int oldY, int x, int y) {}

  private static void withSession(Consumer<Context> test) throws Exception {
    AtomicReference<Throwable> failure = new AtomicReference<>();
    SwingUtilities.invokeAndWait(
        () -> {
          try (var mapTool = mockStatic(MapTool.class)) {
            Context context = new Context();
            when(context.player.isGM()).thenAnswer(invocation -> context.gm.get());
            mapTool.when(MapTool::getPlayer).thenReturn(context.player);
            mapTool.when(MapTool::getCampaign).thenAnswer(invocation -> context.campaign.get());
            mapTool.when(MapTool::getClient).thenReturn(mock(MapToolClient.class));
            mapTool.when(MapTool::isHostingServer).thenAnswer(invocation -> context.hosting.get());
            mapTool
                .when(MapTool::isPersonalServer)
                .thenAnswer(invocation -> context.personal.get());
            context.hero = context.addToken("Hero", 0, 0);
            test.accept(context);
          } catch (Throwable exception) {
            failure.set(exception);
          }
        });
    if (failure.get() != null) throw new AssertionError("EDT follower test failed", failure.get());
  }

  private static class Context {
    final AtomicReference<Campaign> campaign = new AtomicReference<>(new Campaign());
    final Zone zone = new Zone();
    final LocalPlayer player = mock(LocalPlayer.class);
    final AtomicBoolean gm = new AtomicBoolean(true);
    final AtomicBoolean hosting = new AtomicBoolean(true);
    final AtomicBoolean personal = new AtomicBoolean(false);
    final List<Move> moves = new ArrayList<>();
    Consumer<Move> afterMove = move -> {};
    Token hero;

    Context() {
      zone.setGrid(GridFactory.createGrid(Grid.GridType.Square));
      campaign.get().putZone(zone);
    }

    McpFollowerController controller() {
      return new McpFollowerController(
          (map, original, updated) -> {
            assertTrue(SwingUtilities.isEventDispatchThread());
            assertSame(map.getToken(updated.getId()).getId(), updated.getId());
            Move move =
                new Move(
                    updated.getId().toString(),
                    original.getX(),
                    original.getY(),
                    updated.getX(),
                    updated.getY());
            moves.add(move);
            afterMove.accept(move);
            JsonObject result = new JsonObject();
            result.add("moved", new JsonArray());
            result.add("blocked", new JsonArray());
            return result;
          },
          false);
    }

    Token addToken(String name, int x, int y) {
      Token token = new Token();
      token.setName(name);
      token.setLayer(Zone.Layer.TOKEN);
      token.setX(x);
      token.setY(y);
      zone.putToken(token);
      return token;
    }

    void move(Token token, int x, int y) {
      token.setX(x);
      token.setY(y);
      zone.putToken(token);
    }

    void post(Object event) {
      new MapToolEventBus().getMainEventBus().post(event);
    }
  }
}
