package example.spring37199;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.instrument.ClassFileTransformer;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.CodeSource;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Queue;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.atomic.AtomicLong;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Stream;

import org.springframework.core.SpringVersion;
import org.springframework.instrument.classloading.LoadTimeWeaver;
import org.springframework.orm.jpa.persistenceunit.SpringPersistenceUnitInfo;

/**
 * Reproduces spring-framework#37199: two JPA persistence units under load-time weaving
 * deadlock on the monitors of their two {@code ClassFileTransformerAdapter}s.
 *
 * <p>There is no container here, no JPA provider, no database and no entity. What the
 * cycle needs is only three things, and all three are real:
 *
 * <ol>
 * <li><b>Two adapters on one class loader.</b> Registered the way an application
 *     registers them - {@code new SpringPersistenceUnitInfo(loadTimeWeaver)} and
 *     {@code addTransformer(...)}, which is public Spring API and is what a persistence
 *     provider calls. Spring creates the {@code ClassFileTransformerAdapter}; this
 *     reproducer never constructs one and never touches one by reflection.</li>
 * <li><b>A class loader that runs the chain the way Tomcat does.</b> See
 *     {@link WebappLikeClassLoader} - two behaviours, both read out of Tomcat 11.0.22's
 *     own bytecode.</li>
 * <li><b>A delegate that re-enters the chain.</b> See {@link EnhancerLikeTransformer} -
 *     Hibernate's enhancer resolving a type through
 *     {@code ClassLoader.getResourceAsStream} from inside its own transform.</li>
 * </ol>
 *
 * <p>Usage:
 *
 * <pre>
 * --mode=staged      (default) put the two threads in the two positions, exactly
 * --mode=race        let two threads find the interleaving on their own
 * --adapter=spring   (default) Spring's ClassFileTransformerAdapter
 * --adapter=threadlocal   the fix the issue suggests, for comparison
 * --timeout=30       seconds to wait before giving up
 * --names=4000       race mode only: how many distinct class resources to walk
 * --seed=1           race mode only: shuffle and cache-warming seed
 * </pre>
 *
 * <p>Exit code 1 means a deadlock was detected - the bug reproduced. Exit code 0 means
 * the run finished without one. Exit code 2 means the run could not be set up.
 */
public final class Reproducer {

    static final String THREAD_X = "thread-X";
    static final String THREAD_Y = "thread-Y";

    private static final String OUTER_READ_X = "example/spring37199/Reproducer.class";
    private static final String OUTER_READ_Y = "example/spring37199/WebappLikeClassLoader.class";
    private static final String NESTED_READ = "example/spring37199/ThreadLocalGuardAdapter.class";

    private static final String CLASS_SUFFIX = ".class";
    private static final String[] SKIPPED_PREFIXES = {
        "java/", "jakarta/",
        "org/hibernate/annotations/", "org/hibernate/bytecode/enhance/spi/",
        "org/hibernate/engine/spi/",
        /* Not for that reason - for a worse one. A multi release jar carries
         * META-INF/versions/N/foo/Bar.class, whose resource path does not match the class
         * name inside the bytes, so every such entry produces an exception rather than a
         * measurement. */
        "META-INF/",
    };

    /**
     * One class from each jar the race is allowed to walk, used only to ask that jar
     * where it is. Any class of the artifact would do.
     */
    private static final Class<?>[] CLASSPATH_ANCHORS = {
        Reproducer.class,
        SpringPersistenceUnitInfo.class,
        LoadTimeWeaver.class,
        SpringVersion.class,
        org.springframework.beans.factory.BeanFactory.class,
        jakarta.persistence.spi.ClassTransformer.class,
    };

    private static final int UNIT_COUNT = 2;
    private static final long WATCH_PERIOD_MS = 100L;
    private static final int DUMP_MAX_DEPTH = 32;

    private Reproducer() {
    }

    public static void main(String[] args) throws Exception {
        Options options = Options.parse(args);

        System.out.println("spring-framework#37199 reproducer");
        System.out.println("  spring   : " + SpringVersion.getVersion());
        System.out.println("  java     : " + System.getProperty("java.version")
                + " (" + System.getProperty("java.vm.name") + ")");
        System.out.println("  mode     : " + options.mode);
        System.out.println("  adapter  : " + options.adapter);
        System.out.println();

        WebappLikeClassLoader loader = new WebappLikeClassLoader(
                Reproducer.class.getClassLoader());
        LoadTimeWeaver weaver = new WebappLikeClassLoader.Weaver(loader);

        List<String> names = "race".equals(options.mode)
                ? collectClassResourceNames(options.names, options.seed)
                : List.of();

        if ("race".equals(options.mode) && names.isEmpty()) {
            System.out.println("no class resources found on the class path; nothing to walk");
            System.exit(2);
        }

        EnhancerLikeTransformer.Enhancement enhancement = "race".equals(options.mode)
                ? new EnhancerLikeTransformer.CacheDriven(names, UNIT_COUNT, options.seed)
                : new EnhancerLikeTransformer.Staged(NESTED_READ, options.timeoutMs);

        List<EnhancerLikeTransformer> delegates = new ArrayList<>();
        for (int unit = 0; unit < UNIT_COUNT; unit++) {
            EnhancerLikeTransformer delegate =
                    new EnhancerLikeTransformer(unit, "unit-" + (unit + 1), enhancement);
            delegates.add(delegate);
            register(weaver, delegate, options.adapter);
        }

        describeChain(loader);

        /* Before anything is allowed to block: one call now loads every class the
         * detection and the reporting need, so the watcher is not resolving them at the
         * moment two threads have stopped. */
        warmUpDeadlockDetection();

        boolean deadlocked = "race".equals(options.mode)
                ? runRace(loader, names, options.timeoutMs)
                : runStaged(loader, (EnhancerLikeTransformer.Staged) enhancement, options.timeoutMs);

        long nested = 0L;
        for (EnhancerLikeTransformer delegate : delegates) {
            nested += delegate.getNestedReads();
        }
        System.out.println();
        System.out.println("nested reads     : " + nested);
        System.out.println("transform errors : " + loader.getTransformFailures());

        if (deadlocked) {
            System.out.println();
            System.out.println("RESULT: deadlocked - reproduced.");
            System.exit(1);
        }

        if (enhancement instanceof EnhancerLikeTransformer.Staged staged && staged.hasTimedOut()) {
            System.out.println();
            System.out.println("RESULT: the staging did not complete in time; nothing was measured.");
            System.exit(2);
        }

        System.out.println();
        System.out.println("RESULT: no deadlock.");
        System.exit(0);
    }

    private static void register(LoadTimeWeaver weaver, EnhancerLikeTransformer delegate,
            String adapter) {

        if ("threadlocal".equals(adapter)) {
            weaver.addTransformer(new ThreadLocalGuardAdapter(delegate));
            return;
        }

        if ("revised".equals(adapter)) {
            weaver.addTransformer(new RevisedSpringAdapter(delegate));
            return;
        }

        if ("chainguard".equals(adapter)) {
            weaver.addTransformer(new ChainGuardAdapter(delegate));
            return;
        }

        /* The path an application takes. SpringPersistenceUnitInfo.addTransformer wraps
         * what it is handed in a ClassFileTransformerAdapter and hands that to the
         * weaver; nothing below this line is written by the reproducer. */
        SpringPersistenceUnitInfo unitInfo = new SpringPersistenceUnitInfo(weaver);
        unitInfo.addTransformer(delegate);
    }

    private static void describeChain(WebappLikeClassLoader loader) {
        List<ClassFileTransformer> chain = loader.getTransformers();
        System.out.println("transformer chain on the class loader:");
        for (int i = 0; i < chain.size(); i++) {
            ClassFileTransformer transformer = chain.get(i);
            System.out.println("  position " + (i + 1) + ": "
                    + transformer.getClass().getName());
            System.out.println("              " + transformer);
        }
        System.out.println();
    }

    /**
     * Puts one thread in each of the two positions the issue names, then lets both ask
     * for the other's monitor.
     */
    private static boolean runStaged(WebappLikeClassLoader loader,
            EnhancerLikeTransformer.Staged staged, long timeoutMs) throws InterruptedException {

        System.out.println("staging:");
        System.out.println("  " + THREAD_Y + " passes adapter 1 without resolving, then holds adapter 2");
        System.out.println("  " + THREAD_X + " then holds adapter 1 and asks for adapter 2");
        System.out.println("  " + THREAD_Y + " then asks for adapter 1");
        System.out.println();

        Thread y = new Thread(() -> read(loader, OUTER_READ_Y), THREAD_Y);
        Thread x = new Thread(() -> {
            /* X must not enter the chain until Y is past adapter 1 and inside adapter 2.
             * Enter earlier and X takes adapter 1 first, Y blocks on it, and the result is
             * one thread waiting for another - which is not what this issue is about. */
            if (!staged.awaitInsideAdapter2()) {
                return;
            }
            read(loader, OUTER_READ_X);
        }, THREAD_X);

        y.setDaemon(true);
        x.setDaemon(true);
        y.start();
        x.start();

        return watch(new Thread[] {x, y}, timeoutMs);
    }

    /**
     * The production shape: two threads reading distinct class resources, no coordination
     * of any kind, both persistence units long since built.
     */
    private static boolean runRace(WebappLikeClassLoader loader, List<String> names,
            long timeoutMs) throws InterruptedException {

        System.out.println("racing 2 threads over " + names.size() + " distinct class resources");
        System.out.println();

        Queue<String> queue = new ConcurrentLinkedQueue<>(names);
        CyclicBarrier barrier = new CyclicBarrier(2);
        AtomicLong readCount = new AtomicLong();

        Runnable racer = () -> {
            try {
                barrier.await();
            }
            catch (Exception ex) {
                return;
            }
            String name = queue.poll();
            while (name != null) {
                read(loader, name);
                readCount.incrementAndGet();
                name = queue.poll();
            }
        };

        Thread x = new Thread(racer, THREAD_X);
        Thread y = new Thread(racer, THREAD_Y);
        x.setDaemon(true);
        y.setDaemon(true);
        x.start();
        y.start();

        boolean deadlocked = watch(new Thread[] {x, y}, timeoutMs);
        System.out.println("resources read   : " + readCount.get());
        return deadlocked;
    }

    private static void read(ClassLoader loader, String resourceName) {
        try (InputStream stream = loader.getResourceAsStream(resourceName)) {
            if (stream != null) {
                stream.readAllBytes();
            }
        }
        catch (IOException | RuntimeException ex) {
            // a resource this run cannot read is a resource it does not need
        }
    }

    private static boolean watch(Thread[] threads, long timeoutMs) throws InterruptedException {
        ThreadMXBean bean = ManagementFactory.getThreadMXBean();
        long deadline = System.currentTimeMillis() + timeoutMs;
        long start = System.currentTimeMillis();

        while (System.currentTimeMillis() < deadline) {
            long[] deadlocked = bean.findDeadlockedThreads();
            if (deadlocked != null) {
                report(bean, deadlocked, System.currentTimeMillis() - start);
                return true;
            }
            if (!anyAlive(threads)) {
                return false;
            }
            Thread.sleep(WATCH_PERIOD_MS);
        }

        System.out.println("gave up after " + timeoutMs + " ms without a deadlock");
        return false;
    }

    private static boolean anyAlive(Thread[] threads) {
        for (Thread thread : threads) {
            if (thread.isAlive()) {
                return true;
            }
        }
        return false;
    }

    private static void report(ThreadMXBean bean, long[] ids, long elapsedMs) {
        System.out.println("DEADLOCK after " + elapsedMs + " ms, " + ids.length
                + " threads in the cycle");
        System.out.println();

        for (ThreadInfo info : bean.getThreadInfo(ids, true, true, DUMP_MAX_DEPTH)) {
            if (info == null) {
                continue;
            }
            System.out.println("\"" + info.getThreadName() + "\" waiting to lock "
                    + info.getLockName() + " held by \"" + info.getLockOwnerName() + "\"");
            for (StackTraceElement frame : info.getStackTrace()) {
                System.out.println("    at " + frame);
            }
            System.out.println();
        }
    }

    private static void warmUpDeadlockDetection() {
        ThreadMXBean bean = ManagementFactory.getThreadMXBean();
        bean.findDeadlockedThreads();
        bean.getThreadInfo(new long[] {Thread.currentThread().threadId()}, true, true,
                DUMP_MAX_DEPTH);
    }

    /**
     * Distinct class resource names from whatever this run can actually read.
     *
     * <p>Distinct is the requirement, not many: the enhancer's type cache never evicts,
     * so a repeated name stops producing the nested read after the first pass.
     *
     * <p>{@code java.class.path} is not enough on its own. Under {@code mvn exec:java}
     * that property holds Maven's own launcher and nothing else, because the project and
     * its dependencies are loaded by a separate realm - so a run launched that way would
     * walk a handful of names rather than thousands. Asking a few known classes where
     * they came from finds the same jars under either launch.
     */
    private static List<String> collectClassResourceNames(int limit, long seed) {
        List<String> found = new ArrayList<>();
        Set<File> roots = new LinkedHashSet<>();

        for (String entry : System.getProperty("java.class.path", "")
                .split(File.pathSeparator)) {
            if (!entry.isEmpty()) {
                roots.add(new File(entry));
            }
        }
        for (Class<?> anchor : CLASSPATH_ANCHORS) {
            File root = codeSourceOf(anchor);
            if (root != null) {
                roots.add(root);
            }
        }

        for (File root : roots) {
            if (root.isDirectory()) {
                collectFromDirectory(root.toPath(), found);
            }
            else if (root.getName().endsWith(".jar")) {
                collectFromJar(root, found);
            }
        }

        System.out.println("scanned " + roots.size() + " class path entries");
        Collections.shuffle(found, new Random(seed));
        return found.size() > limit ? new ArrayList<>(found.subList(0, limit)) : found;
    }

    private static File codeSourceOf(Class<?> anchor) {
        try {
            CodeSource source = anchor.getProtectionDomain().getCodeSource();
            if (source == null || source.getLocation() == null) {
                return null;
            }
            return new File(source.getLocation().toURI());
        }
        catch (URISyntaxException | RuntimeException ex) {
            return null;
        }
    }

    private static void collectFromJar(File jar, List<String> into) {
        try (JarFile open = new JarFile(jar)) {
            Enumeration<JarEntry> entries = open.entries();
            while (entries.hasMoreElements()) {
                String name = entries.nextElement().getName();
                if (name.endsWith(CLASS_SUFFIX) && !isSkipped(name)) {
                    into.add(name);
                }
            }
        }
        catch (IOException ex) {
            // a library this run cannot read is a library it does not need
        }
    }

    private static void collectFromDirectory(Path root, List<String> into) {
        try (Stream<Path> walk = Files.walk(root)) {
            walk.filter(Files::isRegularFile).forEach(path -> {
                String name = root.relativize(path).toString().replace('\\', '/');
                if (name.endsWith(CLASS_SUFFIX) && !isSkipped(name)) {
                    into.add(name);
                }
            });
        }
        catch (IOException ex) {
            // likewise
        }
    }

    private static boolean isSkipped(String resourceName) {
        for (String prefix : SKIPPED_PREFIXES) {
            if (resourceName.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    private static final class Options {

        private String mode = "staged";
        private String adapter = "spring";
        private long timeoutMs = 30_000L;
        private int names = 4000;
        private long seed = 1L;

        static Options parse(String[] args) {
            Options options = new Options();
            for (String arg : args) {
                int split = arg.indexOf('=');
                if (!arg.startsWith("--") || split < 0) {
                    continue;
                }
                String key = arg.substring(2, split);
                String value = arg.substring(split + 1);
                switch (key) {
                    case "mode" -> options.mode = value;
                    case "adapter" -> options.adapter = value;
                    case "timeout" -> options.timeoutMs = Long.parseLong(value) * 1000L;
                    case "names" -> options.names = Integer.parseInt(value);
                    case "seed" -> options.seed = Long.parseLong(value);
                    default -> System.out.println("ignoring unknown option " + arg);
                }
            }
            return options;
        }
    }
}
