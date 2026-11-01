package dev.gurney.minecraftmcp.game;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.DisconnectedScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;

/**
 * Joins, leaves and rejoins a multiplayer server directly, and reloads resource packs, without
 * going through the title screen's own menus. Remembers the address last joined, so a later join
 * without one reconnects to it; without an earlier join this session, falls back to whatever
 * server the game is currently connected to, if any.
 */
public final class ServerConnection {
	/** Leaving the previous world or server first can take a while if it has to save. */
	private static final long SWITCH_TIMEOUT_SECONDS = 30;
	private static final long JOIN_TIMEOUT_SECONDS = 45;
	private static final long RELOAD_TIMEOUT_SECONDS = 30;
	private static final long POLL_MILLIS = 250;

	private static volatile String lastAddress;

	private ServerConnection() {
	}

	/** Joins the given server, or without one, the last one joined or currently connected to. */
	public static String join(String address) {
		String target = address != null ? address : fallbackAddress();
		if (target == null) {
			throw new ToolException("no address given, and nothing to reconnect to (no earlier join this session, and not currently on a server)");
		}
		if (!ServerAddress.isValidAddress(target)) {
			throw new ToolException("\"" + target + "\" isn't a valid address; use host or host:port");
		}
		lastAddress = target;
		CompletableFuture<Void> started = Minecraft.getInstance().submit(() -> {
			if (Minecraft.getInstance().gui.screen() instanceof ConnectScreen) {
				throw new ToolException("already connecting to a server; wait for it to finish");
			}
			ServerData data = new ServerData(target, target, ServerData.Type.OTHER);
			// The resource pack is how Falloria looks at all; load it without a confirmation prompt.
			data.setResourcePackStatus(ServerData.ServerPackStatus.ENABLED);
			ConnectScreen.startConnecting(new TitleScreen(), Minecraft.getInstance(), ServerAddress.parseString(target), data, false, null);
			return null;
		});
		GameThreads.await(started, SWITCH_TIMEOUT_SECONDS, "leaving the previous world or server took more than " + SWITCH_TIMEOUT_SECONDS + "s (it may be saving)");
		return awaitJoin(target);
	}

	/** Leaves the current world or server, back to the title screen. */
	public static String disconnect() {
		CompletableFuture<Void> done = Minecraft.getInstance().submit(() -> {
			if (Minecraft.getInstance().level == null) {
				throw new ToolException("not in a world");
			}
			Minecraft.getInstance().disconnectFromWorld(ClientLevel.DEFAULT_QUIT_MESSAGE);
			return null;
		});
		GameThreads.await(done, SWITCH_TIMEOUT_SECONDS, "disconnecting took more than " + SWITCH_TIMEOUT_SECONDS + "s (it may be saving a large world); check get_status");
		return "disconnected; " + StatusReport.brief();
	}

	/** Reloads resource packs and data, as F3+T does, picking up local resource pack edits without rejoining. */
	public static String reloadResourcePacks() {
		CompletableFuture<Void> reloaded = GameThreads.onClient(() -> Minecraft.getInstance().reloadResourcePacks());
		GameThreads.await(reloaded, RELOAD_TIMEOUT_SECONDS, "resource packs hadn't finished reloading after " + RELOAD_TIMEOUT_SECONDS
			+ "s; a broken pack may have made the game roll back to the previous set, or it's still loading - check get_status or a screenshot");
		return "resource packs reloaded; " + StatusReport.brief();
	}

	private static String fallbackAddress() {
		if (lastAddress != null) {
			return lastAddress;
		}
		ServerData current = GameThreads.onClient(() -> Minecraft.getInstance().getCurrentServer());
		return current == null ? null : current.ip;
	}

	private static String awaitJoin(String target) {
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(JOIN_TIMEOUT_SECONDS);
		while (true) {
			if (GameThreads.onClient(ServerConnection::checkJoined)) {
				// Fetched here, off the client thread: StatusReport.brief() submits to it itself, which would
				// deadlock the client thread's single task queue if called from a task already running on it.
				return "joined; " + StatusReport.brief();
			}
			if (System.nanoTime() >= deadline) {
				throw new ToolException("still trying to join " + target + " after " + JOIN_TIMEOUT_SECONDS + "s; check get_status or a screenshot");
			}
			sleep();
		}
	}

	/** Called on the client thread. False means still connecting; throws once a failure screen shows. */
	private static boolean checkJoined() {
		Minecraft minecraft = Minecraft.getInstance();
		Screen screen = minecraft.gui.screen();
		if (screen instanceof DisconnectedScreen disconnected) {
			throw new ToolException(disconnected.getNarrationMessage().getString());
		}
		return minecraft.level != null && minecraft.player != null;
	}

	private static void sleep() {
		try {
			Thread.sleep(POLL_MILLIS);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException(e);
		}
	}
}
