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

import com.google.gson.JsonObject;
import java.awt.Color;
import java.awt.Rectangle;
import java.awt.geom.Area;
import java.util.EnumSet;
import net.rptools.maptool.client.MapTool;
import net.rptools.maptool.model.Zone;
import net.rptools.maptool.model.drawing.DrawableColorPaint;
import net.rptools.maptool.model.drawing.Pen;
import net.rptools.maptool.model.drawing.ShapeDrawable;

/** Small procedural layouts for a newly materialized place. Existing maps are never redrawn. */
final class McpLocationLayout {
  private McpLocationLayout() {}

  static void validate(JsonObject location) {
    JsonObject blueprint = location.getAsJsonObject("blueprint");
    int grid = number(blueprint, "gridSize", 50);
    long width = (long) number(blueprint, "widthCells", 16) * grid;
    long height = (long) number(blueprint, "heightCells", 14) * grid;
    long left = (long) number(location, "entryX", 0) - width / 2;
    long top = (long) number(location, "entryY", 0) - height + 2L * grid;
    if (width > 4096
        || height > 4096
        || width < 6L * grid
        || height < 6L * grid
        || left < -1_000_000
        || top < -1_000_000
        || left + width > 1_000_000
        || top + height > 1_000_000) {
      throw new IllegalArgumentException(
          "Location layout must fit within 4096 pixels per side and map coordinate limits");
    }
  }

  static void build(Zone zone, JsonObject location) {
    JsonObject blueprint = location.getAsJsonObject("blueprint");
    String template = blueprint.has("template") ? blueprint.get("template").getAsString() : "blank";
    if ("blank".equals(template)) return;
    int grid = number(blueprint, "gridSize", 50);
    int width = number(blueprint, "widthCells", 16) * grid;
    int height = number(blueprint, "heightCells", 14) * grid;
    int entryX = number(location, "entryX", 0), entryY = number(location, "entryY", 0);
    int left = entryX - width / 2, top = entryY - height + 2 * grid;
    if ("world".equals(template)) {
      draw(zone, new Rectangle(left, top, width, height), "#406A91");
      draw(
          zone,
          new Rectangle(left + grid, top + grid, width - 2 * grid, height - 2 * grid),
          "#779659");
      draw(zone, new Rectangle(entryX - grid / 2, top + grid, grid, height - 2 * grid), "#AAB07B");
      return;
    }
    if ("settlement".equals(template)) {
      draw(zone, new Rectangle(left, top, width, height), "#65804C");
      draw(zone, new Rectangle(entryX - grid, top, 2 * grid, height), "#C2B18D");
      draw(zone, new Rectangle(left, top + height / 2 - grid, width, 2 * grid), "#C2B18D");
      draw(
          zone,
          new Rectangle(entryX - 2 * grid, top + height / 2 - 2 * grid, 4 * grid, 4 * grid),
          "#D6C9AE");
      return;
    }
    draw(zone, new Rectangle(left, top, width, height), "#BCAF94");
    int wall = Math.max(2, grid / 5);
    int opening = 2 * grid;
    int openingX = entryX - grid / 2;
    int south = top + height - wall;
    Area blocking = new Area();
    Rectangle[] walls = {
      new Rectangle(left, top, width, wall),
      new Rectangle(left, top, wall, height),
      new Rectangle(left + width - wall, top, wall, height),
      new Rectangle(left, south, openingX - left, wall),
      new Rectangle(openingX + opening, south, left + width - openingX - opening, wall)
    };
    for (Rectangle rectangle : walls) {
      blocking.add(new Area(rectangle));
      draw(zone, rectangle, "#494842");
    }
    if ("building".equals(template)) {
      // Keep the entry footprint in the larger room, clear of the internal partition.
      int splitX = left + width / 3;
      int gapY = top + height / 2;
      Rectangle first = new Rectangle(splitX, top + wall, wall, gapY - top - wall);
      Rectangle second = new Rectangle(splitX, gapY + 2 * grid, wall, south - gapY - 2 * grid);
      for (Rectangle rectangle : new Rectangle[] {first, second}) {
        blocking.add(new Area(rectangle));
        draw(zone, rectangle, "#494842");
      }
    }
    MapTool.serverCommand()
        .updateMaskTopology(
            zone, blocking, false, EnumSet.of(Zone.TopologyType.WALL_VBL, Zone.TopologyType.MBL));
    JsonObject door = new JsonObject();
    door.addProperty("mapId", zone.getId().toString());
    door.addProperty("x", openingX);
    door.addProperty("y", south);
    door.addProperty("width", opening);
    door.addProperty("height", wall);
    door.addProperty("name", "Entrance door");
    door.addProperty("open", true);
    new MapToolMcpService().callTool("maptool_create_door", door);
  }

  private static void draw(Zone zone, Rectangle shape, String color) {
    ShapeDrawable drawing = new ShapeDrawable(shape, true);
    drawing.setLayer(Zone.Layer.BACKGROUND);
    DrawableColorPaint paint = new DrawableColorPaint(Color.decode(color));
    Pen pen = new Pen(paint, paint, 1, false, false, 1);
    MapTool.serverCommand().draw(zone.getId(), pen, drawing);
    // Native server echo inserts the drawable. This overload only registers local undo.
    zone.addDrawable(pen, drawing);
  }

  private static int number(JsonObject object, String key, int fallback) {
    return object.has(key) ? object.get(key).getAsInt() : fallback;
  }
}
