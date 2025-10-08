/*
 * SPDX-FileCopyrightText: © Sebastian Thomschke
 * SPDX-License-Identifier: EPL-2.0
 * SPDX-FileContributor: Sebastian Thomschke: initial API and implementation
 */
package io.github.futures4j;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.Collection;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;

import io.github.futures4j.util.ThrowingBiConsumer;
import io.github.futures4j.util.ThrowingBiFunction;
import io.github.futures4j.util.ThrowingConsumer;
import io.github.futures4j.util.ThrowingFunction;
import io.github.futures4j.util.ThrowingRunnable;
import io.github.futures4j.util.ThrowingSupplier;

/**
 * An enhanced version of {@link CompletableFuture} providing additional features:
 * <ul>
 * <li><b>Interruptible Tasks:</b> Allows task thread interruption via {@code cancel(true)}. This behavior is controllable using
 * {@link #asNonInterruptible()} and {@link #withInterruptibleStages(boolean)}.</li>
 * <li><b>Dependent Stage Cancellation:</b> Enables dependent stages to cancel preceding stages, controllable via
 * {@link #asCancellableByDependents(boolean)}.</li>
 * <li><b>Checked Exceptions:</b> Supports running tasks that throw checked exceptions, e.g., {@link #runAsync(ThrowingRunnable)}.</li>
 * <li><b>Read-Only Views:</b> Allows creating read-only views of a future using {@link #asReadOnly(ReadOnlyMode)}.</li>
 * <li><b>Default Executor:</b> Enables defining a default executor for this future and all subsequent stages via
 * {@link #withDefaultExecutor(Executor)} or {@link Builder#withDefaultExecutor(Executor)}.</li>
 * <li><b>Convenience Methods:</b> Offers additional methods such as {@link #completeWith(CompletableFuture)}, {@link #isSuccess()},
 * {@link #isFailed()}, {@link #getNowOptional()}, {@link #getNowOrFallback(Object)}, {@link #getOptional(long, TimeUnit)},
 * {@link #getOrFallback(Object)}, and {@link #getOrFallback(Object, long, TimeUnit)}.</li>
 * </ul>
 *
 * <p>
 * For more information on issues addressed by this class, refer to the following resources:
 * </p>
 * <ul>
 * <li>https://stackoverflow.com/questions/25417881/canceling-a-completablefuture-chain</li>
 * <li>https://stackoverflow.com/questions/36727820/cancellation-of-completablefuture-controlled-by-executorservice</li>
 * <li>https://stackoverflow.com/questions/62106428/is-there-a-better-way-for-cancelling-a-chain-of-futures-in-java</li>
 * </ul>
 * and:
 * <ul>
 * <li>https://stackoverflow.com/questions/29013831/how-to-interrupt-underlying-execution-of-completablefuture</li>
 * <li>https://nurkiewicz.com/2015/03/completablefuture-cant-be-interrupted.html</li>
 * <li>https://blog.tremblay.pro/2017/08/supply-async.html</li>
 * </ul>
 *
 * @author <a href="https://sebthom.de/">Sebastian Thomschke</a>
 * @param <T> the result type returned by this {@code ExtendedFuture}
 */
public class ExtendedFuture<T> extends CompletableFuture<T> {

   /**
    * A builder for constructing customized {@link ExtendedFuture} instances with specific configurations.
    *
    * @param <V> the result type of the future
    */
   public static class Builder<V> {

      private boolean cancellableByDependents = false;
      private boolean interruptible = true;
      private boolean interruptibleStages = true;
      private @Nullable Executor defaultExecutor;
      private @Nullable CompletableFuture<V> wrapped;
      private boolean resultSet = false;
      private @Nullable V result;

      protected Builder() {
      }

      /**
       * Builds an {@link ExtendedFuture} instance with the configured settings.
       *
       * @return a new {@link ExtendedFuture} instance
       */
      @SuppressWarnings("null")
      public ExtendedFuture<V> build() {
         final var wrapped = this.wrapped;
         final ExtendedFuture<V> fut = wrapped == null //
               ? interruptible //
                     ? new InterruptibleFuture<>(cancellableByDependents, interruptibleStages, defaultExecutor)
                     : new ExtendedFuture<>(cancellableByDependents, interruptibleStages, defaultExecutor)
               : new WrappingFuture<>(wrapped, cancellableByDependents, interruptibleStages, defaultExecutor);
         if (resultSet) {
            fut.complete(result);
         }
         return fut;
      }

      /**
       * Sets whether the future can be cancelled by its dependent stages.
       *
       * @param isCancellableByDependents {@code true} if the future can be cancelled by dependents, {@code false} otherwise
       * @return this {@code Builder} instance for method chaining
       */
      public Builder<V> withCancellableByDependents(final boolean isCancellableByDependents) {
         cancellableByDependents = isCancellableByDependents;
         return this;
      }

      /**
       * Completes the newly constructed future with the given value.
       *
       * @param value the value to complete the new future with
       * @return this {@code Builder} instance for method chaining
       */
      public Builder<V> withCompletedValue(final V value) {
         resultSet = true;
         result = value;
         return this;
      }

      /**
       * Sets the default executor for this future and all subsequent stages.
       *
       * @param defaultExecutor the default {@link Executor} to use
       * @return this {@code Builder} instance for method chaining
       */
      public Builder<V> withDefaultExecutor(final @Nullable Executor defaultExecutor) {
         this.defaultExecutor = defaultExecutor;
         return this;
      }

      /**
       * Sets whether the future is interruptible.
       *
       * @param interruptible {@code true} if the future is interruptible, {@code false} otherwise
       * @return this {@code Builder} instance for method chaining
       */
      public Builder<V> withInterruptible(final boolean interruptible) {
         this.interruptible = interruptible;
         return this;
      }

      /**
       * Sets whether new stages are interruptible.
       *
       * @param interruptibleStages {@code true} if new stages are interruptible, {@code false} otherwise
       * @return this {@code Builder} instance for method chaining
       */
      public Builder<V> withInterruptibleStages(final boolean interruptibleStages) {
         this.interruptibleStages = interruptibleStages;
         return this;
      }

      /**
       * Wraps an existing {@link CompletableFuture} with an {@link ExtendedFuture}.
       *
       * @param wrapped the {@link CompletableFuture} to wrap
       * @return this {@code Builder} instance for method chaining
       */
      public Builder<V> withWrapped(final @Nullable CompletableFuture<V> wrapped) {
         this.wrapped = wrapped;
         return this;
      }
   }

   static final class InterruptibleFuture<T> extends ExtendedFuture<T> {

      /**
       * Set by methods like {@link ExtendedFuture#interruptiblyRun(int, Runnable)}.
       */
      private @Nullable Thread executingThread;
      private final Object executingThreadLock = new Object();

      private InterruptibleFuture(final boolean cancellableByDependents, final boolean interruptibleStages,
            final @Nullable Executor defaultExecutor) {
         super(cancellableByDependents, interruptibleStages, defaultExecutor);
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

   private static final class InterruptibleWrappingFuture<T> extends WrappingFuture<T> {

      private InterruptibleWrappingFuture(final InterruptibleFuture<T> wrapped, final boolean cancellableByDependents,
            final boolean interruptibleStages, final @Nullable Executor defaultExecutor) {
         super(wrapped, cancellableByDependents, interruptibleStages, defaultExecutor);
      }

      @Override
      public boolean isInterruptible() {
         return true;
      }
   }

   /**
    * Internal helper to wire up interruptible stages.
    *
    * <p>
    * Flow:
    * </p>
    * <ol>
    * <li>{@link #generateFutureId()} puts a fresh id in a thread-local.</li>
    * <li>{@link ExtendedFuture#newIncompleteFuture()} picks up that id and {@link #store(InterruptibleFuture) stores} the new
    * {@link InterruptibleFuture} in a static map.</li>
    * <li>When the stage begins, {@link #lookup(int)} removes the future from the map and records the current thread so {@code cancel(true)}
    * can call {@link Thread#interrupt()}.</li>
    * </ol>
    */
   static final class InterruptibleFuturesTracker {

      private static final AtomicInteger ID_GENERATOR = new AtomicInteger();
      private static final ThreadLocal<@Nullable Integer> ID_HOLDER = new ThreadLocal<>();
      static final ConcurrentMap<Integer, FutureWeakRef> BY_ID = new ConcurrentHashMap<>(4);

      private static final class FutureWeakRef extends WeakReference<@Nullable InterruptibleFuture<?>> {
         private static final ReferenceQueue<@Nullable InterruptibleFuture<?>> REF_QUEUE = new ReferenceQueue<>();
         final int id; // immutable, used for fast removal

         FutureWeakRef(final InterruptibleFuture<?> referent, final int id) {
            super(referent, REF_QUEUE);
            this.id = id;
         }
      }

      static void purgeStaleEntries() {
         for (var ref = FutureWeakRef.REF_QUEUE.poll(); ref != null; ref = FutureWeakRef.REF_QUEUE.poll()) {
            final int id = ((FutureWeakRef) ref).id;
            BY_ID.remove(id, ref);
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

      /**
       * Retrieves the {@link InterruptibleFuture} associated with the given ID.
       * <p>
       * This method is used by interruptible operations (e.g., {@link ExtendedFuture#interruptiblyRun(Runnable)})
       * to fetch and bind the future to the current executing thread. The thread reference is required for enabling
       * the {@link InterruptibleFuture#cancel(boolean)} method to interrupt the thread if cancellation is requested.
       */
      @SuppressWarnings("unchecked")
      static <V> InterruptibleFuture<V> lookup(final int futureId) {
         purgeStaleEntries();

         final var ref = BY_ID.remove(futureId);
         if (ref == null) // should never happen
            throw new IllegalStateException("No future present with id " + futureId);

         final var newFuture = ref.get();
         if (newFuture == null) // should never happen
            throw new IllegalStateException("No future present with id " + futureId);

         return (InterruptibleFuture<V>) newFuture;
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
            BY_ID.put(futureId, new FutureWeakRef(newFuture, futureId));
         }
      }
   }

   /**
    * Remembers the original {@code mayInterruptIfRunning} intent on the concrete stage instance being cancelled
    * (useful when a non-interruptible wrapper masks the flag for its own cancellation but we still want upstream
    * propagation to honor the caller's intent).
    */
   private boolean hasCancelIntent;
   private boolean cancelIntentMayInterrupt;

   private void rememberCancelIntentIfAbsent(final boolean mayInterruptIfRunning) {
      if (!hasCancelIntent) {
         hasCancelIntent = true;
         cancelIntentMayInterrupt = mayInterruptIfRunning;
      }
   }

   private boolean resolveCancelIntentOrDefault(final boolean defaultMayInterruptIfRunning) {
      final boolean result = hasCancelIntent ? cancelIntentMayInterrupt : defaultMayInterruptIfRunning;
      hasCancelIntent = false;
      return result;
   }

   /**
    * Modes for creating a read-only view of an {@link ExtendedFuture}.
    */
   public enum ReadOnlyMode {

      /** Mutation attempts will throw {@link UnsupportedOperationException}. */
      THROW_ON_MUTATION,

      /** Mutation attempts will be silently ignored. */
      IGNORE_MUTATION
   }

   static class WrappingFuture<T> extends ExtendedFuture<T> {

      protected final CompletableFuture<T> wrapped;

      private WrappingFuture(final CompletableFuture<T> wrapped, final boolean cancellableByDependents, final boolean interruptibleStages,
            final @Nullable Executor defaultExecutor) {
         super(cancellableByDependents, interruptibleStages, defaultExecutor);
         this.wrapped = wrapped;
         wrapped.whenComplete((result, ex) -> {
            if (ex == null) {
               super.complete(result);
            } else {
               super.completeExceptionally(ex);
            }
         });
      }

      @Override
      public boolean cancel(final boolean mayInterruptIfRunning) {
         // Preserve the caller's original intent for upstream propagation even if this wrapper masks interrupts
         if (wrapped instanceof ExtendedFuture) {
            ((ExtendedFuture<?>) wrapped).rememberCancelIntentIfAbsent(mayInterruptIfRunning);
         }
         return wrapped.cancel(mayInterruptIfRunning && isInterruptible());
      }

      @Override
      public boolean complete(final T value) {
         return wrapped.complete(value);
      }

      @Override
      public ExtendedFuture<T> completeAsync(final Supplier<? extends T> supplier) {
         wrapped.completeAsync(supplier);
         return this;
      }

      @Override
      public ExtendedFuture<T> completeAsync(final Supplier<? extends T> supplier, final Executor executor) {
         wrapped.completeAsync(supplier, executor);
         return this;
      }

      @Override
      public ExtendedFuture<T> completeAsync(final ThrowingSupplier<? extends T, ?> supplier) {
         wrapped.completeAsync(supplier);
         return this;
      }

      @Override
      public ExtendedFuture<T> completeAsync(final ThrowingSupplier<? extends T, ?> supplier, final Executor executor) {
         wrapped.completeAsync(supplier, executor);
         return this;
      }

      @Override
      public boolean completeExceptionally(final Throwable ex) {
         return wrapped.completeExceptionally(ex);
      }

      @Override
      public ExtendedFuture<T> completeWith(final CompletableFuture<? extends T> future) {
         future.whenComplete((result, ex) -> {
            if (ex == null) {
               wrapped.complete(result);
            } else {
               wrapped.completeExceptionally(ex);
            }
         });
         return this;
      }
   }

   private static final Logger LOG = System.getLogger(ExtendedFuture.class.getName());

   /**
    * Returns a new {@link ExtendedFuture} that completes when all of the given futures complete.
    *
    * @param cfs the array of {@link CompletableFuture} instances
    * @return an {@link ExtendedFuture} that completes when all given futures complete
    * @see CompletableFuture#allOf(CompletableFuture...)
    */
   public static ExtendedFuture<@Nullable Void> allOf(final CompletableFuture<?>... cfs) {
      return ExtendedFuture.from(CompletableFuture.allOf(cfs));
   }

   /**
    * Returns a new {@link ExtendedFuture} that completes when any of the given futures complete.
    *
    * @param cfs the array of {@link CompletableFuture} instances
    * @return an {@link ExtendedFuture} that completes when any given future completes
    * @see CompletableFuture#anyOf(CompletableFuture...)
    */
   public static ExtendedFuture<@Nullable Object> anyOf(final CompletableFuture<?>... cfs) {
      return ExtendedFuture.from(CompletableFuture.anyOf(cfs));
   }

   /**
    * Creates a new {@link Builder} for constructing an {@link ExtendedFuture}.
    *
    * @param <V> the result type of the future
    * @return a new {@link Builder} instance
    */
   @NonNullByDefault({})
   public static <V> Builder<V> builder(@SuppressWarnings("unused") final Class<V> targetType) {
      return new Builder<>();
   }

   /**
    * Returns a completed {@link ExtendedFuture} with the given value.
    *
    * @param value the value to complete the future with
    * @param <V> the result type of the future
    * @return a completed {@link ExtendedFuture}
    */
   public static <V> ExtendedFuture<V> completedFuture(final V value) {
      final var f = new ExtendedFuture<V>(false, true, null);
      f.complete(value);
      return f;
   }

   /**
    * Returns a completed {@link ExtendedFuture} that has completed exceptionally with the given exception.
    *
    * @param ex the exception to complete the future with
    * @param <V> the result type of the future
    * @return a completed {@link ExtendedFuture} that completed exceptionally
    */
   public static <V> ExtendedFuture<V> failedFuture(final Throwable ex) {
      final var f = new ExtendedFuture<V>(false, true, null);
      f.completeExceptionally(ex);
      return f;
   }

   /**
    * Wraps a given {@link CompletableFuture} into an {@link ExtendedFuture}.
    * If the given future is already an instance of {@link ExtendedFuture} with {@link #isCancellableByDependents()} set to
    * {@code false}, it is returned as-is.
    *
    * @param source the {@link CompletableFuture} to wrap
    * @param <V> the result type of the future
    * @return an {@link ExtendedFuture} wrapping the given future
    */
   public static <V> ExtendedFuture<V> from(final CompletableFuture<V> source) {
      if (source instanceof ExtendedFuture)
         return ((ExtendedFuture<V>) source).asCancellableByDependents(false);
      return new WrappingFuture<>(source, false, true, source.defaultExecutor());
   }

   /**
    * Returns an {@link ExtendedFuture} that runs the given runnable asynchronously.
    *
    * @param runnable the {@link Runnable} to execute
    * @return an {@link ExtendedFuture} representing the asynchronous computation
    */
   public static ExtendedFuture<@Nullable Void> runAsync(final Runnable runnable) {
      return completedFuture(null).thenRunAsync(runnable);
   }

   /**
    * Returns an {@link ExtendedFuture} that runs the given runnable asynchronously using the provided executor.
    *
    * @param runnable the {@link Runnable} to execute
    * @param executor the {@link Executor} to use for execution
    * @return an {@link ExtendedFuture} representing the asynchronous computation
    */
   public static ExtendedFuture<@Nullable Void> runAsync(final Runnable runnable, final Executor executor) {
      return completedFuture(null).thenRunAsync(runnable, executor);
   }

   /**
    * Returns an {@link ExtendedFuture} that runs the given throwing runnable asynchronously.
    *
    * @param runnable the {@link ThrowingRunnable} to execute
    * @return an {@link ExtendedFuture} representing the asynchronous computation
    */
   public static ExtendedFuture<@Nullable Void> runAsync(final ThrowingRunnable<?> runnable) {
      return completedFuture(null).thenRunAsync(runnable);
   }

   /**
    * Returns an {@link ExtendedFuture} that runs the given throwing runnable asynchronously using the provided executor.
    *
    * @param runnable the {@link ThrowingRunnable} to execute
    * @param executor the {@link Executor} to use for execution
    * @return an {@link ExtendedFuture} representing the asynchronous computation
    */
   public static ExtendedFuture<@Nullable Void> runAsync(final ThrowingRunnable<?> runnable, final Executor executor) {
      return completedFuture(null).thenRunAsync(runnable, executor);
   }

   /**
    * Returns an {@link ExtendedFuture} with a specified default executor that runs the given throwing runnable asynchronously.
    *
    * @param runnable the {@link ThrowingRunnable} to execute
    * @param defaultExecutor the default {@link Executor} to use for execution
    * @return an {@link ExtendedFuture} representing the asynchronous computation
    */
   public static ExtendedFuture<@Nullable Void> runAsyncWithDefaultExecutor(final ThrowingRunnable<?> runnable,
         final Executor defaultExecutor) {
      final var f = new ExtendedFuture<>(false, true, defaultExecutor);
      f.complete(null);
      return f.thenRunAsync(runnable);
   }

   /**
    * Returns an {@link ExtendedFuture} that runs the given supplier asynchronously.
    *
    * @param supplier the {@link Supplier} to execute
    * @param <V> the result type of the future
    * @return an {@link ExtendedFuture} representing the asynchronous computation
    */
   public static <V> ExtendedFuture<V> supplyAsync(final Supplier<V> supplier) {
      return completedFuture(null).thenApplyAsync(unused -> supplier.get());
   }

   /**
    * Returns an {@link ExtendedFuture} that runs the given supplier asynchronously using the provided executor.
    *
    * @param supplier the {@link Supplier} to execute
    * @param executor the {@link Executor} to use for execution
    * @param <V> the result type of the future
    * @return an {@link ExtendedFuture} representing the asynchronous computation
    */
   public static <V> ExtendedFuture<V> supplyAsync(final Supplier<V> supplier, final Executor executor) {
      return completedFuture(null).thenApplyAsync(unused -> supplier.get(), executor);
   }

   /**
    * Returns an {@link ExtendedFuture} that runs the given throwing supplier asynchronously.
    *
    * @param supplier the {@link ThrowingSupplier} to execute
    * @param <V> the result type of the future
    * @return an {@link ExtendedFuture} representing the asynchronous computation
    */
   public static <V> ExtendedFuture<V> supplyAsync(final ThrowingSupplier<V, ?> supplier) {
      return completedFuture(null).thenApplyAsync(unused -> supplier.get());
   }

   /**
    * Returns an {@link ExtendedFuture} that runs the given throwing supplier asynchronously using the provided executor.
    *
    * @param supplier the {@link ThrowingSupplier} to execute
    * @param executor the {@link Executor} to use for execution
    * @param <V> the result type of the future
    * @return an {@link ExtendedFuture} representing the asynchronous computation
    */
   public static <V> ExtendedFuture<V> supplyAsync(final ThrowingSupplier<V, ?> supplier, final Executor executor) {
      return completedFuture(null).thenApplyAsync(unused -> supplier.get(), executor);
   }

   /**
    * Returns an {@link ExtendedFuture} with a specified default executor that runs the given throwing supplier asynchronously.
    *
    * @param supplier the {@link ThrowingSupplier} to execute
    * @param defaultExecutor the default {@link Executor} to use for execution
    * @param <V> the result type of the future
    * @return an {@link ExtendedFuture} representing the asynchronous computation
    */
   public static <V> ExtendedFuture<V> supplyAsyncWithDefaultExecutor(final ThrowingSupplier<V, ?> supplier,
         final Executor defaultExecutor) {
      final var f = new ExtendedFuture<>(false, true, defaultExecutor);
      f.complete(null);
      return f.thenApplyAsync(unused -> supplier.get());
   }

   protected final Collection<Future<?>> cancellablePrecedingStages;
   protected final boolean cancellableByDependents;
   protected final boolean interruptibleStages;
   protected final Executor defaultExecutor;

   /**
    * Creates a new {@code ExtendedFuture} with default settings.
    */
   public ExtendedFuture() {
      this(false, true, null);
   }

   ExtendedFuture(final boolean cancellableByDependents, final boolean interruptibleStages, final @Nullable Executor defaultExecutor) {
      this.defaultExecutor = defaultExecutor == null ? super.defaultExecutor() : defaultExecutor;
      this.cancellableByDependents = cancellableByDependents;
      cancellablePrecedingStages = new ConcurrentLinkedQueue<>();
      this.interruptibleStages = interruptibleStages;
   }

   @Override
   public ExtendedFuture<@Nullable Void> acceptEither(final CompletionStage<? extends T> other, final Consumer<? super T> action) {
      if (interruptibleStages) {
         final var fId = InterruptibleFuturesTracker.generateFutureId();
         return toExtendedFuture(super.acceptEither(toExtendedFuture(other), result -> interruptiblyAccept(fId, result, action)));
      }
      return toExtendedFuture(super.acceptEither(other, action));
   }

   public ExtendedFuture<@Nullable Void> acceptEither(final CompletionStage<? extends T> other,
         final ThrowingConsumer<? super T, ?> action) {
      return acceptEither(other, (Consumer<? super T>) action);
   }

   @Override
   public ExtendedFuture<@Nullable Void> acceptEitherAsync(final CompletionStage<? extends T> other, final Consumer<? super T> action) {
      if (interruptibleStages) {
         final var fId = InterruptibleFuturesTracker.generateFutureId();
         return toExtendedFuture(super.acceptEitherAsync(toExtendedFuture(other), result -> interruptiblyAccept(fId, result, action)));
      }
      return toExtendedFuture(super.acceptEitherAsync(other, action));
   }

   @Override
   public ExtendedFuture<@Nullable Void> acceptEitherAsync(final CompletionStage<? extends T> other, final Consumer<? super T> action,
         final Executor executor) {
      if (interruptibleStages) {
         final var fId = InterruptibleFuturesTracker.generateFutureId();
         return toExtendedFuture(super.acceptEitherAsync(toExtendedFuture(other), result -> interruptiblyAccept(fId, result, action),
            executor));
      }
      return toExtendedFuture(super.acceptEitherAsync(other, action, executor));
   }

   public ExtendedFuture<@Nullable Void> acceptEitherAsync(final CompletionStage<? extends T> other,
         final ThrowingConsumer<? super T, ?> action) {
      return acceptEitherAsync(other, (Consumer<? super T>) action);
   }

   public ExtendedFuture<@Nullable Void> acceptEitherAsync(final CompletionStage<? extends T> other,
         final ThrowingConsumer<? super T, ?> action, final Executor executor) {
      return acceptEitherAsync(other, (Consumer<? super T>) action, executor);
   }

   /**
    * Passes this future to the given {@link Consumer}.
    *
    * @param consumer the consumer to which this future is added
    * @return this {@code ExtendedFuture} instance for method chaining
    */
   public ExtendedFuture<T> addTo(final Consumer<Future<T>> consumer) {
      consumer.accept(this);
      return this;
   }

   /**
    * Adds this future to the given {@link Futures.Combiner}.
    *
    * @param combiner the future combiner to which this future is added
    * @return this {@code ExtendedFuture} instance for method chaining
    */
   public ExtendedFuture<T> addTo(final Futures.Combiner<T> combiner) {
      combiner.add(this);
      return this;
   }

   @Override
   public <U> ExtendedFuture<U> applyToEither(final CompletionStage<? extends T> other, final Function<? super T, U> fn) {
      if (interruptibleStages) {
         final var fId = InterruptibleFuturesTracker.generateFutureId();
         return toExtendedFuture(super.applyToEither(toExtendedFuture(other), result -> interruptiblyApply(fId, result, fn)));
      }
      return toExtendedFuture(super.applyToEither(other, fn));
   }

   public <U> ExtendedFuture<U> applyToEither(final CompletionStage<? extends T> other, final ThrowingFunction<? super T, U, ?> fn) {
      return applyToEither(other, (Function<? super T, U>) fn);
   }

   @Override
   public <U> ExtendedFuture<U> applyToEitherAsync(final CompletionStage<? extends T> other, final Function<? super T, U> fn) {
      if (interruptibleStages) {
         final var fId = InterruptibleFuturesTracker.generateFutureId();
         return toExtendedFuture(super.applyToEitherAsync(toExtendedFuture(other), result -> interruptiblyApply(fId, result, fn)));
      }
      return toExtendedFuture(super.applyToEitherAsync(other, fn));
   }

   @Override
   public <U> ExtendedFuture<U> applyToEitherAsync(final CompletionStage<? extends T> other, final Function<? super T, U> fn,
         final Executor executor) {
      if (interruptibleStages) {
         final var fId = InterruptibleFuturesTracker.generateFutureId();
         return toExtendedFuture(super.applyToEitherAsync(toExtendedFuture(other), result -> interruptiblyApply(fId, result, fn),
            executor));
      }
      return toExtendedFuture(super.applyToEitherAsync(other, fn, executor));
   }

   public <U> ExtendedFuture<U> applyToEitherAsync(final CompletionStage<? extends T> other, final ThrowingFunction<? super T, U, ?> fn) {
      return applyToEitherAsync(other, (Function<? super T, U>) fn);
   }

   public <U> ExtendedFuture<U> applyToEitherAsync(final CompletionStage<? extends T> other, final ThrowingFunction<? super T, U, ?> fn,
         final Executor executor) {
      return applyToEitherAsync(other, (Function<? super T, U>) fn, executor);
   }

   /**
    * Returns an {@link ExtendedFuture} that shares the result with this future but allows control over whether
    * cancellation of dependent stages cancels this future.
    * <p>
    * If the requested cancellation behavior matches the current one, this instance is returned.
    * Otherwise, a new {@link ExtendedFuture} is created with the updated behavior.
    * <p>
    * Any newly created dependent stages will inherit this cancellation behavior.
    *
    * @param isCancellableByDependents {@code true} if cancellation of a dependent stage should also cancel this future and its preceding
    *           stages;
    *           {@code false} if cancellation of dependent stages should not affect this future.
    * @return a new {@link ExtendedFuture} with the specified cancellation behavior, or this instance if the behavior remains unchanged.
    */
   public ExtendedFuture<T> asCancellableByDependents(final boolean isCancellableByDependents) {
      if (isCancellableByDependents == cancellableByDependents)
         return this;
      return isInterruptible() //
            ? new InterruptibleWrappingFuture<>((InterruptibleFuture<T>) this, isCancellableByDependents, interruptibleStages,
               defaultExecutor)
            : new WrappingFuture<>(this, isCancellableByDependents, interruptibleStages, defaultExecutor);
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
      return new WrappingFuture<>(this, cancellableByDependents, interruptibleStages, defaultExecutor);
   }

   /**
    * Creates a read-only view of this {@link ExtendedFuture}.
    * <p>
    * The returned future is backed by this future, allowing only read operations
    * such as {@link ExtendedFuture#get()}, {@link ExtendedFuture#join()}, and other non-mutating methods.
    * Any attempt to invoke mutating operations such as {@link ExtendedFuture#cancel(boolean)},
    * {@link ExtendedFuture#complete(Object)}, {@link ExtendedFuture#completeExceptionally(Throwable)},
    * or {@link ExtendedFuture#obtrudeValue(Object)} will result in an {@link UnsupportedOperationException}
    * or be silently ignored, depending on the specified {@link ReadOnlyMode}.
    *
    * @param readOnlyMode the behavior when a mutating operation is attempted:
    *           {@link ReadOnlyMode#THROW_ON_MUTATION} to throw {@link UnsupportedOperationException},
    *           or {@link ReadOnlyMode#IGNORE_MUTATION} to silently ignore mutation attempts.
    * @return a read-only {@link ExtendedFuture} that is backed by the original future
    */
   public ExtendedFuture<T> asReadOnly(final ReadOnlyMode readOnlyMode) {
      final var throwOnMutationAttempt = readOnlyMode.equals(ReadOnlyMode.THROW_ON_MUTATION);
      return new WrappingFuture<>(this, false, interruptibleStages, defaultExecutor) {

         @Override
         public boolean cancel(final boolean mayInterruptIfRunning) {
            handleModificationAttempt();
            return isCancelled();
         }

         @Override
         public boolean complete(final T value) {
            handleModificationAttempt();
            return false;
         }

         @Override
         public ExtendedFuture<T> completeAsync(final Supplier<? extends T> supplier) {
            return handleModificationAttempt();
         }

         @Override
         public ExtendedFuture<T> completeAsync(final Supplier<? extends T> supplier, final Executor executor) {
            return handleModificationAttempt();
         }

         @Override
         public ExtendedFuture<T> completeAsync(final ThrowingSupplier<? extends T, ?> supplier) {
            return handleModificationAttempt();
         }

         @Override
         public ExtendedFuture<T> completeAsync(final ThrowingSupplier<? extends T, ?> supplier, final Executor executor) {
            return handleModificationAttempt();
         }

         @Override
         public boolean completeExceptionally(final Throwable ex) {
            handleModificationAttempt();
            return false;
         }

         @Override
         public ExtendedFuture<T> completeOnTimeout(final T value, final long timeout, final TimeUnit unit) {
            return handleModificationAttempt();
         }

         @Override
         public ExtendedFuture<T> completeWith(final CompletableFuture<? extends T> future) {
            return handleModificationAttempt();
         }

         @Override
         public ExtendedFuture<T> orTimeout(final long timeout, final TimeUnit unit) {
            return handleModificationAttempt();
         }

         private WrappingFuture<T> handleModificationAttempt() {
            if (throwOnMutationAttempt)
               throw new UnsupportedOperationException(this + " is read-only.");
            LOG.log(Level.WARNING, "Attempted to alter a read-only future: " + this);
            return this;
         }

         @Override
         public boolean isReadOnly() {
            return true;
         }

         @Override
         public void obtrudeException(final Throwable ex) {
            handleModificationAttempt();
         }

         @Override
         public void obtrudeValue(final T value) {
            handleModificationAttempt();
         }
      };
   }

   /**
    * {@inheritDoc}
    * <p>
    * If the preceding stage has {@link #isCancellableByDependents()} set, the cancellation will also propagate to the preceding stage.
    * </p>
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

      final boolean cancelled = super.cancel(mayInterruptIfRunning && isInterruptible());
      if (cancelled && !cancellablePrecedingStages.isEmpty()) {
         // Use the original caller intent if captured by a wrapper; otherwise, use the given flag
         final boolean requestedMayInterrupt = resolveCancelIntentOrDefault(mayInterruptIfRunning);
         cancellablePrecedingStages.removeIf(stage -> {
            if (!stage.isDone()) {
               final boolean stageInterruptible = !(stage instanceof ExtendedFuture) || ((ExtendedFuture<?>) stage).isInterruptible();
               stage.cancel(requestedMayInterrupt && stageInterruptible);
            }
            return true;
         });
      }
      return cancelled;
   }

   /**
    * Completes this future with the given value if not already completed.
    *
    * @param value the value to complete this future with
    * @return {@code true} if this invocation caused this future to transition to a completed state, otherwise {@code false}
    */
   @Override
   public boolean complete(final T value) {
      cancellablePrecedingStages.clear();
      return super.complete(value);
   }

   /**
    * Completes this future with the result of the given supplier function, running it asynchronously using the default executor.
    *
    * @param supplier the supplier function to produce the completion value
    * @return this {@code ExtendedFuture} for method chaining
    */
   @Override
   public ExtendedFuture<T> completeAsync(final Supplier<? extends T> supplier) {
      super.completeAsync(supplier);
      return this;
   }

   /**
    * Completes this future with the result of the given supplier function, running it asynchronously using the specified executor.
    *
    * @param supplier the supplier function to produce the completion value
    * @param executor the executor to use for asynchronous execution
    * @return this {@code ExtendedFuture} for method chaining
    */
   @Override
   public ExtendedFuture<T> completeAsync(final Supplier<? extends T> supplier, final Executor executor) {
      super.completeAsync(supplier, executor);
      return this;
   }

   /**
    * Completes this future with the result of the given throwing supplier function, running it asynchronously using the default executor.
    *
    * @param supplier the throwing supplier function to produce the completion value
    * @return this {@code ExtendedFuture} for method chaining
    */
   public ExtendedFuture<T> completeAsync(final ThrowingSupplier<? extends T, ?> supplier) {
      return completeAsync((Supplier<? extends T>) supplier);
   }

   /**
    * Completes this future with the result of the given throwing supplier function, running it asynchronously using the specified executor.
    *
    * @param supplier the throwing supplier function to produce the completion value
    * @param executor the executor to use for asynchronous execution
    * @return this {@code ExtendedFuture} for method chaining
    */
   public ExtendedFuture<T> completeAsync(final ThrowingSupplier<? extends T, ?> supplier, final Executor executor) {
      return completeAsync((Supplier<? extends T>) supplier, executor);
   }

   /**
    * Completes this future exceptionally with the given exception if not already completed.
    *
    * @param ex the exception to complete this future with
    * @return {@code true} if this invocation caused this future to transition to a completed state, otherwise {@code false}
    */
   @Override
   public boolean completeExceptionally(final Throwable ex) {
      cancellablePrecedingStages.clear();
      return super.completeExceptionally(ex);
   }

   @Override
   public ExtendedFuture<T> completeOnTimeout(final T value, final long timeout, final TimeUnit unit) {
      super.completeOnTimeout(value, timeout, unit);
      return this;
   }

   /**
    * Completes this {@code ExtendedFuture} when the provided {@code CompletableFuture} finishes,
    * with either its result or its exception.
    *
    * @param future the {@code CompletableFuture} whose completion will trigger this future's completion
    * @return this {@code ExtendedFuture} for chaining
    */
   public ExtendedFuture<T> completeWith(final CompletableFuture<? extends T> future) {
      future.whenComplete((result, ex) -> {
         if (ex == null) {
            complete(result);
         } else {
            completeExceptionally(ex);
         }
      });
      return this;
   }

   @Override
   public ExtendedFuture<T> copy() {
      return toExtendedFuture(super.copy());
   }

   @Override
   public Executor defaultExecutor() {
      return defaultExecutor;
   }

   @Override
   public ExtendedFuture<T> exceptionally(final Function<Throwable, ? extends T> fn) {
      return toExtendedFuture(super.exceptionally(fn));
   }

   public ExtendedFuture<T> exceptionally(final ThrowingFunction<Throwable, ? extends T, ?> fn) {
      return exceptionally((Function<Throwable, ? extends T>) fn);
   }

   /**
    * This method emulates the {@link CompletableFuture}'s exceptionallyAsync method introduced in Java 12.
    */
   // @Override
   public ExtendedFuture<T> exceptionallyAsync(final Function<Throwable, ? extends T> fn) {
      // emulate exceptionallyAsync introduced in Java 12
      return handleAsync((result, ex) -> ex == null ? result : fn.apply(ex));
   }

   /**
    * This method emulates the {@link CompletableFuture}'s exceptionallyAsync method introduced in Java 12.
    */
   // @Override
   public ExtendedFuture<T> exceptionallyAsync(final Function<Throwable, ? extends T> fn, final Executor executor) {
      // emulate exceptionallyAsync introduced in Java 12
      return handleAsync((result, ex) -> ex == null ? result : fn.apply(ex), executor);
   }

   public ExtendedFuture<T> exceptionallyAsync(final ThrowingFunction<Throwable, ? extends T, ?> fn) {
      return exceptionallyAsync((Function<Throwable, ? extends T>) fn);
   }

   public ExtendedFuture<T> exceptionallyAsync(final ThrowingFunction<Throwable, ? extends T, ?> fn, final Executor executor) {
      return exceptionallyAsync((Function<Throwable, ? extends T>) fn, executor);
   }

   /**
    * This method emulates the {@link CompletableFuture}'s exceptionallyCompose method introduced in Java 12.
    */
   // @Override
   public ExtendedFuture<T> exceptionallyCompose(final Function<Throwable, ? extends CompletionStage<T>> fn) {
      // emulate exceptionallyCompose introduced in Java 12
      return handle((result, ex) -> ex == null ? ExtendedFuture.completedFuture(result) : fn.apply(ex)).thenCompose(f -> f);
   }

   public ExtendedFuture<T> exceptionallyCompose(final ThrowingFunction<Throwable, ? extends CompletionStage<T>, ?> fn) {
      return exceptionallyCompose((Function<Throwable, ? extends CompletionStage<T>>) fn);
   }

   /**
    * This method emulates the {@link CompletableFuture}'s exceptionallyComposeAsync method introduced in Java 12.
    */
   // @Override
   public ExtendedFuture<T> exceptionallyComposeAsync(final Function<Throwable, ? extends CompletionStage<T>> fn) {
      // emulate exceptionallyComposeAsync introduced in Java 12
      return handleAsync((result, ex) -> ex == null ? ExtendedFuture.completedFuture(result) : fn.apply(ex)).thenCompose(f -> f);
   }

   /**
    * This method emulates the {@link CompletableFuture}'s exceptionallyComposeAsync method introduced in Java 12.
    */
   // @Override
   public ExtendedFuture<T> exceptionallyComposeAsync(final Function<Throwable, ? extends CompletionStage<T>> fn, final Executor executor) {
      // emulate exceptionallyComposeAsync introduced in Java 12
      return handleAsync((result, ex) -> ex == null ? ExtendedFuture.completedFuture(result) : fn.apply(ex), executor).thenCompose(f -> f);
   }

   public ExtendedFuture<T> exceptionallyComposeAsync(final ThrowingFunction<Throwable, ? extends CompletionStage<T>, ?> fn) {
      return exceptionallyComposeAsync((Function<Throwable, ? extends CompletionStage<T>>) fn);
   }

   public ExtendedFuture<T> exceptionallyComposeAsync(final ThrowingFunction<Throwable, ? extends CompletionStage<T>, ?> fn,
         final Executor executor) {
      return exceptionallyComposeAsync((Function<Throwable, ? extends CompletionStage<T>>) fn, executor);
   }

   /**
    * This method emulates the {@link CompletableFuture}'s exceptionNow method introduced in Java 19.
    *
    * @throws IllegalStateException if the task has not yet completed, completed normally, or was cancelled
    */
   // @Override
   public Throwable exceptionNow() {
      if (!isDone())
         throw new IllegalStateException("Future has not yet completed");
      if (isCancelled())
         throw new IllegalStateException("Future was cancelled");

      try {
         get();
         throw new IllegalStateException("Future completed with a result");
      } catch (final ExecutionException ex) {
         var cause = ex.getCause();
         if (cause instanceof CompletionException) {
            final var cex = (CompletionException) cause;
            cause = cex.getCause();
            return cause == null ? cex : cause;
         }
         return cause == null ? ex : cause;
      } catch (final InterruptedException ex) {
         Thread.currentThread().interrupt();
         throw new IllegalStateException("Thread was interrupted", ex);
      }
   }

   /**
    * Propagates the cancellation of this {@link ExtendedFuture} to another {@link Future}.
    * <p>
    * If this {@link ExtendedFuture} is cancelled, the {@code to} future will be cancelled too.
    *
    * @param to the {@link Future} instance that should be cancelled if this future is cancelled
    * @return this {@code ExtendedFuture} for method chaining
    */
   public ExtendedFuture<T> forwardCancellation(final @Nullable Future<?> to) {
      Futures.forwardCancellation(this, to);
      return this;
   }

   /**
    * Propagates the cancellation of this {@link ExtendedFuture} to other {@link Future}s.
    * <p>
    * If this {@link ExtendedFuture} is cancelled, all futures in the provided {@code to} array will be cancelled too.
    *
    * @param to the array of {@link Future} instances that should be cancelled if this future is cancelled
    * @return this {@code ExtendedFuture} for method chaining
    */
   public ExtendedFuture<T> forwardCancellation(final @NonNullByDefault({}) Future<?> @Nullable... to) {
      Futures.forwardCancellation(this, to);
      return this;
   }

   /**
    * Propagates the cancellation of this {@link ExtendedFuture} to other {@link Future}s.
    * <p>
    * If this {@link ExtendedFuture} is cancelled, all futures in the provided {@code to} collection will be cancelled too.
    *
    * @param to the collection of {@link Future} instances that should be cancelled if this future is cancelled
    * @return this {@code ExtendedFuture} for method chaining
    */
   public ExtendedFuture<T> forwardCancellationTo(final @Nullable Collection<? extends @Nullable Future<?>> to) {
      Futures.forwardCancellation(this, to);
      return this;
   }

   /**
    * Returns the result of this future if it is already completed wrapped in an {@link Optional},
    * or an empty {@link Optional} if the future is incomplete, cancelled or failed.
    *
    * @return an {@link Optional} containing the result of the future if completed normally, or an empty {@link Optional} otherwise
    */
   public Optional<T> getNowOptional() {
      return Futures.getNowOptional(this);
   }

   /**
    * Returns the result of this future if it is already completed, or the value provided by
    * {@code fallbackComputer} if the future is incomplete, cancelled or failed.
    *
    * @return the result of the future if completed normally, otherwise the value computed by {@code fallbackComputer}
    */
   public T getNowOrComputeFallback(final BiFunction<Future<T>, @Nullable Exception, T> fallbackComputer) {
      return Futures.getNowOrComputeFallback(this, fallbackComputer);
   }

   /**
    * Returns the result of this future if it is already completed, or the value provided by
    * {@code fallbackComputer} if the future is incomplete, cancelled or failed.
    *
    * @return the result of the future if completed normally, otherwise the value computed by {@code fallbackComputer}
    */
   public T getNowOrComputeFallback(final Function<@Nullable Exception, T> fallbackComputer) {
      return Futures.getNowOrComputeFallback(this, fallbackComputer);
   }

   /**
    * Returns the result of this future if it is already completed, or the specified
    * {@code fallback} if the future is incomplete, cancelled or failed.
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
   public Optional<T> getOptional() {
      return Futures.getOptional(this);
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
    * Attempts to retrieve the result of this future.
    *
    * @return the result of the future if completed normally, otherwise the value computed by {@code fallbackComputer}
    */
   public T getOrComputeFallback(final BiFunction<Future<T>, @Nullable Exception, T> fallbackComputer) {
      return Futures.getOrComputeFallback(this, fallbackComputer);
   }

   /**
    * Attempts to retrieve the result of this future within the specified timeout.
    *
    * @return the result of the future if completed normally within given timeout, otherwise the value computed by {@code fallbackComputer}
    */
   public T getOrComputeFallback(final BiFunction<Future<T>, @Nullable Exception, T> fallbackComputer, final long timeout,
         final TimeUnit unit) {
      return Futures.getOrComputeFallback(this, fallbackComputer, timeout, unit);
   }

   /**
    * Attempts to retrieve the result of this future.
    *
    * @return the result of the future if completed normally, otherwise the value computed by {@code fallbackComputer}
    */
   public T getOrComputeFallback(final Function<@Nullable Exception, T> fallbackComputer) {
      return Futures.getOrComputeFallback(this, fallbackComputer);
   }

   /**
    * Attempts to retrieve the result of this future within the specified timeout.
    *
    * @return the result of the future if completed normally within given timeout, otherwise the value computed by {@code fallbackComputer}
    */
   public T getOrComputeFallback(final Function<@Nullable Exception, T> fallbackComputer, final long timeout, final TimeUnit unit) {
      return Futures.getOrComputeFallback(this, fallbackComputer, timeout, unit);
   }

   /**
    * Attempts to retrieve the result of this future.
    *
    * @return the result of the future if completed normally, otherwise {@code fallback}
    */
   public T getOrFallback(final T fallback) {
      return Futures.getOrFallback(this, fallback);
   }

   /**
    * Attempts to retrieve the result of this future within the specified timeout.
    *
    * @return the result of the future if completed normally within given timeout, otherwise {@code fallback}
    */
   public T getOrFallback(final T fallback, final long timeout, final TimeUnit unit) {
      return Futures.getOrFallback(this, fallback, timeout, unit);
   }

   @Override
   public <U> ExtendedFuture<U> handle(final BiFunction<? super T, @Nullable Throwable, ? extends U> fn) {
      if (interruptibleStages) {
         final var fId = InterruptibleFuturesTracker.generateFutureId();
         return toExtendedFuture(super.handle((result, ex) -> interruptiblyHandle(fId, result, ex, fn)));
      }
      return toExtendedFuture(super.handle(fn));
   }

   @Override
   public <U> ExtendedFuture<U> handleAsync(final BiFunction<? super T, @Nullable Throwable, ? extends U> fn) {
      if (interruptibleStages) {
         final var fId = InterruptibleFuturesTracker.generateFutureId();
         return toExtendedFuture(super.handleAsync((result, ex) -> interruptiblyHandle(fId, result, ex, fn)));
      }
      return toExtendedFuture(super.handleAsync(fn));
   }

   @Override
   public <U> ExtendedFuture<U> handleAsync(final BiFunction<? super T, @Nullable Throwable, ? extends U> fn, final Executor executor) {
      if (interruptibleStages) {
         final var fId = InterruptibleFuturesTracker.generateFutureId();
         return toExtendedFuture(super.handleAsync((result, ex) -> interruptiblyHandle(fId, result, ex, fn), executor));
      }
      return toExtendedFuture(super.handleAsync(fn, executor));
   }

   private void interruptiblyAccept(final int futureId, final T result, final Consumer<? super T> action) {
      final var f = InterruptibleFuturesTracker.lookup(futureId);
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
      final var f = InterruptibleFuturesTracker.lookup(futureId);
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
      final var f = InterruptibleFuturesTracker.lookup(futureId);
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
      final var f = InterruptibleFuturesTracker.lookup(futureId);
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

   private <U> U interruptiblyHandle(final int futureId, final T result, final @Nullable Throwable ex,
         final BiFunction<? super T, @Nullable Throwable, ? extends U> fn) {
      final var f = InterruptibleFuturesTracker.lookup(futureId);
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
      final var f = InterruptibleFuturesTracker.lookup(futureId);
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
      final var f = InterruptibleFuturesTracker.lookup(futureId);
      synchronized (f.executingThreadLock) {
         f.executingThread = Thread.currentThread();
      }

      try {
         final var stage = fn.apply(result);
         if (stage instanceof ExtendedFuture) {
            final var ef = (ExtendedFuture<?>) stage;
            if (ef.isCancellableByDependents()) {
               f.cancellablePrecedingStages.add(ef);
            }
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
      final var f = InterruptibleFuturesTracker.lookup(futureId);
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
    * @return {@code true} if this future is cancellable by dependents, {@code false} otherwise
    */
   public boolean isCancellableByDependents() {
      return cancellableByDependents;
   }

   /**
    * Returns {@code true} if this {@link ExtendedFuture} was completed exceptionally, excluding cancellation.
    *
    * @return {@code true} if the future completed exceptionally but was not cancelled
    */
   public boolean isFailed() {
      return CompletionState.of(this) == CompletionState.FAILED;
   }

   /**
    * Returns {@code true} if this future is incomplete.
    *
    * @return {@code true} if this future is incomplete, {@code false} otherwise
    */
   public boolean isIncomplete() {
      return CompletionState.of(this) == CompletionState.INCOMPLETE;
   }

   /**
    * Returns {@code true} if this future is interruptible, i.e., {@code cancel(true)} will result in thread interruption.
    *
    * @return {@code true} if this future is interruptible, {@code false} otherwise
    */
   public boolean isInterruptible() {
      return false;
   }

   /**
    * Returns {@code true} if new stages created from this future are interruptible.
    *
    * @return {@code true} if new stages are interruptible, {@code false} otherwise
    */
   public boolean isInterruptibleStages() {
      return interruptibleStages;
   }

   /**
    * Returns {@code true} if this future cannot be completed programmatically through methods like {@link #cancel(boolean)} or
    * {@link #complete(Object)}.
    *
    * @return {@code true} if this future is read-only, {@code false} otherwise
    */
   public boolean isReadOnly() {
      return false;
   }

   /**
    * Returns {@code true} if this future completed normally.
    *
    * @return {@code true} if this future completed normally, {@code false} otherwise
    */
   public boolean isSuccess() {
      return CompletionState.of(this) == CompletionState.SUCCESS;
   }

   @Override
   public <V> ExtendedFuture<V> newIncompleteFuture() {
      final ExtendedFuture<V> newFuture;
      if (interruptibleStages) {
         final var newInterruptibleFuture = new InterruptibleFuture<V>(cancellableByDependents, interruptibleStages, defaultExecutor);
         InterruptibleFuturesTracker.store(newInterruptibleFuture);
         newFuture = newInterruptibleFuture;
      } else {
         newFuture = new ExtendedFuture<>(cancellableByDependents, interruptibleStages, defaultExecutor);
      }
      if (cancellableByDependents && !isDone()) {
         newFuture.cancellablePrecedingStages.add(this);
      }
      return newFuture;
   }

   /**
    * This method emulates the {@link CompletableFuture}'s resultNow method introduced in Java 19.
    *
    * @return the computed result
    * @throws IllegalStateException if the task has not yet completed or failed
    */
   // @Override
   public T resultNow() {
      if (!isDone())
         throw new IllegalStateException("Future is not completed yet.");
      if (isCompletedExceptionally())
         throw new IllegalStateException("Future completed exceptionally");

      return join();
   }

   @Override
   public ExtendedFuture<@Nullable Void> runAfterBoth(final CompletionStage<?> other, final Runnable action) {
      if (interruptibleStages) {
         final var fId = InterruptibleFuturesTracker.generateFutureId();
         return toExtendedFuture(super.runAfterBoth(toExtendedFuture(other), () -> interruptiblyRun(fId, action)));
      }
      return toExtendedFuture(super.runAfterBoth(other, action));
   }

   public ExtendedFuture<@Nullable Void> runAfterBoth(final CompletionStage<?> other, final ThrowingRunnable<?> action) {
      return runAfterBoth(other, (Runnable) action);
   }

   @Override
   public ExtendedFuture<@Nullable Void> runAfterBothAsync(final CompletionStage<?> other, final Runnable action) {
      if (interruptibleStages) {
         final var fId = InterruptibleFuturesTracker.generateFutureId();
         return toExtendedFuture(super.runAfterBothAsync(toExtendedFuture(other), () -> interruptiblyRun(fId, action)));
      }
      return toExtendedFuture(super.runAfterBothAsync(other, action));
   }

   @Override
   public ExtendedFuture<@Nullable Void> runAfterBothAsync(final CompletionStage<?> other, final Runnable action, final Executor executor) {
      if (interruptibleStages) {
         final var fId = InterruptibleFuturesTracker.generateFutureId();
         return toExtendedFuture(super.runAfterBothAsync(toExtendedFuture(other), () -> interruptiblyRun(fId, action), executor));
      }
      return toExtendedFuture(super.runAfterBothAsync(other, action, executor));
   }

   public ExtendedFuture<@Nullable Void> runAfterBothAsync(final CompletionStage<?> other, final ThrowingRunnable<?> action) {
      return runAfterBothAsync(other, (Runnable) action);
   }

   public ExtendedFuture<@Nullable Void> runAfterBothAsync(final CompletionStage<?> other, final ThrowingRunnable<?> action,
         final Executor executor) {
      return runAfterBothAsync(other, (Runnable) action, executor);
   }

   @Override
   public ExtendedFuture<@Nullable Void> runAfterEither(final CompletionStage<?> other, final Runnable action) {
      if (interruptibleStages) {
         final var fId = InterruptibleFuturesTracker.generateFutureId();
         return toExtendedFuture(super.runAfterEither(toExtendedFuture(other), () -> interruptiblyRun(fId, action)));
      }
      return toExtendedFuture(super.runAfterEither(other, action));
   }

   public ExtendedFuture<@Nullable Void> runAfterEither(final CompletionStage<?> other, final ThrowingRunnable<?> action) {
      return runAfterEither(other, (Runnable) action);
   }

   @Override
   public ExtendedFuture<@Nullable Void> runAfterEitherAsync(final CompletionStage<?> other, final Runnable action) {
      if (interruptibleStages) {
         final var fId = InterruptibleFuturesTracker.generateFutureId();
         return toExtendedFuture(super.runAfterEitherAsync(toExtendedFuture(other), () -> interruptiblyRun(fId, action)));
      }
      return toExtendedFuture(super.runAfterEitherAsync(other, action));
   }

   @Override
   public ExtendedFuture<@Nullable Void> runAfterEitherAsync(final CompletionStage<?> other, final Runnable action,
         final Executor executor) {
      if (interruptibleStages) {
         final var fId = InterruptibleFuturesTracker.generateFutureId();
         return toExtendedFuture(super.runAfterEitherAsync(toExtendedFuture(other), () -> interruptiblyRun(fId, action), executor));
      }
      return toExtendedFuture(super.runAfterEitherAsync(other, action, executor));
   }

   public ExtendedFuture<@Nullable Void> runAfterEitherAsync(final CompletionStage<?> other, final ThrowingRunnable<?> action) {
      return runAfterEitherAsync(other, (Runnable) action);
   }

   public ExtendedFuture<@Nullable Void> runAfterEitherAsync(final CompletionStage<?> other, final ThrowingRunnable<?> action,
         final Executor executor) {
      return runAfterEitherAsync(other, (Runnable) action, executor);
   }

   @Override
   public ExtendedFuture<@Nullable Void> thenAccept(final Consumer<? super T> action) {
      if (interruptibleStages) {
         final var fId = InterruptibleFuturesTracker.generateFutureId();
         return toExtendedFuture(super.thenAccept(result -> interruptiblyAccept(fId, result, action)));
      }
      return toExtendedFuture(super.thenAccept(action));
   }

   public ExtendedFuture<@Nullable Void> thenAccept(final ThrowingConsumer<? super T, ?> action) {
      return thenAccept((Consumer<? super T>) action);
   }

   @Override
   public ExtendedFuture<@Nullable Void> thenAcceptAsync(final Consumer<? super T> action) {
      if (interruptibleStages) {
         final var fId = InterruptibleFuturesTracker.generateFutureId();
         return toExtendedFuture(super.thenAcceptAsync(result -> interruptiblyAccept(fId, result, action)));
      }
      return toExtendedFuture(super.thenAcceptAsync(action));
   }

   @Override
   public ExtendedFuture<@Nullable Void> thenAcceptAsync(final Consumer<? super T> action, final Executor executor) {
      if (interruptibleStages) {
         final var fId = InterruptibleFuturesTracker.generateFutureId();
         return toExtendedFuture(super.thenAcceptAsync(result -> interruptiblyAccept(fId, result, action), executor));
      }
      return toExtendedFuture(super.thenAcceptAsync(action, executor));
   }

   public ExtendedFuture<@Nullable Void> thenAcceptAsync(final ThrowingConsumer<? super T, ?> action) {
      return thenAcceptAsync((Consumer<? super T>) action);
   }

   public ExtendedFuture<@Nullable Void> thenAcceptAsync(final ThrowingConsumer<? super T, ?> action, final Executor executor) {
      return thenAcceptAsync((Consumer<? super T>) action, executor);
   }

   @Override
   public <U> ExtendedFuture<@Nullable Void> thenAcceptBoth(final CompletionStage<? extends U> other,
         final BiConsumer<? super T, ? super U> action) {
      if (interruptibleStages) {
         final var fId = InterruptibleFuturesTracker.generateFutureId();
         return toExtendedFuture(super.thenAcceptBoth(toExtendedFuture(other), (result, otherResult) -> interruptiblyAcceptBoth(fId, result,
            otherResult, action)));
      }
      return toExtendedFuture(super.thenAcceptBoth(other, action));
   }

   public <U> ExtendedFuture<@Nullable Void> thenAcceptBoth(final CompletionStage<? extends U> other,
         final ThrowingBiConsumer<? super T, ? super U, ?> action) {
      return thenAcceptBoth(other, (BiConsumer<? super T, ? super U>) action);
   }

   @Override
   public <U> ExtendedFuture<@Nullable Void> thenAcceptBothAsync(final CompletionStage<? extends U> other,
         final BiConsumer<? super T, ? super U> action) {
      if (interruptibleStages) {
         final var fId = InterruptibleFuturesTracker.generateFutureId();
         return toExtendedFuture(super.thenAcceptBothAsync(toExtendedFuture(other), (result, otherResult) -> interruptiblyAcceptBoth(fId,
            result, otherResult, action)));
      }
      return toExtendedFuture(super.thenAcceptBothAsync(other, action));
   }

   @Override
   public <U> ExtendedFuture<@Nullable Void> thenAcceptBothAsync(final CompletionStage<? extends U> other,
         final BiConsumer<? super T, ? super U> action, final Executor executor) {
      if (interruptibleStages) {
         final var fId = InterruptibleFuturesTracker.generateFutureId();
         return toExtendedFuture(super.thenAcceptBothAsync(toExtendedFuture(other), (result, otherResult) -> interruptiblyAcceptBoth(fId,
            result, otherResult, action), executor));
      }
      return toExtendedFuture(super.thenAcceptBothAsync(other, action, executor));
   }

   public <U> ExtendedFuture<@Nullable Void> thenAcceptBothAsync(final CompletionStage<? extends U> other,
         final ThrowingBiConsumer<? super T, ? super U, ?> action) {
      return thenAcceptBothAsync(other, (BiConsumer<? super T, ? super U>) action);
   }

   public <U> ExtendedFuture<@Nullable Void> thenAcceptBothAsync(final CompletionStage<? extends U> other,
         final ThrowingBiConsumer<? super T, ? super U, ?> action, final Executor executor) {
      return thenAcceptBothAsync(other, (BiConsumer<? super T, ? super U>) action, executor);
   }

   @Override
   public <U> ExtendedFuture<U> thenApply(final Function<? super T, ? extends U> fn) {
      if (interruptibleStages) {
         final var fId = InterruptibleFuturesTracker.generateFutureId();
         return toExtendedFuture(super.thenApply(result -> interruptiblyApply(fId, result, fn)));
      }
      return toExtendedFuture(super.thenApply(fn));
   }

   public <U> ExtendedFuture<U> thenApply(final ThrowingFunction<? super T, ? extends U, ?> fn) {
      return thenApply((Function<? super T, ? extends U>) fn);
   }

   @Override
   public <U> ExtendedFuture<U> thenApplyAsync(final Function<? super T, ? extends U> fn) {
      if (interruptibleStages) {
         final var fId = InterruptibleFuturesTracker.generateFutureId();
         return toExtendedFuture(super.thenApplyAsync(result -> interruptiblyApply(fId, result, fn)));
      }
      return toExtendedFuture(super.thenApplyAsync(fn));
   }

   @Override
   public <U> ExtendedFuture<U> thenApplyAsync(final Function<? super T, ? extends U> fn, final Executor executor) {
      if (interruptibleStages) {
         final var fId = InterruptibleFuturesTracker.generateFutureId();
         return toExtendedFuture(super.thenApplyAsync(result -> interruptiblyApply(fId, result, fn), executor));
      }
      return toExtendedFuture(super.thenApplyAsync(fn, executor));
   }

   public <U> ExtendedFuture<U> thenApplyAsync(final ThrowingFunction<? super T, ? extends U, ?> fn) {
      return thenApplyAsync((Function<? super T, ? extends U>) fn);
   }

   public <U> ExtendedFuture<U> thenApplyAsync(final ThrowingFunction<? super T, ? extends U, ?> fn, final Executor executor) {
      return thenApplyAsync((Function<? super T, ? extends U>) fn, executor);
   }

   @Override
   public <U, V> ExtendedFuture<V> thenCombine(final CompletionStage<? extends U> other,
         final BiFunction<? super T, ? super U, ? extends V> fn) {
      if (interruptibleStages) {
         final var fId = InterruptibleFuturesTracker.generateFutureId();
         return toExtendedFuture(super.thenCombine(toExtendedFuture(other), (result, otherResult) -> interruptiblyCombine(fId, result,
            otherResult, fn)));
      }
      return toExtendedFuture(super.thenCombine(other, fn));
   }

   public <U, V> ExtendedFuture<V> thenCombine(final CompletionStage<? extends U> other,
         final ThrowingBiFunction<? super T, ? super U, ? extends V, ?> fn) {
      return thenCombine(other, (BiFunction<? super T, ? super U, ? extends V>) fn);
   }

   @Override
   public <U, V> ExtendedFuture<V> thenCombineAsync(final CompletionStage<? extends U> other,
         final BiFunction<? super T, ? super U, ? extends V> fn) {
      if (interruptibleStages) {
         final var fId = InterruptibleFuturesTracker.generateFutureId();
         return toExtendedFuture(super.thenCombineAsync(toExtendedFuture(other), (result, otherResult) -> interruptiblyCombine(fId, result,
            otherResult, fn)));
      }
      return toExtendedFuture(super.thenCombineAsync(other, fn));
   }

   @Override
   public <U, V> ExtendedFuture<V> thenCombineAsync(final CompletionStage<? extends U> other,
         final BiFunction<? super T, ? super U, ? extends V> fn, final Executor executor) {
      if (interruptibleStages) {
         final var fId = InterruptibleFuturesTracker.generateFutureId();
         return toExtendedFuture(super.thenCombineAsync(toExtendedFuture(other), (result, otherResult) -> interruptiblyCombine(fId, result,
            otherResult, fn), executor));
      }
      return toExtendedFuture(super.thenCombineAsync(other, fn, executor));
   }

   public <U, V> ExtendedFuture<V> thenCombineAsync(final CompletionStage<? extends U> other,
         final ThrowingBiFunction<? super T, ? super U, ? extends V, ?> fn) {
      return thenCombineAsync(other, (BiFunction<? super T, ? super U, ? extends V>) fn);
   }

   public <U, V> ExtendedFuture<V> thenCombineAsync(final CompletionStage<? extends U> other,
         final ThrowingBiFunction<? super T, ? super U, ? extends V, ?> fn, final Executor executor) {
      return thenCombineAsync(other, (BiFunction<? super T, ? super U, ? extends V>) fn, executor);
   }

   @Override
   public <U> ExtendedFuture<U> thenCompose(final Function<? super T, ? extends CompletionStage<U>> fn) {
      if (interruptibleStages) {
         final var fId = InterruptibleFuturesTracker.generateFutureId();
         return toExtendedFuture(super.thenCompose(result -> interruptiblyThenCompose(fId, result, fn)));
      }
      return toExtendedFuture(super.thenCompose(fn));
   }

   public <U> ExtendedFuture<U> thenCompose(final ThrowingFunction<? super T, ? extends CompletionStage<U>, ?> fn) {
      return thenCompose((Function<? super T, ? extends CompletionStage<U>>) fn);
   }

   @Override
   public <U> ExtendedFuture<U> thenComposeAsync(final Function<? super T, ? extends CompletionStage<U>> fn) {
      if (interruptibleStages) {
         final var fId = InterruptibleFuturesTracker.generateFutureId();
         return toExtendedFuture(super.thenComposeAsync(result -> interruptiblyThenCompose(fId, result, fn)));
      }
      return toExtendedFuture(super.thenComposeAsync(fn));
   }

   @Override
   public <U> ExtendedFuture<U> thenComposeAsync(final Function<? super T, ? extends CompletionStage<U>> fn, final Executor executor) {
      if (interruptibleStages) {
         final var fId = InterruptibleFuturesTracker.generateFutureId();
         return toExtendedFuture(super.thenComposeAsync(result -> interruptiblyThenCompose(fId, result, fn), executor));
      }
      return toExtendedFuture(super.thenComposeAsync(fn, executor));
   }

   public <U> ExtendedFuture<U> thenComposeAsync(final ThrowingFunction<? super T, ? extends CompletionStage<U>, ?> fn) {
      return thenComposeAsync((Function<? super T, ? extends CompletionStage<U>>) fn);
   }

   public <U> ExtendedFuture<U> thenComposeAsync(final ThrowingFunction<? super T, ? extends CompletionStage<U>, ?> fn,
         final Executor executor) {
      return thenComposeAsync((Function<? super T, ? extends CompletionStage<U>>) fn, executor);
   }

   @Override
   public ExtendedFuture<@Nullable Void> thenRun(final Runnable action) {
      if (interruptibleStages) {
         final var fId = InterruptibleFuturesTracker.generateFutureId();
         return toExtendedFuture(super.thenRun(() -> interruptiblyRun(fId, action)));
      }
      return toExtendedFuture(super.thenRun(action));
   }

   public ExtendedFuture<@Nullable Void> thenRun(final ThrowingRunnable<?> action) {
      return thenRun((Runnable) action);
   }

   @Override
   public ExtendedFuture<@Nullable Void> thenRunAsync(final Runnable action) {
      if (interruptibleStages) {
         final var fId = InterruptibleFuturesTracker.generateFutureId();
         return toExtendedFuture(super.thenRunAsync(() -> interruptiblyRun(fId, action)));
      }
      return toExtendedFuture(super.thenRunAsync(action));
   }

   @Override
   public ExtendedFuture<@Nullable Void> thenRunAsync(final Runnable action, final Executor executor) {
      if (interruptibleStages) {
         final var fId = InterruptibleFuturesTracker.generateFutureId();
         return toExtendedFuture(super.thenRunAsync(() -> interruptiblyRun(fId, action), executor));
      }
      return toExtendedFuture(super.thenRunAsync(action, executor));
   }

   public ExtendedFuture<@Nullable Void> thenRunAsync(final ThrowingRunnable<?> action) {
      return thenRunAsync((Runnable) action);
   }

   public ExtendedFuture<@Nullable Void> thenRunAsync(final ThrowingRunnable<?> action, final Executor executor) {
      return thenRunAsync((Runnable) action, executor);
   }

   private <V> ExtendedFuture<V> toExtendedFuture(final CompletionStage<V> source) {
      if (source instanceof ExtendedFuture)
         return (ExtendedFuture<V>) source;
      final var cf = source.toCompletableFuture();
      return new WrappingFuture<>(cf, cancellableByDependents, interruptibleStages, cf.defaultExecutor());
   }

   @Override
   public ExtendedFuture<T> whenComplete(final BiConsumer<? super @Nullable T, ? super @Nullable Throwable> action) {
      if (interruptibleStages) {
         final var fId = InterruptibleFuturesTracker.generateFutureId();
         return toExtendedFuture(super.whenComplete((result, ex) -> interruptiblyWhenComplete(fId, result, ex, action)));
      }
      return toExtendedFuture(super.whenComplete(action));
   }

   public ExtendedFuture<T> whenComplete(final ThrowingBiConsumer<? super @Nullable T, ? super @Nullable Throwable, ?> action) {
      return whenComplete((BiConsumer<? super @Nullable T, ? super @Nullable Throwable>) action);
   }

   @Override
   public ExtendedFuture<T> whenCompleteAsync(final BiConsumer<? super @Nullable T, ? super @Nullable Throwable> action) {
      if (interruptibleStages) {
         final var fId = InterruptibleFuturesTracker.generateFutureId();
         return toExtendedFuture(super.whenCompleteAsync((result, ex) -> interruptiblyWhenComplete(fId, result, ex, action)));
      }
      return toExtendedFuture(super.whenCompleteAsync(action));
   }

   @Override
   public ExtendedFuture<T> whenCompleteAsync(final BiConsumer<? super @Nullable T, ? super @Nullable Throwable> action,
         final Executor executor) {
      if (interruptibleStages) {
         final var fId = InterruptibleFuturesTracker.generateFutureId();
         return toExtendedFuture(super.whenCompleteAsync((result, ex) -> interruptiblyWhenComplete(fId, result, ex, action), executor));
      }
      return toExtendedFuture(super.whenCompleteAsync(action, executor));
   }

   public ExtendedFuture<T> whenCompleteAsync(final ThrowingBiConsumer<? super @Nullable T, ? super @Nullable Throwable, ?> action) {
      return whenCompleteAsync((BiConsumer<? super @Nullable T, ? super @Nullable Throwable>) action);
   }

   public ExtendedFuture<T> whenCompleteAsync(final ThrowingBiConsumer<? super @Nullable T, ? super @Nullable Throwable, ?> action,
         final Executor executor) {
      return whenCompleteAsync((BiConsumer<? super @Nullable T, ? super @Nullable Throwable>) action, executor);
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
            ? new InterruptibleWrappingFuture<>((InterruptibleFuture<T>) this, cancellableByDependents, interruptibleStages,
               defaultExecutor)
            : new WrappingFuture<>(this, cancellableByDependents, interruptibleStages, defaultExecutor);
   }

   /**
    * Returns an {@link ExtendedFuture} that shares the result with this future but with
    * the specified behavior for new stages being interruptible or not.
    * <p>
    * If the requested interruptibility behavior matches the current one, this instance is returned.
    *
    * @param interruptibleStages {@code true} if new stages should be interruptible, {@code false} otherwise
    * @return a new {@link ExtendedFuture} with the specified interruptibility behavior for new stages,
    *         or this instance if the behavior remains unchanged
    */
   public ExtendedFuture<T> withInterruptibleStages(final boolean interruptibleStages) {
      if (interruptibleStages == this.interruptibleStages)
         return this;

      return isInterruptible() //
            ? new InterruptibleWrappingFuture<>((InterruptibleFuture<T>) this, cancellableByDependents, interruptibleStages,
               defaultExecutor)
            : new WrappingFuture<>(this, cancellableByDependents, interruptibleStages, defaultExecutor);
   }
}
