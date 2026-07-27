package com.chatmoderator.command;

import com.chatmoderator.ChatModeratorPlugin;
import org.bukkit.command.Command;
import org.bukkit.entity.Player;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;

import java.util.Arrays;

/**
 * 游戏内管理命令：/chatmod reload | status | test（需求 §10，仅 OP）。
 */
public class CommandHandler implements CommandExecutor {

    private final ChatModeratorPlugin plugin;

    public CommandHandler(ChatModeratorPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("chatmoderator.admin")) {
            sender.sendMessage("§c你没有权限执行该命令");
            return true;
        }
        if (args.length == 0) {
            sender.sendMessage("§b用法: /chatmod <reload|status|test>");
            return true;
        }
        switch (args[0].toLowerCase()) {
            case "reload":
                plugin.reloadAll();
                sender.sendMessage("§a配置、词库、提示词与模型已热重载");
                return true;
            case "status":
                plugin.printStatus(sender);
                return true;
            case "test":
                // 仅 OP 可使用（控制台除外），防止非 OP 滥用大模型/探测提示词
                if (sender instanceof Player && !((Player) sender).isOp()) {
                    sender.sendMessage("§c需要 OP 权限");
                    return true;
                }
                if (args.length < 3) {
                    sender.sendMessage("§c用法: /chatmod test <玩家名> <消息内容>");
                    return true;
                }
                String target = args[1];
                String message = String.join(" ", Arrays.copyOfRange(args, 2, args.length));
                plugin.runTest(sender, target, message);
                return true;
            default:
                sender.sendMessage("§c未知子命令，可用: reload | status | test");
                return true;
        }
    }
}
