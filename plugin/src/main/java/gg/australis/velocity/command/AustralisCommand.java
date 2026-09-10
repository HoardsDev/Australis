package gg.australis.velocity.command;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import gg.australis.velocity.AustralisPlugin;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

import java.util.List;

/**
 * {@code /australis <stats|reload|verify|unverify>} — admin command.
 * Requires the {@code australis.admin} permission.
 */
public final class AustralisCommand implements SimpleCommand {

    private final AustralisPlugin plugin;

    public AustralisCommand(AustralisPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public void execute(Invocation invocation) {
        CommandSource src = invocation.source();
        String[] args = invocation.arguments();

        if (args.length == 0) {
            src.sendMessage(prefix().append(Component.text(
                    "commands: stats, reload, verify <ip>, unverify <ip>", NamedTextColor.GRAY)));
            return;
        }

        switch (args[0].toLowerCase()) {
            case "stats" -> src.sendMessage(prefix().append(Component.text(
                    plugin.stats().summary(plugin.attackDetector().isUnderAttack(),
                            plugin.verification().verifiedCount()), NamedTextColor.WHITE)));
            case "reload" -> {
                plugin.reload();
                src.sendMessage(prefix().append(Component.text("configuration reloaded.", NamedTextColor.GREEN)));
            }
            case "verify" -> {
                if (args.length < 2) {
                    src.sendMessage(prefix().append(Component.text("usage: /australis verify <ip>", NamedTextColor.RED)));
                    return;
                }
                plugin.verification().forceVerify(args[1], plugin.config().verifyVerifiedTtlMillis());
                src.sendMessage(prefix().append(Component.text("verified " + args[1], NamedTextColor.GREEN)));
            }
            case "unverify" -> {
                if (args.length < 2) {
                    src.sendMessage(prefix().append(Component.text("usage: /australis unverify <ip>", NamedTextColor.RED)));
                    return;
                }
                plugin.verification().unverify(args[1]);
                src.sendMessage(prefix().append(Component.text("unverified " + args[1], NamedTextColor.YELLOW)));
            }
            default -> src.sendMessage(prefix().append(Component.text(
                    "unknown subcommand: " + args[0], NamedTextColor.RED)));
        }
    }

    @Override
    public List<String> suggest(Invocation invocation) {
        if (invocation.arguments().length <= 1) {
            return List.of("stats", "reload", "verify", "unverify");
        }
        return List.of();
    }

    @Override
    public boolean hasPermission(Invocation invocation) {
        return invocation.source().hasPermission("australis.admin");
    }

    private static Component prefix() {
        return Component.text("Australis ", NamedTextColor.AQUA)
                .append(Component.text("» ", NamedTextColor.GRAY));
    }
}
