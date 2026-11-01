package dev.gurney.minecraftmcp.game;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.network.protocol.Packet;

/**
 * Records packets sent and received on the current connection, e.g. to see what a server's menu
 * or dialog actually sends over the wire. Packets arrive and are sent on Netty's own event loop
 * thread, not the client thread, so {@link #record} is lock-free; only start/stop take a lock,
 * against each other.
 */
public final class PacketRecorder {
	private static final int MAX_ENTRIES = 2000;
	/**
	 * High enough that record-based packets (most of them, including dialogs) never get cut off -
	 * a record's toString nests its fields' own toString, so this is genuinely the packet's raw
	 * data, not a lossy summary. Still bounded, so an unfiltered capture that catches something
	 * like raw chunk data doesn't blow up the result.
	 */
	private static final int MAX_SUMMARY_LENGTH = 20_000;
	private static final double MAX_SECONDS = 600;

	private static final ScheduledExecutorService SCHEDULER = Executors.newSingleThreadScheduledExecutor(runnable -> {
		Thread thread = new Thread(runnable, "minecraft-mcp-packet-recorder");
		thread.setDaemon(true);
		return thread;
	});

	private record Entry(long elapsedMillis, String direction, String summary) {
	}

	private static final ConcurrentLinkedQueue<Entry> entries = new ConcurrentLinkedQueue<>();
	private static final AtomicInteger count = new AtomicInteger();
	/** Gates {@link #record}; false once the time budget runs out, even before {@link #stop} retrieves the log. */
	private static volatile boolean capturing;
	/** True from {@link #start} until {@link #stop} retrieves the log, whether or not still capturing. */
	private static boolean sessionActive;
	private static volatile String filter;
	private static long startNanos;
	private static ScheduledFuture<?> autoStop;

	private PacketRecorder() {
	}

	public static synchronized String start(String filterSubstring, Double seconds) {
		if (sessionActive) {
			throw new ToolException("already recording (or stopped automatically and not yet retrieved); call stop_packet_recording first");
		}
		if (seconds != null && (seconds <= 0 || seconds > MAX_SECONDS)) {
			throw new ToolException("seconds must be greater than 0 and at most " + (int) MAX_SECONDS);
		}
		filter = filterSubstring == null || filterSubstring.isBlank() ? null : filterSubstring.toLowerCase(Locale.ROOT);
		entries.clear();
		count.set(0);
		startNanos = System.nanoTime();
		sessionActive = true;
		capturing = true;
		autoStop = seconds == null ? null : SCHEDULER.schedule(PacketRecorder::autoStop, Math.round(seconds * 1000), TimeUnit.MILLISECONDS);
		return "recording" + (filter == null ? " every packet" : " packets whose class name contains \"" + filterSubstring + "\"")
			+ (seconds == null ? "" : ", stopping automatically after " + seconds + "s")
			+ "; call stop_packet_recording for the log.";
	}

	private static synchronized void autoStop() {
		capturing = false;
	}

	public static synchronized String stop() {
		if (!sessionActive) {
			throw new ToolException("not recording; call start_packet_recording first");
		}
		capturing = false;
		sessionActive = false;
		if (autoStop != null) {
			autoStop.cancel(false);
			autoStop = null;
		}
		List<Entry> captured = new ArrayList<>(entries);
		entries.clear();
		return format(captured);
	}

	/** Called on Netty's event loop thread for every packet sent or received. */
	public static void record(String direction, Packet<?> packet) {
		if (!capturing) {
			return;
		}
		String className = packet.getClass().getSimpleName();
		String activeFilter = filter;
		if (activeFilter != null && !className.toLowerCase(Locale.ROOT).contains(activeFilter)) {
			return;
		}
		if (count.incrementAndGet() > MAX_ENTRIES) {
			return;
		}
		entries.add(new Entry(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos), direction, summarize(packet, className)));
	}

	private static String summarize(Packet<?> packet, String className) {
		String text;
		try {
			text = packet.toString();
		} catch (RuntimeException e) {
			text = null;
		}
		// Falls back to Object's identity-hash default when a packet class has no real toString.
		String summary = text == null || text.isBlank() || text.startsWith(packet.getClass().getName() + "@") ? className : text;
		return summary.length() > MAX_SUMMARY_LENGTH ? summary.substring(0, MAX_SUMMARY_LENGTH) + "...(truncated)" : summary;
	}

	private static String format(List<Entry> captured) {
		if (captured.isEmpty()) {
			return "recorded 0 packets";
		}
		StringBuilder out = new StringBuilder();
		out.append(captured.size()).append(" packets recorded, oldest first");
		if (count.get() > MAX_ENTRIES) {
			out.append(" (stopped at the ").append(MAX_ENTRIES).append("-packet limit; more arrived after that, use filter to narrow it down)");
		}
		out.append(":\n");
		for (Entry entry : captured) {
			out.append(entry.elapsedMillis()).append("ms ").append(entry.direction()).append(' ').append(entry.summary()).append('\n');
		}
		return out.toString();
	}
}
