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
 * {@code ClassFileTransformerAdapter} exactly as revised for 7.0.10 by commit
 * 133a372f910176d677aab692fdbfabecb356ecdd: the {@code boolean} field became a
 * {@code ThreadLocal}, and {@code synchronized (this)} was kept around the whole method,
 * delegate call included.
 *
 * <p>It is here so that the released shape can be run rather than reasoned about, under
 * the same harness as everything else. The flag and the monitor answer two different
 * questions - the flag answers "am I already inside on THIS thread", the monitor answers
 * "may another thread be inside at all" - and only the first was changed.
 *
 * <p>Selected with {@code --adapter=revised}. Not Spring's class; a copy of its body, so
 * that a run against 7.0.8 can show what 7.0.10 will do before 7.0.10 exists.
 */
public final class RevisedSpringAdapter implements ClassFileTransformer {

    private final ClassTransformer classTransformer;

    private final ThreadLocal<Boolean> currentlyTransforming = new ThreadLocal<>();

    public RevisedSpringAdapter(ClassTransformer classTransformer) {
        this.classTransformer = classTransformer;
    }

    @Override
    public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined,
            ProtectionDomain protectionDomain, byte[] classfileBuffer) {

        synchronized (this) {
            if (this.currentlyTransforming.get() == Boolean.TRUE) {
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
    }

    @Override
    public String toString() {
        return "7.0.10-revised ClassFileTransformer wrapping JPA transformer: "
                + this.classTransformer;
    }
}
