/*
 * SPDX-FileCopyrightText: © Sebastian Thomschke
 * SPDX-License-Identifier: EPL-2.0
 * SPDX-FileContributor: Sebastian Thomschke: initial API and implementation
 */
package io.github.futures4j;

import java.util.Collection;
import java.util.Collections;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executor;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
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
 * An enhanced version of {@link CompletableFuture} that:
 * <ol>
 * <li>supports task thread interruption via <code>cancel(true)</code>
 * <li>allows dependent stages cancel preceding stages. controllable via {@link #asCancellableByDependents(boolean)}
 * <li>allows running tasks that throw checked exceptions via e.g. {@link #runAsync(ThrowingRunnable)}, etc.
 * <li>allows creating a read-only view of a future using {@link #asReadOnly(ReadOnlyMode)}
 * <li>allows defining a default executor for this future and all subsequent stages e.g. via {@link #ExtendedFuture(Executor)} or
 * {@link #withDefaultExecutor(Executor)}
 * <li>offers additional convenience method such as {@link #isCompleted()}, {@link #getCompletionState()},
 * {@link #getNowOptional()}, {@link #getOptional(long, TimeUnit)},
 * {@link #getNowOrFallback(Object)}, {@link #getOrFallback(long, TimeUnit, Object)}
 * </ol>
 * <p>
 * For more information on issues addressed by this class, see:
 * <ul>
 * <li>https://stackoverflow.com/questions/25417881/canceling-a-completablefuture-chain
 * <li>https://stackoverflow.com/questions/36727820/cancellation-of-completablefuture-controlled-by-executorservice
 * <li>https://stackoverflow.com/questions/62106428/is-there-a-better-way-for-cancelling-a-chain-of-futures-in-java
 * and:
 * <li>https://stackoverflow.com/questions/29013831/how-to-interrupt-underlying-execution-of-completablefuture
 * <li>https://nurkiewicz.com/2015/03/completablefuture-cant-be-interrupted.html
 * <li>https://blog.tremblay.pro/2017/08/supply-async.html
 * </ul>
 *
 * @author <a href="https://sebthom.de/">Sebastian Thomschke</a>
 */
public class ExtendedFuture<T> extends CompletableFuture<T> {

   public enum ReadOnlyMode {

      /** mutation attempts will throw {@link UnsupportedOperationException} */
      THROW_ON_MUTATION,

      /** mutation attempts will be silently ignored */
      IGNORE_MUTATION
   }

   /**
    * This {@link IncompleteFuture} record and the following class members are used to implement thread interruption support:
    * <li>the static fields: {@link INCOMPLETE_FUTURE_ID_GENERATOR}, {@link INCOMPLETE_FUTURE_ID}, {@link INCOMPLETE_FUTURES}
    * <li>the static methods: {@link _getNewIncompleteFuture}, {@link _getNewIncompleteFutureId}
    * <li>the instance fields: {@link #executingThread}, {@link #executingThreadLock}
    */
   private record IncompleteFuture(ExtendedFuture<?> future, long createdOn) {
   }

   private static final AtomicInteger INCOMPLETE_FUTURE_ID_GENERATOR = new AtomicInteger();
   private static final ThreadLocal<@Nullable Integer> INCOMPLETE_FUTURE_ID = new ThreadLocal<>();
   private static final ConcurrentMap<Integer, IncompleteFuture> INCOMPLETE_FUTURES = new ConcurrentHashMap<>(4);

   @SuppressWarnings("unchecked")
   private static <V> ExtendedFuture<V> _getNewIncompleteFuture(final int futureId) {
      final var newFuture = INCOMPLETE_FUTURES.remove(futureId);
      if (newFuture == null) // should never happen
         throw new IllegalStateException("No future present with id " + futureId);

      // remove obsolete incomplete futures (should actually not happen, just a precaution to avoid potential memory leaks)
      if (!INCOMPLETE_FUTURES.isEmpty()) {
         final var now = System.currentTimeMillis();
         INCOMPLETE_FUTURES.values().removeIf(f -> now - f.createdOn > 5_000);
      }

      return (ExtendedFuture<V>) newFuture.future;
   }

   private static int _getNewIncompleteFutureId() {
      final var fId = INCOMPLETE_FUTURE_ID_GENERATOR.incrementAndGet();
      INCOMPLETE_FUTURE_ID.set(fId);
      return fId;
   }

   /**
    * @see CompletableFuture#allOf(CompletableFuture...)
    */
   public static ExtendedFuture<@Nullable Void> allOf(final CompletableFuture<?>... cfs) {
      return ExtendedFuture.from(CompletableFuture.allOf(cfs));
   }

   /**
    * @see CompletableFuture#anyOf(CompletableFuture...)
    */
   public static ExtendedFuture<@Nullable Object> anyOf(final CompletableFuture<?>... cfs) {
      return ExtendedFuture.from(CompletableFuture.anyOf(cfs));
   }

   public static <V> ExtendedFuture<V> completedFuture(final V value) {
      final var f = new ExtendedFuture<V>(false);
      f.complete(value);
      return f;
   }

   /**
    * @param defaultExecutor default executor for subsequent stages
    */
   public static <V> ExtendedFuture<V> completedFutureWithDefaultExecutor(final V value, final Executor defaultExecutor) {
      final var f = new ExtendedFuture<V>(false, defaultExecutor);
      f.complete(value);
      return f;
   }

   public static <V> ExtendedFuture<V> failedFuture(final Throwable ex) {
      final var f = new ExtendedFuture<V>(false);
      f.completeExceptionally(ex);
      return f;
   }

   /**
    * @param defaultExecutor default executor for subsequent stages
    */
   public static <V> ExtendedFuture<V> failedFutureWithDefaultExecutor(final Throwable ex, final Executor defaultExecutor) {
      final var f = new ExtendedFuture<V>(false, defaultExecutor);
      f.completeExceptionally(ex);
      return f;
   }

   /**
    * Derives a {@link ExtendedFuture} from a {@link CompletableFuture} with {@link #isCancellableByDependents()} set to {@code false}.
    * <p>
    * Returns the given future if it is already an instance of {@link ExtendedFuture} with {@link #isCancellableByDependents()} set to
    * {@code false}.
    */
   public static <V> ExtendedFuture<V> from(final CompletableFuture<V> source) {
      return from(source, false);
   }

   /**
    * Derives a {@link ExtendedFuture} from a {@link CompletableFuture}.
    * <p>
    * Returns the given future if it is already an instance of {@link ExtendedFuture}.
    *
    * @param cancellableByDependents if {@code true} cancelling dependent stages will cancel this future
    */
   public static <V> ExtendedFuture<V> from(final CompletableFuture<V> source, final boolean cancellableByDependents) {
      if (source instanceof final ExtendedFuture<V> cf)
         return cf;
      return new ExtendedFuture<>(source.defaultExecutor(), cancellableByDependents, source);
   }

   public static ExtendedFuture<@Nullable Void> runAsync(final Runnable runnable) {
      return completedFuture(null).thenRunAsync(runnable);
   }

   public static ExtendedFuture<@Nullable Void> runAsync(final Runnable runnable, final Executor executor) {
      return completedFuture(null).thenRunAsync(runnable, executor);
   }

   public static ExtendedFuture<@Nullable Void> runAsync(final ThrowingRunnable<?> runnable) {
      return completedFuture(null).thenRunAsync(runnable);
   }

   public static ExtendedFuture<@Nullable Void> runAsync(final ThrowingRunnable<?> runnable, final Executor executor) {
      return completedFuture(null).thenRunAsync(runnable, executor);
   }

   public static ExtendedFuture<@Nullable Void> runAsyncWithDefaultExecutor(final ThrowingRunnable<?> runnable,
         final Executor defaultExecutor) {
      return completedFutureWithDefaultExecutor(null, defaultExecutor).thenRunAsync(runnable);
   }

   public static <V> ExtendedFuture<V> supplyAsync(final Supplier<V> supplier) {
      return completedFuture(null).thenApplyAsync(unused -> supplier.get());
   }

   public static <V> ExtendedFuture<V> supplyAsync(final Supplier<V> supplier, final Executor executor) {
      return completedFuture(null).thenApplyAsync(unused -> supplier.get(), executor);
   }

   public static <V> ExtendedFuture<V> supplyAsync(final ThrowingSupplier<V, ?> supplier) {
      return completedFuture(null).thenApplyAsync(unused -> supplier.get());
   }

   public static <V> ExtendedFuture<V> supplyAsync(final ThrowingSupplier<V, ?> supplier, final Executor executor) {
      return completedFuture(null).thenApplyAsync(unused -> supplier.get(), executor);
   }

   public static <V> ExtendedFuture<V> supplyAsyncWithDefaultExecutor(final ThrowingSupplier<V, ?> supplier,
         final Executor defaultExecutor) {
      return completedFutureWithDefaultExecutor(null, defaultExecutor).thenApplyAsync(unused -> supplier.get());
   }

   private final Collection<Future<?>> cancellablePrecedingStages;
   private final boolean cancellableByDependents;

   private final Executor defaultExecutor;

   private @Nullable Thread executingThread;
   private final Object executingThreadLock = new Object();

   private final @Nullable CompletableFuture<T> wrapped;

   /**
    * Constructs a new {@link ExtendedFuture} with {@link #isCancellableByDependents()} set to {@code false}.
    */
   public ExtendedFuture() {
      this(null, false, null);
   }

   /**
    * @param cancellableByDependents if {@code true} cancelling dependent stages will cancel this future
    */
   public ExtendedFuture(final boolean cancellableByDependents) {
      this(null, cancellableByDependents, null);
   }

   /**
    * @param cancellableByDependents if {@code true} cancelling dependent stages will cancel this future
    * @param defaultExecutor executor to be used by this future and all subsequent stages by default
    */
   public ExtendedFuture(final boolean cancellableByDependents, final Executor defaultExecutor) {
      this(defaultExecutor, cancellableByDependents, null);
   }

   /**
    * Constructs a new {@link ExtendedFuture} with {@link #isCancellableByDependents()} set to {@code false}.
    *
    * @param defaultExecutor executor to be used by this future and all subsequent stages by default
    */
   public ExtendedFuture(final Executor defaultExecutor) {
      this(defaultExecutor, false, null);
   }

   protected ExtendedFuture(@Nullable final Executor defaultExecutor, final boolean cancellableByDependents,
         final @Nullable CompletableFuture<T> wrapped) {
      this.defaultExecutor = defaultExecutor == null ? super.defaultExecutor() : defaultExecutor;
      this.cancellableByDependents = cancellableByDependents;
      cancellablePrecedingStages = wrapped == null ? new ConcurrentLinkedQueue<>() : Collections.emptyList();
      this.wrapped = wrapped;
      if (wrapped != null) {
         wrapped.whenComplete((result, ex) -> {
            if (ex == null) {
               super.complete(result);
            } else {
               super.completeExceptionally(ex);
            }
         });
      }
   }

   @Override
   public ExtendedFuture<@Nullable Void> acceptEither(final CompletionStage<? extends T> other, final Consumer<? super T> action) {
      final var fId = _getNewIncompleteFutureId();
      return (ExtendedFuture<@Nullable Void>) super.acceptEither(other, result -> interruptiblyAccept(fId, result, action));
   }

   public ExtendedFuture<@Nullable Void> acceptEither(final CompletionStage<? extends T> other,
         final ThrowingConsumer<? super T, ?> action) {
      final var fId = _getNewIncompleteFutureId();
      return (ExtendedFuture<@Nullable Void>) super.acceptEither(other, result -> interruptiblyAccept(fId, result, action));
   }

   @Override
   public ExtendedFuture<@Nullable Void> acceptEitherAsync(final CompletionStage<? extends T> other, final Consumer<? super T> action) {
      final var fId = _getNewIncompleteFutureId();
      return (ExtendedFuture<@Nullable Void>) super.acceptEitherAsync(other, result -> interruptiblyAccept(fId, result, action));
   }

   @Override
   public ExtendedFuture<@Nullable Void> acceptEitherAsync(final CompletionStage<? extends T> other, final Consumer<? super T> action,
         final Executor executor) {
      final var fId = _getNewIncompleteFutureId();
      return (ExtendedFuture<@Nullable Void>) super.acceptEitherAsync(other, result -> interruptiblyAccept(fId, result, action), executor);
   }

   public ExtendedFuture<@Nullable Void> acceptEitherAsync(final CompletionStage<? extends T> other,
         final ThrowingConsumer<? super T, ?> action) {
      final var fId = _getNewIncompleteFutureId();
      return (ExtendedFuture<@Nullable Void>) super.acceptEitherAsync(other, result -> interruptiblyAccept(fId, result, action));
   }

   public ExtendedFuture<@Nullable Void> acceptEitherAsync(final CompletionStage<? extends T> other,
         final ThrowingConsumer<? super T, ?> action, final Executor executor) {
      final var fId = _getNewIncompleteFutureId();
      return (ExtendedFuture<@Nullable Void>) super.acceptEitherAsync(other, result -> interruptiblyAccept(fId, result, action), executor);
   }

   @Override
   public <U> ExtendedFuture<U> applyToEither(final CompletionStage<? extends T> other, final Function<? super T, U> fn) {
      final var fId = _getNewIncompleteFutureId();
      return (ExtendedFuture<U>) super.applyToEither(other, result -> interruptiblyApply(fId, result, fn));
   }

   public <U> ExtendedFuture<U> applyToEither(final CompletionStage<? extends T> other, final ThrowingFunction<? super T, U, ?> fn) {
      final var fId = _getNewIncompleteFutureId();
      return (ExtendedFuture<U>) super.applyToEither(other, result -> interruptiblyApply(fId, result, fn));
   }

   @Override
   public <U> ExtendedFuture<U> applyToEitherAsync(final CompletionStage<? extends T> other, final Function<? super T, U> fn) {
      final var fId = _getNewIncompleteFutureId();
      return (ExtendedFuture<U>) super.applyToEitherAsync(other, result -> interruptiblyApply(fId, result, fn));
   }

   @Override
   public <U> ExtendedFuture<U> applyToEitherAsync(final CompletionStage<? extends T> other, final Function<? super T, U> fn,
         final Executor executor) {
      final var fId = _getNewIncompleteFutureId();
      return (ExtendedFuture<U>) super.applyToEitherAsync(other, result -> interruptiblyApply(fId, result, fn), executor);
   }

   public <U> ExtendedFuture<U> applyToEitherAsync(final CompletionStage<? extends T> other, final ThrowingFunction<? super T, U, ?> fn) {
      final var fId = _getNewIncompleteFutureId();
      return (ExtendedFuture<U>) super.applyToEitherAsync(other, result -> interruptiblyApply(fId, result, fn));
   }

   public <U> ExtendedFuture<U> applyToEitherAsync(final CompletionStage<? extends T> other, final ThrowingFunction<? super T, U, ?> fn,
         final Executor executor) {
      final var fId = _getNewIncompleteFutureId();
      return (ExtendedFuture<U>) super.applyToEitherAsync(other, result -> interruptiblyApply(fId, result, fn), executor);
   }

   /**
    * Returns an {@link ExtendedFuture} that shares the result with this future, but allows control over whether
    * cancellation of dependent stages cancels this future.
    * <p>
    * If the requested cancellation behavior matches the current one, this instance is returned.
    * Otherwise, a new {@link ExtendedFuture} is created with the updated behavior.
    * <p>
    * Any newly created dependent stages will inherit this cancellation behavior.
    *
    * @param isCancellableByDependents
    *           If {@code true}, cancellation of a dependent stage will also cancels this future and its underlying future; if
    *           {@code false}, cancellation of dependent stages will not affect this future.
    * @return a new {@link ExtendedFuture} the specified cancellation behavior,
    *         or this instance if the requested behavior remains unchanged.
    */
   public ExtendedFuture<T> asCancellableByDependents(final boolean isCancellableByDependents) {
      if (isCancellableByDependents == cancellableByDependents)
         return this;
      return new ExtendedFuture<>(defaultExecutor, isCancellableByDependents, this);
   }

   /**
    * Creates a read-only view of this {@link ExtendedFuture}.
    * <p>
    * The returned future is backed by the this future, allowing only read operations
    * such as {@link ExtendedFuture#get()}, {@link ExtendedFuture#join()}, and other non-mutating methods.
    * Any attempt to invoke mutating operations such as {@link ExtendedFuture#cancel(boolean)},
    * {@link ExtendedFuture#complete(Object)}, {@link ExtendedFuture#completeExceptionally(Throwable)},
    * or {@link ExtendedFuture#obtrudeValue(Object)} will result in an {@link UnsupportedOperationException}.
    *
    * @param readOnlyMode if {@code true}, mutating operations will throw {@link UnsupportedOperationException};
    *           if {@code false}, mutation attempts will be silently ignored
    * @return a read-only {@link CompletableFuture} that is backed by the original future
    * @throws UnsupportedOperationException if any mutating methods are called
    */
   public ExtendedFuture<T> asReadOnly(final ReadOnlyMode readOnlyMode) {
      final var throwOnMutationAttempt = readOnlyMode.equals(ReadOnlyMode.THROW_ON_MUTATION);
      return new ExtendedFuture<>(defaultExecutor, false, this) {
         @Override
         public boolean cancel(final boolean mayInterruptIfRunning) {
            if (throwOnMutationAttempt)
               throw new UnsupportedOperationException(this + " is read-only.");
            return isCancelled();
         }

         @Override
         public boolean complete(final T value) {
            if (throwOnMutationAttempt)
               throw new UnsupportedOperationException(this + " is read-only.");
            return false;
         }

         @Override
         public ExtendedFuture<T> completeAsync(final Supplier<? extends T> supplier) {
            if (throwOnMutationAttempt)
               throw new UnsupportedOperationException(this + " is read-only.");
            return this;
         }

         @Override
         public ExtendedFuture<T> completeAsync(final Supplier<? extends T> supplier, final Executor executor) {
            if (throwOnMutationAttempt)
               throw new UnsupportedOperationException(this + " is read-only.");
            return this;
         }

         @Override
         public boolean completeExceptionally(final Throwable ex) {
            if (throwOnMutationAttempt)
               throw new UnsupportedOperationException(this + " is read-only.");
            return false;
         }

         @Override
         public ExtendedFuture<T> completeOnTimeout(final T value, final long timeout, final TimeUnit unit) {
            if (throwOnMutationAttempt)
               throw new UnsupportedOperationException(this + " is read-only.");
            return this;
         }

         @Override
         public boolean isReadOnly() {
            return true;
         }

         @Override
         public void obtrudeException(final Throwable ex) {
            throw new UnsupportedOperationException(this + " is read-only.");
         }

         @Override
         public void obtrudeValue(final T value) {
            throw new UnsupportedOperationException(this + " is read-only.");
         }
      };
   }

   /**
    * Cancels this {@link ExtendedFuture} by completing it with a {@link java.util.concurrent.CancellationException}
    * if it has not already been completed. Any dependent {@link CompletableFuture}s that have not
    * yet completed will also complete exceptionally, with a {@link CompletionException} caused by
    * the {@code CancellationException} from this task.
    *
    * If preceding stage has {@link #isCancellableByDependents()} set, this will also propagate the cancellation to the preceding stage.
    *
    * @param mayInterruptIfRunning {@code true} if the thread executing this task should be
    *           interrupted (if the thread is known to the implementation); otherwise,
    *           in-progress tasks are allowed to complete.
    *
    * @return {@code true} if this task was successfully cancelled; {@code false} if the task
    *         could not be cancelled, typically because it has already completed.
    */
   @Override
   public boolean cancel(final boolean mayInterruptIfRunning) {
      if (isDone())
         return isCancelled();

      final var wrapped = this.wrapped;
      final boolean cancelled;
      if (wrapped == null) {
         cancelled = super.cancel(mayInterruptIfRunning);

         if (cancelled && !cancellablePrecedingStages.isEmpty()) {
            cancellablePrecedingStages.removeIf(stage -> {
               if (!stage.isDone()) {
                  stage.cancel(mayInterruptIfRunning);
               }
               return true;
            });
         }
      } else {
         cancelled = wrapped.cancel(mayInterruptIfRunning);
      }

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
   public boolean complete(final T value) {
      final var wrapped = this.wrapped;
      if (wrapped == null)
         return super.complete(value);
      wrapped.complete(value);
      return super.complete(wrapped.getNow(value));
   }

   @Override
   public ExtendedFuture<T> completeAsync(final Supplier<? extends T> supplier) {
      final var fId = _getNewIncompleteFutureId();
      return (ExtendedFuture<T>) super.completeAsync(() -> interruptiblyComplete(fId, supplier));
   }

   @Override
   public ExtendedFuture<T> completeAsync(final Supplier<? extends T> supplier, final Executor executor) {
      final var fId = _getNewIncompleteFutureId();
      return (ExtendedFuture<T>) super.completeAsync(() -> interruptiblyComplete(fId, supplier), executor);
   }

   public ExtendedFuture<T> completeAsync(final ThrowingSupplier<? extends T, ?> supplier) {
      final var fId = _getNewIncompleteFutureId();
      return (ExtendedFuture<T>) super.completeAsync(() -> interruptiblyComplete(fId, supplier));
   }

   public ExtendedFuture<T> completeAsync(final ThrowingSupplier<? extends T, ?> supplier, final Executor executor) {
      final var fId = _getNewIncompleteFutureId();
      return (ExtendedFuture<T>) super.completeAsync(() -> interruptiblyComplete(fId, supplier), executor);
   }

   @Override
   public ExtendedFuture<T> completeOnTimeout(final T value, final long timeout, final TimeUnit unit) {
      return (ExtendedFuture<T>) super.completeOnTimeout(value, timeout, unit);
   }

   @Override
   public ExtendedFuture<T> copy() {
      return (ExtendedFuture<T>) super.copy();
   }

   @Override
   public Executor defaultExecutor() {
      return defaultExecutor;
   }

   @Override
   public ExtendedFuture<T> exceptionally(final Function<Throwable, ? extends T> fn) {
      return (ExtendedFuture<T>) super.exceptionally(fn);
   }

   public ExtendedFuture<T> exceptionally(final ThrowingFunction<Throwable, ? extends T, ?> fn) {
      return (ExtendedFuture<T>) super.exceptionally(fn);
   }

   @Override
   public ExtendedFuture<T> exceptionallyAsync(final Function<Throwable, ? extends T> fn) {
      return (ExtendedFuture<T>) super.exceptionallyAsync(fn);
   }

   @Override
   public ExtendedFuture<T> exceptionallyAsync(final Function<Throwable, ? extends T> fn, final Executor executor) {
      return (ExtendedFuture<T>) super.exceptionallyAsync(fn, executor);
   }

   public ExtendedFuture<T> exceptionallyAsync(final ThrowingFunction<Throwable, ? extends T, ?> fn) {
      return (ExtendedFuture<T>) super.exceptionallyAsync(fn);
   }

   public ExtendedFuture<T> exceptionallyAsync(final ThrowingFunction<Throwable, ? extends T, ?> fn, final Executor executor) {
      return (ExtendedFuture<T>) super.exceptionallyAsync(fn, executor);
   }

   @Override
   public ExtendedFuture<T> exceptionallyCompose(final Function<Throwable, ? extends CompletionStage<T>> fn) {
      return (ExtendedFuture<T>) super.exceptionallyCompose(fn);
   }

   public ExtendedFuture<T> exceptionallyCompose(final ThrowingFunction<Throwable, ? extends CompletionStage<T>, ?> fn) {
      return (ExtendedFuture<T>) super.exceptionallyCompose(fn);
   }

   @Override
   public ExtendedFuture<T> exceptionallyComposeAsync(final Function<Throwable, ? extends CompletionStage<T>> fn) {
      return (ExtendedFuture<T>) super.exceptionallyComposeAsync(fn);
   }

   @Override
   public ExtendedFuture<T> exceptionallyComposeAsync(final Function<Throwable, ? extends CompletionStage<T>> fn, final Executor executor) {
      return (ExtendedFuture<T>) super.exceptionallyComposeAsync(fn, executor);
   }

   public ExtendedFuture<T> exceptionallyComposeAsync(final ThrowingFunction<Throwable, ? extends CompletionStage<T>, ?> fn) {
      return (ExtendedFuture<T>) super.exceptionallyComposeAsync(fn);
   }

   public ExtendedFuture<T> exceptionallyComposeAsync(final ThrowingFunction<Throwable, ? extends CompletionStage<T>, ?> fn,
         final Executor executor) {
      return (ExtendedFuture<T>) super.exceptionallyComposeAsync(fn, executor);
   }

   public CompletionState getCompletionState() {
      return CompletionState.of(this);
   }

   /**
    * Returns the result of this future if it is already completed wrapped in an {@link Optional},
    * or an empty {@link Optional} if the future is incomplete, cancelled or completed exceptionally.
    *
    * @return an {@link Optional} containing the result of the future if completed normally, or an empty {@link Optional} otherwise
    */
   public Optional<T> getNowOptional() {
      return Futures.getNowOptional(this);
   }

   /**
    * Returns the result of this future if it is already completed, or the specified
    * {@code fallback} if the future is incomplete, cancelled or completed exceptionally.
    *
    * @return the result of the future if completed normally, otherwise {@code fallback}
    */
   public T getNowOrFallback(final T fallback) {
      return Futures.getNowOrFallback(this, fallback);
   }

   /**
    * Attempts to retrieve the result this future within the specified timeout.
    *
    * @return an {@link Optional} containing the result of the future if completed normally within the timeout,
    *         or an empty {@link Optional} otherwise
    */
   public Optional<T> getOptional(final long timeout, final TimeUnit unit) {
      return Futures.getOptional(this, timeout, unit);
   }

   /**
    * Attempts to retrieve the result of this future within the specified timeout.
    *
    * @return the result of the future if completed normally within given timeout, otherwise {@code fallback}
    */
   public T getOrFallback(final long timeout, final TimeUnit unit, final T fallback) {
      return Futures.getOrFallback(this, timeout, unit, fallback);
   }

   @Override
   @SuppressWarnings("unchecked")
   public <U> ExtendedFuture<U> handle(final BiFunction<? super T, @Nullable Throwable, ? extends U> fn) {
      final var fId = _getNewIncompleteFutureId();
      return (ExtendedFuture<U>) super.handle((result, ex) -> interruptiblyHandle(fId, result, ex, fn));
   }

   @Override
   @SuppressWarnings("unchecked")
   public <U> ExtendedFuture<U> handleAsync(final BiFunction<? super T, @Nullable Throwable, ? extends U> fn) {
      final var fId = _getNewIncompleteFutureId();
      return (ExtendedFuture<U>) super.handleAsync((result, ex) -> interruptiblyHandle(fId, result, ex, fn));
   }

   @Override
   @SuppressWarnings("unchecked")
   public <U> ExtendedFuture<U> handleAsync(final BiFunction<? super T, @Nullable Throwable, ? extends U> fn, final Executor executor) {
      final var fId = _getNewIncompleteFutureId();
      return (ExtendedFuture<U>) super.handleAsync((result, ex) -> interruptiblyHandle(fId, result, ex, fn), executor);
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
         if (stage instanceof final ExtendedFuture<?> fut && fut.isCancellableByDependents()) {
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

   public boolean isCancellableByDependents() {
      return cancellableByDependents;
   }

   /**
    * @return true if this future is completed normally
    */
   public boolean isCompleted() {
      return isDone() && !isCancelled() && !isCompletedExceptionally();
   }

   /**
    * @return true if this future cannot be completed programmatically throw e.g. {@link #cancel(boolean)} or {@link #complete(Object)}.
    */
   public boolean isReadOnly() {
      return false;
   }

   @Override
   public <V> ExtendedFuture<V> newIncompleteFuture() {
      final ExtendedFuture<V> f = new ExtendedFuture<>(cancellableByDependents);
      if (cancellableByDependents && !isCancelled()) {
         f.cancellablePrecedingStages.add(this);
      }

      final var fId = INCOMPLETE_FUTURE_ID.get();
      INCOMPLETE_FUTURE_ID.remove();
      if (fId != null) { // for cases where newIncompleteFuture is used through code path by super class not handled by this implementation
         INCOMPLETE_FUTURES.put(fId, new IncompleteFuture(f, System.currentTimeMillis()));
      }
      return f;
   }

   @Override
   public ExtendedFuture<@Nullable Void> runAfterBoth(final CompletionStage<?> other, final Runnable action) {
      final var fId = _getNewIncompleteFutureId();
      return (ExtendedFuture<@Nullable Void>) super.runAfterBoth(other, () -> interruptiblyRun(fId, action));
   }

   public ExtendedFuture<@Nullable Void> runAfterBoth(final CompletionStage<?> other, final ThrowingRunnable<?> action) {
      final var fId = _getNewIncompleteFutureId();
      return (ExtendedFuture<@Nullable Void>) super.runAfterBoth(other, () -> interruptiblyRun(fId, action));
   }

   @Override
   public ExtendedFuture<@Nullable Void> runAfterBothAsync(final CompletionStage<?> other, final Runnable action) {
      final var fId = _getNewIncompleteFutureId();
      return (ExtendedFuture<@Nullable Void>) super.runAfterBothAsync(other, () -> interruptiblyRun(fId, action));
   }

   @Override
   public ExtendedFuture<@Nullable Void> runAfterBothAsync(final CompletionStage<?> other, final Runnable action, final Executor executor) {
      final var fId = _getNewIncompleteFutureId();
      return (ExtendedFuture<@Nullable Void>) super.runAfterBothAsync(other, () -> interruptiblyRun(fId, action), executor);
   }

   public ExtendedFuture<@Nullable Void> runAfterBothAsync(final CompletionStage<?> other, final ThrowingRunnable<?> action) {
      final var fId = _getNewIncompleteFutureId();
      return (ExtendedFuture<@Nullable Void>) super.runAfterBothAsync(other, () -> interruptiblyRun(fId, action));
   }

   public ExtendedFuture<@Nullable Void> runAfterBothAsync(final CompletionStage<?> other, final ThrowingRunnable<?> action,
         final Executor executor) {
      final var fId = _getNewIncompleteFutureId();
      return (ExtendedFuture<@Nullable Void>) super.runAfterBothAsync(other, () -> interruptiblyRun(fId, action), executor);
   }

   @Override
   public ExtendedFuture<@Nullable Void> runAfterEither(final CompletionStage<?> other, final Runnable action) {
      final var fId = _getNewIncompleteFutureId();
      return (ExtendedFuture<@Nullable Void>) super.runAfterEither(other, () -> interruptiblyRun(fId, action));
   }

   public ExtendedFuture<@Nullable Void> runAfterEither(final CompletionStage<?> other, final ThrowingRunnable<?> action) {
      final var fId = _getNewIncompleteFutureId();
      return (ExtendedFuture<@Nullable Void>) super.runAfterEither(other, () -> interruptiblyRun(fId, action));
   }

   @Override
   public ExtendedFuture<@Nullable Void> runAfterEitherAsync(final CompletionStage<?> other, final Runnable action) {
      final var fId = _getNewIncompleteFutureId();
      return (ExtendedFuture<@Nullable Void>) super.runAfterEitherAsync(other, () -> interruptiblyRun(fId, action));
   }

   @Override
   public ExtendedFuture<@Nullable Void> runAfterEitherAsync(final CompletionStage<?> other, final Runnable action,
         final Executor executor) {
      final var fId = _getNewIncompleteFutureId();
      return (ExtendedFuture<@Nullable Void>) super.runAfterEitherAsync(other, () -> interruptiblyRun(fId, action), executor);
   }

   public ExtendedFuture<@Nullable Void> runAfterEitherAsync(final CompletionStage<?> other, final ThrowingRunnable<?> action) {
      final var fId = _getNewIncompleteFutureId();
      return (ExtendedFuture<@Nullable Void>) super.runAfterEitherAsync(other, () -> interruptiblyRun(fId, action));
   }

   public ExtendedFuture<@Nullable Void> runAfterEitherAsync(final CompletionStage<?> other, final ThrowingRunnable<?> action,
         final Executor executor) {
      final var fId = _getNewIncompleteFutureId();
      return (ExtendedFuture<@Nullable Void>) super.runAfterEitherAsync(other, () -> interruptiblyRun(fId, action), executor);
   }

   @Override
   public ExtendedFuture<@Nullable Void> thenAccept(final Consumer<? super T> action) {
      final var fId = _getNewIncompleteFutureId();
      return (ExtendedFuture<@Nullable Void>) super.thenAccept(result -> interruptiblyAccept(fId, result, action));
   }

   public ExtendedFuture<@Nullable Void> thenAccept(final ThrowingConsumer<? super T, ?> action) {
      final var fId = _getNewIncompleteFutureId();
      return (ExtendedFuture<@Nullable Void>) super.thenAccept(result -> interruptiblyAccept(fId, result, action));
   }

   @Override
   public ExtendedFuture<@Nullable Void> thenAcceptAsync(final Consumer<? super T> action) {
      final var fId = _getNewIncompleteFutureId();
      return (ExtendedFuture<@Nullable Void>) super.thenAcceptAsync(result -> interruptiblyAccept(fId, result, action));
   }

   @Override
   public ExtendedFuture<@Nullable Void> thenAcceptAsync(final Consumer<? super T> action, final Executor executor) {
      final var fId = _getNewIncompleteFutureId();
      return (ExtendedFuture<@Nullable Void>) super.thenAcceptAsync(result -> interruptiblyAccept(fId, result, action), executor);
   }

   public ExtendedFuture<@Nullable Void> thenAcceptAsync(final ThrowingConsumer<? super T, ?> action) {
      final var fId = _getNewIncompleteFutureId();
      return (ExtendedFuture<@Nullable Void>) super.thenAcceptAsync(result -> interruptiblyAccept(fId, result, action));
   }

   public ExtendedFuture<@Nullable Void> thenAcceptAsync(final ThrowingConsumer<? super T, ?> action, final Executor executor) {
      final var fId = _getNewIncompleteFutureId();
      return (ExtendedFuture<@Nullable Void>) super.thenAcceptAsync(result -> interruptiblyAccept(fId, result, action), executor);
   }

   @Override
   public <U> ExtendedFuture<@Nullable Void> thenAcceptBoth(final CompletionStage<? extends U> other,
         final BiConsumer<? super T, ? super U> action) {
      final var fId = _getNewIncompleteFutureId();
      return (ExtendedFuture<@Nullable Void>) super.thenAcceptBoth(other, (result, otherResult) -> interruptiblyAcceptBoth(fId, result,
         otherResult, action));
   }

   @Override
   public <U> ExtendedFuture<@Nullable Void> thenAcceptBothAsync(final CompletionStage<? extends U> other,
         final BiConsumer<? super T, ? super U> action) {
      final var fId = _getNewIncompleteFutureId();
      return (ExtendedFuture<@Nullable Void>) super.thenAcceptBothAsync(other, (result, otherResult) -> interruptiblyAcceptBoth(fId, result,
         otherResult, action));
   }

   @Override
   public <U> ExtendedFuture<@Nullable Void> thenAcceptBothAsync(final CompletionStage<? extends U> other,
         final BiConsumer<? super T, ? super U> action, final Executor executor) {
      final var fId = _getNewIncompleteFutureId();
      return (ExtendedFuture<@Nullable Void>) super.thenAcceptBothAsync(other, (result, otherResult) -> interruptiblyAcceptBoth(fId, result,
         otherResult, action), executor);
   }

   @Override
   @SuppressWarnings("unchecked")
   public <U> ExtendedFuture<U> thenApply(final Function<? super T, ? extends U> fn) {
      final var fId = _getNewIncompleteFutureId();
      return (ExtendedFuture<U>) super.thenApply(result -> interruptiblyApply(fId, result, fn));
   }

   @SuppressWarnings("unchecked")
   public <U> ExtendedFuture<U> thenApply(final ThrowingFunction<? super T, ? extends U, ?> fn) {
      final var fId = _getNewIncompleteFutureId();
      return (ExtendedFuture<U>) super.thenApply(result -> interruptiblyApply(fId, result, fn));
   }

   @Override
   @SuppressWarnings("unchecked")
   public <U> ExtendedFuture<U> thenApplyAsync(final Function<? super T, ? extends U> fn) {
      final var fId = _getNewIncompleteFutureId();
      return (ExtendedFuture<U>) super.thenApplyAsync(result -> interruptiblyApply(fId, result, fn));
   }

   @Override
   @SuppressWarnings("unchecked")
   public <U> ExtendedFuture<U> thenApplyAsync(final Function<? super T, ? extends U> fn, final Executor executor) {
      final var fId = _getNewIncompleteFutureId();
      return (ExtendedFuture<U>) super.thenApplyAsync(result -> interruptiblyApply(fId, result, fn), executor);
   }

   @SuppressWarnings("unchecked")
   public <U> ExtendedFuture<U> thenApplyAsync(final ThrowingFunction<? super T, ? extends U, ?> fn) {
      final var fId = _getNewIncompleteFutureId();
      return (ExtendedFuture<U>) super.thenApplyAsync(result -> interruptiblyApply(fId, result, fn));
   }

   @SuppressWarnings("unchecked")
   public <U> ExtendedFuture<U> thenApplyAsync(final ThrowingFunction<? super T, ? extends U, ?> fn, final Executor executor) {
      final var fId = _getNewIncompleteFutureId();
      return (ExtendedFuture<U>) super.thenApplyAsync(result -> interruptiblyApply(fId, result, fn), executor);
   }

   @Override
   @SuppressWarnings("unchecked")
   public <U, V> ExtendedFuture<V> thenCombine(final CompletionStage<? extends U> other,
         final BiFunction<? super T, ? super U, ? extends V> fn) {
      final var fId = _getNewIncompleteFutureId();
      return (ExtendedFuture<V>) super.thenCombine(other, (result, otherResult) -> interruptiblyCombine(fId, result, otherResult, fn));
   }

   @Override
   @SuppressWarnings("unchecked")
   public <U, V> ExtendedFuture<V> thenCombineAsync(final CompletionStage<? extends U> other,
         final BiFunction<? super T, ? super U, ? extends V> fn) {
      final var fId = _getNewIncompleteFutureId();
      return (ExtendedFuture<V>) super.thenCombineAsync(other, (result, otherResult) -> interruptiblyCombine(fId, result, otherResult, fn));
   }

   @Override
   @SuppressWarnings("unchecked")
   public <U, V> ExtendedFuture<V> thenCombineAsync(final CompletionStage<? extends U> other,
         final BiFunction<? super T, ? super U, ? extends V> fn, final Executor executor) {
      final var fId = _getNewIncompleteFutureId();
      return (ExtendedFuture<V>) super.thenCombineAsync(other, (result, otherResult) -> interruptiblyCombine(fId, result, otherResult, fn),
         executor);
   }

   @Override
   public <U> ExtendedFuture<U> thenCompose(final Function<? super T, ? extends CompletionStage<U>> fn) {
      final var fId = _getNewIncompleteFutureId();
      return (ExtendedFuture<U>) super.thenCompose(result -> interruptiblyThenCompose(fId, result, fn));
   }

   @Override
   public <U> ExtendedFuture<U> thenComposeAsync(final Function<? super T, ? extends CompletionStage<U>> fn) {
      final var fId = _getNewIncompleteFutureId();
      return (ExtendedFuture<U>) super.thenComposeAsync(result -> interruptiblyThenCompose(fId, result, fn));
   }

   @Override
   public <U> ExtendedFuture<U> thenComposeAsync(final Function<? super T, ? extends CompletionStage<U>> fn, final Executor executor) {
      final var fId = _getNewIncompleteFutureId();
      return (ExtendedFuture<U>) super.thenComposeAsync(result -> interruptiblyThenCompose(fId, result, fn), executor);
   }

   @Override
   public ExtendedFuture<@Nullable Void> thenRun(final Runnable action) {
      final var fId = _getNewIncompleteFutureId();
      return (ExtendedFuture<@Nullable Void>) super.thenRun(() -> interruptiblyRun(fId, action));
   }

   public ExtendedFuture<@Nullable Void> thenRun(final ThrowingRunnable<?> action) {
      final var fId = _getNewIncompleteFutureId();
      return (ExtendedFuture<@Nullable Void>) super.thenRun(() -> interruptiblyRun(fId, action));
   }

   @Override
   public ExtendedFuture<@Nullable Void> thenRunAsync(final Runnable action) {
      final var fId = _getNewIncompleteFutureId();
      return (ExtendedFuture<@Nullable Void>) super.thenRunAsync(() -> interruptiblyRun(fId, action));
   }

   @Override
   public ExtendedFuture<@Nullable Void> thenRunAsync(final Runnable action, final Executor executor) {
      final var fId = _getNewIncompleteFutureId();
      return (ExtendedFuture<@Nullable Void>) super.thenRunAsync(() -> interruptiblyRun(fId, action), executor);
   }

   public ExtendedFuture<@Nullable Void> thenRunAsync(final ThrowingRunnable<?> action) {
      final var fId = _getNewIncompleteFutureId();
      return (ExtendedFuture<@Nullable Void>) super.thenRunAsync(() -> interruptiblyRun(fId, action));
   }

   public ExtendedFuture<@Nullable Void> thenRunAsync(final ThrowingRunnable<?> action, final Executor executor) {
      final var fId = _getNewIncompleteFutureId();
      return (ExtendedFuture<@Nullable Void>) super.thenRunAsync(() -> interruptiblyRun(fId, action), executor);
   }

   @Override
   public ExtendedFuture<T> whenComplete(final BiConsumer<? super @Nullable T, ? super @Nullable Throwable> action) {
      final var fId = _getNewIncompleteFutureId();
      return (ExtendedFuture<T>) super.whenComplete((result, ex) -> interruptiblyWhenComplete(fId, result, ex, action));
   }

   @Override
   public ExtendedFuture<T> whenCompleteAsync(final BiConsumer<? super @Nullable T, ? super @Nullable Throwable> action) {
      final var fId = _getNewIncompleteFutureId();
      return (ExtendedFuture<T>) super.whenCompleteAsync((result, ex) -> interruptiblyWhenComplete(fId, result, ex, action));
   }

   @Override
   public ExtendedFuture<T> whenCompleteAsync(final BiConsumer<? super @Nullable T, ? super @Nullable Throwable> action,
         final Executor executor) {
      final var fId = _getNewIncompleteFutureId();
      return (ExtendedFuture<T>) super.whenCompleteAsync((result, ex) -> interruptiblyWhenComplete(fId, result, ex, action), executor);
   }

   /**
    * Returns an {@link ExtendedFuture} that shares the result with this future, but with the
    * specified {@link Executor} as the default for asynchronous operations of subsequent stages.
    *
    * @param defaultExecutor the default {@link Executor} for async tasks, must not be {@code null}
    * @return a new {@code ExtendedFuture} with the specified executor, or {@code this} if the
    *         executor is unchanged
    */
   public ExtendedFuture<T> withDefaultExecutor(final Executor defaultExecutor) {
      if (defaultExecutor == this.defaultExecutor)
         return this;
      return new ExtendedFuture<>(defaultExecutor, cancellableByDependents, this);
   }
}
