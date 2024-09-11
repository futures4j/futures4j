/*
 * SPDX-FileCopyrightText: © Sebastian Thomschke
 * SPDX-License-Identifier: EPL-2.0
 * SPDX-FileContributor: Sebastian Thomschke: initial API and implementation
 */
package io.github.futures4j;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

import org.eclipse.jdt.annotation.Nullable;

import io.github.futures4j.util.ThrowingConsumer;
import io.github.futures4j.util.ThrowingFunction;
import io.github.futures4j.util.ThrowingRunnable;
import io.github.futures4j.util.ThrowingSupplier;

/**
 * Specialised version of {@link ExtendedFuture} that supports cancellation with Thread interruption,
 *
 * @author <a href="https://sebthom.de/">Sebastian Thomschke</a>
 */
final class ExtendedFutureInterruptible<T> extends ExtendedFuture<T> {

   private record IncompleteFuture<T>(ExtendedFutureInterruptible<T> future, long createdOn) {
   }

   private static final AtomicInteger INCOMPLETE_FUTURE_ID_GENERATOR = new AtomicInteger();
   private static final ThreadLocal<@Nullable Integer> INCOMPLETE_FUTURE_ID = new ThreadLocal<>();
   private static final ConcurrentMap<Integer, IncompleteFuture<?>> INCOMPLETE_FUTURES = new ConcurrentHashMap<>(4);

   @SuppressWarnings("unchecked")
   private static <V> ExtendedFutureInterruptible<V> _getNewIncompleteFuture(final int futureId) {
      final var newFuture = INCOMPLETE_FUTURES.remove(futureId);
      if (newFuture == null) // should never happen
         throw new IllegalStateException("No future present with id " + futureId);

      // remove obsolete incomplete futures (should actually not happen, just a precaution to avoid potential memory leaks)
      if (!INCOMPLETE_FUTURES.isEmpty()) {
         final var now = System.currentTimeMillis();
         INCOMPLETE_FUTURES.values().removeIf(f -> now - f.createdOn > 5_000);
      }

      return (ExtendedFutureInterruptible<V>) newFuture.future;
   }

   private static int _getNewIncompleteFutureId() {
      final var fId = INCOMPLETE_FUTURE_ID_GENERATOR.incrementAndGet();
      INCOMPLETE_FUTURE_ID.set(fId);
      return fId;
   }

   private @Nullable Thread executingThread;
   private final Object executingThreadLock = new Object();

   protected ExtendedFutureInterruptible(@Nullable final Executor defaultExecutor, final boolean cancellableByDependents,
         final @Nullable CompletableFuture<T> wrapped) {
      super(defaultExecutor, cancellableByDependents, wrapped);
   }

   @Override
   public ExtendedFuture<@Nullable Void> acceptEither(final CompletionStage<? extends T> other, final Consumer<? super T> action) {
      final var fId = _getNewIncompleteFutureId();
      return super.acceptEither(other, result -> interruptiblyAccept(fId, result, action));
   }

   @Override
   public ExtendedFuture<@Nullable Void> acceptEither(final CompletionStage<? extends T> other,
         final ThrowingConsumer<? super T, ?> action) {
      final var fId = _getNewIncompleteFutureId();
      return super.acceptEither(other, result -> interruptiblyAccept(fId, result, action));
   }

   @Override
   public ExtendedFuture<@Nullable Void> acceptEitherAsync(final CompletionStage<? extends T> other, final Consumer<? super T> action) {
      final var fId = _getNewIncompleteFutureId();
      return super.acceptEitherAsync(other, result -> interruptiblyAccept(fId, result, action));
   }

   @Override
   public ExtendedFuture<@Nullable Void> acceptEitherAsync(final CompletionStage<? extends T> other, final Consumer<? super T> action,
         final Executor executor) {
      final var fId = _getNewIncompleteFutureId();
      return super.acceptEitherAsync(other, result -> interruptiblyAccept(fId, result, action), executor);
   }

   @Override
   public ExtendedFuture<@Nullable Void> acceptEitherAsync(final CompletionStage<? extends T> other,
         final ThrowingConsumer<? super T, ?> action) {
      final var fId = _getNewIncompleteFutureId();
      return super.acceptEitherAsync(other, result -> interruptiblyAccept(fId, result, action));
   }

   @Override
   public ExtendedFuture<@Nullable Void> acceptEitherAsync(final CompletionStage<? extends T> other,
         final ThrowingConsumer<? super T, ?> action, final Executor executor) {
      final var fId = _getNewIncompleteFutureId();
      return super.acceptEitherAsync(other, result -> interruptiblyAccept(fId, result, action), executor);
   }

   @Override
   public <U> ExtendedFutureInterruptible<U> applyToEither(final CompletionStage<? extends T> other, final Function<? super T, U> fn) {
      final var fId = _getNewIncompleteFutureId();
      return (ExtendedFutureInterruptible<U>) super.applyToEither(other, result -> interruptiblyApply(fId, result, fn));
   }

   @Override
   public <U> ExtendedFutureInterruptible<U> applyToEither(final CompletionStage<? extends T> other,
         final ThrowingFunction<? super T, U, ?> fn) {
      final var fId = _getNewIncompleteFutureId();
      return (ExtendedFutureInterruptible<U>) super.applyToEither(other, result -> interruptiblyApply(fId, result, fn));
   }

   @Override
   public <U> ExtendedFutureInterruptible<U> applyToEitherAsync(final CompletionStage<? extends T> other, final Function<? super T, U> fn) {
      final var fId = _getNewIncompleteFutureId();
      return (ExtendedFutureInterruptible<U>) super.applyToEitherAsync(other, result -> interruptiblyApply(fId, result, fn));
   }

   @Override
   public <U> ExtendedFutureInterruptible<U> applyToEitherAsync(final CompletionStage<? extends T> other, final Function<? super T, U> fn,
         final Executor executor) {
      final var fId = _getNewIncompleteFutureId();
      return (ExtendedFutureInterruptible<U>) super.applyToEitherAsync(other, result -> interruptiblyApply(fId, result, fn), executor);
   }

   @Override
   public <U> ExtendedFutureInterruptible<U> applyToEitherAsync(final CompletionStage<? extends T> other,
         final ThrowingFunction<? super T, U, ?> fn) {
      final var fId = _getNewIncompleteFutureId();
      return (ExtendedFutureInterruptible<U>) super.applyToEitherAsync(other, result -> interruptiblyApply(fId, result, fn));
   }

   @Override
   public <U> ExtendedFutureInterruptible<U> applyToEitherAsync(final CompletionStage<? extends T> other,
         final ThrowingFunction<? super T, U, ?> fn, final Executor executor) {
      final var fId = _getNewIncompleteFutureId();
      return (ExtendedFutureInterruptible<U>) super.applyToEitherAsync(other, result -> interruptiblyApply(fId, result, fn), executor);
   }

   @Override
   public ExtendedFuture<T> completeAsync(final Supplier<? extends T> supplier) {
      final var fId = _getNewIncompleteFutureId();
      return super.completeAsync(() -> interruptiblyComplete(fId, supplier));
   }

   @Override
   public ExtendedFuture<T> completeAsync(final Supplier<? extends T> supplier, final Executor executor) {
      final var fId = _getNewIncompleteFutureId();
      return super.completeAsync(() -> interruptiblyComplete(fId, supplier), executor);
   }

   @Override
   public ExtendedFuture<T> completeAsync(final ThrowingSupplier<? extends T, ?> supplier) {
      final var fId = _getNewIncompleteFutureId();
      return super.completeAsync(() -> interruptiblyComplete(fId, supplier));
   }

   @Override
   public ExtendedFuture<T> completeAsync(final ThrowingSupplier<? extends T, ?> supplier, final Executor executor) {
      final var fId = _getNewIncompleteFutureId();
      return super.completeAsync(() -> interruptiblyComplete(fId, supplier), executor);
   }

   @Override

   public <U> ExtendedFuture<U> handle(final BiFunction<? super T, @Nullable Throwable, ? extends U> fn) {
      final var fId = _getNewIncompleteFutureId();
      return super.handle((result, ex) -> interruptiblyHandle(fId, result, ex, fn));
   }

   @Override

   public <U> ExtendedFuture<U> handleAsync(final BiFunction<? super T, @Nullable Throwable, ? extends U> fn) {
      final var fId = _getNewIncompleteFutureId();
      return super.handleAsync((result, ex) -> interruptiblyHandle(fId, result, ex, fn));
   }

   @Override

   public <U> ExtendedFuture<U> handleAsync(final BiFunction<? super T, @Nullable Throwable, ? extends U> fn, final Executor executor) {
      final var fId = _getNewIncompleteFutureId();
      return super.handleAsync((result, ex) -> interruptiblyHandle(fId, result, ex, fn), executor);
   }

   private void interruptiblyAccept(final int futureId, final T result, final Consumer<? super T> action) {
      final var f = _getNewIncompleteFuture(futureId);
      synchronized (f.executingThreadLock) {
         f.executingThread = Thread.currentThread();
      }
      try {
         action.accept(result);
      } finally {
         synchronized (f.executingThreadLock) {
            f.executingThread = null;
         }
      }
   }

   private <U> void interruptiblyAcceptBoth(final int futureId, final T result, final U otherResult,
         final BiConsumer<? super T, ? super U> action) {
      final var f = _getNewIncompleteFuture(futureId);
      synchronized (f.executingThreadLock) {
         f.executingThread = Thread.currentThread();
      }
      try {
         action.accept(result, otherResult);
      } finally {
         synchronized (f.executingThreadLock) {
            f.executingThread = null;
         }
      }
   }

   private <U> U interruptiblyApply(final int futureId, final T result, final Function<? super T, ? extends U> fn) {
      final var f = _getNewIncompleteFuture(futureId);
      synchronized (f.executingThreadLock) {
         f.executingThread = Thread.currentThread();
      }
      try {
         return fn.apply(result);
      } finally {
         synchronized (f.executingThreadLock) {
            f.executingThread = null;
         }
      }
   }

   private <U, V> V interruptiblyCombine(final int futureId, final T result, final U otherResult,
         final BiFunction<? super T, ? super U, ? extends V> fn) {
      final var f = _getNewIncompleteFuture(futureId);
      synchronized (f.executingThreadLock) {
         f.executingThread = Thread.currentThread();
      }
      try {
         return fn.apply(result, otherResult);
      } finally {
         synchronized (f.executingThreadLock) {
            f.executingThread = null;
         }
      }
   }

   private T interruptiblyComplete(final int futureId, final Supplier<? extends T> supplier) {
      final var f = _getNewIncompleteFuture(futureId);
      synchronized (f.executingThreadLock) {
         f.executingThread = Thread.currentThread();
      }
      try {
         return supplier.get();
      } finally {
         synchronized (f.executingThreadLock) {
            f.executingThread = null;
         }
      }
   }

   private <U> U interruptiblyHandle(final int futureId, final T result, final @Nullable Throwable ex,
         final BiFunction<? super T, @Nullable Throwable, ? extends U> fn) {
      final var f = _getNewIncompleteFuture(futureId);
      synchronized (f.executingThreadLock) {
         f.executingThread = Thread.currentThread();
      }
      try {
         return fn.apply(result, ex);
      } finally {
         synchronized (f.executingThreadLock) {
            f.executingThread = null;
         }
      }
   }

   private void interruptiblyRun(final int futureId, final Runnable action) {
      final var f = _getNewIncompleteFuture(futureId);
      synchronized (f.executingThreadLock) {
         f.executingThread = Thread.currentThread();
      }
      try {
         action.run();
      } finally {
         synchronized (f.executingThreadLock) {
            f.executingThread = null;
         }
      }
   }

   private <U> CompletionStage<U> interruptiblyThenCompose(final int futureId, final T result,
         final Function<? super T, ? extends CompletionStage<U>> fn) {
      final var f = _getNewIncompleteFuture(futureId);
      synchronized (f.executingThreadLock) {
         f.executingThread = Thread.currentThread();
      }

      try {
         final var stage = fn.apply(result);
         if (stage instanceof final ExtendedFutureInterruptible<?> fut && fut.isCancellableByDependents()) {
            f.cancellablePrecedingStages.add(fut);
         }
         return stage;
      } finally {
         synchronized (f.executingThreadLock) {
            f.executingThread = null;
         }
      }
   }

   private void interruptiblyWhenComplete(final int futureId, final @Nullable T result, final @Nullable Throwable ex,
         final BiConsumer<? super @Nullable T, ? super @Nullable Throwable> action) {
      final var f = _getNewIncompleteFuture(futureId);
      synchronized (f.executingThreadLock) {
         f.executingThread = Thread.currentThread();
      }
      try {
         action.accept(result, ex);
      } finally {
         synchronized (f.executingThreadLock) {
            f.executingThread = null;
         }
      }
   }

   @Override
   public boolean cancel(final boolean mayInterruptIfRunning) {
      if (isDone())
         return isCancelled();

      final var cancelled = super.cancel(mayInterruptIfRunning);
      if (cancelled && mayInterruptIfRunning) {
         synchronized (executingThreadLock) {
            if (executingThread != null) {
               executingThread.interrupt();
            }
         }
      }

      return cancelled;
   }

   @Override
   public boolean isInterruptible() {
      return true;
   }

   @Override
   public <V> ExtendedFutureInterruptible<V> newIncompleteFuture() {
      final var f = new ExtendedFutureInterruptible<V>(defaultExecutor, cancellableByDependents, null);
      if (cancellableByDependents && !isCancelled()) {
         f.cancellablePrecedingStages.add(this);
      }

      final var fId = INCOMPLETE_FUTURE_ID.get();
      INCOMPLETE_FUTURE_ID.remove();
      if (fId != null) { // for cases where newIncompleteFuture is used through code path by super class not handled by this implementation
         INCOMPLETE_FUTURES.put(fId, new IncompleteFuture<>(f, System.currentTimeMillis()));
      }
      return f;
   }

   @Override
   public ExtendedFuture<@Nullable Void> runAfterBoth(final CompletionStage<?> other, final Runnable action) {
      final var fId = _getNewIncompleteFutureId();
      return super.runAfterBoth(other, () -> interruptiblyRun(fId, action));
   }

   @Override
   public ExtendedFuture<@Nullable Void> runAfterBoth(final CompletionStage<?> other, final ThrowingRunnable<?> action) {
      final var fId = _getNewIncompleteFutureId();
      return super.runAfterBoth(other, () -> interruptiblyRun(fId, action));
   }

   @Override
   public ExtendedFuture<@Nullable Void> runAfterBothAsync(final CompletionStage<?> other, final Runnable action) {
      final var fId = _getNewIncompleteFutureId();
      return super.runAfterBothAsync(other, () -> interruptiblyRun(fId, action));
   }

   @Override
   public ExtendedFuture<@Nullable Void> runAfterBothAsync(final CompletionStage<?> other, final Runnable action, final Executor executor) {
      final var fId = _getNewIncompleteFutureId();
      return super.runAfterBothAsync(other, () -> interruptiblyRun(fId, action), executor);
   }

   @Override
   public ExtendedFuture<@Nullable Void> runAfterBothAsync(final CompletionStage<?> other, final ThrowingRunnable<?> action) {
      final var fId = _getNewIncompleteFutureId();
      return super.runAfterBothAsync(other, () -> interruptiblyRun(fId, action));
   }

   @Override
   public ExtendedFuture<@Nullable Void> runAfterBothAsync(final CompletionStage<?> other, final ThrowingRunnable<?> action,
         final Executor executor) {
      final var fId = _getNewIncompleteFutureId();
      return super.runAfterBothAsync(other, () -> interruptiblyRun(fId, action), executor);
   }

   @Override
   public ExtendedFuture<@Nullable Void> runAfterEither(final CompletionStage<?> other, final Runnable action) {
      final var fId = _getNewIncompleteFutureId();
      return super.runAfterEither(other, () -> interruptiblyRun(fId, action));
   }

   @Override
   public ExtendedFuture<@Nullable Void> runAfterEither(final CompletionStage<?> other, final ThrowingRunnable<?> action) {
      final var fId = _getNewIncompleteFutureId();
      return super.runAfterEither(other, () -> interruptiblyRun(fId, action));
   }

   @Override
   public ExtendedFuture<@Nullable Void> runAfterEitherAsync(final CompletionStage<?> other, final Runnable action) {
      final var fId = _getNewIncompleteFutureId();
      return super.runAfterEitherAsync(other, () -> interruptiblyRun(fId, action));
   }

   @Override
   public ExtendedFuture<@Nullable Void> runAfterEitherAsync(final CompletionStage<?> other, final Runnable action,
         final Executor executor) {
      final var fId = _getNewIncompleteFutureId();
      return super.runAfterEitherAsync(other, () -> interruptiblyRun(fId, action), executor);
   }

   @Override
   public ExtendedFuture<@Nullable Void> runAfterEitherAsync(final CompletionStage<?> other, final ThrowingRunnable<?> action) {
      final var fId = _getNewIncompleteFutureId();
      return super.runAfterEitherAsync(other, () -> interruptiblyRun(fId, action));
   }

   @Override
   public ExtendedFuture<@Nullable Void> runAfterEitherAsync(final CompletionStage<?> other, final ThrowingRunnable<?> action,
         final Executor executor) {
      final var fId = _getNewIncompleteFutureId();
      return super.runAfterEitherAsync(other, () -> interruptiblyRun(fId, action), executor);
   }

   @Override
   public ExtendedFuture<@Nullable Void> thenAccept(final Consumer<? super T> action) {
      final var fId = _getNewIncompleteFutureId();
      return super.thenAccept(result -> interruptiblyAccept(fId, result, action));
   }

   @Override
   public ExtendedFuture<@Nullable Void> thenAccept(final ThrowingConsumer<? super T, ?> action) {
      final var fId = _getNewIncompleteFutureId();
      return super.thenAccept(result -> interruptiblyAccept(fId, result, action));
   }

   @Override
   public ExtendedFuture<@Nullable Void> thenAcceptAsync(final Consumer<? super T> action) {
      final var fId = _getNewIncompleteFutureId();
      return super.thenAcceptAsync(result -> interruptiblyAccept(fId, result, action));
   }

   @Override
   public ExtendedFuture<@Nullable Void> thenAcceptAsync(final Consumer<? super T> action, final Executor executor) {
      final var fId = _getNewIncompleteFutureId();
      return super.thenAcceptAsync(result -> interruptiblyAccept(fId, result, action), executor);
   }

   @Override
   public ExtendedFuture<@Nullable Void> thenAcceptAsync(final ThrowingConsumer<? super T, ?> action) {
      final var fId = _getNewIncompleteFutureId();
      return super.thenAcceptAsync(result -> interruptiblyAccept(fId, result, action));
   }

   @Override
   public ExtendedFuture<@Nullable Void> thenAcceptAsync(final ThrowingConsumer<? super T, ?> action, final Executor executor) {
      final var fId = _getNewIncompleteFutureId();
      return super.thenAcceptAsync(result -> interruptiblyAccept(fId, result, action), executor);
   }

   @Override
   public <U> ExtendedFuture<@Nullable Void> thenAcceptBoth(final CompletionStage<? extends U> other,
         final BiConsumer<? super T, ? super U> action) {
      final var fId = _getNewIncompleteFutureId();
      return super.thenAcceptBoth(other, (result, otherResult) -> interruptiblyAcceptBoth(fId, result, otherResult, action));
   }

   @Override
   public <U> ExtendedFuture<@Nullable Void> thenAcceptBothAsync(final CompletionStage<? extends U> other,
         final BiConsumer<? super T, ? super U> action) {
      final var fId = _getNewIncompleteFutureId();
      return super.thenAcceptBothAsync(other, (result, otherResult) -> interruptiblyAcceptBoth(fId, result, otherResult, action));
   }

   @Override
   public <U> ExtendedFuture<@Nullable Void> thenAcceptBothAsync(final CompletionStage<? extends U> other,
         final BiConsumer<? super T, ? super U> action, final Executor executor) {
      final var fId = _getNewIncompleteFutureId();
      return super.thenAcceptBothAsync(other, (result, otherResult) -> interruptiblyAcceptBoth(fId, result, otherResult, action), executor);
   }

   @Override
   public <U> ExtendedFuture<U> thenApply(final Function<? super T, ? extends U> fn) {
      final var fId = _getNewIncompleteFutureId();
      return super.thenApply(result -> interruptiblyApply(fId, result, fn));
   }

   @Override
   public <U> ExtendedFuture<U> thenApply(final ThrowingFunction<? super T, ? extends U, ?> fn) {
      final var fId = _getNewIncompleteFutureId();
      return super.thenApply(result -> interruptiblyApply(fId, result, fn));
   }

   @Override
   public <U> ExtendedFuture<U> thenApplyAsync(final Function<? super T, ? extends U> fn) {
      final var fId = _getNewIncompleteFutureId();
      return super.thenApplyAsync(result -> interruptiblyApply(fId, result, fn));
   }

   @Override
   public <U> ExtendedFuture<U> thenApplyAsync(final Function<? super T, ? extends U> fn, final Executor executor) {
      final var fId = _getNewIncompleteFutureId();
      return super.thenApplyAsync(result -> interruptiblyApply(fId, result, fn), executor);
   }

   @Override
   public <U> ExtendedFuture<U> thenApplyAsync(final ThrowingFunction<? super T, ? extends U, ?> fn) {
      final var fId = _getNewIncompleteFutureId();
      return super.thenApplyAsync(result -> interruptiblyApply(fId, result, fn));
   }

   @Override
   public <U> ExtendedFuture<U> thenApplyAsync(final ThrowingFunction<? super T, ? extends U, ?> fn, final Executor executor) {
      final var fId = _getNewIncompleteFutureId();
      return super.thenApplyAsync(result -> interruptiblyApply(fId, result, fn), executor);
   }

   @Override
   public <U, V> ExtendedFuture<V> thenCombine(final CompletionStage<? extends U> other,
         final BiFunction<? super T, ? super U, ? extends V> fn) {
      final var fId = _getNewIncompleteFutureId();
      return super.thenCombine(other, (result, otherResult) -> interruptiblyCombine(fId, result, otherResult, fn));
   }

   @Override
   public <U, V> ExtendedFuture<V> thenCombineAsync(final CompletionStage<? extends U> other,
         final BiFunction<? super T, ? super U, ? extends V> fn) {
      final var fId = _getNewIncompleteFutureId();
      return super.thenCombineAsync(other, (result, otherResult) -> interruptiblyCombine(fId, result, otherResult, fn));
   }

   @Override
   public <U, V> ExtendedFuture<V> thenCombineAsync(final CompletionStage<? extends U> other,
         final BiFunction<? super T, ? super U, ? extends V> fn, final Executor executor) {
      final var fId = _getNewIncompleteFutureId();
      return super.thenCombineAsync(other, (result, otherResult) -> interruptiblyCombine(fId, result, otherResult, fn), executor);
   }

   @Override
   public <U> ExtendedFuture<U> thenCompose(final Function<? super T, ? extends CompletionStage<U>> fn) {
      final var fId = _getNewIncompleteFutureId();
      return super.thenCompose(result -> interruptiblyThenCompose(fId, result, fn));
   }

   @Override
   public <U> ExtendedFuture<U> thenComposeAsync(final Function<? super T, ? extends CompletionStage<U>> fn) {
      final var fId = _getNewIncompleteFutureId();
      return super.thenComposeAsync(result -> interruptiblyThenCompose(fId, result, fn));
   }

   @Override
   public <U> ExtendedFuture<U> thenComposeAsync(final Function<? super T, ? extends CompletionStage<U>> fn, final Executor executor) {
      final var fId = _getNewIncompleteFutureId();
      return super.thenComposeAsync(result -> interruptiblyThenCompose(fId, result, fn), executor);
   }

   @Override
   public ExtendedFuture<@Nullable Void> thenRun(final Runnable action) {
      final var fId = _getNewIncompleteFutureId();
      return super.thenRun(() -> interruptiblyRun(fId, action));
   }

   @Override
   public ExtendedFuture<@Nullable Void> thenRun(final ThrowingRunnable<?> action) {
      final var fId = _getNewIncompleteFutureId();
      return super.thenRun(() -> interruptiblyRun(fId, action));
   }

   @Override
   public ExtendedFuture<@Nullable Void> thenRunAsync(final Runnable action) {
      final var fId = _getNewIncompleteFutureId();
      return super.thenRunAsync(() -> interruptiblyRun(fId, action));
   }

   @Override
   public ExtendedFuture<@Nullable Void> thenRunAsync(final Runnable action, final Executor executor) {
      final var fId = _getNewIncompleteFutureId();
      return super.thenRunAsync(() -> interruptiblyRun(fId, action), executor);
   }

   @Override
   public ExtendedFuture<@Nullable Void> thenRunAsync(final ThrowingRunnable<?> action) {
      final var fId = _getNewIncompleteFutureId();
      return super.thenRunAsync(() -> interruptiblyRun(fId, action));
   }

   @Override
   public ExtendedFuture<@Nullable Void> thenRunAsync(final ThrowingRunnable<?> action, final Executor executor) {
      final var fId = _getNewIncompleteFutureId();
      return super.thenRunAsync(() -> interruptiblyRun(fId, action), executor);
   }

   @Override
   public ExtendedFuture<T> whenComplete(final BiConsumer<? super @Nullable T, ? super @Nullable Throwable> action) {
      final var fId = _getNewIncompleteFutureId();
      return super.whenComplete((result, ex) -> interruptiblyWhenComplete(fId, result, ex, action));
   }

   @Override
   public ExtendedFuture<T> whenCompleteAsync(final BiConsumer<? super @Nullable T, ? super @Nullable Throwable> action) {
      final var fId = _getNewIncompleteFutureId();
      return super.whenCompleteAsync((result, ex) -> interruptiblyWhenComplete(fId, result, ex, action));
   }

   @Override
   public ExtendedFuture<T> whenCompleteAsync(final BiConsumer<? super @Nullable T, ? super @Nullable Throwable> action,
         final Executor executor) {
      final var fId = _getNewIncompleteFutureId();
      return super.whenCompleteAsync((result, ex) -> interruptiblyWhenComplete(fId, result, ex, action), executor);
   }
}
