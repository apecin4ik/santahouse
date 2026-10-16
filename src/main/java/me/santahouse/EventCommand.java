package me.santahouse;

import java.util.ArrayList;
import java.util.List;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;

public class EventCommand implements CommandExecutor, TabCompleter {

    private final EventManager manager;

    public EventCommand(EventManager manager) {
        this.manager = manager;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        String sub = args.length > 0 ? args[0].toLowerCase() : "";
        switch (sub) {
            case "start":
                if (!sender.hasPermission("santahouse.admin")) {
                    sender.sendMessage(manager.prefix() + "§cНет прав.");
                    return true;
                }
                if (manager.isActive()) {
                    sender.sendMessage(manager.prefix() + "§cИвент уже идёт.");
                } else if (!manager.start()) {
                    sender.sendMessage(manager.prefix() + "§cНе удалось запустить (смотри консоль: мир или место не найдены).");
                }
                return true;
            case "stop":
                if (!sender.hasPermission("santahouse.admin")) {
                    sender.sendMessage(manager.prefix() + "§cНет прав.");
                    return true;
                }
                if (!manager.isActive()) {
                    sender.sendMessage(manager.prefix() + "§cИвент сейчас не идёт.");
                } else {
                    manager.stop();
                    sender.sendMessage(manager.prefix() + "§eИвент остановлен без наград.");
                }
                return true;
            case "reload":
                if (!sender.hasPermission("santahouse.admin")) {
                    sender.sendMessage(manager.prefix() + "§cНет прав.");
                    return true;
                }
                manager.load();
                sender.sendMessage(manager.prefix() + "§aКонфиг перезагружен.");
                return true;
            default:
                if (!sender.hasPermission("santahouse.use")) {
                    sender.sendMessage(manager.prefix() + "§cНет прав.");
                    return true;
                }
                for (String line : manager.infoLines()) {
                    sender.sendMessage(line);
                }
                return true;
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String alias, String[] args) {
        List<String> out = new ArrayList<>();
        if (args.length == 1) {
            List<String> subs = new ArrayList<>();
            if (sender.hasPermission("santahouse.admin")) {
                subs.add("start");
                subs.add("stop");
                subs.add("reload");
            }
            for (String s : subs) {
                if (s.startsWith(args[0].toLowerCase())) {
                    out.add(s);
                }
            }
        }
        return out;
    }
}
