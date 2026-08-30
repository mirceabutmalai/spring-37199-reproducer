# Reproducer for spring-framework#37199

`ClassFileTransformerAdapter.transform` holds `synchronized (this)` for the whole call,
including the delegate. With two persistence units there are two adapters on one class
loader, the JPA delegate re-enters the transformer chain from the start, and two threads
at different positions in the chain take the two monitors in opposite orders.

This runs that cycle in about a tenth of a second, in a plain JVM.

```bash
mvn -q clean compile exec:java
```

Exit code **1** means the deadlock was detected — the bug reproduced. Exit code 0 means
the run finished without one.

There is no container here, no JPA provider, no database, no entity and no agent. Three
Maven artifacts (`spring-orm`, `spring-context`, `jakarta.persistence-api`), four source
files, JDK 21.

## What it does

```
thread X : holds adapter 1 -> wants adapter 2
thread Y : holds adapter 2 -> wants adapter 1
```

Y is let through adapter 1 without resolving anything, so adapter 1 is released; Y then
takes adapter 2 and resolves a type from inside it, which re-enters the chain at position
1. X meanwhile holds adapter 1 and resolves from inside *it*, which re-enters at position
1 — reentrant, declined by the flag, correctly — and then asks for adapter 2.

Both monitors are ordinary, so `ThreadMXBean.findDeadlockedThreads()` reports the cycle,
and the run prints it and exits 1.

## What is Spring's, and what is this reproducer's

The point of the split below is that everything on the Spring side is used, not modelled.

| | supplied by |
|---|---|
| `ClassFileTransformerAdapter` | **Spring.** Never constructed here and never reached by reflection. |
| Registering two of them on one class loader | **Spring.** `new SpringPersistenceUnitInfo(loadTimeWeaver)` then `addTransformer(...)` — public API, and what a persistence provider calls. `addTransformer` is what wraps the delegate in the adapter. |
| The class loader that runs the chain | This reproducer — see [`WebappLikeClassLoader`](src/main/java/example/spring37199/WebappLikeClassLoader.java). |
| The JPA delegate that re-enters | This reproducer — see [`EnhancerLikeTransformer`](src/main/java/example/spring37199/EnhancerLikeTransformer.java). |
| The interleaving | Staged in `--mode=staged`. **Not** staged in `--mode=race`. |

The run prints the chain before it starts, so what is in it is visible rather than
claimed:

```
transformer chain on the class loader:
  position 1: org.springframework.orm.jpa.persistenceunit.ClassFileTransformerAdapter
              Standard ClassFileTransformer wrapping JPA transformer: ...
  position 2: org.springframework.orm.jpa.persistenceunit.ClassFileTransformerAdapter
              Standard ClassFileTransformer wrapping JPA transformer: ...
```

### The class loader

Two behaviours of Tomcat's `WebappClassLoaderBase`, and no others. Both were read out of
`catalina.jar` of Tomcat 11.0.22 with `javap -c`, from `getResourceAsStream(String)`:

- **reading a `.class` resource runs the chain.** When the name ends in `.class` and the
  transformer list is not empty, Tomcat reads the bytes, derives an internal class name
  from the path, runs every transformer over them and returns a stream over the result.
  No class is defined. This is what lets a delegate re-enter the chain by resolving a
  type through `ClassLoader.getResourceAsStream`.
- **the chain runs one transformer at a time.** The loop holds no lock of its own, so
  each adapter takes its monitor, works, and releases it before the next is entered.
  That is what allows one thread to be inside adapter 2 while another is inside adapter
  1 — the precondition for a cycle.

Tomcat passes `null` for both `classBeingRedefined` and `protectionDomain`; so does this.

### The delegate

Hibernate's `EnhancingClassTransformerImpl.transform` reaches
`EnhancerImpl.alreadyEnhanced`, which resolves the type through a Byte Buddy `TypePool`,
which reaches `ClassFileLocator.ForClassLoader.locate` and then
`ClassLoader.getResourceAsStream`. The delegate here does the last step and nothing else.

It rewrites no bytes and returns `null` — "no change" — from every call. Weaving is not
what deadlocks; the lock protocol around it is. That also keeps the reproducer free of
any bytecode library.

`--mode=race` additionally models the two properties of the enhancer that make the cycle
reachable without staging:

- **its type cache never evicts.** `EnhancerCacheProvider` extends
  `TypePool.CacheProvider.Simple`, so the nested read happens only the first time each
  name is seen — which is why the names walked have to be distinct.
- **the two units do not start level.** Each unit's enhancer was warmed by its own
  entities while that unit's factory was built, so the two caches hold different sets
  before any application thread arrives. That asymmetry is what produces a name unit 1
  answers from cache — releasing adapter 1 — while unit 2 still has to resolve it,
  holding adapter 2. Two units warmed identically would make adapter 1 behave as a
  single global lock and nothing would ever deadlock. Here each unit is pre-seeded with
  a different random half of the names.

## Results

Measured on JDK 21.0.11 (Temurin), Windows 10, Maven from the command above.

| run | outcome |
|---|---|
| `--mode=staged` | deadlock in **118 ms** |
| `--mode=race` | deadlock in **106–118 ms**, after 1–7 completed reads, on **5 of 5** seeds |
| `--mode=staged --adapter=revised` | deadlock in **106 ms** |
| `--mode=race --adapter=revised` | deadlock in **105–107 ms**, on **3 of 3** seeds |
| `--mode=staged --adapter=threadlocal` | completes, no deadlock |
| `--mode=race --adapter=threadlocal` | **2713** resources read, **2724** nested reads, 0 errors, no deadlock |

Spring versions, `--mode=staged`, all deadlocked: **7.0.6** (the production version),
**7.0.7**, **7.0.8**. Pass `-Dspring.version=…` to choose.

The 6.2.x line carries the identical construct — `javap -c` on spring-orm 6.2.13 shows
`monitorenter` at offset 4 and the `ClassTransformer.transform` delegate call at offset
33, inside it — but `SpringPersistenceUnitInfo` is package private there, so this
reproducer, which registers through public API only, needs 7.x.

## The revision made for 7.0.10

The issue was closed on 2026-08-29 by
[`133a372`](https://github.com/spring-projects/spring-framework/commit/133a372f910176d677aab692fdbfabecb356ecdd),
*"Reduce lock-guarded boolean field to thread-local"*, milestoned 7.0.10. It changes the
`boolean` field to a `ThreadLocal` and **keeps `synchronized (this)` around the whole
method**, delegate call included.

`--adapter=revised` is that body, copied into
[`RevisedSpringAdapter`](src/main/java/example/spring37199/RevisedSpringAdapter.java) so
that the released shape can be run before 7.0.10 exists. It deadlocks: 106 ms staged, and
105–107 ms on 3 of 3 race seeds, with the same two frames naming the revised class.

That is not surprising on inspection. The flag and the monitor answer two different
questions. The flag answers *am I already inside on this thread* — and under a monitor
admitting one thread at a time it was already thread-confined, set and cleared entirely
inside the lock, unreadable by anybody else. The cycle is between the two adapters'
**monitors**, which the revision leaves exactly as they were.

## The suggested fix, under the same harness

`--adapter=threadlocal` swaps in
[`ThreadLocalGuardAdapter`](src/main/java/example/spring37199/ThreadLocalGuardAdapter.java):
`ClassFileTransformerAdapter` with the `boolean` field and the monitor replaced by a
`ThreadLocal`, and nothing else changed. Same class loader, same transformers, same
staging.

It exists so the claim can be run rather than argued. The guard's purpose is unchanged —
the comment in Spring's version describes an over-eager delegate re-entering from inside
the transform, which is the same thread coming back, which is what a `ThreadLocal`
answers. The monitor answers a question nobody asked, about two *different* threads, and
answers it with an ordering that can invert.

That the protection is preserved rather than dropped is visible in the counts: the staged
run makes the same 2 nested reads under either adapter, and the race run under the
`ThreadLocal` adapter performs 2724 nested reads with 0 transform errors — more than the
deadlocking run ever reaches — and finishes.

## Options

```
--mode=staged        (default) put the two threads in the two positions, exactly
--mode=race          let two threads find the interleaving on their own
--adapter=spring     (default) Spring's ClassFileTransformerAdapter
--adapter=revised        the body committed for 7.0.10: ThreadLocal, monitor kept
--adapter=threadlocal    the fix the issue suggests: ThreadLocal, monitor removed
--timeout=30         seconds to wait before giving up
--names=4000         race mode only: how many distinct class resources to walk
--seed=1             race mode only: shuffle and cache-warming seed
```

```bash
mvn -q compile exec:java -Dexec.args="--mode=race --seed=3"
```

Exit code 2 means the run could not be set up — the staging did not complete, or race
mode found no class resources to walk.

## Known differences from the container traces

- The blocked frame here reports `ClassFileTransformerAdapter.java:61` where the
  container traces in the issue report `:60`. Both are the same `synchronized (this)`
  block — line 60 is the statement, 61 the first line inside it.
- Nothing here defines a class, so the run shows the cycle but not its consequence. In a
  container the consequence is the finding: every subsequent class definition in the
  application stops, silently, with nothing in any log.
- Race mode resolves more eagerly than Hibernate does, so it fires after 1–7 reads rather
  than the 18–38 seen in the container.
- Race mode is single-shot per JVM for the same reason the container probe was: the
  caches never evict, so a second pass over the same names measures nothing.

## Licence

[Apache License 2.0](LICENSE), the same as Spring Framework. Use it however is useful.

`ThreadLocalGuardAdapter` is adapted from Spring's own `ClassFileTransformerAdapter` and
carries that file's notice.
