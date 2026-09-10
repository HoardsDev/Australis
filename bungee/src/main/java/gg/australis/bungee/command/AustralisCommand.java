package gg.australis.bungee.command;

import gg.australis.bungee.AustralisBungee;
import net.md_5.bungee.api.ChatColor;
import net.md_5.bungee.api.CommandSender;
import net.md_5.bungee.api.chat.TextComponent;
import net.md_5.bungee.api.plugin.Command;
import net.md_5.bungee.api.plugin.TabExecutor;

import java.util.List;

/**
 * {@code /australis <stats|reload|verify|unverify>} — admin command.
 * Requires the {@code australis.admin} permission (enforced by Bungee via the
 * permission passed to the {@link Command} super-constructor).
 */
public final class AustralisCommand extends Command implements TabExecutor {

    private static final String PREFIX = ChatColor.AQUA + "Australis " + ChatColor.GRAY + "» ";

    private final AustralisBungee plugin;

    public AustralisCommand(AustralisBungee plugin) {
        super("australis", "australis.admin");
        this.plugin = plugin;
    }

    @Override
    public void execute(CommandSender sender, String[] args) {
        if (args.length == 0) {
            send(sender, ChatColor.GRAY + "commands: stats, reload, verify <ip>, unverify <ip>");
            return;
        }

        switch (args[0].toLowerCase()) {
            case "stats" -> send(sender, ChatColor.WHITE + plugin.stats().summary(
                    plugin.attackDetector().isUnderAttack(), plugin.verification().verifiedCount()));
            case "reload" -> {
                plugin.reload();
                send(sender, ChatColor.GREEN + "configuration reloaded.");
            }
            case "verify" -> {
                if (args.length < 2) {
                    send(sender, ChatColor.RED + "usage: /australis verify <ip>");
                    return;
                }
                plugin.verification().forceVerify(args[1], plugin.config().verifyVerifiedTtlMillis());
                send(sender, ChatColor.GREEN + "verified " + args[1]);
            }
            case "unverify" -> {
                if (args.length < 2) {
                    send(sender, ChatColor.RED + "usage: /australis unverify <ip>");
                    return;
                }
                plugin.verification().unverify(args[1]);
                send(sender, ChatColor.YELLOW + "unverified " + args[1]);
            }
            default -> send(sender, ChatColor.RED + "unknown subcommand: " + args[0]);
        }
    }

    @Override
    public Iterable<String> onTabComplete(CommandSender sender, String[] args) {
        if (args.length <= 1) {
            return List.of("stats", "reload", "verify", "unverify");
        }
        return List.of();
    }

    private static void send(CommandSender sender, String message) {
        sender.sendMessage(new TextComponent(PREFIX + message));
    }
}
