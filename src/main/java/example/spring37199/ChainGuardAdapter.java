/*
 * Adapted from org.springframework.orm.jpa.persistenceunit.ClassFileTransformerAdapter,
 * Copyright 2002-present the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package example.spring37199;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;

import jakarta.persistence.spi.ClassTransformer;

/**
 * {@code ClassFileTransformerAdapter} that <b>keeps the monitor</b> and still cannot form
 * the cycle, by making the re-entrancy guard span the chain rather than one adapter.
 *
 * <p>The guard is static, so it means "this thread is already inside <i>some</i> adapter"
 * rather than "inside <i>this</i> adapter", and it is read <b>before</b> the monitor is
 * taken. A thread that re-enters the chain from inside a delegate therefore declines at
 * every adapter without acquiring anything, so no thread ever holds two monitors and there
 * is no ordering to invert.
 *
 * <p>What this preserves is the reason the monitor is presumably there: two threads still
 * cannot be inside one delegate at the same time. A provider that is not thread safe keeps
 * the protection it has today. {@code jakarta.persistence.spi.ClassTransformer} says
 * nothing about being callable concurrently, and neither does
 * {@code java.lang.instrument.ClassFileTransformer}, so that protection is not obviously
 * free to drop.
 *
 * <p>What it changes: during a nested read, no adapter transforms at all, where today the
 * adapters after the re-entering one still do. A nested read is a delegate resolving a
 * type to inspect it rather than a class being defined, so declining looks closer to the
 * intent of the existing comment - which speaks of backing out when called from within the
 * transform delegate - but it is a behaviour change and is the part worth arguing about.
 *
 * <p>Selected with {@code --adapter=chainguard}.
 */
public final class ChainGuardAdapter implements ClassFileTransformer {

    /**
     * Shared by every adapter, because the question is about the chain.
     *
     * <p>In Spring this would be a static field of the adapter class, which is per
     * application in the ordinary deployment where spring-orm sits in WEB-INF/lib.
     */
    private static final ThreadLocal<Boolean> CURRENTLY_TRANSFORMING = new ThreadLocal<>();

    private final ClassTransformer classTransformer;

    public ChainGuardAdapter(ClassTransformer classTransformer) {
        this.classTransformer = classTransformer;
    }

    @Override
    public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined,
            ProtectionDomain protectionDomain, byte[] classfileBuffer) {

        /* Read before the monitor, or the thread blocks before it can decline - which is
         * the whole of the difference from the version committed for 7.0.10. */
        if (CURRENTLY_TRANSFORMING.get() == Boolean.TRUE) {
            // Defensively back out when called from within the transform delegate below:
            // in particular, for the over-eager transformer implementation in Hibernate.
            return null;
        }

        synchronized (this) {
            CURRENTLY_TRANSFORMING.set(Boolean.TRUE);
            try {
                return this.classTransformer.transform(
                        loader, className, classBeingRedefined, protectionDomain, classfileBuffer);
            }
            catch (ClassCircularityError ex) {
                throw new IllegalStateException("Failed to weave class [" + className + "]", ex);
            }
            catch (Throwable ex) {
                throw new IllegalStateException("Could not weave class [" + className + "]", ex);
            }
            finally {
                CURRENTLY_TRANSFORMING.remove();
            }
        }
    }

    @Override
    public String toString() {
        return "chain-guarded ClassFileTransformer wrapping JPA transformer: "
                + this.classTransformer;
    }
}
