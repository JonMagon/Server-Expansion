/*
 *
 * Server-Expansion
 * Copyright (C) 2018 Ryan McCarthy
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 *
 *
 */
package com.extendedclip.papi.expansion.server;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import me.clip.placeholderapi.PlaceholderAPI;
import me.clip.placeholderapi.PlaceholderAPIPlugin;
import me.clip.placeholderapi.expansion.Cacheable;
import me.clip.placeholderapi.expansion.Configurable;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.OfflinePlayer;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.PluginManager;
import org.jetbrains.annotations.NotNull;

import java.lang.management.ManagementFactory;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.text.SimpleDateFormat;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ServerExpansion extends PlaceholderExpansion implements Cacheable, Configurable {

	private final Map<String, SimpleDateFormat> dateFormats = new HashMap<>();
	private final Runtime runtime = Runtime.getRuntime();
	private Object craftServer;
	private Field tps;
	private Field tickTimes10s;
	private String version;
	private final String variant;

	// config stuff
	private String serverName;
	private String low = "&c";
	private String medium = "&e";
	private String high = "&a";
	private boolean isPapermc = false;
	private TickListener tickListener;
	// -----
	
	private final Cache<String, Integer> cache = Caffeine.newBuilder()
			.expireAfterWrite(1, TimeUnit.MINUTES)
			.build();

	private final String VERSION = getClass().getPackage().getImplementationVersion();

	public ServerExpansion() {
		this.version = Bukkit.getServer().getClass().getPackage().getName().split("\\.")[3];

		try {
			if (minecraftVersion() >= 17) {
				craftServer = Class.forName("net.minecraft.server.MinecraftServer").getMethod("getServer").invoke(null);
			} else {
				craftServer = Class.forName("net.minecraft.server." + version + ".MinecraftServer").getMethod("getServer").invoke(null);
			}
			tps = craftServer.getClass().getField("recentTps");
			tickTimes10s = craftServer.getClass().getField("tickTimes10s");
		} catch (Exception e) {
			e.printStackTrace();
		}

		this.variant = ServerUtils.getServerVariant();
	}

	@Override
	public boolean canRegister() {
		serverName = this.getString("server_name", "A Minecraft Server");
		low = this.getString("tps_color.low", "&c");
		medium = this.getString("tps_color.medium", "&e");
		high = this.getString("tps_color.high", "&a");

		try {
			isPapermc = Class.forName("com.destroystokyo.paper.VersionHistoryManager$VersionData") != null;
		} catch (ClassNotFoundException e) {}

		if (isPapermc) {
			PluginManager pluginManager = Bukkit.getPluginManager();

			tickListener = new TickListener();
			pluginManager.registerEvents(tickListener, pluginManager.getPlugin("PlaceholderAPI"));
		}

		return true;
	}

	@Override
	public void clear() {
		craftServer = null;
		tps = null;
		tickTimes10s = null;
		version = null;
		dateFormats.clear();
		
		cache.invalidateAll();
	}

	@Override
	public @NotNull String getIdentifier() {
		return "server";
	}

	@Override
	public @NotNull String getAuthor() {
		return "clip";
	}

	@Override
	public @NotNull String getVersion() {
		return VERSION;
	}

	@Override
	public Map<String, Object> getDefaults() {
		final Map<String, Object> defaults = new HashMap<>();
		defaults.put("tps_color.high", "&a");
		defaults.put("tps_color.medium", "&e");
		defaults.put("tps_color.low", "&c");
		defaults.put("server_name", "A Minecraft Server");
		return defaults;
	}

	@Override
	public String onRequest(OfflinePlayer p, String identifier) {
		final int MB = 1048576;

		switch (identifier) {
			// Players placeholders
			case "online":
				return String.valueOf(Bukkit.getOnlinePlayers().size());
			case "max_players":
				return String.valueOf(Bukkit.getMaxPlayers());
			case "unique_joins":
				return String.valueOf(Bukkit.getOfflinePlayers().length);
			// -----

			// Version placeholders
			case "version":
				return ServerUtils.VERSION;
			case "build":
				return ServerUtils.BUILD;
			case "version_build":
			case "version_full":
				return ServerUtils.VERSION + '-' + ServerUtils.BUILD;
			// -----

			// Ram placeholders
			case "ram_used":
				return String.valueOf((runtime.totalMemory() - runtime.freeMemory()) / MB);
			case "ram_free":
				return String.valueOf(runtime.freeMemory() / MB);
			case "ram_total":
				return String.valueOf(runtime.totalMemory() / MB);
			case "ram_max":
				return String.valueOf(runtime.maxMemory() / MB);
			// -----

			// Identity placeholders
			case "name":
				return serverName == null ? "" : serverName;
			case "variant":
				return variant;
			// -----

			// Other placeholders
			case "tps":
				return getTps(null);
			case "mspt":
				return getMspt(null);
			case "uptime":
				long seconds = TimeUnit.MILLISECONDS.toSeconds(ManagementFactory.getRuntimeMXBean().getUptime());
				return formatTime(Duration.of(seconds, ChronoUnit.SECONDS));
			case "total_chunks":
				return String.valueOf(cache.get("chunks", k -> getChunks()));
			case "total_living_entities":
				return String.valueOf(cache.get("livingEntities", k -> getLivingEntities()));
			case "total_entities":
				return String.valueOf(cache.get("totalEntities", k -> getTotalEntities()));
			case "has_whitelist":
				return Bukkit.getServer().hasWhitelist() ? PlaceholderAPIPlugin.booleanTrue() : PlaceholderAPIPlugin.booleanFalse();
		}

		if (identifier.startsWith("tps_")) {
			identifier = identifier.replace("tps_", "");
			return getTps(identifier);
		}

		if (identifier.startsWith("mspt_")) {
			identifier = identifier.replace("mspt_", "");
			return getMspt(identifier);
		}

		if (identifier.startsWith("online_")) {

			identifier = identifier.replace("online_", "");

			int i = 0;

			for (Player o : Bukkit.getOnlinePlayers()) {
				if (o.getWorld().getName().equals(identifier)) {
					i = i + 1;
				}
			}
			return String.valueOf(i);
		}

		if (identifier.startsWith("countdown_")) {
			String time = identifier.replace("countdown_", "");

			if (!time.contains("_")) {

				Date then;

				try {
					then = PlaceholderAPIPlugin.getDateFormat().parse(time);
				} catch (Exception e) {
					return null;
				}

				Date now = new Date();

				long between = then.getTime() - now.getTime();

				if (between <= 0) {
					return "0";
				}

				return formatTime(Duration.of((int) TimeUnit.MILLISECONDS.toSeconds(between), ChronoUnit.SECONDS));

			} else {

				String[] parts = PlaceholderAPI.setBracketPlaceholders(p, time).split("_");

				if (parts.length != 2) {
					return "invalid format and time";
				}

				time = parts[1];

				String format = parts[0];

				SimpleDateFormat f;

				try {
					f = new SimpleDateFormat(format);
				} catch (Exception e) {
					return "invalid date format";
				}

				Date then;

				try {
					then = f.parse(time);
				} catch (Exception e) {
					return "invalid date";
				}

				long t = System.currentTimeMillis();

				long between = then.getTime() - t;

				if (between <= 0) {
					return "0";
				}

				return formatTime(Duration.of((int) TimeUnit.MILLISECONDS.toSeconds(between), ChronoUnit.SECONDS));

			}
		}

		if (identifier.startsWith("time_")) {

			identifier = identifier.replace("time_", "");

			if (dateFormats.containsKey(identifier)) {
				return dateFormats.get(identifier).format(new Date());
			}

			try {
				SimpleDateFormat format = new SimpleDateFormat(identifier);

				dateFormats.put(identifier, format);

				return format.format(new Date());
			} catch (NullPointerException | IllegalArgumentException ex) {
				return null;
			}
		}

		return null;
	}

	public String getTps(String arg) {
		if (arg == null || arg.isEmpty()) {
			StringBuilder sb = new StringBuilder();
			for (double t : tps()) {
				sb.append(getColoredTps(t))
				  .append(ChatColor.GRAY)
				  .append(", ");
			}
			return sb.toString();
		}
		switch (arg) { 
			case "1":
			case "one":
			return String.valueOf(fix(tps()[0]));
			case "5":
			case "five":
				return String.valueOf(fix(tps()[1]));
			case "15":
			case "fifteen":
				return String.valueOf(tps()[2]);
			case "1_colored":
			case "one_colored":
				return getColoredTps(tps()[0]);
			case "5_colored":
			case "five_colored":
				return getColoredTps(tps()[1]);
			case "15_colored":
			case "fifteen_colored":
				return getColoredTps(tps()[2]);
			case "percent": {
				final StringJoiner joiner = new StringJoiner(ChatColor.GRAY + ", ");

				for (double t : tps()) {
					joiner.add(getColoredTpsPercent(t));
				}

				return joiner.toString();
			}
			case "1_percent":
			case "one_percent":
				return getPercent(tps()[0]);
			case "5_percent":
			case "five_percent":
				return getPercent(tps()[1]);
			case "15_percent":
			case "fifteen_percent":
				return getPercent(tps()[2]);
			case "1_percent_colored":
			case "one_percent_colored":
				return getColoredTpsPercent(tps()[0]);
			case "5_percent_colored":
			case "five_percent_colored":
				return getColoredTpsPercent(tps()[1]);
			case "15_percent_colored":
			case "fifteen_percent_colored":
				return getColoredTpsPercent(tps()[2]);
		}
		return null;
	}

	/**
	 * @author Sxtanna
	 */
	public static String formatTime(final Duration duration) {
		final StringBuilder builder = new StringBuilder();

		long seconds = duration.getSeconds();
		long minutes = seconds / 60;
		long hours = minutes / 60;
		long days = hours / 24;
		final long weeks = days / 7;

		seconds %= 60;
		minutes %= 60;
		hours %= 24;
		days %= 7;

		if (seconds > 0) {
			builder.insert(0, seconds + "s");
		}

		if (minutes > 0) {
			if (builder.length() > 0) {
				builder.insert(0, ' ');
			}

			builder.insert(0, minutes + "m");
		}

		if (hours > 0) {
			if (builder.length() > 0) {
				builder.insert(0, ' ');
			}

			builder.insert(0, hours + "h");
		}

		if (days > 0) {
			if (builder.length() > 0) {
				builder.insert(0, ' ');
			}

			builder.insert(0, days + "d");
		}

		if (weeks > 0) {
			if (builder.length() > 0) {
				builder.insert(0, ' ');
			}

			builder.insert(0, weeks + "w");
		}

		return builder.toString();
	}
	
	private double[] tps() {
		if (version == null || craftServer == null || tps == null) {
			return new double[] { 0, 0, 0 };
		}
		try {
			return ((double[]) tps.get(craftServer));
		} catch (IllegalAccessException e) {
			e.printStackTrace();
		}
		return new double[] { 0, 0, 0 };
	}
	
	private double fix(double tps) {
		return Math.min(Math.round(tps * 100.0) / 100.0, 20.0);
	}
	
	private String color(double tps) {
		return ChatColor.translateAlternateColorCodes('&', (tps > 18.0) ? high : (tps > 16.0) ? medium : low)
				+ ((tps > 20.0) ? "*" : "");
	}
	
	private String getColoredTps(double tps) {
		return color(tps) + fix(tps);
	}
	
	private String getColoredTpsPercent(double tps){
		return color(tps) + getPercent(tps);
	}
	
	private Integer getChunks(){
		int loadedChunks = 0;
		for (final World world : Bukkit.getWorlds()) {
			loadedChunks += world.getLoadedChunks().length;
		}
		
		return loadedChunks;
	}
	
	private Integer getLivingEntities(){
		int livingEntities = 0;
		for (final World world : Bukkit.getWorlds()) {
			livingEntities += world.getLivingEntities().size();
		}
		
		return livingEntities;
	}
	
	private Integer getTotalEntities(){
		int allEntities = 0;
		for (World world : Bukkit.getWorlds()) {
			allEntities += world.getEntities().size();
		}
		
		return allEntities;
	}
	
	private String getPercent(double tps){
		return Math.min(Math.round(100 / 20.0 * tps), 100.0) + "%";
	}

	/**
	 * Helper method to return the major version that the server is running.
	 *
	 * This is needed because in 1.17, NMS is no longer versioned.
	 *
	 * @return the major version of Minecraft the server is running
	 */
	public static int minecraftVersion() {
		try {
			final Matcher matcher = Pattern.compile("\\(MC: (\\d)\\.(\\d+)\\.?(\\d+?)?\\)").matcher(Bukkit.getVersion());
			if (matcher.find()) {
				return Integer.parseInt(matcher.toMatchResult().group(2), 10);
			} else {
				throw new IllegalArgumentException(String.format("No match found in '%s'", Bukkit.getVersion()));
			}
		} catch (final IllegalArgumentException ex) {
			throw new RuntimeException("Failed to determine Minecraft version", ex);
		}
	}

	private static double round(double value, int precision) {
		int scale = (int) Math.pow(10, precision);
		return (double) Math.round(value * scale) / scale;
	}

	private String colorMspt(double mspt) {
		return ChatColor.translateAlternateColorCodes('&', (mspt < 25.0) ? high : (mspt < 50.0) ? medium : low) + mspt;
	}

	private long[] tickTimes() {
		if (version == null || craftServer == null || tickTimes10s == null) {
			return null;
		}
		try {
			Object ticksObject = tickTimes10s.get(craftServer);
			return ((long[]) ticksObject.getClass().getDeclaredMethod("getTimes").invoke(ticksObject));
		} catch (IllegalAccessException | NoSuchMethodException | InvocationTargetException e) {
			e.printStackTrace();
		}
		return null;
	}

	public String getMspt(String arg) {
		if (arg == null || arg.isEmpty()) {
			long[] times = tickTimes();
			long min = Integer.MAX_VALUE;
			long max = 0L;
			long total = 0L;
			for (long value : times) {
				if (value > 0L && value < min) min = value;
				if (value > max) max = value;
				total += value;
			}
			return String.format("%.2f", ((double) total / (double) times.length) * 1.0E-6D);
		}

		int idx = 0;
		boolean colored = false;

		if (!(arg == null || arg.isEmpty())) {
			colored = arg.endsWith("_colored") || arg.equals("colored");

			if (arg.startsWith("10s")) {
				idx = 1;
			} else if (arg.startsWith("1m")) {
				idx = 2;
			}
		}

		double mspt = round(tickListener.getAverages()[idx], 1);

		if (colored) {
			return colorMspt(mspt);
		} else {
			return String.valueOf(mspt);
		}
	}
}
