package dev.engine_room.flywheel.backend.compile;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

/**
 * Render-thread subscriptions to native program builds. Publication runs at the pipeline poll boundary.
 */
public final class GlNativeProgramRequests {
    private static final Map<GlNativeProgramPool.Key, Build> BUILDS = new HashMap<>();
    private static final ArrayDeque<Subscription> READY = new ArrayDeque<>();

    public static long requests;
    public static long links;
    public static long coalesced;
    public static long poolHits;

    private GlNativeProgramRequests() {
    }

    public static GlAsyncCompiler.Pending request(GlNativeProgramPool.Key key,
                                                  BiFunction<IntConsumer, Runnable, GlAsyncCompiler.Pending> submit,
                                                  Consumer<GlNativeProgramPool.Entry> publish, Runnable onFailure) {
        requests++;
        GlNativeProgramPool.Entry entry = GlNativeProgramPool.acquire(key);
        if (entry != null) {
            poolHits++;
            Subscription subscription = new Subscription(publish, onFailure);
            subscription.entry = entry;
            READY.add(subscription);
            return subscription;
        }

        Build build = BUILDS.get(key);
        if (build == null) {
            build = new Build(key);
            BUILDS.put(key, build);
            Build target = build;
            try {
                build.pending = submit.apply(target::publish, target::fail);
            } catch (RuntimeException | Error failure) {
                BUILDS.remove(key, build);
                throw failure;
            }
            links++;
        } else {
            coalesced++;
        }
        Subscription subscription = new Subscription(publish, onFailure);
        subscription.build = build;
        build.subscribers.add(subscription);
        return subscription;
    }

    public static void poll() {
        while (!READY.isEmpty()) {
            Subscription subscription = READY.removeFirst();
            GlNativeProgramPool.Entry entry = subscription.entry;
            subscription.entry = null;
            subscription.publish.accept(entry);
        }
    }

    private static final class Build {
        private final GlNativeProgramPool.Key key;
        private final List<Subscription> subscribers = new ArrayList<>();
        private GlAsyncCompiler.Pending pending;

        private Build(GlNativeProgramPool.Key key) {
            this.key = key;
        }

        private void fail() {
            BUILDS.remove(key, this);
            for (Subscription subscription : subscribers) {
                subscription.build = null;
                subscription.onFailure.run();
            }
            subscribers.clear();
        }

        private void publish(int handle) {
            BUILDS.remove(key, this);
            GlNativeProgramPool.Entry entry = GlNativeProgramPool.adopt(key, handle);
            for (int i = 0; i < subscribers.size(); i++) {
                Subscription subscription = subscribers.get(i);
                subscription.build = null;
                if (i != 0) entry = GlNativeProgramPool.acquire(key);
                subscription.publish.accept(entry);
            }
            subscribers.clear();
        }
    }

    private static final class Subscription implements GlAsyncCompiler.Pending {
        private final Consumer<GlNativeProgramPool.Entry> publish;
        private final Runnable onFailure;
        private Build build;
        private GlNativeProgramPool.Entry entry;

        private Subscription(Consumer<GlNativeProgramPool.Entry> publish, Runnable onFailure) {
            this.publish = publish;
            this.onFailure = onFailure;
        }

        @Override
        public void cancel() {
            if (entry != null) {
                READY.remove(this);
                entry.release();
                entry = null;
            } else if (build != null) {
                build.subscribers.remove(this);
                if (build.subscribers.isEmpty()) {
                    BUILDS.remove(build.key, build);
                    build.pending.cancel();
                }
                build = null;
            }
        }
    }
}
