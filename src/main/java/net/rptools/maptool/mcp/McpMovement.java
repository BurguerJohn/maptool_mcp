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

import java.awt.Rectangle;
import java.awt.geom.Area;
import java.util.ArrayList;
import java.util.EnumSet;
import javax.swing.SwingUtilities;
import net.rptools.lib.GeometryUtil;
import net.rptools.maptool.client.MapTool;
import net.rptools.maptool.client.ui.zone.renderer.ZoneRenderer;
import net.rptools.maptool.client.ui.zone.vbl.MovementBlockingTopology;
import net.rptools.maptool.model.Token;
import net.rptools.maptool.model.Zone;
import net.rptools.maptool.model.player.Player;
import net.rptools.maptool.model.topology.MaskTopology;
import net.rptools.maptool.server.ServerPolicy;
import org.locationtech.jts.algorithm.ConvexHull;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;

/** Validates a direct MCP movement without changing campaign state. */
final class McpMovement {
  private McpMovement() {}

  /**
   * Check and commit must occur together on the EDT, so no wall or permission can change between
   * them. Players move their own visible tokens on the active map. A GM retains normal GM control.
   */
  static void validate(Zone zone, Token token, int x, int y) {
    var renderer = validatePlayerContext(zone, token);
    if (renderer == null) {
      return;
    }
    Area exposed =
        zone.hasFog()
            ? renderer.getZoneView().getVisibility(renderer.getPlayerView()).exposedArea()
            : null;
    validatePath(zone, token, x, y, MapTool.getServerPolicy().getVblBlocksMove(), exposed);
  }

  /** Adds initiative, current visibility and unobstructed reach checks to a door interaction. */
  static void validateDoorInteraction(Zone zone, Token actor, Token door) {
    var renderer = validatePlayerContext(zone, actor);
    if (renderer == null) {
      return;
    }
    var policy = MapTool.getServerPolicy();
    if (policy.isTokenEditorLocked() || policy.isTokenContextLocked()) {
      throw new SecurityException("Token editing is currently locked by the GM");
    }
    if (!door.getLayer().isVisibleToPlayers()
        || !zone.isTokenVisible(door)
        || (door.isVisibleOnlyToOwner() && !door.isOwner(MapTool.getPlayer().getName()))) {
      throw new SecurityException("The door is not visible to this player");
    }
    if (zone.getVisionType() != Zone.VisionType.OFF) {
      var visible = renderer.getViewModel().getVisibleArea();
      if (visible == null || !visible.intersects(door.getFootprintBounds(zone))) {
        throw new SecurityException("The door is not visible to this player");
      }
    }
    Area exposed =
        zone.hasFog()
            ? renderer.getZoneView().getVisibility(renderer.getPlayerView()).exposedArea()
            : null;
    validateDoorPath(zone, actor, door, exposed);
  }

  /** Returns the player's active renderer, or null when a GM may bypass player constraints. */
  private static ZoneRenderer validatePlayerContext(Zone zone, Token token) {
    if (!SwingUtilities.isEventDispatchThread()) {
      throw new IllegalStateException("Token movement must be validated on the Swing event thread");
    }
    var player = MapTool.getPlayer();
    if (player == null) {
      throw new SecurityException("A signed-in MapTool player is required");
    }
    if (player.isGM()) {
      return null;
    }

    var frame = MapTool.getFrame();
    var renderer = frame == null ? null : frame.getCurrentZoneRenderer();
    if (!zone.isVisible() || renderer == null || renderer.getZone() != zone) {
      throw new SecurityException("Players can move tokens only on their active visible map");
    }
    var policy = MapTool.getServerPolicy();
    if (policy == null || frame.getInitiativePanel() == null) {
      throw new SecurityException("MapTool movement permissions are not available");
    }
    validatePlayerAccess(token, player, policy, frame.getInitiativePanel().isMovementLocked(token));
    if (!zone.isTokenVisible(token)) {
      throw new SecurityException("The token is not visible to this player");
    }

    return renderer;
  }

  /**
   * A closed door must not block its own interaction. Static masks, native walls, and every other
   * token's masks remain in the check, including blockers overlapping the target door.
   */
  static void validateDoorPath(Zone zone, Token actor, Token door, Area exposed) {
    var actorBounds = actor.getFootprintBounds(zone);
    var doorBounds = door.getFootprintBounds(zone);
    if (actorBounds.isEmpty() || doorBounds.isEmpty()) {
      throw new IllegalArgumentException("Door interaction requires nonempty token footprints");
    }
    var start = new Coordinate(actorBounds.getCenterX(), actorBounds.getCenterY());
    var end =
        new Coordinate(
            Math.max(doorBounds.getMinX(), Math.min(start.x, doorBounds.getMaxX())),
            Math.max(doorBounds.getMinY(), Math.min(start.y, doorBounds.getMaxY())));
    var factory = GeometryUtil.getGeometryFactory();
    Geometry ray =
        start.equals2D(end)
            ? factory.createPoint(start)
            : factory.createLineString(new Coordinate[] {start, end});
    var masks = new ArrayList<MaskTopology>();
    for (var type : Zone.TopologyType.values()) {
      masks.addAll(MaskTopology.createFromLegacy(type, zone.getMaskTopology(type)));
      for (var token : zone.getAllTokens()) {
        if (!token.getId().equals(actor.getId()) && !token.getId().equals(door.getId())) {
          masks.addAll(
              MaskTopology.createFromLegacy(type, token.getTransformedMaskTopology(zone, type)));
        }
      }
    }
    if (new MovementBlockingTopology(zone.getWalls(), masks).intersects(ray)) {
      throw new SecurityException("The character cannot reach the door through blocking topology");
    }
    if (exposed != null && !GeometryUtil.toJts(exposed).covers(ray)) {
      throw new SecurityException("The character cannot reach the door through unexplored fog");
    }
  }

  static void validatePlayerAccess(
      Token token, Player player, ServerPolicy policy, boolean initiativeLocked) {
    // MCP is intentionally conservative even when strict token management is disabled.
    if (token.getLayer() != Zone.Layer.TOKEN || !token.isOwner(player.getName())) {
      throw new SecurityException("Players can move only tokens they own on the TOKEN layer");
    }
    if (!token.isVisible()) {
      throw new SecurityException("The token is not visible to this player");
    }
    if (policy.isMovementLocked() || initiativeLocked) {
      throw new SecurityException("Token movement is currently locked by the GM or initiative");
    }
  }

  /**
   * Validate the complete straight movement, including both footprints. The native topology
   * collector includes map masks, other tokens' masks and walls, including doors' current state.
   * Unlike endpoint-only checks this cannot jump across a thin wall, blocked terrain or hard fog.
   * The bounding footprint is conservative for hexagonal and irregular tokens. Route around an
   * obstacle with explicit waypoints; this method does not run A* or infer a route.
   *
   * @param exposed the player's exposed fog area, or {@code null} when fog is disabled
   */
  static void validatePath(
      Zone zone, Token token, int x, int y, boolean vblBlocksMove, Area exposed) {
    var proposed = new Token(token, true);
    proposed.setX(x);
    proposed.setY(y);
    var swept = sweptFootprint(token.getFootprintBounds(zone), proposed.getFootprintBounds(zone));

    var types =
        vblBlocksMove ? EnumSet.allOf(Zone.TopologyType.class) : EnumSet.of(Zone.TopologyType.MBL);
    var topology =
        new MovementBlockingTopology(zone.getWalls(), zone.getMasks(types, token.getId()));
    if (topology.intersects(swept)) {
      throw new IllegalArgumentException(
          "The direct movement crosses blocking topology; use clear waypoints or ask the GM");
    }

    // An empty exposed area blocks movement; it must never mean unrestricted movement.
    if (exposed != null && !GeometryUtil.toJts(exposed).covers(swept)) {
      throw new IllegalArgumentException("The direct movement crosses unexplored fog of war");
    }

    if (!token.getTerrainModifiersIgnored().contains(Token.TerrainModifierOperation.BLOCK)) {
      for (var terrain : zone.getTokensWithTerrainModifiers()) {
        if (terrain.getId().equals(token.getId())
            || terrain.getTerrainModifierOperation() != Token.TerrainModifierOperation.BLOCK) {
          continue;
        }
        for (var cell : terrain.getOccupiedCells(zone.getGrid())) {
          if (swept.intersects(GeometryUtil.toJts(new Area(zone.getGrid().getBounds(cell))))) {
            throw new IllegalArgumentException("The direct movement crosses blocking terrain");
          }
        }
      }
    }
  }

  static Geometry sweptFootprint(Rectangle start, Rectangle end) {
    if (start.isEmpty() || end.isEmpty()) {
      throw new IllegalArgumentException("The token must have a nonempty movement footprint");
    }
    var coordinates = new Coordinate[8];
    addCorners(coordinates, 0, start);
    addCorners(coordinates, 4, end);
    return new ConvexHull(coordinates, GeometryUtil.getGeometryFactory()).getConvexHull();
  }

  private static void addCorners(Coordinate[] coordinates, int offset, Rectangle bounds) {
    // Permit an exact fit against a wall while remaining above the native geometry precision.
    // This removes only 1/1000 pixel; it never skips an interior wall or mask along the movement.
    double inset = 0.001;
    double left = bounds.getMinX() + inset;
    double right = bounds.getMaxX() - inset;
    double top = bounds.getMinY() + inset;
    double bottom = bounds.getMaxY() - inset;
    coordinates[offset] = new Coordinate(left, top);
    coordinates[offset + 1] = new Coordinate(right, top);
    coordinates[offset + 2] = new Coordinate(right, bottom);
    coordinates[offset + 3] = new Coordinate(left, bottom);
  }
}
