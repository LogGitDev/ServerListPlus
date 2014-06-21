/*
 *        _____                     __    _     _   _____ _
 *       |   __|___ ___ _ _ ___ ___|  |  |_|___| |_|  _  | |_ _ ___
 *       |__   | -_|  _| | | -_|  _|  |__| |_ -|  _|   __| | | |_ -|
 *       |_____|___|_|  \_/|___|_| |_____|_|___|_| |__|  |_|___|___|
 *
 *  ServerListPlus - Customize your complete server status ping!
 *  Copyright (C) 2014, Minecrell <https://github.com/Minecrell>
 *
 *     This program is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU General Public License as published by
 *     the Free Software Foundation, either version 3 of the License, or
 *     (at your option) any later version.
 *
 *     This program is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *     GNU General Public License for more details.
 *
 *     You should have received a copy of the GNU General Public License
 *     along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package net.minecrell.serverlistplus.bungee;

import net.minecrell.serverlistplus.bungee.metrics.BungeeMetrics;
import net.minecrell.serverlistplus.core.ServerListPlusCore;
import net.minecrell.serverlistplus.core.ServerListPlusException;
import net.minecrell.serverlistplus.core.ServerStatusManager;
import net.minecrell.serverlistplus.core.config.PluginConf;
import net.minecrell.serverlistplus.core.favicon.FaviconSource;
import net.minecrell.serverlistplus.core.plugin.ServerListPlusPlugin;
import net.minecrell.serverlistplus.core.plugin.ServerType;
import net.minecrell.serverlistplus.core.util.InstanceStorage;

import java.util.logging.Level;

import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;

import net.md_5.bungee.api.ChatColor;
import net.md_5.bungee.api.CommandSender;
import net.md_5.bungee.api.Favicon;
import net.md_5.bungee.api.ServerPing;
import net.md_5.bungee.api.event.LoginEvent;
import net.md_5.bungee.api.event.ProxyPingEvent;
import net.md_5.bungee.api.plugin.Command;
import net.md_5.bungee.api.plugin.Listener;
import net.md_5.bungee.event.EventHandler;

public class BungeePlugin extends BungeePluginBase implements ServerListPlusPlugin {
    private ServerListPlusCore core;
    private LoadingCache<FaviconSource, Favicon> faviconCache;

    private LoginListener loginListener;
    private PingListener pingListener;

    private BungeeMetrics metrics;

    @Override
    public void onEnable() {
        try {
            this.core = new ServerListPlusCore(this);
        } catch (ServerListPlusException e) {
            this.getLogger().info("Please fix the error before restarting the server!"); return;
        } catch (Exception e) {
            this.getLogger().log(Level.SEVERE, "An internal error occurred while initializing the core.", e);
            return;
        }

        this.getProxy().getPluginManager().registerCommand(this, new ServerListPlusCommand());
    }

    public final class ServerListPlusCommand extends Command {
        private ServerListPlusCommand() {
            super("serverlistplus", "serverlistplus.admin", "serverlist+", "serverlist", "slp", "sl+", "s++",
                    "serverping+", "serverping", "spp");
        }

        @Override
        public void execute(CommandSender sender, String[] args) {
            core.executeCommand(new BungeeCommandSender(sender), this.getName(), args);
        }
    }

    public final class LoginListener implements Listener {
        private LoginListener() {}

        @EventHandler
        public void onPlayerLogin(LoginEvent event) {
            core.addClient(event.getConnection().getName(), event.getConnection().getAddress().getAddress());
        }
    }

    public final class PingListener implements Listener {
        private PingListener() {}

        @EventHandler
        public void onProxyPing(ProxyPingEvent event) {
            if (event.getResponse() == null) return;
            final ServerPing ping = event.getResponse();
            final ServerPing.Players players = ping.getPlayers();

            ServerStatusManager.Response response = core.getStatus().createResponse(event.getConnection().
                    getAddress().getAddress(), players == null ? new ServerStatusManager.ResponseFetcher() :
                    new ServerStatusManager.ResponseFetcher() {

                @Override
                public Integer fetchPlayersOnline() {
                    return players.getOnline();
                }

                @Override
                public Integer fetchMaxPlayers() {
                    return players.getMax();
                }
            });

            String message = response.getDescription();
            if (message != null) ping.setDescription(message);

            if (players != null) {
                Integer count = response.getPlayersOnline();
                if (count != null) players.setOnline(count);
                count = response.getMaxPlayers();
                if (count != null) players.setMax(count);

                message = response.getPlayerHover();
                if (message != null) players.setSample(new ServerPing.PlayerInfo[]{
                        new ServerPing.PlayerInfo(message, ServerStatusManager.EMPTY_UUID) });
            }

            ServerPing.Protocol version = ping.getVersion();
            if (version != null) {
                message = response.getVersion();
                if (message != null) version.setName(message);
                Integer protocol = response.getProtocol();
                if (protocol != null) version.setProtocol(protocol);
            }
        }
    }

    @Override
    public ServerType getServerType() {
        return ServerType.BUNGEE;
    }

    @Override
    public LoadingCache<FaviconSource, Favicon> getFaviconCache() {
        return faviconCache;
    }

    @Override
    public String colorize(String s) {
        return ChatColor.translateAlternateColorCodes('&', s);
    }

    @Override
    public void initialize(ServerListPlusCore core) {

    }

    @Override
    public void reloadFaviconCache(CacheBuilder<Object, Object> builder) {
        if (builder != null) {
            this.faviconCache = builder.build(new CacheLoader<FaviconSource, Favicon>() {
                @Override
                public Favicon load(FaviconSource key) throws Exception {
                    return Favicon.create(key.getLoader().load(core, key.getSource()));
                }
            });
        } else {
            faviconCache.invalidateAll();
            faviconCache.cleanUp();
            this.faviconCache = null;
        }
    }

    @Override
    public void configChanged(InstanceStorage<Object> confs) {
        if (confs.get(PluginConf.class).PlayerTracking) {
            if (loginListener == null) {
                this.registerListener(this.loginListener = new LoginListener());
                this.getLogger().info("Registered proxy player tracking listener.");
            }
        } else if (loginListener != null) {
            this.unregisterListener(loginListener);
            this.loginListener = null;
            this.getLogger().info("Unregistered proxy player tracking listener.");
        }

        if (confs.get(PluginConf.class).Stats) {
            if (metrics == null)
                try {
                    this.metrics = new BungeeMetrics(this);
                    metrics.start();
                } catch (Throwable e) {
                    this.getLogger().warning("Failed to enable plugin statistics: " + e.getMessage());
                }
        } else if (metrics != null)
            try {
                metrics.stop();
                this.metrics = null;
            } catch (Throwable e) {
                this.getLogger().warning("Failed to disable plugin statistics: " + e.getMessage());
            }
    }

    @Override
    public void statusChanged(ServerStatusManager status) {
        if (status.hasChanges()) {
            if (pingListener == null) {
                this.registerListener(this.pingListener = new PingListener());
                this.getLogger().info("Registered proxy ping listener.");
            }
        } else if (pingListener != null) {
            this.unregisterListener(pingListener);
            this.pingListener = null;
            this.getLogger().info("Unregistered proxy ping listener.");
        }
    }
}
