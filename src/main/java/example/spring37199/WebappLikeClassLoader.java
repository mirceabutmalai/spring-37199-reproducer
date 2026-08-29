package example.spring37199;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.instrument.ClassFileTransformer;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.instrument.classloading.LoadTimeWeaver;

/**
 * A class loader that behaves the way Tomcat's {@code WebappClassLoaderBase} does in the
 * two respects this deadlock depends on, and in no other respect at all.
 *
 * <p>Both behaviours below were read out of {@code catalina.jar} of Tomcat 11.0.22 with
 * {@code javap -c}, from {@code WebappClassLoaderBase.getResourceAsStream(String)}:
 *
 * <ol>
 * <li><b>Reading a {@code .class} resource runs the transformer chain.</b> When the
 *     resource name ends in {@code .class} and the transformer list is not empty, Tomcat
 *     reads the bytes, derives an internal class name from the path, runs every
 *     transformer over the bytes and hands back a stream over the result. No class is
 *     defined. This is what lets a transformer re-enter the chain from inside its own
 *     transform, by resolving a type through {@code ClassLoader.getResourceAsStream}.</li>
 * <li><b>The chain runs one transformer at a time.</b> The loop holds no lock of its
 *     own: it iterates the list and calls each transformer in turn, so each
 *     {@code ClassFileTransformerAdapter} takes its monitor, does its work, and releases
 *     it before the next one is entered. That is what allows one thread to be inside
 *     adapter 2 while another is inside adapter 1 - the precondition for a cycle.</li>
 * </ol>
 *
 * <p>Deliberate differences from Tomcat, none of which touch the lock protocol:
 * parent-first delegation is left to {@code ClassLoader}, resources are not looked up in
 * a {@code WebResourceRoot}, the internal name is derived without stripping a leading
 * slash because these paths have none, and nothing here ever defines a class. Tomcat
 * passes {@code null} for both {@code classBeingRedefined} and {@code protectionDomain};
 * so does this.
 *
 * <p>A transformer that throws is counted and the read gives up, which is Tomcat's
 * behaviour too - it logs {@code webappClassLoader.transformError} and returns null. The
 * count is reported at the end of a run so that a failed run cannot be mistaken for a
 * clean one.
 */
public final class WebappLikeClassLoader extends ClassLoader {

    private static final String CLASS_SUFFIX = ".class";

    private final List<ClassFileTransformer> transformers = new CopyOnWriteArrayList<>();
    private final AtomicInteger transformFailures = new AtomicInteger();

    public WebappLikeClassLoader(ClassLoader parent) {
        super("webapp-like", parent);
    }

    /**
     * The chain, in registration order. Position 1 is adapter 1.
     */
    public List<ClassFileTransformer> getTransformers() {
        return List.copyOf(transformers);
    }

    public int getTransformFailures() {
        return transformFailures.get();
    }

    @Override
    public InputStream getResourceAsStream(String name) {
        InputStream stream = super.getResourceAsStream(name);
        if (stream == null || !name.endsWith(CLASS_SUFFIX) || transformers.isEmpty()) {
            return stream;
        }

        byte[] bytes;
        try (InputStream toRead = stream) {
            bytes = toRead.readAllBytes();
        }
        catch (IOException ex) {
            transformFailures.incrementAndGet();
            return null;
        }

        String internalName = name.substring(0, name.length() - CLASS_SUFFIX.length());

        for (ClassFileTransformer transformer : transformers) {
            byte[] transformed;
            try {
                transformed = transformer.transform(this, internalName, null, null, bytes);
            }
            catch (Throwable ex) {
                transformFailures.incrementAndGet();
                return null;
            }
            if (transformed != null) {
                bytes = transformed;
            }
        }

        return new ByteArrayInputStream(bytes);
    }

    /**
     * The {@code LoadTimeWeaver} Spring registers adapters into.
     *
     * <p>Its whole job is to put what it is handed onto the chain above, which is what
     * {@code Tomcat}'s {@code TomcatLoadTimeWeaver} does through
     * {@code WebappClassLoader.addTransformer}.
     */
    public static final class Weaver implements LoadTimeWeaver {

        private final WebappLikeClassLoader loader;

        public Weaver(WebappLikeClassLoader loader) {
            this.loader = loader;
        }

        @Override
        public void addTransformer(ClassFileTransformer transformer) {
            this.loader.transformers.add(transformer);
        }

        @Override
        public ClassLoader getInstrumentableClassLoader() {
            return this.loader;
        }

        @Override
        public ClassLoader getThrowawayClassLoader() {
            return new WebappLikeClassLoader(this.loader.getParent());
        }
    }
}
