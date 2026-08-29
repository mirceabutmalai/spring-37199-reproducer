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
 * {@code ClassFileTransformerAdapter} with the change the issue suggests, and nothing
 * else: the {@code boolean} field and the monitor around it are replaced by a
 * {@code ThreadLocal}.
 *
 * <p>This is not Spring's class and is not used unless the run asks for it with
 * {@code --adapter=threadlocal}. It exists so that the claim "the suggested fix removes
 * the cycle" can be run rather than argued: same harness, same class loader, same
 * transformers, same staging - only the adapter differs.
 *
 * <p>What the guard is for is unchanged. The comment in Spring's version says it: an
 * over-eager delegate re-enters the chain from inside the transform, and the adapter has
 * to decline the re-entry. That is the same thread coming back, which is what a
 * {@code ThreadLocal} answers. The monitor answers a question nobody asked - what two
 * different threads should do - and answers it with an ordering that can invert.
 *
 * <p>The error handling is copied from the original so that the two differ in one thing.
 */
public final class ThreadLocalGuardAdapter implements ClassFileTransformer {

    private final ClassTransformer classTransformer;

    private final ThreadLocal<Boolean> currentlyTransforming = new ThreadLocal<>();

    public ThreadLocalGuardAdapter(ClassTransformer classTransformer) {
        this.classTransformer = classTransformer;
    }

    @Override
    public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined,
            ProtectionDomain protectionDomain, byte[] classfileBuffer) {

        if (Boolean.TRUE.equals(this.currentlyTransforming.get())) {
            // Defensively back out when called from within the transform delegate below:
            // in particular, for the over-eager transformer implementation in Hibernate.
            return null;
        }

        this.currentlyTransforming.set(Boolean.TRUE);
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
            this.currentlyTransforming.remove();
        }
    }

    @Override
    public String toString() {
        return "ThreadLocal-guarded ClassFileTransformer wrapping JPA transformer: "
                + this.classTransformer;
    }
}
