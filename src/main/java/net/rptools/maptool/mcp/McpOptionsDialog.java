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
import java.awt.GridLayout;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.swing.JCheckBox;
import javax.swing.JLabel;
import javax.swing.JMenu;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import net.rptools.maptool.client.MapTool;

/** Edits the same campaign settings enforced by the MCP service. */
public final class McpOptionsDialog {
  private McpOptionsDialog() {}

  public static void installMenu() {
    if (!SwingUtilities.isEventDispatchThread()) {
      SwingUtilities.invokeLater(McpOptionsDialog::installMenu);
      return;
    }
    var bar = MapTool.getFrame().getJMenuBar();
    if (bar == null) return;
    for (int index = 0; index < bar.getMenuCount(); index++) {
      JMenu existing = bar.getMenu(index);
      if (existing != null && "maptool.mcp.menu".equals(existing.getName())) return;
    }
    JMenu menu = new JMenu("MCP");
    menu.setName("maptool.mcp.menu");
    JMenuItem options = new JMenuItem("Opções de criação...");
    options.addActionListener(event -> show());
    menu.add(options);
    bar.add(menu);
    bar.revalidate();
    bar.repaint();
  }

  private static void show() {
    try {
      JsonObject settings = McpContentService.options();
      boolean editable = MapTool.getPlayer().isGM();
      Map<String, JCheckBox> checkboxes = createCheckboxes(settings, editable);
      JPanel panel = new JPanel(new GridLayout(0, 1, 4, 6));
      panel.add(new JLabel("Recursos de criação permitidos nesta campanha:"));
      checkboxes.values().forEach(panel::add);
      panel.add(new JLabel("ImageGen é executado pelo Codex quando a ferramenta está disponível."));
      panel.add(new JLabel("A campanha salva estas opções; somente o mestre pode alterá-las."));
      int answer =
          JOptionPane.showConfirmDialog(
              MapTool.getFrame(),
              panel,
              "MCP — Opções de criação",
              editable ? JOptionPane.OK_CANCEL_OPTION : JOptionPane.DEFAULT_OPTION,
              JOptionPane.PLAIN_MESSAGE);
      if (editable && answer == JOptionPane.OK_OPTION) {
        JsonObject changes = new JsonObject();
        checkboxes.forEach((key, checkbox) -> changes.addProperty(key, checkbox.isSelected()));
        McpContentService.setOptions(changes);
      }
    } catch (RuntimeException e) {
      JOptionPane.showMessageDialog(
          MapTool.getFrame(),
          e.getMessage(),
          "Opções MCP indisponíveis",
          JOptionPane.ERROR_MESSAGE);
    }
  }

  static Map<String, JCheckBox> createCheckboxes(JsonObject settings, boolean editable) {
    Map<String, JCheckBox> result = new LinkedHashMap<>();
    addCheckbox(result, settings, "imagegen", "Usar ImageGen para gerar imagens", editable);
    addCheckbox(result, settings, "npc", "Criar NPCs com a skill de NPCs", editable);
    addCheckbox(
        result, settings, "scenery", "Criar gráficos de cenário com a skill de cenários", editable);
    addCheckbox(result, settings, "misc", "Criar MISC com a skill de objetos diversos", editable);
    return result;
  }

  private static void addCheckbox(
      Map<String, JCheckBox> target,
      JsonObject settings,
      String key,
      String label,
      boolean editable) {
    JCheckBox checkbox = new JCheckBox(label, settings.get(key).getAsBoolean());
    checkbox.setEnabled(editable);
    target.put(key, checkbox);
  }
}
