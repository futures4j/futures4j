/*
 * SPDX-FileCopyrightText: © Sebastian Thomschke
 * SPDX-License-Identifier: EPL-2.0
 * SPDX-FileContributor: Sebastian Thomschke: initial API and implementation
 */
package io.github.futures4j;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
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
 * <li>supports task thread interruption via <code>cancel(true)</code>, controllable via {@link #asNonInterruptible()} and
 * {@link #withInterruptibleStages(boolean)}
 * <li>allows dependent stages cancel preceding stages, controllable via {@link #asCancellableByDependents(boolean)}
 * <li>allows running tasks that throw checked exceptions via e.g. {@link #runAsync(ThrowingRunnable)}, etc.
 * <li>allows creating a read-only view of a future using {@link #asReadOnly(ReadOnlyMode)}
 * <li>allows defining a default executor for this future and all subsequent stages e.g. via {@link #createWithDefaultExecutor(Executor)} or
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

   private static final Logger LOG = System.getLogger(Futures.class.getName());

   public static class Builder<V> {

      private boolean cancellableByDependents = false;
      private boolean interruptible = true;
      private boolean interruptibleStages = true;
      private @Nullable Executor defaultExecutor;
      private @Nullable CompletableFuture<V> wrapped;

      protected Builder() {
      }

      public ExtendedFuture<V> build() {
         return interruptible && wrapped == null //
               ? new InterruptibleFuture<>(defaultExecutor, cancellableByDependents, interruptibleStages)
               : new ExtendedFuture<>(defaultExecutor, cancellableByDependents, interruptibleStages, wrapped);
      }

      public Builder<V> withCancellableByDependents(final boolean isCancellableByDependents) {
         cancellableByDependents = isCancellableByDependents;
         return this;
      }

      public Builder<V> withDefaultExecutor(final @Nullable Executor defaultExecutor) {
         this.defaultExecutor = defaultExecutor;
         return this;
      }

      public Builder<V> withInterruptible(final boolean interruptible) {
         this.interruptible = interruptible;
         return this;
      }

      public Builder<V> withInterruptibleStages(final boolean interruptibleStages) {
         this.interruptibleStages = interruptibleStages;
         return this;
      }

      @SuppressWarnings("unchecked")
      public <T extends V> Builder<T> withWrapped(final @Nullable CompletableFuture<T> wrapped) {
         this.wrapped = (CompletableFuture<V>) wrapped;
         return (Builder<T>) this;
      }
   }

   public enum ReadOnlyMode {

      /** mutation attempts will throw {@link UnsupportedOperationException} */
      THROW_ON_MUTATION,

      /** mutation attempts will be silently ignored */
      IGNORE_MUTATION
   }

   static final class InterruptibleFuture<T> extends ExtendedFuture<T> {

      /**
       * Set by e.g. {@link ExtendedFuture#interruptiblyRun(int, Runnable)}
       */
      private @Nullable Thread executingThread;
      private final Object executingThreadLock = new Object();

      InterruptibleFuture(@Nullable final Executor defaultExecutor, final boolean cancellableByDependents,
            final boolean interruptibleStages) {
         super(defaultExecutor, cancellableByDependents, interruptibleStages, null);
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
   }

   static final class InterruptibleFutureWrapper<T> extends ExtendedFuture<T> {

      InterruptibleFutureWrapper(final @Nullable Executor defaultExecutor, final boolean cancellableByDependents,
            final boolean interruptibleStages, final ExtendedFuture<T> wrapped) {
         super(defaultExecutor, cancellableByDependents, interruptibleStages, wrapped);
      }

      @Override
      public boolean isInterruptible() {
         return true;
      }
   }

   /**
    * A holder for new incomplete {@link InterruptibleFuture} instances.
    * It is used to enable interruptible tasks.
    */
   private record NewIncompleteFutureHolder<T>(InterruptibleFuture<T> future, long expiresOn) {

      private static final AtomicInteger ID_GENERATOR = new AtomicInteger();
      private static final ThreadLocal<@Nullable Integer> ID_HOLDER = new ThreadLocal<>();
      private static final ConcurrentMap<Integer, NewIncompleteFutureHolder<?>> BY_ID = new ConcurrentHashMap<>(4);

      /**
       * Retrieves the {@link InterruptibleFuture} associated with the given ID.
       * <p>
       * This method is used by interruptible operations (e.g., {@link ExtendedFuture#interruptiblyRun(Runnable)})
       * to fetch and bind the future to the current executing thread. The thread reference is required for enabling
       * the {@link InterruptibleFuture#cancel(boolean)} method to interrupt the thread if cancellation is requested.
       */
      @SuppressWarnings("unchecked")
      static <V> InterruptibleFuture<V> lookup(final int futureId) {
         final var newFuture = BY_ID.remove(futureId);
         if (newFuture == null) // should never happen
            throw new IllegalStateException("No future present with id " + futureId);

         // remove obsolete incomplete futures (should actually not happen, just a precaution to avoid potential memory leaks)
         if (!BY_ID.isEmpty()) {
            final var now = System.currentTimeMillis();
            BY_ID.values().removeIf(holder -> now > holder.expiresOn);
         }

         return (InterruptibleFuture<V>) newFuture.future;
      }

      /**
       * Used by {@link ExtendedFuture#newIncompleteFuture()} to store newly created incomplete stages in the {@link #BY_ID} map for later
       * retrieval via {@link #lookup(int)} by interruptible operations (e.g., {@link ExtendedFuture#interruptiblyRun(Runnable)}).
       * <p>
       * This method requires that {@link #generateFutureId()} was called first as this method retrieves the future's ID from the
       * ThreadLocal {@link #ID_HOLDER}.
       */
      static void store(final InterruptibleFuture<?> newFuture) {
         final var futureId = ID_HOLDER.get();
         ID_HOLDER.remove();
         // potentially null for cases where #newIncompleteFuture() is used through code paths by super class not handled by this subclass
         if (futureId != null) {
            BY_ID.put(futureId, new NewIncompleteFutureHolder<>(newFuture, System.currentTimeMillis() + 5_000));
         }
      }

      /**
       * Generates a unique future ID and stores it in the ThreadLocal {@link #ID_HOLDER}.
       * <p>
       * The {@link #ID_HOLDER} is used to pass the ID to the {@link ExtendedFuture#newIncompleteFuture()} method,
       * allowing it to store new incomplete future in the {@link #BY_ID} map via {@link #store(InterruptibleFuture)}.
       *
       * @return the generated unique future ID
       */
      static int generateFutureId() {
         final var futureId = ID_GENERATOR.incrementAndGet();
         ID_HOLDER.set(futureId);
         return futureId;
      }
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

   public static <V> Builder<V> builder() {
      return new Builder<>();
   }

   public static <V> ExtendedFuture<V> completedFuture(final V value) {
      final var f = new ExtendedFuture<V>(null, false, true, null);
      f.complete(value);
      return f;
   }

   /**
    * @param defaultExecutor default executor for subsequent stages
    */
   public static <V> ExtendedFuture<V> completedFutureWithDefaultExecutor(final V value, final Executor defaultExecutor) {
      final var f = new ExtendedFuture<V>(defaultExecutor, false, true, null);
      f.complete(value);
      return f;
   }

   /**
    * @return a new {@link ExtendedFuture} with {@link #isCancellableByDependents()} set to {@code false} and
    *         {@link #isInterruptibleStages()} set to {@code true}.
    */
   public static <V> ExtendedFuture<V> create() {
      return new ExtendedFuture<>(null, false, true, null);
   }

   /**
    * @return a new {@link ExtendedFuture} with {@link #isCancellableByDependents()} set to {@code true} and
    *         {@link #isInterruptibleStages()} set to {@code true}.
    */
   public static <V> ExtendedFuture<V> createCancellableByDependents() {
      return new ExtendedFuture<>(null, true, true, null);
   }

   /**
    * @return a new {@link ExtendedFuture} with the given default executor for all subsequent stages and
    *         {@link #isCancellableByDependents()} set to {@code false} and {@link #isInterruptibleStages()} set to {@code true}.
    */
   public static <V> ExtendedFuture<V> createWithDefaultExecutor(final Executor defaultExecutor) {
      return new ExtendedFuture<>(defaultExecutor, false, true, null);
   }

   public static <V> ExtendedFuture<V> failedFuture(final Throwable ex) {
      final var f = new ExtendedFuture<V>(null, false, true, null);
      f.completeExceptionally(ex);
      return f;
   }

   /**
    * @param defaultExecutor default executor for subsequent stages
    */
   public static <V> ExtendedFuture<V> failedFutureWithDefaultExecutor(final Throwable ex, final Executor defaultExecutor) {
      final var f = new ExtendedFuture<V>(defaultExecutor, false, true, null);
      f.completeExceptionally(ex);
      return f;
   }

   /**
    * Derives an {@link ExtendedFuture} from a {@link CompletableFuture} with {@link #isCancellableByDependents()} set to {@code false}.
    * <p>
    * Returns the given future if it is already an instance of {@link ExtendedFuture} with {@link #isCancellableByDependents()} set to
    * {@code false}.
    */
   public static <V> ExtendedFuture<V> from(final CompletableFuture<V> source) {
      if (source instanceof final ExtendedFuture<V> cf)
         return cf.asCancellableByDependents(false);
      return new ExtendedFuture<>(source.defaultExecutor(), false, true, source);
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

   protected final Collection<Future<?>> cancellablePrecedingStages;
   protected final boolean cancellableByDependents;
   protected final boolean interruptibleStages;
   protected final Executor defaultExecutor;
   protected final @Nullable CompletableFuture<T> wrapped;

   protected ExtendedFuture(@Nullable final Executor defaultExecutor, final boolean cancellableByDependents,
         final boolean interruptibleStages, final @Nullable CompletableFuture<T> wrapped) {
      this.defaultExecutor = defaultExecutor == null ? super.defaultExecutor() : defaultExecutor;
      this.cancellableByDependents = cancellableByDependents;
      cancellablePrecedingStages = wrapped == null ? new ConcurrentLinkedQueue<>() : Collections.emptyList();
      this.interruptibleStages = interruptibleStages;

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
      if (interruptibleStages) {
         final var fId = NewIncompleteFutureHolder.generateFutureId();
         return (ExtendedFuture<@Nullable Void>) super.acceptEither(other, result -> interruptiblyAccept(fId, result, action));
      }
      return (ExtendedFuture<@Nullable Void>) super.acceptEither(other, action);
   }

   public ExtendedFuture<@Nullable Void> acceptEither(final CompletionStage<? extends T> other,
         final ThrowingConsumer<? super T, ?> action) {
      if (interruptibleStages) {
         final var fId = NewIncompleteFutureHolder.generateFutureId();
         return (ExtendedFuture<@Nullable Void>) super.acceptEither(other, result -> interruptiblyAccept(fId, result, action));
      }
      return (ExtendedFuture<@Nullable Void>) super.acceptEither(other, action);
   }

   @Override
   public ExtendedFuture<@Nullable Void> acceptEitherAsync(final CompletionStage<? extends T> other, final Consumer<? super T> action) {
      if (interruptibleStages) {
         final var fId = NewIncompleteFutureHolder.generateFutureId();
         return (ExtendedFuture<@Nullable Void>) super.acceptEitherAsync(other, result -> interruptiblyAccept(fId, result, action));
      }
      return (ExtendedFuture<@Nullable Void>) super.acceptEitherAsync(other, action);
   }

   @Override
   public ExtendedFuture<@Nullable Void> acceptEitherAsync(final CompletionStage<? extends T> other, final Consumer<? super T> action,
         final Executor executor) {
      if (interruptibleStages) {
         final var fId = NewIncompleteFutureHolder.generateFutureId();
         return (ExtendedFuture<@Nullable Void>) super.acceptEitherAsync(other, result -> interruptiblyAccept(fId, result, action),
            executor);
      }
      return (ExtendedFuture<@Nullable Void>) super.acceptEitherAsync(other, action, executor);
   }

   public ExtendedFuture<@Nullable Void> acceptEitherAsync(final CompletionStage<? extends T> other,
         final ThrowingConsumer<? super T, ?> action) {
      if (interruptibleStages) {
         final var fId = NewIncompleteFutureHolder.generateFutureId();
         return (ExtendedFuture<@Nullable Void>) super.acceptEitherAsync(other, result -> interruptiblyAccept(fId, result, action));
      }
      return (ExtendedFuture<@Nullable Void>) super.acceptEitherAsync(other, action);
   }

   public ExtendedFuture<@Nullable Void> acceptEitherAsync(final CompletionStage<? extends T> other,
         final ThrowingConsumer<? super T, ?> action, final Executor executor) {
      if (interruptibleStages) {
         final var fId = NewIncompleteFutureHolder.generateFutureId();
         return (ExtendedFuture<@Nullable Void>) super.acceptEitherAsync(other, result -> interruptiblyAccept(fId, result, action),
            executor);
      }
      return (ExtendedFuture<@Nullable Void>) super.acceptEitherAsync(other, action, executor);
   }

   @Override
   public <U> ExtendedFuture<U> applyToEither(final CompletionStage<? extends T> other, final Function<? super T, U> fn) {
      if (interruptibleStages) {
         final var fId = NewIncompleteFutureHolder.generateFutureId();
         return (ExtendedFuture<U>) super.applyToEither(other, result -> interruptiblyApply(fId, result, fn));
      }
      return (ExtendedFuture<U>) super.applyToEither(other, fn);
   }

   public <U> ExtendedFuture<U> applyToEither(final CompletionStage<? extends T> other, final ThrowingFunction<? super T, U, ?> fn) {
      if (interruptibleStages) {
         final var fId = NewIncompleteFutureHolder.generateFutureId();
         return (ExtendedFuture<U>) super.applyToEither(other, result -> interruptiblyApply(fId, result, fn));
      }
      return (ExtendedFuture<U>) super.applyToEither(other, fn);
   }

   @Override
   public <U> ExtendedFuture<U> applyToEitherAsync(final CompletionStage<? extends T> other, final Function<? super T, U> fn) {
      if (interruptibleStages) {
         final var fId = NewIncompleteFutureHolder.generateFutureId();
         return (ExtendedFuture<U>) super.applyToEitherAsync(other, result -> interruptiblyApply(fId, result, fn));
      }
      return (ExtendedFuture<U>) super.applyToEitherAsync(other, fn);
   }

   @Override
   public <U> ExtendedFuture<U> applyToEitherAsync(final CompletionStage<? extends T> other, final Function<? super T, U> fn,
         final Executor executor) {
      if (interruptibleStages) {
         final var fId = NewIncompleteFutureHolder.generateFutureId();
         return (ExtendedFuture<U>) super.applyToEitherAsync(other, result -> interruptiblyApply(fId, result, fn), executor);
      }
      return (ExtendedFuture<U>) super.applyToEitherAsync(other, fn, executor);
   }

   public <U> ExtendedFuture<U> applyToEitherAsync(final CompletionStage<? extends T> other, final ThrowingFunction<? super T, U, ?> fn) {
      if (interruptibleStages) {
         final var fId = NewIncompleteFutureHolder.generateFutureId();
         return (ExtendedFuture<U>) super.applyToEitherAsync(other, result -> interruptiblyApply(fId, result, fn));
      }
      return (ExtendedFuture<U>) super.applyToEitherAsync(other, fn);
   }

   public <U> ExtendedFuture<U> applyToEitherAsync(final CompletionStage<? extends T> other, final ThrowingFunction<? super T, U, ?> fn,
         final Executor executor) {
      if (interruptibleStages) {
         final var fId = NewIncompleteFutureHolder.generateFutureId();
         return (ExtendedFuture<U>) super.applyToEitherAsync(other, result -> interruptiblyApply(fId, result, fn), executor);
      }
      return (ExtendedFuture<U>) super.applyToEitherAsync(other, fn, executor);
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
      return isInterruptible() //
            ? new InterruptibleFutureWrapper<>(defaultExecutor, isCancellableByDependents, interruptibleStages, this)
            : new ExtendedFuture<>(defaultExecutor, isCancellableByDependents, interruptibleStages, this);
   }

   /**
    * Returns an {@link ExtendedFuture} that shares the result with this future but ensures
    * that this future cannot be interrupted, i.e., calling {@code cancel(true)} will not
    * result in a thread interruption.
    * <p>
    * If the future is already non-interruptible, this instance is returned.
    *
    * @return a new {@link ExtendedFuture} that is non-interruptible,
    *         or this instance if it is already non-interruptible.
    */
   public ExtendedFuture<T> asNonInterruptible() {
      if (!isInterruptible())
         return this;
      return new ExtendedFuture<>(defaultExecutor, cancellableByDependents, interruptibleStages, this);
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
      return new ExtendedFuture<>(defaultExecutor, false, interruptibleStages, this) {
         @Override
         public boolean cancel(final boolean mayInterruptIfRunning) {
            if (throwOnMutationAttempt)
               throw new UnsupportedOperationException(this + " is read-only.");
            LOG.log(Level.WARNING, "Attempted to cancel a read-only future: " + this);
            return isCancelled();
         }

         @Override
         public boolean complete(final T value) {
            if (throwOnMutationAttempt)
               throw new UnsupportedOperationException(this + " is read-only.");
            LOG.log(Level.WARNING, "Attempted to complete a read-only future: " + this);
            return false;
         }

         @Override
         public ExtendedFuture<T> completeAsync(final Supplier<? extends T> supplier) {
            if (throwOnMutationAttempt)
               throw new UnsupportedOperationException(this + " is read-only.");
            LOG.log(Level.WARNING, "Attempted to complete a read-only future: " + this);
            return this;
         }

         @Override
         public ExtendedFuture<T> completeAsync(final ThrowingSupplier<? extends T, ?> supplier) {
            if (throwOnMutationAttempt)
               throw new UnsupportedOperationException(this + " is read-only.");
            LOG.log(Level.WARNING, "Attempted to complete a read-only future: " + this);
            return this;
         }

         @Override
         public ExtendedFuture<T> completeAsync(final Supplier<? extends T> supplier, final Executor executor) {
            if (throwOnMutationAttempt)
               throw new UnsupportedOperationException(this + " is read-only.");
            LOG.log(Level.WARNING, "Attempted to complete a read-only future: " + this);
            return this;
         }

         @Override
         public ExtendedFuture<T> completeAsync(final ThrowingSupplier<? extends T, ?> supplier, final Executor executor) {
            if (throwOnMutationAttempt)
               throw new UnsupportedOperationException(this + " is read-only.");
            LOG.log(Level.WARNING, "Attempted to complete a read-only future: " + this);
            return this;
         }

         @Override
         public boolean completeExceptionally(final Throwable ex) {
            if (throwOnMutationAttempt)
               throw new UnsupportedOperationException(this + " is read-only.");
            LOG.log(Level.WARNING, "Attempted to complete a read-only future: " + this);
            return false;
         }

         @Override
         public ExtendedFuture<T> completeOnTimeout(final T value, final long timeout, final TimeUnit unit) {
            if (throwOnMutationAttempt)
               throw new UnsupportedOperationException(this + " is read-only.");
            LOG.log(Level.WARNING, "Attempted to complete a read-only future: " + this);
            return this;
         }

         @Override
         public boolean isReadOnly() {
            return true;
         }

         @Override
         public void obtrudeException(final Throwable ex) {
            if (throwOnMutationAttempt)
               throw new UnsupportedOperationException(this + " is read-only.");
            LOG.log(Level.WARNING, "Attempted to obtrude a read-only future: " + this);
         }

         @Override
         public void obtrudeValue(final T value) {
            if (throwOnMutationAttempt)
               throw new UnsupportedOperationException(this + " is read-only.");
            LOG.log(Level.WARNING, "Attempted to obtrude a read-only future: " + this);
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
         cancelled = super.cancel(mayInterruptIfRunning && isInterruptible());

         if (cancelled && !cancellablePrecedingStages.isEmpty()) {
            cancellablePrecedingStages.removeIf(stage -> {
               if (!stage.isDone()) {
                  stage.cancel(mayInterruptIfRunning && isInterruptible());
               }
               return true;
            });
         }
      } else {
         cancelled = wrapped.cancel(mayInterruptIfRunning && isInterruptible());
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
      if (interruptibleStages) {
         final var fId = NewIncompleteFutureHolder.generateFutureId();
         return (ExtendedFuture<T>) super.completeAsync(() -> interruptiblyComplete(fId, supplier));
      }
      return (ExtendedFuture<T>) super.completeAsync(supplier);
   }

   @Override
   public ExtendedFuture<T> completeAsync(final Supplier<? extends T> supplier, final Executor executor) {
      if (interruptibleStages) {
         final var fId = NewIncompleteFutureHolder.generateFutureId();
         return (ExtendedFuture<T>) super.completeAsync(() -> interruptiblyComplete(fId, supplier), executor);
      }
      return (ExtendedFuture<T>) super.completeAsync(supplier, executor);
   }

   public ExtendedFuture<T> completeAsync(final ThrowingSupplier<? extends T, ?> supplier) {
      if (interruptibleStages) {
         final var fId = NewIncompleteFutureHolder.generateFutureId();
         return (ExtendedFuture<T>) super.completeAsync(() -> interruptiblyComplete(fId, supplier));
      }
      return (ExtendedFuture<T>) super.completeAsync(supplier);
   }

   public ExtendedFuture<T> completeAsync(final ThrowingSupplier<? extends T, ?> supplier, final Executor executor) {
      if (interruptibleStages) {
         final var fId = NewIncompleteFutureHolder.generateFutureId();
         return (ExtendedFuture<T>) super.completeAsync(() -> interruptiblyComplete(fId, supplier), executor);
      }
      return (ExtendedFuture<T>) super.completeAsync(supplier, executor);
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
    * Returns the result of this future if it is already completed, or the value provided by
    * {@code fallbackComputer} if the future is incomplete, cancelled or completed exceptionally.
    *
    * @return the result of the future if completed normally, otherwise the value computed by {@code fallbackComputer}
    */
   public T getNowOrComputeFallback(final BiFunction<Future<T>, @Nullable Exception, T> fallbackComputer) {
      return Futures.getNowOrComputeFallback(this, fallbackComputer);
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
    * @return the result of the future if completed normally within given timeout, the value computed by {@code fallbackComputer}
    */
   public T getOrComputeFallback(final long timeout, final TimeUnit unit,
         final BiFunction<Future<T>, @Nullable Exception, T> fallbackComputer) {
      return Futures.getOrComputeFallback(this, timeout, unit, fallbackComputer);
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
      if (interruptibleStages) {
         final var fId = NewIncompleteFutureHolder.generateFutureId();
         return (ExtendedFuture<U>) super.handle((result, ex) -> interruptiblyHandle(fId, result, ex, fn));
      }
      return (ExtendedFuture<U>) super.handle(fn);
   }

   @Override
   @SuppressWarnings("unchecked")
   public <U> ExtendedFuture<U> handleAsync(final BiFunction<? super T, @Nullable Throwable, ? extends U> fn) {
      if (interruptibleStages) {
         final var fId = NewIncompleteFutureHolder.generateFutureId();
         return (ExtendedFuture<U>) super.handleAsync((result, ex) -> interruptiblyHandle(fId, result, ex, fn));
      }
      return (ExtendedFuture<U>) super.handleAsync(fn);
   }

   @Override
   @SuppressWarnings("unchecked")
   public <U> ExtendedFuture<U> handleAsync(final BiFunction<? super T, @Nullable Throwable, ? extends U> fn, final Executor executor) {
      if (interruptibleStages) {
         final var fId = NewIncompleteFutureHolder.generateFutureId();
         return (ExtendedFuture<U>) super.handleAsync((result, ex) -> interruptiblyHandle(fId, result, ex, fn), executor);
      }
      return (ExtendedFuture<U>) super.handleAsync(fn, executor);
   }

   private void interruptiblyAccept(final int futureId, final T result, final Consumer<? super T> action) {
      final var f = NewIncompleteFutureHolder.lookup(futureId);
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
      final var f = NewIncompleteFutureHolder.lookup(futureId);
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
      final var f = NewIncompleteFutureHolder.lookup(futureId);
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
      final var f = NewIncompleteFutureHolder.lookup(futureId);
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
      final var f = NewIncompleteFutureHolder.lookup(futureId);
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
      final var f = NewIncompleteFutureHolder.lookup(futureId);
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
      final var f = NewIncompleteFutureHolder.lookup(futureId);
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
      final var f = NewIncompleteFutureHolder.lookup(futureId);
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
      final var f = NewIncompleteFutureHolder.lookup(futureId);
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

   /**
    * Returns {@code true} if this future is cancellable by its dependent stages.
    * If {@code true}, cancellation of a dependent stage will also cancel this future and any preceding stages.
    * If {@code false}, cancellation of dependent stages will not affect this future.
    *
    * @return {@code true} if this future is cancellable by dependents, {@code false} otherwise.
    */
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
    * @return if this stage is interruptible, i.e. {@code cancel(true)} will result in thread interruption
    */
   public boolean isInterruptible() {
      return false;
   }

   /**
    * @return if new stages are interruptible
    */
   public boolean isInterruptibleStages() {
      return interruptibleStages;
   }

   /**
    * @return true if this future cannot be completed programmatically throw e.g. {@link #cancel(boolean)} or {@link #complete(Object)}.
    */
   public boolean isReadOnly() {
      return false;
   }

   @Override
   public <V> ExtendedFuture<V> newIncompleteFuture() {
      final ExtendedFuture<V> newFuture;
      if (interruptibleStages) {
         final var newInterruptibleFuture = new InterruptibleFuture<V>(defaultExecutor, cancellableByDependents, interruptibleStages);
         NewIncompleteFutureHolder.store(newInterruptibleFuture);
         newFuture = newInterruptibleFuture;
      } else {
         newFuture = new ExtendedFuture<>(defaultExecutor, cancellableByDependents, interruptibleStages, null);
      }
      if (cancellableByDependents && !isCancelled()) {
         newFuture.cancellablePrecedingStages.add(this);
      }
      return newFuture;
   }

   /**
    * Registers this future with the given {@link Consumer}.
    *
    * @return this instance
    */
   public ExtendedFuture<T> registerWith(final Consumer<? super Future<?>> target) {
      target.accept(this);
      return this;
   }

   /**
    * Registers this future with the given {@link FuturesContext}.
    *
    * @return this instance
    */
   public ExtendedFuture<T> registerWith(final FuturesContext<? super T> ctx) {
      ctx.register(this);
      return this;
   }

   @Override
   public ExtendedFuture<@Nullable Void> runAfterBoth(final CompletionStage<?> other, final Runnable action) {
      if (interruptibleStages) {
         final var fId = NewIncompleteFutureHolder.generateFutureId();
         return (ExtendedFuture<@Nullable Void>) super.runAfterBoth(other, () -> interruptiblyRun(fId, action));
      }
      return (ExtendedFuture<@Nullable Void>) super.runAfterBoth(other, action);
   }

   public ExtendedFuture<@Nullable Void> runAfterBoth(final CompletionStage<?> other, final ThrowingRunnable<?> action) {
      if (interruptibleStages) {
         final var fId = NewIncompleteFutureHolder.generateFutureId();
         return (ExtendedFuture<@Nullable Void>) super.runAfterBoth(other, () -> interruptiblyRun(fId, action));
      }
      return (ExtendedFuture<@Nullable Void>) super.runAfterBoth(other, action);
   }

   @Override
   public ExtendedFuture<@Nullable Void> runAfterBothAsync(final CompletionStage<?> other, final Runnable action) {
      if (interruptibleStages) {
         final var fId = NewIncompleteFutureHolder.generateFutureId();
         return (ExtendedFuture<@Nullable Void>) super.runAfterBothAsync(other, () -> interruptiblyRun(fId, action));
      }
      return (ExtendedFuture<@Nullable Void>) super.runAfterBothAsync(other, action);
   }

   @Override
   public ExtendedFuture<@Nullable Void> runAfterBothAsync(final CompletionStage<?> other, final Runnable action, final Executor executor) {
      if (interruptibleStages) {
         final var fId = NewIncompleteFutureHolder.generateFutureId();
         return (ExtendedFuture<@Nullable Void>) super.runAfterBothAsync(other, () -> interruptiblyRun(fId, action), executor);
      }
      return (ExtendedFuture<@Nullable Void>) super.runAfterBothAsync(other, action, executor);
   }

   public ExtendedFuture<@Nullable Void> runAfterBothAsync(final CompletionStage<?> other, final ThrowingRunnable<?> action) {
      if (interruptibleStages) {
         final var fId = NewIncompleteFutureHolder.generateFutureId();
         return (ExtendedFuture<@Nullable Void>) super.runAfterBothAsync(other, () -> interruptiblyRun(fId, action));
      }
      return (ExtendedFuture<@Nullable Void>) super.runAfterBothAsync(other, action);
   }

   public ExtendedFuture<@Nullable Void> runAfterBothAsync(final CompletionStage<?> other, final ThrowingRunnable<?> action,
         final Executor executor) {
      if (interruptibleStages) {
         final var fId = NewIncompleteFutureHolder.generateFutureId();
         return (ExtendedFuture<@Nullable Void>) super.runAfterBothAsync(other, () -> interruptiblyRun(fId, action), executor);
      }
      return (ExtendedFuture<@Nullable Void>) super.runAfterBothAsync(other, action, executor);
   }

   @Override
   public ExtendedFuture<@Nullable Void> runAfterEither(final CompletionStage<?> other, final Runnable action) {
      if (interruptibleStages) {
         final var fId = NewIncompleteFutureHolder.generateFutureId();
         return (ExtendedFuture<@Nullable Void>) super.runAfterEither(other, () -> interruptiblyRun(fId, action));
      }
      return (ExtendedFuture<@Nullable Void>) super.runAfterEither(other, action);
   }

   public ExtendedFuture<@Nullable Void> runAfterEither(final CompletionStage<?> other, final ThrowingRunnable<?> action) {
      if (interruptibleStages) {
         final var fId = NewIncompleteFutureHolder.generateFutureId();
         return (ExtendedFuture<@Nullable Void>) super.runAfterEither(other, () -> interruptiblyRun(fId, action));
      }
      return (ExtendedFuture<@Nullable Void>) super.runAfterEither(other, action);
   }

   @Override
   public ExtendedFuture<@Nullable Void> runAfterEitherAsync(final CompletionStage<?> other, final Runnable action) {
      if (interruptibleStages) {
         final var fId = NewIncompleteFutureHolder.generateFutureId();
         return (ExtendedFuture<@Nullable Void>) super.runAfterEitherAsync(other, () -> interruptiblyRun(fId, action));
      }
      return (ExtendedFuture<@Nullable Void>) super.runAfterEitherAsync(other, action);
   }

   @Override
   public ExtendedFuture<@Nullable Void> runAfterEitherAsync(final CompletionStage<?> other, final Runnable action,
         final Executor executor) {
      if (interruptibleStages) {
         final var fId = NewIncompleteFutureHolder.generateFutureId();
         return (ExtendedFuture<@Nullable Void>) super.runAfterEitherAsync(other, () -> interruptiblyRun(fId, action), executor);
      }
      return (ExtendedFuture<@Nullable Void>) super.runAfterEitherAsync(other, action, executor);
   }

   public ExtendedFuture<@Nullable Void> runAfterEitherAsync(final CompletionStage<?> other, final ThrowingRunnable<?> action) {
      if (interruptibleStages) {
         final var fId = NewIncompleteFutureHolder.generateFutureId();
         return (ExtendedFuture<@Nullable Void>) super.runAfterEitherAsync(other, () -> interruptiblyRun(fId, action));
      }
      return (ExtendedFuture<@Nullable Void>) super.runAfterEitherAsync(other, action);
   }

   public ExtendedFuture<@Nullable Void> runAfterEitherAsync(final CompletionStage<?> other, final ThrowingRunnable<?> action,
         final Executor executor) {
      if (interruptibleStages) {
         final var fId = NewIncompleteFutureHolder.generateFutureId();
         return (ExtendedFuture<@Nullable Void>) super.runAfterEitherAsync(other, () -> interruptiblyRun(fId, action), executor);
      }
      return (ExtendedFuture<@Nullable Void>) super.runAfterEitherAsync(other, action, executor);
   }

   @Override
   public ExtendedFuture<@Nullable Void> thenAccept(final Consumer<? super T> action) {
      if (interruptibleStages) {
         final var fId = NewIncompleteFutureHolder.generateFutureId();
         return (ExtendedFuture<@Nullable Void>) super.thenAccept(result -> interruptiblyAccept(fId, result, action));
      }
      return (ExtendedFuture<@Nullable Void>) super.thenAccept(action);
   }

   public ExtendedFuture<@Nullable Void> thenAccept(final ThrowingConsumer<? super T, ?> action) {
      if (interruptibleStages) {
         final var fId = NewIncompleteFutureHolder.generateFutureId();
         return (ExtendedFuture<@Nullable Void>) super.thenAccept(result -> interruptiblyAccept(fId, result, action));
      }
      return (ExtendedFuture<@Nullable Void>) super.thenAccept(action);
   }

   @Override
   public ExtendedFuture<@Nullable Void> thenAcceptAsync(final Consumer<? super T> action) {
      if (interruptibleStages) {
         final var fId = NewIncompleteFutureHolder.generateFutureId();
         return (ExtendedFuture<@Nullable Void>) super.thenAcceptAsync(result -> interruptiblyAccept(fId, result, action));
      }
      return (ExtendedFuture<@Nullable Void>) super.thenAcceptAsync(action);
   }

   @Override
   public ExtendedFuture<@Nullable Void> thenAcceptAsync(final Consumer<? super T> action, final Executor executor) {
      if (interruptibleStages) {
         final var fId = NewIncompleteFutureHolder.generateFutureId();
         return (ExtendedFuture<@Nullable Void>) super.thenAcceptAsync(result -> interruptiblyAccept(fId, result, action), executor);
      }
      return (ExtendedFuture<@Nullable Void>) super.thenAcceptAsync(action, executor);
   }

   public ExtendedFuture<@Nullable Void> thenAcceptAsync(final ThrowingConsumer<? super T, ?> action) {
      if (interruptibleStages) {
         final var fId = NewIncompleteFutureHolder.generateFutureId();
         return (ExtendedFuture<@Nullable Void>) super.thenAcceptAsync(result -> interruptiblyAccept(fId, result, action));
      }
      return (ExtendedFuture<@Nullable Void>) super.thenAcceptAsync(action);
   }

   public ExtendedFuture<@Nullable Void> thenAcceptAsync(final ThrowingConsumer<? super T, ?> action, final Executor executor) {
      if (interruptibleStages) {
         final var fId = NewIncompleteFutureHolder.generateFutureId();
         return (ExtendedFuture<@Nullable Void>) super.thenAcceptAsync(result -> interruptiblyAccept(fId, result, action), executor);
      }
      return (ExtendedFuture<@Nullable Void>) super.thenAcceptAsync(action, executor);
   }

   @Override
   public <U> ExtendedFuture<@Nullable Void> thenAcceptBoth(final CompletionStage<? extends U> other,
         final BiConsumer<? super T, ? super U> action) {
      if (interruptibleStages) {
         final var fId = NewIncompleteFutureHolder.generateFutureId();
         return (ExtendedFuture<@Nullable Void>) super.thenAcceptBoth(other, (result, otherResult) -> interruptiblyAcceptBoth(fId, result,
            otherResult, action));
      }
      return (ExtendedFuture<@Nullable Void>) super.thenAcceptBoth(other, action);
   }

   @Override
   public <U> ExtendedFuture<@Nullable Void> thenAcceptBothAsync(final CompletionStage<? extends U> other,
         final BiConsumer<? super T, ? super U> action) {
      if (interruptibleStages) {
         final var fId = NewIncompleteFutureHolder.generateFutureId();
         return (ExtendedFuture<@Nullable Void>) super.thenAcceptBothAsync(other, (result, otherResult) -> interruptiblyAcceptBoth(fId,
            result, otherResult, action));
      }
      return (ExtendedFuture<@Nullable Void>) super.thenAcceptBothAsync(other, action);
   }

   @Override
   public <U> ExtendedFuture<@Nullable Void> thenAcceptBothAsync(final CompletionStage<? extends U> other,
         final BiConsumer<? super T, ? super U> action, final Executor executor) {
      if (interruptibleStages) {
         final var fId = NewIncompleteFutureHolder.generateFutureId();
         return (ExtendedFuture<@Nullable Void>) super.thenAcceptBothAsync(other, (result, otherResult) -> interruptiblyAcceptBoth(fId,
            result, otherResult, action), executor);
      }
      return (ExtendedFuture<@Nullable Void>) super.thenAcceptBothAsync(other, action, executor);
   }

   @Override
   @SuppressWarnings("unchecked")
   public <U> ExtendedFuture<U> thenApply(final Function<? super T, ? extends U> fn) {
      if (interruptibleStages) {
         final var fId = NewIncompleteFutureHolder.generateFutureId();
         return (ExtendedFuture<U>) super.thenApply(result -> interruptiblyApply(fId, result, fn));
      }
      return (ExtendedFuture<U>) super.thenApply(fn);
   }

   @SuppressWarnings("unchecked")
   public <U> ExtendedFuture<U> thenApply(final ThrowingFunction<? super T, ? extends U, ?> fn) {
      if (interruptibleStages) {
         final var fId = NewIncompleteFutureHolder.generateFutureId();
         return (ExtendedFuture<U>) super.thenApply(result -> interruptiblyApply(fId, result, fn));
      }
      return (ExtendedFuture<U>) super.thenApply(fn);
   }

   @Override
   @SuppressWarnings("unchecked")
   public <U> ExtendedFuture<U> thenApplyAsync(final Function<? super T, ? extends U> fn) {
      if (interruptibleStages) {
         final var fId = NewIncompleteFutureHolder.generateFutureId();
         return (ExtendedFuture<U>) super.thenApplyAsync(result -> interruptiblyApply(fId, result, fn));
      }
      return (ExtendedFuture<U>) super.thenApplyAsync(fn);
   }

   @Override
   @SuppressWarnings("unchecked")
   public <U> ExtendedFuture<U> thenApplyAsync(final Function<? super T, ? extends U> fn, final Executor executor) {
      if (interruptibleStages) {
         final var fId = NewIncompleteFutureHolder.generateFutureId();
         return (ExtendedFuture<U>) super.thenApplyAsync(result -> interruptiblyApply(fId, result, fn), executor);
      }
      return (ExtendedFuture<U>) super.thenApplyAsync(fn, executor);
   }

   @SuppressWarnings("unchecked")
   public <U> ExtendedFuture<U> thenApplyAsync(final ThrowingFunction<? super T, ? extends U, ?> fn) {
      if (interruptibleStages) {
         final var fId = NewIncompleteFutureHolder.generateFutureId();
         return (ExtendedFuture<U>) super.thenApplyAsync(result -> interruptiblyApply(fId, result, fn));
      }
      return (ExtendedFuture<U>) super.thenApplyAsync(fn);
   }

   @SuppressWarnings("unchecked")
   public <U> ExtendedFuture<U> thenApplyAsync(final ThrowingFunction<? super T, ? extends U, ?> fn, final Executor executor) {
      if (interruptibleStages) {
         final var fId = NewIncompleteFutureHolder.generateFutureId();
         return (ExtendedFuture<U>) super.thenApplyAsync(result -> interruptiblyApply(fId, result, fn), executor);
      }
      return (ExtendedFuture<U>) super.thenApplyAsync(fn, executor);
   }

   @Override
   @SuppressWarnings("unchecked")
   public <U, V> ExtendedFuture<V> thenCombine(final CompletionStage<? extends U> other,
         final BiFunction<? super T, ? super U, ? extends V> fn) {
      if (interruptibleStages) {
         final var fId = NewIncompleteFutureHolder.generateFutureId();
         return (ExtendedFuture<V>) super.thenCombine(other, (result, otherResult) -> interruptiblyCombine(fId, result, otherResult, fn));
      }
      return (ExtendedFuture<V>) super.thenCombine(other, fn);
   }

   @Override
   @SuppressWarnings("unchecked")
   public <U, V> ExtendedFuture<V> thenCombineAsync(final CompletionStage<? extends U> other,
         final BiFunction<? super T, ? super U, ? extends V> fn) {
      if (interruptibleStages) {
         final var fId = NewIncompleteFutureHolder.generateFutureId();
         return (ExtendedFuture<V>) super.thenCombineAsync(other, (result, otherResult) -> interruptiblyCombine(fId, result, otherResult,
            fn));
      }
      return (ExtendedFuture<V>) super.thenCombineAsync(other, fn);
   }

   @Override
   @SuppressWarnings("unchecked")
   public <U, V> ExtendedFuture<V> thenCombineAsync(final CompletionStage<? extends U> other,
         final BiFunction<? super T, ? super U, ? extends V> fn, final Executor executor) {
      if (interruptibleStages) {
         final var fId = NewIncompleteFutureHolder.generateFutureId();
         return (ExtendedFuture<V>) super.thenCombineAsync(other, (result, otherResult) -> interruptiblyCombine(fId, result, otherResult,
            fn), executor);
      }
      return (ExtendedFuture<V>) super.thenCombineAsync(other, fn, executor);
   }

   @Override
   public <U> ExtendedFuture<U> thenCompose(final Function<? super T, ? extends CompletionStage<U>> fn) {
      if (interruptibleStages) {
         final var fId = NewIncompleteFutureHolder.generateFutureId();
         return (ExtendedFuture<U>) super.thenCompose(result -> interruptiblyThenCompose(fId, result, fn));
      }
      return (ExtendedFuture<U>) super.thenCompose(fn);
   }

   @Override
   public <U> ExtendedFuture<U> thenComposeAsync(final Function<? super T, ? extends CompletionStage<U>> fn) {
      if (interruptibleStages) {
         final var fId = NewIncompleteFutureHolder.generateFutureId();
         return (ExtendedFuture<U>) super.thenComposeAsync(result -> interruptiblyThenCompose(fId, result, fn));
      }
      return (ExtendedFuture<U>) super.thenComposeAsync(fn);
   }

   @Override
   public <U> ExtendedFuture<U> thenComposeAsync(final Function<? super T, ? extends CompletionStage<U>> fn, final Executor executor) {
      if (interruptibleStages) {
         final var fId = NewIncompleteFutureHolder.generateFutureId();
         return (ExtendedFuture<U>) super.thenComposeAsync(result -> interruptiblyThenCompose(fId, result, fn), executor);
      }
      return (ExtendedFuture<U>) super.thenComposeAsync(fn, executor);
   }

   @Override
   public ExtendedFuture<@Nullable Void> thenRun(final Runnable action) {
      if (interruptibleStages) {
         final var fId = NewIncompleteFutureHolder.generateFutureId();
         return (ExtendedFuture<@Nullable Void>) super.thenRun(() -> interruptiblyRun(fId, action));
      }
      return (ExtendedFuture<@Nullable Void>) super.thenRun(action);
   }

   public ExtendedFuture<@Nullable Void> thenRun(final ThrowingRunnable<?> action) {
      if (interruptibleStages) {
         final var fId = NewIncompleteFutureHolder.generateFutureId();
         return (ExtendedFuture<@Nullable Void>) super.thenRun(() -> interruptiblyRun(fId, action));
      }
      return (ExtendedFuture<@Nullable Void>) super.thenRun(action);
   }

   @Override
   public ExtendedFuture<@Nullable Void> thenRunAsync(final Runnable action) {
      if (interruptibleStages) {
         final var fId = NewIncompleteFutureHolder.generateFutureId();
         return (ExtendedFuture<@Nullable Void>) super.thenRunAsync(() -> interruptiblyRun(fId, action));
      }
      return (ExtendedFuture<@Nullable Void>) super.thenRunAsync(action);
   }

   @Override
   public ExtendedFuture<@Nullable Void> thenRunAsync(final Runnable action, final Executor executor) {
      if (interruptibleStages) {
         final var fId = NewIncompleteFutureHolder.generateFutureId();
         return (ExtendedFuture<@Nullable Void>) super.thenRunAsync(() -> interruptiblyRun(fId, action), executor);
      }
      return (ExtendedFuture<@Nullable Void>) super.thenRunAsync(action, executor);
   }

   public ExtendedFuture<@Nullable Void> thenRunAsync(final ThrowingRunnable<?> action) {
      if (interruptibleStages) {
         final var fId = NewIncompleteFutureHolder.generateFutureId();
         return (ExtendedFuture<@Nullable Void>) super.thenRunAsync(() -> interruptiblyRun(fId, action));
      }
      return (ExtendedFuture<@Nullable Void>) super.thenRunAsync(action);
   }

   public ExtendedFuture<@Nullable Void> thenRunAsync(final ThrowingRunnable<?> action, final Executor executor) {
      if (interruptibleStages) {
         final var fId = NewIncompleteFutureHolder.generateFutureId();
         return (ExtendedFuture<@Nullable Void>) super.thenRunAsync(() -> interruptiblyRun(fId, action), executor);
      }
      return (ExtendedFuture<@Nullable Void>) super.thenRunAsync(action, executor);
   }

   @Override
   public ExtendedFuture<T> whenComplete(final BiConsumer<? super @Nullable T, ? super @Nullable Throwable> action) {
      if (interruptibleStages) {
         final var fId = NewIncompleteFutureHolder.generateFutureId();
         return (ExtendedFuture<T>) super.whenComplete((result, ex) -> interruptiblyWhenComplete(fId, result, ex, action));
      }
      return (ExtendedFuture<T>) super.whenComplete(action);
   }

   @Override
   public ExtendedFuture<T> whenCompleteAsync(final BiConsumer<? super @Nullable T, ? super @Nullable Throwable> action) {
      if (interruptibleStages) {
         final var fId = NewIncompleteFutureHolder.generateFutureId();
         return (ExtendedFuture<T>) super.whenCompleteAsync((result, ex) -> interruptiblyWhenComplete(fId, result, ex, action));
      }
      return (ExtendedFuture<T>) super.whenCompleteAsync(action);
   }

   @Override
   public ExtendedFuture<T> whenCompleteAsync(final BiConsumer<? super @Nullable T, ? super @Nullable Throwable> action,
         final Executor executor) {
      if (interruptibleStages) {
         final var fId = NewIncompleteFutureHolder.generateFutureId();
         return (ExtendedFuture<T>) super.whenCompleteAsync((result, ex) -> interruptiblyWhenComplete(fId, result, ex, action), executor);
      }
      return (ExtendedFuture<T>) super.whenCompleteAsync(action, executor);
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

      return isInterruptible() //
            ? new InterruptibleFutureWrapper<>(defaultExecutor, cancellableByDependents, interruptibleStages, this)
            : new ExtendedFuture<>(defaultExecutor, cancellableByDependents, interruptibleStages, this);
   }

   /**
    * Returns an {@link ExtendedFuture} that shares the result with this future but with
    * the specified behavior for new stages being interruptible or not.
    * <p>
    * If the requested interruptibility behavior matches the current one, this instance is returned.
    *
    * @param interruptibleStages {@code true} if new stages should be interruptible, {@code false} otherwise
    * @return a new {@link ExtendedFuture} with the specified interruptibility behavior for new stages,
    *         or this instance if the behavior remains unchanged.
    */
   public ExtendedFuture<T> withInterruptibleStages(final boolean interruptibleStages) {
      if (interruptibleStages == this.interruptibleStages)
         return this;

      return isInterruptible() //
            ? new InterruptibleFutureWrapper<>(defaultExecutor, cancellableByDependents, interruptibleStages, this)
            : new ExtendedFuture<>(defaultExecutor, cancellableByDependents, interruptibleStages, this);
   }
}
