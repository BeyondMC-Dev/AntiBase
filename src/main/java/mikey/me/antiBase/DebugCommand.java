package mikey.me.antiBase;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

public class DebugCommand extends Command {
    private final AntiBase plugin;
    public DebugCommand(AntiBase plugin) {
        super("antibase");
        this.setDescription("AntiBase debug commands");
        this.setPermission("antibase.debug");
        this.setUsage("/antibase debug");
        this.plugin = plugin;
    }

    @Override
    public boolean execute(@NotNull CommandSender sender, @NotNull String raw, String[] args) {
        if (args.length == 0 || !args[0].equals("debug")) return true;
        if (!(sender instanceof Player player)) {
            sender.sendRichMessage("<red>This command can only be used by players.");
            return true;
        }
        boolean newState = !plugin.isDebugEnabled(player.getUniqueId());
        plugin.setDebug(player.getUniqueId(), newState);
        if (newState) {
            player.sendRichMessage("<green>[AntiBase]</green> <gray>Debug mode</gray> <green>ENABLED");
            player.sendRichMessage("<gray>You will see visibility changes in your action bar");
        } else {
            player.sendRichMessage("<red>[AntiBase]</red> <gray>Debug mode</gray> <red>DISABLED");
        }
        return true;
    }
}
