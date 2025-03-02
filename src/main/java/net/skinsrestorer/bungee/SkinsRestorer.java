/*
 * #%L
 * SkinsRestorer
 * %%
 * Copyright (C) 2021 SkinsRestorer
 * %%
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public
 * License along with this program.  If not, see
 * <http://www.gnu.org/licenses/gpl-3.0.html>.
 * #L%
 */
package net.skinsrestorer.bungee;

import co.aikar.commands.BungeeCommandManager;
import lombok.Getter;
import lombok.SneakyThrows;
import net.md_5.bungee.api.ProxyServer;
import net.md_5.bungee.api.connection.ProxiedPlayer;
import net.md_5.bungee.api.plugin.Plugin;
import net.skinsrestorer.api.PlayerWrapper;
import net.skinsrestorer.api.SkinsRestorerAPI;
import net.skinsrestorer.api.exception.SkinRequestException;
import net.skinsrestorer.api.property.IProperty;
import net.skinsrestorer.bungee.commands.GUICommand;
import net.skinsrestorer.bungee.commands.SkinCommand;
import net.skinsrestorer.bungee.commands.SrCommand;
import net.skinsrestorer.bungee.listeners.LoginListener;
import net.skinsrestorer.bungee.listeners.PluginMessageListener;
import net.skinsrestorer.shared.interfaces.ISRPlugin;
import net.skinsrestorer.shared.serverinfo.Platform;
import net.skinsrestorer.shared.storage.Config;
import net.skinsrestorer.shared.storage.Locale;
import net.skinsrestorer.shared.storage.SkinStorage;
import net.skinsrestorer.shared.update.UpdateChecker;
import net.skinsrestorer.shared.update.UpdateCheckerGitHub;
import net.skinsrestorer.shared.utils.CommandPropertiesManager;
import net.skinsrestorer.shared.utils.CommandReplacements;
import net.skinsrestorer.shared.utils.MetricsCounter;
import net.skinsrestorer.shared.utils.SharedMethods;
import net.skinsrestorer.shared.utils.connections.MineSkinAPI;
import net.skinsrestorer.shared.utils.connections.MojangAPI;
import net.skinsrestorer.shared.utils.log.LoggerImpl;
import net.skinsrestorer.shared.utils.log.SRLogger;
import net.skinsrestorer.shared.utils.log.console.BungeeConsoleImpl;
import org.bstats.bungeecord.Metrics;
import org.bstats.charts.SingleLineChart;
import org.inventivetalent.update.spiget.UpdateCallback;

import java.io.File;
import java.util.Arrays;
import java.util.Random;
import java.util.concurrent.TimeUnit;

@Getter
@SuppressWarnings("Duplicates")
public class SkinsRestorer extends Plugin implements ISRPlugin {
    private final File configPath = getDataFolder();
    private boolean multiBungee;
    private boolean outdated;
    private UpdateChecker updateChecker;
    private SkinApplierBungee skinApplierBungee;
    private SRLogger srLogger;
    private SkinStorage skinStorage;
    private MojangAPI mojangAPI;
    private MineSkinAPI mineSkinAPI;
    private PluginMessageListener pluginMessageListener;
    private SkinCommand skinCommand;
    private SkinsRestorerAPI skinsRestorerAPI;

    @Override
    public String getVersion() {
        return getDescription().getVersion();
    }

    @Override
    public void onEnable() {
        srLogger = new SRLogger(getDataFolder(), new LoggerImpl(getProxy().getLogger(), new BungeeConsoleImpl(getProxy().getConsole())), true);
        File updaterDisabled = new File(getDataFolder(), "noupdate.txt");

        Metrics metrics = new Metrics(this, 1686);
        metrics.addCustomChart(new SingleLineChart("mineskin_calls", MetricsCounter::collectMineskinCalls));
        metrics.addCustomChart(new SingleLineChart("minetools_calls", MetricsCounter::collectMinetoolsCalls));
        metrics.addCustomChart(new SingleLineChart("mojang_calls", MetricsCounter::collectMojangCalls));
        metrics.addCustomChart(new SingleLineChart("backup_calls", MetricsCounter::collectBackupCalls));

        if (!updaterDisabled.exists()) {
            updateChecker = new UpdateCheckerGitHub(2124, getDescription().getVersion(), srLogger, "SkinsRestorerUpdater/BungeeCord");
            checkUpdate(true);

            Random rn = new Random();
            int delayInt = 60 + rn.nextInt(240 - 60 + 1);
            getProxy().getScheduler().schedule(this, this::checkUpdate, delayInt, delayInt, TimeUnit.MINUTES);
        } else {
            srLogger.info("Updater Disabled");
        }

        // Init config files
        Config.load(getDataFolder(), getResourceAsStream("config.yml"), srLogger);
        Locale.load(getDataFolder(), srLogger);

        mojangAPI = new MojangAPI(srLogger, Platform.BUNGEECORD);
        mineSkinAPI = new MineSkinAPI(srLogger, mojangAPI);
        skinStorage = new SkinStorage(srLogger, mojangAPI);
        skinsRestorerAPI = new SkinsRestorerBungeeAPI(mojangAPI, skinStorage);
        skinApplierBungee = new SkinApplierBungee(this, srLogger);

        // Init storage
        if (!initStorage())
            return;

        // Init listener
        getProxy().getPluginManager().registerListener(this, new LoginListener(this, srLogger));

        // Init commands
        initCommands();

        getProxy().registerChannel("sr:skinchange");

        // Init message channel
        getProxy().registerChannel("sr:messagechannel");
        pluginMessageListener = new PluginMessageListener(this);
        getProxy().getPluginManager().registerListener(this, pluginMessageListener);

        multiBungee = Config.MULTIBUNGEE_ENABLED || ProxyServer.getInstance().getPluginManager().getPlugin("RedisBungee") != null;

        // Run connection check
        SharedMethods.runServiceCheck(mojangAPI, srLogger);
    }

    @SuppressWarnings({"deprecation"})
    private void initCommands() {
        BungeeCommandManager manager = new BungeeCommandManager(this);
        // optional: enable unstable api to use help
        manager.enableUnstableAPI("help");

        CommandReplacements.permissions.forEach((k, v) -> manager.getCommandReplacements().addReplacement(k, v));
        CommandReplacements.descriptions.forEach((k, v) -> manager.getCommandReplacements().addReplacement(k, v));
        CommandReplacements.syntax.forEach((k, v) -> manager.getCommandReplacements().addReplacement(k, v));
        CommandReplacements.completions.forEach((k, v) -> manager.getCommandCompletions().registerAsyncCompletion(k, c ->
                Arrays.asList(v.split(", "))));

        new CommandPropertiesManager(manager, configPath, getResourceAsStream("command-messages.properties"), srLogger);

        SharedMethods.allowIllegalACFNames();

        this.skinCommand = new SkinCommand(this, srLogger);
        manager.registerCommand(skinCommand);
        manager.registerCommand(new SrCommand(this, srLogger));
        manager.registerCommand(new GUICommand(this));
    }

    private boolean initStorage() {
        // Initialise MySQL
        if (!SharedMethods.initMysql(srLogger, skinStorage, getDataFolder())) {
            getProxy().getPluginManager().unregisterListeners(this);
            getProxy().getPluginManager().unregisterCommands(this);
            return false;
        }

        // Preload default skins
        ProxyServer.getInstance().getScheduler().runAsync(this, skinStorage::preloadDefaultSkins);
        return true;
    }

    private void checkUpdate() {
        checkUpdate(false);
    }

    private void checkUpdate(boolean showUpToDate) {
        ProxyServer.getInstance().getScheduler().runAsync(this, () -> updateChecker.checkForUpdate(new UpdateCallback() {
            @Override
            public void updateAvailable(String newVersion, String downloadUrl, boolean hasDirectDownload) {
                outdated = true;

                updateChecker.getUpdateAvailableMessages(newVersion, downloadUrl, hasDirectDownload, getVersion(), false).forEach(srLogger::info);
            }

            @Override
            public void upToDate() {
                if (!showUpToDate)
                    return;

                updateChecker.getUpToDateMessages(getVersion(), false).forEach(srLogger::info);
            }
        }));
    }

    private class SkinsRestorerBungeeAPI extends SkinsRestorerAPI {
        public SkinsRestorerBungeeAPI(MojangAPI mojangAPI, SkinStorage skinStorage) {
            super(mojangAPI, mineSkinAPI, skinStorage);
        }

        @Override
        public void applySkin(PlayerWrapper playerWrapper) throws SkinRequestException {
            applySkin(playerWrapper, playerWrapper.get(ProxiedPlayer.class).getName());
        }

        @Override
        public void applySkin(PlayerWrapper playerWrapper, String name) throws SkinRequestException {
            applySkin(playerWrapper, skinStorage.getSkinForPlayer(name, false));
        }

        @SneakyThrows
        @Override
        public void applySkin(PlayerWrapper playerWrapper, IProperty props) {
            skinApplierBungee.applySkin(playerWrapper.get(ProxiedPlayer.class), props);
        }
    }
}
