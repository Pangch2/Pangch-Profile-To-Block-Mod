package pangch.pangchProfileToBlockMod.client;

import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.util.profiling.metrics.MetricCategory;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/** Captures the game's existing profiler sections while the window is open. */
public final class ProfilerCapture implements ProfilerFiller {
    public record Sample(long threadId, String thread, String section, long nanos, long ended) {}
    private static final class Section {
        final String path;
        final long started;
        long children;
        Section(String path, long started) { this.path = path; this.started = started; }
    }

    public static volatile boolean enabled;
    private static final ConcurrentLinkedQueue<Sample> SAMPLES = new ConcurrentLinkedQueue<>();
    private static final AtomicInteger QUEUED = new AtomicInteger();
    private static final ThreadLocal<ProfilerCapture> LOCAL = ThreadLocal.withInitial(ProfilerCapture::new);
    private final ArrayDeque<Section> stack = new ArrayDeque<>();

    private ProfilerCapture() {}

    public static ProfilerFiller attach(ProfilerFiller original) {
        return enabled ? ProfilerFiller.combine(original, LOCAL.get()) : original;
    }

    public static List<Sample> drain() {
        List<Sample> result = new ArrayList<>();
        Sample sample;
        while ((sample = SAMPLES.poll()) != null) {
            QUEUED.decrementAndGet();
            result.add(sample);
        }
        return result;
    }

    @Override public void startTick() { stack.clear(); }
    @Override public void endTick() { stack.clear(); }
    @Override public void push(String name) {
        String parent = stack.isEmpty() ? "" : stack.peek().path + " / ";
        stack.push(new Section(parent + name, System.nanoTime()));
    }
    @Override public void push(Supplier<String> name) { push(name.get()); }
    @Override public void pop() {
        if (stack.isEmpty()) return;
        Section section = stack.pop();
        long now = System.nanoTime();
        long elapsed = now - section.started;
        if (!stack.isEmpty()) stack.peek().children += elapsed;
        if (enabled && elapsed > section.children) {
            if (QUEUED.incrementAndGet() <= 20_000) {
                Thread thread = Thread.currentThread();
                SAMPLES.add(new Sample(thread.threadId(), thread.getName(), section.path, elapsed - section.children, now));
            } else {
                QUEUED.decrementAndGet();
            }
        }
    }
    @Override public void popPush(String name) { pop(); push(name); }
    @Override public void popPush(Supplier<String> name) { pop(); push(name); }
    @Override public void markForCharting(MetricCategory category) {}
    @Override public void incrementCounter(String name, int amount) {}
    @Override public void incrementCounter(Supplier<String> name, int amount) {}

    public static void main(String[] args) {
        enabled = true;
        ProfilerCapture capture = LOCAL.get();
        capture.push("parent");
        long until = System.nanoTime() + 1_000_000;
        while (System.nanoTime() < until) Thread.onSpinWait();
        capture.push("child");
        until = System.nanoTime() + 1_000_000;
        while (System.nanoTime() < until) Thread.onSpinWait();
        capture.pop();
        capture.pop();
        List<Sample> samples = drain();
        assert samples.size() == 2;
        assert samples.stream().anyMatch(sample -> sample.section().equals("parent / child") && sample.nanos() > 0);
        assert samples.stream().anyMatch(sample -> sample.section().equals("parent") && sample.nanos() >= 0);
        enabled = false;
    }
}
