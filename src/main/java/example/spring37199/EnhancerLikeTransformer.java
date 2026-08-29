package example.spring37199;

import java.io.IOException;
import java.io.InputStream;
import java.security.ProtectionDomain;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import jakarta.persistence.spi.ClassTransformer;

/**
 * The JPA {@code ClassTransformer} a persistence provider installs, reduced to the one
 * thing that matters here: it resolves a type from inside its own {@code transform}, and
 * it resolves it through {@code ClassLoader.getResourceAsStream}.
 *
 * <p>That is what Hibernate's does. {@code EnhancingClassTransformerImpl.transform} calls
 * {@code EnhancerImpl.doEnhance}, which calls {@code alreadyEnhanced}, which resolves the
 * type through a Byte Buddy {@code TypePool}, which reaches
 * {@code ClassFileLocator.ForClassLoader.locate} and then
 * {@code ClassLoader.getResourceAsStream}. On Tomcat that call runs the whole transformer
 * chain again from position 1 - see {@link WebappLikeClassLoader}.
 *
 * <p>Nothing here rewrites bytes. Weaving is not what deadlocks; the lock protocol around
 * it is, so this returns {@code null} - "no change" - from every call. That also keeps the
 * reproducer independent of any bytecode library.
 *
 * <p>Whether a given call resolves anything, and what, is left to an {@link Enhancement}.
 * The two implementations below are the two ways this reproducer arranges the interleaving:
 * {@link Staged} arranges it exactly, {@link CacheDriven} lets two threads find it.
 */
public final class EnhancerLikeTransformer implements ClassTransformer {

    /**
     * The prefixes Hibernate's {@code CorePrefixFilter} answers immediately. A name under
     * one of these reaches the adapter's monitor but never the enhancer, so it can occupy
     * the chain but can never produce the nested read the cycle needs.
     */
    private static final String[] CORE_PREFIXES = {
        "java/",
        "jakarta/",
        "org/hibernate/annotations/",
        "org/hibernate/bytecode/enhance/spi/",
        "org/hibernate/engine/spi/",
    };

    private final int unitIndex;
    private final String unitName;
    private final Enhancement enhancement;
    private final AtomicLong nestedReads = new AtomicLong();

    public EnhancerLikeTransformer(int unitIndex, String unitName, Enhancement enhancement) {
        this.unitIndex = unitIndex;
        this.unitName = unitName;
        this.enhancement = enhancement;
    }

    public long getNestedReads() {
        return this.nestedReads.get();
    }

    @Override
    public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined,
            ProtectionDomain protectionDomain, byte[] classfileBuffer) {

        if (isCoreType(className)) {
            return null;
        }

        String toResolve = this.enhancement.typeToResolve(this.unitIndex, className);
        if (toResolve == null) {
            return null;
        }

        /* The whole mechanism is this call. It is made from inside
         * ClassFileTransformerAdapter.transform, which is holding this adapter's monitor,
         * and it runs the chain again from position 1 - so it asks for every other
         * adapter's monitor while holding one. */
        this.nestedReads.incrementAndGet();
        try (InputStream nested = loader.getResourceAsStream(toResolve)) {
            if (nested != null) {
                nested.readAllBytes();
            }
        }
        catch (IOException ex) {
            // a type this transformer cannot read is a type it does not need
        }

        return null;
    }

    @Override
    public String toString() {
        return "enhancer-like transformer of persistence unit '" + this.unitName + "'";
    }

    private static boolean isCoreType(String className) {
        for (String prefix : CORE_PREFIXES) {
            if (className.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Decides what, if anything, a transform resolves.
     */
    public interface Enhancement {

        /**
         * @param unitIndex 0 for the first persistence unit, 1 for the second
         * @param className the internal name being transformed, without {@code .class}
         * @return the resource to read from inside the chain, or {@code null} to read
         *         nothing - which is what a type the enhancer has already resolved does
         */
        String typeToResolve(int unitIndex, String className);
    }

    /**
     * Puts the two threads in the two positions the issue names, and no others.
     *
     * <pre>
     * thread X : holds adapter 1 -&gt; wants adapter 2
     * thread Y : holds adapter 2 -&gt; wants adapter 1
     * </pre>
     *
     * <p>Y goes first and is let through adapter 1 without resolving anything, so adapter
     * 1 is released; it then takes adapter 2 and stops there. X is not allowed to enter
     * the chain until that has happened - otherwise X takes adapter 1 first, Y blocks on
     * it, and what results is one thread waiting for another rather than a cycle. X then
     * takes adapter 1, says so, and resolves; Y, released by X saying so, resolves too.
     *
     * <p>Each is now holding one adapter and asking for the other. Both are plain
     * monitors, so {@code ThreadMXBean.findDeadlockedThreads} sees it.
     *
     * <p>This stages an interleaving; it does not invent one. {@link CacheDriven} reaches
     * the same state with no coordination at all.
     */
    public static final class Staged implements Enhancement {

        private final String typeToResolve;
        private final long timeoutMs;
        private final CountDownLatch insideAdapter1 = new CountDownLatch(1);
        private final CountDownLatch insideAdapter2 = new CountDownLatch(1);
        private volatile boolean timedOut;

        public Staged(String typeToResolve, long timeoutMs) {
            this.typeToResolve = typeToResolve;
            this.timeoutMs = timeoutMs;
        }

        /**
         * Blocks until Y is past adapter 1 and inside adapter 2.
         *
         * @return false if that did not happen in time
         */
        public boolean awaitInsideAdapter2() {
            return await(this.insideAdapter2);
        }

        public boolean hasTimedOut() {
            return this.timedOut;
        }

        @Override
        public String typeToResolve(int unitIndex, String className) {
            String role = Thread.currentThread().getName();

            if (unitIndex == 0 && Reproducer.THREAD_X.equals(role)) {
                // X holds adapter 1 from here until it returns
                this.insideAdapter1.countDown();
                return this.typeToResolve;
            }

            if (unitIndex == 1 && Reproducer.THREAD_Y.equals(role)) {
                // Y holds adapter 2 from here until it returns, and adapter 1 is free:
                // the chain released it before entering this one
                this.insideAdapter2.countDown();
                await(this.insideAdapter1);
                return this.typeToResolve;
            }

            // every other visit resolves nothing, which is what lets Y through adapter 1
            return null;
        }

        private boolean await(CountDownLatch latch) {
            try {
                if (latch.await(this.timeoutMs, TimeUnit.MILLISECONDS)) {
                    return true;
                }
            }
            catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
            this.timedOut = true;
            return false;
        }
    }

    /**
     * No coordination: each unit resolves any type it has not resolved before, and
     * remembers it forever.
     *
     * <p>Two properties of Hibernate's enhancer are what this models, and the deadlock
     * needs both.
     *
     * <p><b>The cache never evicts.</b> {@code EnhancerCacheProvider} extends
     * {@code TypePool.CacheProvider.Simple}, which has no eviction at all, so the nested
     * read happens only the first time each name is seen. That is why the names a run
     * walks have to be distinct, and why a second run over the same list measures nothing.
     *
     * <p><b>The two units do not start level.</b> Each unit's enhancer was warmed by its
     * own entities and their supertypes while that unit's factory was being built, so the
     * two caches hold different sets before any application thread arrives. That
     * asymmetry is the point: it is what produces a name that unit 1 answers from cache -
     * releasing adapter 1 - while unit 2 still has to resolve it, holding adapter 2. Two
     * units that were warmed identically would make adapter 1 behave as a single global
     * lock and nothing would ever deadlock. Here each unit is pre-seeded with a different
     * random half of the names, which is the same asymmetry arrived at cheaply.
     */
    public static final class CacheDriven implements Enhancement {

        private final List<String> names;
        private final Set<String>[] resolved;

        @SuppressWarnings("unchecked")
        public CacheDriven(List<String> names, int unitCount, long seed) {
            this.names = names;
            this.resolved = new Set[unitCount];

            for (int unit = 0; unit < unitCount; unit++) {
                Set<String> warm = new HashSet<>();
                Random random = new Random(seed + unit);
                for (String name : names) {
                    if (random.nextBoolean()) {
                        warm.add(stripSuffix(name));
                    }
                }
                this.resolved[unit] = java.util.Collections.synchronizedSet(warm);
            }
        }

        @Override
        public String typeToResolve(int unitIndex, String className) {
            if (!this.resolved[unitIndex].add(className)) {
                return null;
            }
            // a supertype, an interface, an annotation - some other type this one needs
            int pick = Math.floorMod(className.hashCode() * 31, this.names.size());
            return this.names.get(pick);
        }

        private static String stripSuffix(String resourceName) {
            return resourceName.endsWith(".class")
                    ? resourceName.substring(0, resourceName.length() - ".class".length())
                    : resourceName;
        }
    }
}
