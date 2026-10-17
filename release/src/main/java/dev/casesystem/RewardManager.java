package dev.casesystem;

import me.clip.placeholderapi.PlaceholderAPI;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reward delivery: placeholder parsing + console command execution.
 *
 * Built-in placeholders: %player%, %uuid%, %world%, %case%, %rarity%, %amount%.
 * Everything else that looks like %...% is resolved through the PlaceholderAPI
 * *API* (PlaceholderAPI.setPlaceholders(player, text)) - never via /papi parse.
 * Unknown placeholders (or PAPI missing) are left untouched in the string.
 */
public final class RewardManager {

    private static final Pattern PLACEHOLDER = Pattern.compile("%[^\\s%]+%");

    private final CaseSystem plugin;
    private final boolean papi;

    public RewardManager(CaseSystem plugin) {
        this.plugin = plugin;
        this.papi = Bukkit.getPluginManager().getPlugin("PlaceholderAPI") != null;
    }

    /**
     * Delivers a won item: runs every configured command for it from the console,
     * after substituting placeholders. Called on the main thread at animation end.
     */
    public void deliver(Player player, CaseManager.CaseDef c, CaseManager.Rarity rarity, CaseManager.ItemDef item) {
        List<String> commands = item.commands;
        if (commands.isEmpty()) {
            // No commands configured -> give the icon stack directly (1x).
            player.getInventory().addItem(item.icon.clone());
        } else {
            for (String cmd : commands) {
                String parsed = parse(player, cmd, c.id, rarity.id, 1);
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), parsed);
            }
        }
        player.updateInventory();
        player.sendMessage(CaseSystem.Messages.get("won-message")
                .replace("%item%", displayName(item))
                .replace("%rarity%", rarity.color + rarity.id));
    }

    /**
     * Full placeholder pipeline for one string.
     *  1) built-ins,
     *  2) PlaceholderAPI for any remaining %...% tokens (only those PAPI actually knows),
     *  3) unknown tokens stay as-is.
     */
    public String parse(Player player, String text, String caseId, String rarityId, int amount) {
        if (text == null || text.isEmpty()) return "";
        String out = builtin(player, text, caseId, rarityId, amount);
        if (papi && containsPlaceholder(out)) {
            try {
                out = keepUnknown(player, out);
            } catch (Throwable t) {
                plugin.getLogger().warning("PlaceholderAPI resolution failed: " + t.getMessage());
            }
        }
        return out;
    }

    private String builtin(Player p, String text, String caseId, String rarityId, int amount) {
        return text
                .replace("%player%", p.getName())
                .replace("%uuid%", p.getUniqueId().toString())
                .replace("%world%", p.getWorld().getName())
                .replace("%case%", caseId == null ? "" : caseId.toLowerCase(Locale.ROOT))
                .replace("%rarity%", rarityId == null ? "" : rarityId.toLowerCase(Locale.ROOT))
                .replace("%amount%", String.valueOf(amount));
    }

    private static boolean containsPlaceholder(String s) {
        return PLACEHOLDER.matcher(s).find();
    }

    /**
     * Per-token resolution: each remaining %token% is passed individually through
     * PlaceholderAPI.setPlaceholders. If the result equals the token (PAPI does not
     * know it) or still contains '%' (e.g. "%vault_prefix_unresolved%"), the original
     * token is kept verbatim.
     */
    private static String keepUnknown(Player player, String source) {
        Matcher m = PLACEHOLDER.matcher(source);
        StringBuilder sb = new StringBuilder();
        int last = 0;
        while (m.find()) {
            String token = m.group();
            sb.append(source, last, m.start());
            String value = PlaceholderAPI.setPlaceholders(player, token);
            boolean resolved = value != null && !value.equals(token) && !value.contains("%");
            sb.append(resolved ? value : token);
            last = m.end();
        }
        sb.append(source, last, source.length());
        return sb.toString();
    }

    private String displayName(CaseManager.ItemDef item) {
        if (item.icon.hasItemMeta() && item.icon.getItemMeta().hasDisplayName())
            return item.icon.getItemMeta().getDisplayName();
        return item.icon.getType().name().toLowerCase(Locale.ROOT).replace('_', ' ');
    }
}
