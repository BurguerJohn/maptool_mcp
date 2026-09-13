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

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.awt.Rectangle;
import java.awt.geom.Area;
import java.awt.geom.Point2D;
import java.util.Map;
import java.util.Set;
import net.rptools.maptool.model.SquareGrid;
import net.rptools.maptool.model.Token;
import net.rptools.maptool.model.Zone;
import net.rptools.maptool.model.player.Player;
import net.rptools.maptool.model.topology.Wall;
import net.rptools.maptool.server.ServerPolicy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class McpMovementTest {
  private Zone zone;
  private Token token;
  private Player player;
  private ServerPolicy policy;

  @BeforeEach
  void setUp() {
    zone = new Zone();
    var grid = new SquareGrid();
    grid.setSize(50);
    zone.setGrid(grid);
    token = new Token();
    token.setLayer(Zone.Layer.TOKEN);
    token.setWidth(50);
    token.setHeight(50);
    token.addOwner("Alice");
    zone.putToken(token);
    player = mock(Player.class);
    when(player.getName()).thenReturn("Alice");
    policy = mock(ServerPolicy.class);
  }

  @Test
  void movementRequiresTheSwingThread() {
    assertThrows(IllegalStateException.class, () -> McpMovement.validate(zone, token, 200, 0));
  }

  @Test
  void playerMayMoveOwnedVisibleToken() {
    assertDoesNotThrow(() -> McpMovement.validatePlayerAccess(token, player, policy, false));
  }

  @Test
  void looseTokenManagementDoesNotGrantMcpControlOfSomeoneElsesToken() {
    when(player.getName()).thenReturn("Bob");
    when(policy.useStrictTokenManagement()).thenReturn(false);
    assertThrows(
        SecurityException.class,
        () -> McpMovement.validatePlayerAccess(token, player, policy, false));
  }

  @Test
  void sharedOwnershipGrantsMovement() {
    when(player.getName()).thenReturn("Bob");
    token.setOwnedByAll(true);
    assertDoesNotThrow(() -> McpMovement.validatePlayerAccess(token, player, policy, false));
  }

  @Test
  void ownershipDoesNotPermitMovingObjectsOrHiddenTokens() {
    token.setLayer(Zone.Layer.OBJECT);
    assertThrows(
        SecurityException.class,
        () -> McpMovement.validatePlayerAccess(token, player, policy, false));
    token.setLayer(Zone.Layer.TOKEN);
    token.setVisible(false);
    assertThrows(
        SecurityException.class,
        () -> McpMovement.validatePlayerAccess(token, player, policy, false));
  }

  @Test
  void serverAndInitiativeLocksEachPreventMovement() {
    assertThrows(
        SecurityException.class,
        () -> McpMovement.validatePlayerAccess(token, player, policy, true));
    when(policy.isMovementLocked()).thenReturn(true);
    assertThrows(
        SecurityException.class,
        () -> McpMovement.validatePlayerAccess(token, player, policy, false));
  }

  @Test
  void clearMovementDoesNotMutateTheToken() {
    assertDoesNotThrow(() -> McpMovement.validatePath(zone, token, 200, 0, false, null));
    assertEquals(0, token.getX());
    assertEquals(0, token.getY());
    assertEquals(token, zone.getToken(token.getId()));
  }

  @Test
  void movementCannotJumpOverThinMaskBetweenClearEndpoints() {
    zone.getMaskTopology(Zone.TopologyType.MBL).add(new Area(new Rectangle(100, -50, 1, 150)));
    assertThrows(
        IllegalArgumentException.class,
        () -> McpMovement.validatePath(zone, token, 200, 0, false, null));
  }

  @Test
  void movementCannotJumpOverNativeWallAndOpeningItAllowsMovement() {
    zone.getWalls()
        .string(
            new Point2D.Double(100, -50), builder -> builder.push(new Point2D.Double(100, 100)));
    assertThrows(
        IllegalArgumentException.class,
        () -> McpMovement.validatePath(zone, token, 200, 0, false, null));
    var wall = zone.getWalls().getWalls().findFirst().orElseThrow();
    wall.setData(
        new Wall.Data(Wall.Direction.Both, Wall.MovementDirectionModifier.Disabled, Map.of()));
    assertDoesNotThrow(() -> McpMovement.validatePath(zone, token, 200, 0, false, null));
  }

  @Test
  void respectsTheServerPolicyForVisionBlockingMasks() {
    zone.getMaskTopology(Zone.TopologyType.WALL_VBL).add(new Area(new Rectangle(100, -50, 1, 150)));
    assertDoesNotThrow(() -> McpMovement.validatePath(zone, token, 200, 0, false, null));
    assertThrows(
        IllegalArgumentException.class,
        () -> McpMovement.validatePath(zone, token, 200, 0, true, null));
  }

  @Test
  void testsTheWholeFootprintWhenTheCenterLineIsClear() {
    // Token centers travel along y=25; the obstruction touches only the lower part of the token.
    zone.getMaskTopology(Zone.TopologyType.MBL).add(new Area(new Rectangle(100, 40, 10, 10)));
    assertThrows(
        IllegalArgumentException.class,
        () -> McpMovement.validatePath(zone, token, 200, 0, false, null));
  }

  @Test
  void permitsAnExactFitAlongAWall() {
    zone.getWalls()
        .string(new Point2D.Double(-50, 50), builder -> builder.push(new Point2D.Double(300, 50)));
    assertDoesNotThrow(() -> McpMovement.validatePath(zone, token, 200, 0, false, null));
  }

  @Test
  void cannotCrossHardFogEvenWhenBothEndpointsAreExposed() {
    var exposed = new Area(new Rectangle(-50, -50, 125, 150));
    exposed.add(new Area(new Rectangle(175, -50, 125, 150)));
    assertThrows(
        IllegalArgumentException.class,
        () -> McpMovement.validatePath(zone, token, 200, 0, false, exposed));
    assertThrows(
        IllegalArgumentException.class,
        () -> McpMovement.validatePath(zone, token, 200, 0, false, new Area()));
    exposed.add(new Area(new Rectangle(0, 0, 250, 50)));
    assertDoesNotThrow(() -> McpMovement.validatePath(zone, token, 200, 0, false, exposed));
  }

  @Test
  void blockingTerrainCannotBeSkippedAndConfiguredImmunityIsRespected() {
    var terrain = new Token();
    terrain.setX(100);
    terrain.setTerrainModifierOperation(Token.TerrainModifierOperation.BLOCK);
    zone.putToken(terrain);
    assertThrows(
        IllegalArgumentException.class,
        () -> McpMovement.validatePath(zone, token, 200, 0, false, null));
    token.setTerrainModifiersIgnored(Set.of(Token.TerrainModifierOperation.BLOCK));
    assertDoesNotThrow(() -> McpMovement.validatePath(zone, token, 200, 0, false, null));
  }

  @Test
  void ownTopologyDoesNotBlockMovementButAnotherTokensTopologyDoes() {
    token.setMaskTopology(Zone.TopologyType.MBL, new Area(new Rectangle(0, 0, 50, 50)));
    assertDoesNotThrow(() -> McpMovement.validatePath(zone, token, 200, 0, false, null));
    var blocker = new Token();
    blocker.setX(100);
    blocker.setWidth(50);
    blocker.setHeight(50);
    blocker.setMaskTopology(Zone.TopologyType.MBL, new Area(new Rectangle(0, 0, 50, 50)));
    zone.putToken(blocker);
    assertThrows(
        IllegalArgumentException.class,
        () -> McpMovement.validatePath(zone, token, 200, 0, false, null));
  }

  @Test
  void closedDoorAndActorsOwnMaskDoNotBlockTheirInteraction() {
    var door = doorAt(100);
    token.setMaskTopology(Zone.TopologyType.MBL, new Area(new Rectangle(0, 0, 50, 50)));
    assertDoesNotThrow(() -> McpMovement.validateDoorPath(zone, token, door, null));
  }

  @Test
  void cannotInteractWithDoorThroughInterveningNativeWall() {
    var door = doorAt(100);
    zone.getWalls()
        .string(new Point2D.Double(75, 0), builder -> builder.push(new Point2D.Double(75, 50)));
    assertThrows(
        SecurityException.class, () -> McpMovement.validateDoorPath(zone, token, door, null));
  }

  @Test
  void excludingTargetDoorPreservesOverlappingStaticAndOtherTokenMasks() {
    var door = doorAt(100);
    zone.getMaskTopology(Zone.TopologyType.WALL_VBL).add(new Area(new Rectangle(100, 0, 10, 50)));
    assertThrows(
        SecurityException.class, () -> McpMovement.validateDoorPath(zone, token, door, null));
    zone.getMaskTopology(Zone.TopologyType.WALL_VBL).reset();
    doorAt(100);
    assertThrows(
        SecurityException.class, () -> McpMovement.validateDoorPath(zone, token, door, null));
  }

  @Test
  void cannotInteractAcrossAnUnexploredGap() {
    var door = doorAt(100);
    var exposed = new Area(new Rectangle(0, 0, 50, 50));
    exposed.add(new Area(new Rectangle(100, 0, 10, 50)));
    assertThrows(
        SecurityException.class, () -> McpMovement.validateDoorPath(zone, token, door, exposed));
    exposed.add(new Area(new Rectangle(0, 0, 110, 50)));
    assertDoesNotThrow(() -> McpMovement.validateDoorPath(zone, token, door, exposed));
  }

  private Token doorAt(int x) {
    var door = new Token();
    door.setLayer(Zone.Layer.OBJECT);
    door.setSnapToGrid(false);
    door.setSnapToScale(false);
    door.setX(x);
    door.setWidth(10);
    door.setHeight(50);
    door.setMaskTopology(Zone.TopologyType.MBL, new Area(new Rectangle(0, 0, 10, 50)));
    zone.putToken(door);
    return door;
  }

  @Test
  void emptyFootprintsFailClosed() {
    assertThrows(
        IllegalArgumentException.class,
        () -> McpMovement.sweptFootprint(new Rectangle(), new Rectangle(100, 0, 50, 50)));
  }
}
