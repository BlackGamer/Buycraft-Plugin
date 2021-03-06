package net.buycraft.tasks;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map.Entry;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;
import java.util.regex.Pattern;

import net.buycraft.Plugin;
import net.buycraft.api.ApiTask;
import net.buycraft.util.Chat;
import net.buycraft.util.PackageCommand;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

public class CommandExecuteTask extends ApiTask {
    private static final Pattern REPLACE_NAME = Pattern.compile("[{\\(<\\[](name|player|username)[}\\)>\\]]", Pattern.CASE_INSENSITIVE);
    private static final Pattern REPLACE_UUID = Pattern.compile("[{\\(<\\[](uuid)[}\\)>\\]]", Pattern.CASE_INSENSITIVE);
    
    /**
     * Queues commands to be run
     * <p>
     * Note: 'Probably' not required, but safer to use it than not.
     */
    private final PriorityBlockingQueue<PackageCommand> commandQueue;
    private final AtomicBoolean isScheduled;
    private BukkitTask task;

    private final HashMap<String, Integer> requiredInventorySlots = new HashMap<String, Integer>();
    private final HashSet<String> creditedCommands = new HashSet<String>();

    private String lastLongRunningCommand = "None";

    public CommandExecuteTask() {
        commandQueue = new PriorityBlockingQueue<PackageCommand>();
        isScheduled = new AtomicBoolean(false);
    }
    
    public String getLastLongRunningCommand()
    {
        return lastLongRunningCommand;
    }
    
    /**
     * Parses the command and queues it to be executed in the main thread
     * @param delay The time in seconds for the task to be delayed
     */
    public void queueCommand(int commandId, String command, String username, String uuid, int delay, int requiredInventorySlots) {
        // Convert delay from seconds to ticks
        delay *= 20;
        try {
            username = Bukkit.getServer().getOfflinePlayer(username).getName();
            
            command = REPLACE_NAME.matcher(command).replaceAll(username);
            
            if(uuid != null)
            {
            	command = REPLACE_UUID.matcher(command).replaceAll(uuid);
            }

            if (command.startsWith("{mcmyadmin}")) {
                Plugin.getInstance().getLogger().info("Executing command '" + command + "' on behalf of user '" + username + "'.");
                String newCommand = command.replace("{mcmyadmin}", "");                
                Logger.getLogger("McMyAdmin").info("Buycraft tried command: " + newCommand);
            } else {
                PackageCommand pkgCmd = new PackageCommand(commandId, username, uuid, command, delay, requiredInventorySlots);
                if (!Plugin.getInstance().getCommandDeleteTask().queuedForDeletion(commandId) && !commandQueue.contains(pkgCmd)) {
                    commandQueue.add(pkgCmd);
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * Schedules the command executor to run
     * <p>
     * Should be run after PackageChecker finishes
     */
    public void scheduleExecutor() {
        if (commandQueue.isEmpty()) {
            // What?
            return;
        }
        // Make sure the task is not already scheduled
        // NOTE: This will only happen if commands take 6000 ticks to execute (Lets play it safe)
        if (isScheduled.compareAndSet(false, true)) {
            task = syncTimer(this, 1L, 1L);
            // Make sure the task was actually scheduled
            if (task == null) {
                isScheduled.set(false);
            }
        }
    }

    public void run() {
        long start = System.nanoTime();
        // Cap execution time at 500us
        while (!commandQueue.isEmpty() && commandQueue.peek().runtime <= System.currentTimeMillis() && System.nanoTime() - start < 500000) {
            try {
                PackageCommand pkgcmd = commandQueue.poll();

                // Ignore the command if the player does not have enough free item slots
                if (pkgcmd.requiresFreeInventorySlots()) {
                    Player player = Bukkit.getPlayer(pkgcmd.username);
                    int result = pkgcmd.calculateRequiredInventorySlots(player);
                    if (result > 0) {
                        // Fetch any current amounts
                        Integer currentRequired = requiredInventorySlots.get(player.getName());
                        // Check an amount exists
                        if (currentRequired == null) {
                            currentRequired = 0;
                        }

                        // Update the hash map with the higher result
                        if (currentRequired < result) {
                            requiredInventorySlots.put(player.getName(), result);
                        }
                        
                        continue;
                    }
               
                }
                
                Plugin.getInstance().getLogger().info("Executing command '" + pkgcmd.command + "' on behalf of user '" + pkgcmd.username + "'.");
                
                creditedCommands.add(pkgcmd.username);
                long cmdStart = System.currentTimeMillis();

                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), pkgcmd.command);
                // Check if the command lasted longer than our threshold
                long cmdDiff = System.currentTimeMillis() - cmdStart;
                if (cmdDiff >= 10) {
                    // Save the command and time it took to run
                    lastLongRunningCommand = "Time=" + cmdDiff + "ms - CMD=" + pkgcmd.command;
                }

                // Queue the command for deletion
                Plugin.getInstance().getCommandDeleteTask().deleteCommand(pkgcmd.getId());
            } catch (Throwable e) {
                e.printStackTrace();
            }
        }

        if (commandQueue.isEmpty()) {
            // Tell users that they need more inventory space
            for (Entry<String, Integer> e : requiredInventorySlots.entrySet()) {
                Player p = Bukkit.getPlayerExact(e.getKey());
                if (p == null) {
                    continue;
                }
                p.sendMessage(new String[] {
                        Chat.header(), 
                        Chat.seperator(),
                        Chat.seperator() + ChatColor.RED + String.format(Plugin.getInstance().getLanguage().getString("commandExecuteNotEnoughFreeInventory"), e.getValue()),
                        Chat.seperator() + ChatColor.RED + Plugin.getInstance().getLanguage().getString("commandExecuteNotEnoughFreeInventory2"),
                        Chat.seperator(), 
                        Chat.footer()
                });
            }
            // Clear the map
            requiredInventorySlots.clear();
            
            for (String name : creditedCommands) {
                Player p = Bukkit.getPlayerExact(name);
                if (p == null) {
                    continue;
                }
                p.sendMessage(new String[] {
                        Chat.header(), 
                        Chat.seperator(),
                        Chat.seperator() + ChatColor.GREEN + Plugin.getInstance().getLanguage().getString("commandsExecuted"),
                        Chat.seperator(), 
                        Chat.footer()
                });
            }
            // Clear the set
            creditedCommands.clear();

            BukkitTask task = this.task;
            // Null the task now so we can't overwrite a new one
            this.task = null;
            // Allow a new task to be scheduled
            isScheduled.set(false);
            // Cancel the current task
            task.cancel();
        }
    }
}
