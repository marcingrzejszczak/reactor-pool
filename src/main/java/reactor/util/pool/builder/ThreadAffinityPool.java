/*
 * Copyright (c) 2011-2019 Pivotal Software Inc, All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package reactor.util.pool.builder;

import org.reactivestreams.Subscription;
import reactor.core.CoreSubscriber;
import reactor.core.Disposable;
import reactor.core.Exceptions;
import reactor.core.Scannable;
import reactor.core.publisher.Mono;
import reactor.core.publisher.MonoOperator;
import reactor.core.publisher.Operators;
import reactor.core.scheduler.Schedulers;
import reactor.util.Logger;
import reactor.util.Loggers;
import reactor.util.annotation.Nullable;
import reactor.util.concurrent.Queues;
import reactor.util.pool.api.Pool;
import reactor.util.pool.api.PoolConfig;
import reactor.util.pool.api.PooledRef;

import java.io.Closeable;
import java.io.IOException;
import java.util.LinkedList;
import java.util.Objects;
import java.util.Queue;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;

/**
 * A implementation of {@link Pool} that tries to avoid crossing thread boundaries by favoring serving available
 * elements from a {@link ThreadLocal} queue.
 *
 * @author Simon Baslé
 */
final class ThreadAffinityPool<POOLABLE> implements Pool<POOLABLE>, Disposable {


    WeakHashMap<Thread, Queue<AtomicReference<TAPoolInner<POOLABLE>>>> pendingLocal = new WeakHashMap<>();

    final Queue<TAPooledRef<POOLABLE>> available_mpmc;

    private static final Queue TERMINATED = Queues.empty().get();

    volatile Queue<AtomicReference<TAPoolInner<POOLABLE>>> allPendings_mpmc;
    private static final AtomicReferenceFieldUpdater<ThreadAffinityPool, Queue> PENDING = AtomicReferenceFieldUpdater.newUpdater(ThreadAffinityPool.class, Queue.class, "allPendings_mpmc");


    //A pool should be rare enough that having instance loggers should be ok
    //This helps with testability of some methods that for now mainly log
    private final Logger logger = Loggers.getLogger(ThreadAffinityPool.class);

    private final PoolConfig<POOLABLE> poolConfig;

    volatile int live;
    private static final AtomicIntegerFieldUpdater<ThreadAffinityPool> LIVE = AtomicIntegerFieldUpdater.newUpdater(ThreadAffinityPool.class, "live");


    ThreadAffinityPool(PoolConfig<POOLABLE> poolConfig) {
        this.poolConfig = poolConfig;

        //TODO replace with JCTools bounded MPMC queue MpmcArrayQueue?
        this.available_mpmc = new ConcurrentLinkedQueue<>();
        this.allPendings_mpmc = new ConcurrentLinkedQueue<>();

        for (int i = 0; i < poolConfig.minSize(); i++) {
            POOLABLE poolable = Objects.requireNonNull(poolConfig.allocator().block(), "allocator returned null in constructor");
            available_mpmc.offer(new TAPooledRef<>(this, poolable)); //the pool slot won't access this pool instance until after it has been constructed
        }
        this.live = available_mpmc.size();
    }

    @Override
    public Mono<PooledRef<POOLABLE>> borrow() {
        return new TAQueuePoolMono<>(this); //the mono is unknown to the pool until both subscribed and requested
    }

    @SuppressWarnings("WeakerAccess")
    final void registerPendingBorrower(TAPoolInner<POOLABLE> s) {
        if (allPendings_mpmc != TERMINATED) {
            //first check if available
            TAPooledRef<POOLABLE> item = available_mpmc.poll();
            if (item != null) {
                s.deliver(item);
                return;
            }

            if (LIVE.getAndIncrement(this) < poolConfig.maxSize()) {
                poolConfig.allocator()
                        //we expect the allocator will publish in the same thread or a "compatible" one
                        // (like EventLoopGroup for Netty connections), which makes it more suitable to use with Schedulers.immediate()
                        // but we'll still accommodate for the deliveryScheduler
                        .publishOn(poolConfig.deliveryScheduler())
                        .subscribe(newInstance -> s.deliver(new TAPooledRef<>(this, newInstance)),
                                e -> {
                                    LIVE.decrementAndGet(this);
                                    s.fail(e);
                                });
                return;
            }

            //if not available and already at max, put in local pending queue
            //first compensate the increment
            LIVE.decrementAndGet(this);
            //then update the local/global pending queues
            AtomicReference<TAPoolInner<POOLABLE>> aRef = new AtomicReference<>(s);
            allPendings_mpmc.offer(aRef);

            Queue<AtomicReference<TAPoolInner<POOLABLE>>> localQueue = pendingLocal.computeIfAbsent(Thread.currentThread(), it -> new LinkedList<>());
            localQueue.offer(aRef);
        }
        else {
            s.fail(new RuntimeException("Pool has been shut down"));
        }
    }

    void slowPathRecycle(TAPooledRef<POOLABLE> pooledRef) {
        TAPoolInner<POOLABLE> candidate = null;
        //iterate over allPendings_mpmc, removing nulled out pendings
        for (AtomicReference<TAPoolInner<POOLABLE>> pendingRef : allPendings_mpmc) {
            TAPoolInner inner = pendingRef.get();
            if (inner != null && candidate == null) {
                candidate = inner;
                pendingRef.set(null);
                allPendings_mpmc.remove(pendingRef);
            }
            else if (inner == null) {
                allPendings_mpmc.remove(pendingRef);
            }
            //else inner not null but candidate already found
        }

        if (candidate != null) {
            candidate.deliver(pooledRef);
        }
        else {
            available_mpmc.offer(pooledRef);
        }
    }

    void tryRecreate() {
        if (allPendings_mpmc == TERMINATED) return;

        Queue<AtomicReference<TAPoolInner<POOLABLE>>> localQueue = pendingLocal.get(Thread.currentThread());
        if (localQueue == null) return;

        if (LIVE.getAndIncrement(this) < poolConfig.maxSize()) {

            TAPoolInner<POOLABLE> candidate = null;
            AtomicReference<TAPoolInner<POOLABLE>> innerRef;
            while((innerRef = localQueue.poll()) != null) {
                candidate = innerRef.getAndSet(null);
                if (candidate != null) {
                    break;
                }
            }

            if (candidate == null) {
                LIVE.decrementAndGet(this);
                return;
            }

            //is there an available?
            TAPooledRef<POOLABLE> item = available_mpmc.poll();
            if (item != null) {
                candidate.deliver(item);
            }
            else {
                TAPoolInner<POOLABLE> s = candidate;
                poolConfig.allocator()
                        //we expect the allocator will publish in the same thread or a "compatible" one
                        // (like EventLoopGroup for Netty connections), which makes it more suitable to use with Schedulers.immediate()
                        // but we'll still accommodate for the deliveryScheduler
                        .publishOn(poolConfig.deliveryScheduler())
                        .subscribe(newInstance -> s.deliver(new TAPooledRef<>(this, newInstance)),
                                e -> {
                                    LIVE.decrementAndGet(this);
                                    s.fail(e);
                                });
            }
        }
    }

    @SuppressWarnings("WeakerAccess")
    final void maybeRecycleAndDrain(TAPooledRef<POOLABLE> pooledRef) {
        if (allPendings_mpmc != TERMINATED) {
            if (!poolConfig.evictionPredicate().test(pooledRef)) {


                Queue<AtomicReference<TAPoolInner<POOLABLE>>> localQueue = pendingLocal.get(Thread.currentThread());
                if (localQueue == null) {
                    slowPathRecycle(pooledRef);
                    return;
                }

                TAPoolInner<POOLABLE> candidate;
                AtomicReference<TAPoolInner<POOLABLE>> innerRef;
                while ((innerRef = localQueue.poll()) != null) {
                    candidate = innerRef.getAndSet(null);
                    if (candidate != null) {
                        candidate.deliver(pooledRef);
                        return;
                    }
                }
                slowPathRecycle(pooledRef);


            }
            else {
                LIVE.decrementAndGet(this);
                dispose(pooledRef.poolable);
                tryRecreate();
            }
        }
        else {
            LIVE.decrementAndGet(this);
            dispose(pooledRef.poolable());
        }
    }

    @SuppressWarnings("WeakerAccess")
    void dispose(@Nullable POOLABLE poolable) {
        if (poolable instanceof Disposable) {
            ((Disposable) poolable).dispose();
        }
        else if (poolable instanceof Closeable) {
            try {
                ((Closeable) poolable).close();
            } catch (IOException e) {
                logger.trace("Failure while discarding a released Poolable that is Closeable, could not close", e);
            }
        }
    }

    @Override
    public void dispose() {
        @SuppressWarnings("unchecked")
        Queue<AtomicReference<TAPoolInner<POOLABLE>>> q = PENDING.getAndSet(this, TERMINATED);
        if (q != TERMINATED) {
            AtomicReference<TAPoolInner<POOLABLE>> ref;
            while ((ref = q.poll()) != null) {
                if (ref.get() != null) {
                    ref.get().fail(new RuntimeException("Pool has been shut down"));
                }
            }

            pendingLocal.clear();

            TAPooledRef<POOLABLE> element;
            while ((element = available_mpmc.poll()) != null) {
                dispose(element.poolable());
            }
        }
    }

    @Override
    public boolean isDisposed() {
        return allPendings_mpmc == TERMINATED;
    }


    static final class TAPooledRef<T> implements PooledRef<T> {

        final ThreadAffinityPool<T> pool;
        final long creationTimestamp;

        volatile T poolable;

        volatile int borrowCount;

        static final AtomicIntegerFieldUpdater<TAPooledRef> BORROW = AtomicIntegerFieldUpdater.newUpdater(TAPooledRef.class, "borrowCount");

        TAPooledRef(ThreadAffinityPool<T> pool, T poolable) {
            this.pool = pool;
            this.poolable = poolable;
            this.creationTimestamp = System.currentTimeMillis();
        }

        @Override
        public T poolable() {
            return poolable;
        }

        /**
         * Atomically increment the {@link #borrowCount()} of this slot, returning the new value.
         *
         * @return the incremented {@link #borrowCount()}
         */
        int borrowIncrement() {
            return BORROW.incrementAndGet(this);
        }

        @Override
        public int borrowCount() {
            return BORROW.get(this);
        }

        @Override
        public long age() {
            return System.currentTimeMillis() - creationTimestamp;
        }

        @Override
        public Mono<Void> releaseMono() {
            if (PENDING.get(pool) == TERMINATED) {
                pool.dispose(poolable);
                return Mono.empty();
            }

            Mono<Void> cleaner;
            try {
                cleaner = pool.poolConfig.cleaner().apply(poolable);
            }
            catch (Throwable e) {
                return Mono.error(new IllegalStateException("Couldn't apply cleaner function", e));
            }
            //the PoolRecyclerMono will wrap the cleaning Mono returned by the Function and perform state updates
            return new TAPoolRecyclerMono<>(cleaner, this);
        }

        @Override
        public void release() {
            releaseMono().subscribe(v -> {}, e -> pool.logger.debug("error while releasing with release()", e));
        }

        @Override
        public void invalidate() {
            pool.dispose(poolable);
        }

        @Override
        public String toString() {
            return "PooledRef{" +
                    "poolable=" + poolable +
                    ", age=" + age() + "ms" +
                    ", borrowCount=" + borrowCount +
                    '}';
        }
    }

    private static final class TAPoolInner<T> implements Scannable, Subscription {

        final CoreSubscriber<? super TAPooledRef<T>> actual;

        final ThreadAffinityPool<T> parent;

        private static final int STATE_INIT = 0;
        private static final int STATE_REQUESTED = 1;
        private static final int STATE_CANCELLED = 2;

        volatile int state;
        static final AtomicIntegerFieldUpdater<TAPoolInner> STATE = AtomicIntegerFieldUpdater.newUpdater(TAPoolInner.class, "state");


        TAPoolInner(CoreSubscriber<? super TAPooledRef<T>> actual, ThreadAffinityPool<T> parent) {
            this.actual = actual;
            this.parent = parent;
        }

        @Override
        public void request(long n) {
            if (Operators.validate(n) && STATE.compareAndSet(this, STATE_INIT, STATE_REQUESTED)) {
                parent.registerPendingBorrower(this);
            }
        }

        @Override
        public void cancel() {
            STATE.getAndSet(this, STATE_CANCELLED);
        }

        @Override
        @Nullable
        public Object scanUnsafe(Attr key) {
            if (key == Attr.PARENT) return parent;
            if (key == Attr.CANCELLED) return state == STATE_CANCELLED;
            if (key == Attr.REQUESTED_FROM_DOWNSTREAM) return state == STATE_REQUESTED ? 1 : 0;
            if (key == Attr.ACTUAL) return actual;

            return null;
        }

        private void deliver(TAPooledRef<T> poolSlot) {
            switch (state) {
                case STATE_REQUESTED:
                    poolSlot.borrowIncrement();
                    actual.onNext(poolSlot);
                    actual.onComplete();
                    break;
                case STATE_CANCELLED:
                    poolSlot.releaseMono().subscribe(aVoid -> {}, actual::onError);
                    break;
                default:
                    //shouldn't happen since the PoolInner isn't registered with the pool before having requested
                    poolSlot.releaseMono().subscribe(aVoid -> {}, actual::onError, () -> actual.onError(Exceptions.failWithOverflow()));
            }
        }

        private void fail(Throwable error) {
            if (state == STATE_REQUESTED) {
                actual.onError(error);
            }
        }
    }

    private static final class TAQueuePoolMono<T> extends Mono<PooledRef<T>> {

        final ThreadAffinityPool<T> parent;

        TAQueuePoolMono(ThreadAffinityPool<T> pool) {
            this.parent = pool;
        }

        @Override
        public void subscribe(CoreSubscriber<? super PooledRef<T>> actual) {
            Objects.requireNonNull(actual, "subscribing with null");

            TAPoolInner<T> p = new TAPoolInner<>(actual, parent);
            actual.onSubscribe(p);
        }
    }

    private static final class TAQueuePoolRecyclerInner<T> implements CoreSubscriber<Void>, Scannable, Subscription {

        final CoreSubscriber<? super Void> actual;
        final ThreadAffinityPool<T> pool;

        //poolable can be checked for null to protect against protocol errors
        TAPooledRef<T> pooledRef;
        Subscription upstream;

        //once protects against multiple requests
        volatile int once;
        static final AtomicIntegerFieldUpdater<TAQueuePoolRecyclerInner> ONCE = AtomicIntegerFieldUpdater.newUpdater(TAQueuePoolRecyclerInner.class, "once");

        TAQueuePoolRecyclerInner(CoreSubscriber<? super Void> actual, TAPooledRef<T> pooledRef) {
            this.actual = actual;
            this.pooledRef = Objects.requireNonNull(pooledRef, "pooledRef");
            this.pool = pooledRef.pool;
        }

        @Override
        public void onSubscribe(Subscription s) {
            if (Operators.validate(upstream, s)) {
                this.upstream = s;
                actual.onSubscribe(this);
            }
        }

        @Override
        public void onNext(Void o) {
            //N/A
        }

        @Override
        public void onError(Throwable throwable) {
            TAPooledRef<T> slot = pooledRef;
            pooledRef = null;
            if (slot == null) {
                Operators.onErrorDropped(throwable, actual.currentContext());
                return;
            }

            LIVE.decrementAndGet(pool);
            pool.dispose(slot.poolable);

            actual.onError(throwable);
        }

        @Override
        public void onComplete() {
            TAPooledRef<T> slot = pooledRef;
            pooledRef = null;
            if (slot == null) {
                return;
            }

            pool.maybeRecycleAndDrain(slot);
            actual.onComplete();
        }

        @Override
        public void request(long l) {
            if (Operators.validate(l)) {
                upstream.request(l);
            }
        }

        @Override
        public void cancel() {
            //NO-OP, once requested, release cannot be cancelled
        }


        @Override
        public Object scanUnsafe(Attr key) {
            if (key == Attr.ACTUAL) return actual;
            if (key == Attr.PARENT) return upstream;
            if (key == Attr.CANCELLED) return false;
            if (key == Attr.TERMINATED) return pooledRef == null;
            if (key == Attr.BUFFERED) return (pooledRef == null) ? 0 : 1;
            return null;
        }
    }

    private static final class TAPoolRecyclerMono<T> extends MonoOperator<Void, Void> {

        final AtomicReference<TAPooledRef<T>> slotRef;

        TAPoolRecyclerMono(Mono<? extends Void> source, TAPooledRef<T> poolSlot) {
            super(source);
            this.slotRef = new AtomicReference<>(poolSlot);
        }

        @Override
        public void subscribe(CoreSubscriber<? super Void> actual) {
            TAPooledRef<T> slot = slotRef.getAndSet(null);
            if (slot == null) {
                Operators.complete(actual);
            }
            else {
                TAQueuePoolRecyclerInner<T> qpr = new TAQueuePoolRecyclerInner<>(actual, slot);
                source.subscribe(qpr);
            }
        }
    }

}
