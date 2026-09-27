package pangch.pangchProfileToBlockMod.client;

import net.minecraft.client.Minecraft;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import oshi.SystemInfo;
import oshi.hardware.CentralProcessor;

import javax.swing.JFrame;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;

/** A native window, independent of Minecraft's game window. */
public final class ProfileWindow extends JPanel {
    private static final Logger LOGGER = LoggerFactory.getLogger("Pangch Profiling");
    private static final long WINDOW_NANOS = 2_000_000_000L;
    private static final Color[] COLORS = {
            new Color(126, 145, 183), new Color(255, 160, 27),
            new Color(118, 166, 198), new Color(155, 133, 184)
    };
    private static volatile JFrame window;
    private final CentralProcessor processor = new SystemInfo().getHardware().getProcessor();
    private long[][] coreTicks = processor.getProcessorCpuLoadTicks();
    private double[] coreLoad = new double[processor.getLogicalProcessorCount()];
    private long lastCoreSample;
    private final ThreadMXBean threadCpu = ManagementFactory.getThreadMXBean();
    private final boolean threadCpuAvailable;
    private final Map<Long, ArrayDeque<CpuPoint>> threadCpuHistory = new HashMap<>();
    private final List<ProfilerCapture.Sample> recent = new ArrayList<>();
    private final List<Block> blocks = new ArrayList<>();
    private record Block(Rectangle area, String path, long nanos) {}
    private record CpuPoint(long time, long nanos) {}

    private ProfileWindow() {
        boolean available = threadCpu.isThreadCpuTimeSupported();
        if (available && !threadCpu.isThreadCpuTimeEnabled()) {
            try { threadCpu.setThreadCpuTimeEnabled(true); }
            catch (SecurityException exception) { available = false; }
        }
        threadCpuAvailable = available;
        int rows = (processor.getLogicalProcessorCount() + 11) / 12;
        setPreferredSize(new Dimension(900, Math.max(600, 82 + rows * 32 + 370)));
        setBackground(new Color(12, 25, 41));
        setToolTipText("");
    }

    public static void toggle(Minecraft client) {
        if (window != null) {
            SwingUtilities.invokeLater(() -> { if (window != null) window.dispose(); });
            return;
        }
        boolean pauseOnLostFocus = client.options.pauseOnLostFocus;
        client.options.pauseOnLostFocus = false;
        SwingUtilities.invokeLater(() -> {
            JFrame frame = null;
            try {
            frame = new JFrame("Pangch Profiling");
            ProfileWindow panel = new ProfileWindow();
            Timer timer = new Timer(100, event -> panel.refresh());
            JScrollPane scroll = new JScrollPane(panel);
            scroll.setPreferredSize(new Dimension(900, 650));
            frame.setContentPane(scroll);
            frame.setMinimumSize(new Dimension(600, 400));
            frame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
            frame.addWindowListener(new WindowAdapter() {
                @Override public void windowClosed(WindowEvent event) {
                    timer.stop();
                    ProfilerCapture.enabled = false;
                    ProfilerCapture.drain();
                    window = null;
                    client.execute(() -> client.options.pauseOnLostFocus = pauseOnLostFocus);
                }
            });
            frame.pack();
            frame.setLocationByPlatform(true);
            frame.setAlwaysOnTop(true);
            window = frame;
            ProfilerCapture.drain();
            ProfilerCapture.enabled = true;
            timer.start();
            frame.setVisible(true);
            frame.toFront();
            LOGGER.info("Profile window opened");
            } catch (RuntimeException exception) {
                ProfilerCapture.enabled = false;
                if (frame != null) frame.dispose();
                window = null;
                client.execute(() -> client.options.pauseOnLostFocus = pauseOnLostFocus);
                LOGGER.error("Could not open profile window", exception);
            }
        });
    }

    private void refresh() {
        long now = System.nanoTime();
        if (now - lastCoreSample >= 500_000_000L) {
            coreLoad = processor.getProcessorCpuLoadBetweenTicks(coreTicks);
            coreTicks = processor.getProcessorCpuLoadTicks();
            lastCoreSample = now;
        }
        recent.addAll(ProfilerCapture.drain());
        recent.removeIf(sample -> now - sample.ended() > WINDOW_NANOS);
        if (recent.size() > 20_000) recent.subList(0, recent.size() - 20_000).clear();
        if (threadCpuAvailable) {
            Set<Long> ids = new HashSet<>();
            for (ProfilerCapture.Sample sample : recent) ids.add(sample.threadId());
            for (long id : ids) {
                long nanos = threadCpu.getThreadCpuTime(id);
                if (nanos >= 0) threadCpuHistory.computeIfAbsent(id, ignored -> new ArrayDeque<>()).addLast(new CpuPoint(now, nanos));
            }
            for (ArrayDeque<CpuPoint> points : threadCpuHistory.values()) {
                while (!points.isEmpty() && now - points.peekFirst().time() > WINDOW_NANOS) points.removeFirst();
            }
            threadCpuHistory.values().removeIf(ArrayDeque::isEmpty);
        }
        repaint();
    }

    @Override public String getToolTipText(MouseEvent event) {
        for (Block block : blocks) {
            if (block.area().contains(event.getPoint())) {
                return String.format(Locale.ROOT, "<html>%s<br>Estimated CPU %.1f%% (%.2f ms / 2 s)</html>",
                        block.path().replace("&", "&amp;").replace("<", "&lt;"),
                        100.0 * block.nanos() / WINDOW_NANOS, block.nanos() / 1_000_000.0);
            }
        }
        return null;
    }

    @Override protected void paintComponent(Graphics graphics) {
        super.paintComponent(graphics);
        Graphics2D g = (Graphics2D) graphics.create();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 14));
        int width = getWidth(), height = getHeight();
        g.setColor(new Color(9, 21, 35));
        g.fillRect(0, 0, width, 54);
        g.setColor(new Color(22, 149, 255));
        g.fillOval(20, 15, 24, 24);
        g.setColor(new Color(69, 164, 255));
        g.drawString("Pangch Profiling - CPU usage", 54, 32);
        g.setColor(new Color(255, 22, 32));
        g.fillRect(12, 51, width - 24, 7);

        g.setColor(new Color(171, 188, 208));
        g.drawString("Logical cores (whole PC)", 12, 76);
        int coreColumns = Math.max(1, (width - 24) / 72);
        int coreWidth = Math.max(48, (width - 24 - (coreColumns - 1) * 4) / coreColumns);
        for (int core = 0; core < coreLoad.length; core++) {
            int x = 12 + (core % coreColumns) * (coreWidth + 4);
            int y = 82 + (core / coreColumns) * 32;
            g.setColor(new Color(62, 75, 89));
            g.fillRect(x, y, coreWidth, 27);
            g.setColor(new Color(22, 149, 255));
            g.fillRect(x, y + 22, (int) Math.round(coreWidth * Math.clamp(coreLoad[core], 0.0, 1.0)), 5);
            g.setColor(Color.WHITE);
            g.drawString(String.format(Locale.ROOT, "%d  %.0f%%", core, coreLoad[core] * 100), x + 4, y + 18);
        }
        int laneTop = 82 + ((coreLoad.length + coreColumns - 1) / coreColumns) * 32 + 18;
        g.setColor(new Color(171, 188, 208));
        g.drawString("Minecraft profiler sections (estimated CPU%)", 12, laneTop - 4);
        laneTop += 6;

        Map<Long, Map<String, Long>> threads = new HashMap<>();
        Map<Long, String> names = new HashMap<>();
        for (ProfilerCapture.Sample sample : recent) {
            names.put(sample.threadId(), sample.thread());
            threads.computeIfAbsent(sample.threadId(), ignored -> new HashMap<>())
                    .merge(sample.section(), sample.nanos(), Long::sum);
        }
        blocks.clear();
        if (threads.isEmpty()) {
            g.setColor(new Color(171, 188, 208));
            g.drawString(threadCpuAvailable ? "Waiting for profiler sections..." :
                    "Thread CPU time is unavailable on this JVM", 20, laneTop + 25);
            g.dispose();
            return;
        }

        List<Map.Entry<Long, Map<String, Long>>> lanes = new ArrayList<>(threads.entrySet());
        lanes.sort(Comparator.<Map.Entry<Long, Map<String, Long>>>comparingLong(entry ->
                entry.getValue().values().stream().mapToLong(Long::longValue).sum()).reversed());
        if (lanes.size() > 4) lanes = lanes.subList(0, 4);
        int gap = 12;
        int laneWidth = Math.max(100, (width - 24 - gap * (lanes.size() - 1)) / lanes.size());
        int x = 12;
        for (Map.Entry<Long, Map<String, Long>> lane : lanes) {
            g.setColor(new Color(62, 75, 89));
            g.fillRect(x, laneTop, laneWidth, height - laneTop - 14);
            g.setColor(Color.WHITE);
            ArrayDeque<CpuPoint> points = threadCpuHistory.get(lane.getKey());
            long cpuNanos = points == null || points.size() < 2 ? 0 : points.peekLast().nanos() - points.peekFirst().nanos();
            long wallNanos = lane.getValue().values().stream().mapToLong(Long::longValue).sum();
            double cpuFactor = wallNanos == 0 ? 0 : Math.min(1.0, (double) cpuNanos / wallNanos);
            g.drawString(String.format(Locale.ROOT, "%s  %.1f%%", names.get(lane.getKey()),
                    100.0 * cpuNanos / WINDOW_NANOS), x + 8, laneTop + 21);
            List<Map.Entry<String, Long>> sections = new ArrayList<>(lane.getValue().entrySet());
            sections.sort(Map.Entry.<String, Long>comparingByValue().reversed());
            if (sections.size() > 10) sections = sections.subList(0, 10);
            int y = laneTop + 32;
            int available = Math.max(0, height - y - 22 - sections.size() * 4);
            for (int i = 0; i < sections.size(); i++) {
                Map.Entry<String, Long> section = sections.get(i);
                long estimatedCpu = Math.round(section.getValue() * cpuFactor);
                int blockHeight = Math.max(2, (int) Math.round(available * Math.min(1.0, (double) estimatedCpu / WINDOW_NANOS)));
                blockHeight = Math.min(blockHeight, height - 16 - y);
                if (blockHeight <= 0) break;
                Rectangle area = new Rectangle(x + 6, y, laneWidth - 12, blockHeight);
                blocks.add(new Block(area, section.getKey(), estimatedCpu));
                g.setColor(new Color(12, 19, 31));
                g.fillRect(area.x, area.y, area.width, area.height);
                g.setColor(COLORS[i % COLORS.length]);
                g.fillRect(area.x + 2, area.y + 2, area.width - 4, area.height - 4);
                String name = section.getKey();
                int separator = name.lastIndexOf(" / ");
                if (separator >= 0) name = name.substring(separator + 3);
                while (g.getFontMetrics().stringWidth(name) > laneWidth - 30 && name.length() > 1) {
                    name = name.substring(0, name.length() - 1);
                }
                g.setColor(new Color(13, 23, 37));
                if (blockHeight >= 18) g.drawString(name, x + 13, y + 15);
                y += blockHeight + 4;
            }
            x += laneWidth + gap;
        }
        g.dispose();
    }
}
