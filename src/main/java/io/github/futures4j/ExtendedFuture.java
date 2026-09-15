/*
 * SPDX-FileCopyrightText: © Sebastian Thomschke
 * SPDX-License-Identifier: EPL-2.0
 * SPDX-FileContributor: Sebastian Thomschke: initial API and implementation
 */
package io.github.futures4j;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.lang.ref.Reference;
import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.Collection;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
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

   /** Connects a pre-created result to the JDK's iterative completion graph without calling complete on that result. */
   private static final class CompletionRelay<V> extends CompletableFuture<@Nullable Void> {
      private final CompletableFuture<V> result;

      CompletionRelay(final CompletableFuture<V> result) {
         this.result = result;
      }

      void completeFrom(final CompletionStage<V> stage) {
         // Keep this receiver pending until its dependent is installed. The completed-source fast path can overwrite
         // an earlier cancellation of our pre-created result; the pending-source path completes it conditionally.
         thenCompose(unused -> stage);
         complete(null);
      }

      @Override
      @SuppressWarnings("unchecked")
      public <U> CompletableFuture<U> newIncompleteFuture() {
         // This private factory is called once, only by completeFrom's thenCompose, whose result type is always V.
         return (CompletableFuture<U>) result;
      }
   }

   /**
    * Keeps native either-stage allocation on the receiver's factory, even when the other input is already completed.
    * A native relay preserves iterative propagation without adding interruptible observer stages to that input.
    */
   private static final class EitherOperand<V> extends CompletableFuture<V> implements AutoCloseable {
      private @Nullable ExtendedFuture<?> factorySource;

      EitherOperand(final ExtendedFuture<?> factorySource, final CompletableFuture<V> other) {
         this.factorySource = factorySource;
         new CompletionRelay<>(this).completeFrom(other);
      }

      @Override
      public <U> CompletableFuture<U> newIncompleteFuture() {
         return Objects.requireNonNull(factorySource).newIncompleteFuture();
      }

      @Override
      public void close() {
         // Native stage construction has finished; a pending losing input must not keep the receiver alive through this adapter.
         factorySource = null;
      }
   }

   /** A removable weak subscription to a factory source's shared cleanup, with no strong reference to either future. */
   private static final class FactoryRegistration extends WeakReference<@Nullable ExtendedFuture<?>> {
      // The queue may outlive a source. Its entries and their registries must therefore contain no strong future references.
      private static final ReferenceQueue<@Nullable ExtendedFuture<?>> STALE = new ReferenceQueue<>();
      private final FactoryRegistrations owner;
      private @Nullable FactoryRegistration previous;
      private @Nullable FactoryRegistration next;

      FactoryRegistration(final FactoryRegistrations owner, final ExtendedFuture<?> dependent) {
         super(dependent, STALE);
         this.owner = owner;
      }

      static void purgeStale() {
         for (var reference = STALE.poll(); reference != null; reference = STALE.poll()) {
            final var registration = (FactoryRegistration) reference;
            registration.owner.remove(registration);
         }
      }
   }

   /** Shares one source observer across factory dependents, reclaiming both completed and abandoned registrations. */
   private static final class FactoryRegistrations {
      private @Nullable FactoryRegistration first;
      private boolean closed;

      synchronized boolean add(final FactoryRegistration registration) {
         if (closed)
            return false;
         final var head = first;
         registration.next = head;
         if (head != null) {
            head.previous = registration;
         }
         first = registration;
         return true;
      }

      void close(final ExtendedFuture<?> source) {
         while (true) {
            final FactoryRegistration registration;
            final ExtendedFuture<?> dependent;
            synchronized (this) {
               closed = true;
               final var head = first;
               if (head == null)
                  return;
               registration = head;
               dependent = head.get();
               remove(head);
            }
            // Removing another future's links must not run under this registry's lock. Its source is already terminal,
            // so this edge is no longer needed even if the dependent is still forwarding cancellation to other inputs.
            if (dependent != null) {
               dependent.cancellablePrecedingStages.removeIf(stage -> stage == source);
               if (dependent.factoryPredecessor == registration) {
                  dependent.factoryPredecessor = null;
               }
            }
         }
      }

      synchronized void remove(final FactoryRegistration registration) {
         // Intrusive identity links make independent completion O(1), without a map entry or a scan through every sibling.
         final var previous = registration.previous;
         final var next = registration.next;
         if (previous == null) {
            if (first != registration)
               return; // Another completion or stale-reference purge already removed it.
            first = next;
         } else {
            previous.next = next;
         }
         if (next != null) {
            next.previous = previous;
         }
         registration.previous = null;
         registration.next = null;
         registration.clear();
      }
   }

   /** Owns interruption of all active computations that can complete this future. */
   static final class InterruptibleFuture<T> extends ExtendedFuture<T> {

      // Under executingThreadLock: null when idle, a Thread for one registration, or an IdentityHashMap<Thread, Integer> of counts.
      // Reusing one reference field avoids both a common-case registry allocation and extra fields on every future.
      private @Nullable Object executingThreads;
      private final Object executingThreadLock = new Object();

      private InterruptibleFuture(final boolean cancellableByDependents, final boolean interruptibleStages,
            final @Nullable Executor defaultExecutor) {
         super(cancellableByDependents, interruptibleStages, defaultExecutor);
      }

      @Override
      public boolean cancel(final boolean mayInterruptIfRunning) {
         if (isDone())
            return isCancelled();

         // Publishing cancellation can run user callbacks inline, so it must not hold the execution lock.
         final var cancelled = super.cancel(mayInterruptIfRunning);
         // Use the local flag, not the preserved upstream intent: a non-interruptible view may have masked this task's interruption.
         if (cancelled && mayInterruptIfRunning) {
            synchronized (executingThreadLock) {
               final var executions = executingThreads;
               // Interrupt under the cleanup lock so a deregistered worker cannot start unrelated work before a late interrupt.
               if (executions instanceof Thread) {
                  ((Thread) executions).interrupt();
               } else if (executions != null) {
                  @SuppressWarnings("unchecked") // The private state invariant above permits only a counted map here.
                  final var threads = (Map<Thread, Integer>) executions;
                  for (final var thread : threads.keySet()) {
                     thread.interrupt();
                  }
               }
            }
         }
         return cancelled;
      }

      @Override
      public ExtendedFuture<T> completeAsync(final Supplier<? extends T> supplier, final Executor executor) {
         // Preserve immediate null rejection before replacing the supplier with a non-null wrapper.
         Objects.requireNonNull(supplier);
         // Keep the Supplier type: the ThrowingSupplier overload would dispatch back to this method.
         final Supplier<T> interruptibleSupplier = () -> {
            // completeAsync owns this existing future; it does not need a dependent-stage execution binding.
            registerExecutingThread();
            try {
               return supplier.get();
            } finally {
               unregisterExecutingThread();
            }
         };
         // The inherited default-executor and throwing overloads already funnel through this overload once.
         return super.completeAsync(interruptibleSupplier, executor);
      }

      @Override
      public boolean isInterruptible() {
         return true;
      }

      private void registerExecutingThread() {
         if (!tryRegisterExecutingThread())
            // Other callbacks can abort without a placeholder result. whenComplete must instead skip without throwing.
            throw new CancellationException("Future already completed");
      }

      private boolean tryRegisterExecutingThread() {
         synchronized (executingThreadLock) {
            if (isDone())
               // Cancellation may win after the JDK's completion check but before registration. Do not start untracked user code.
               return false;
            final var executions = executingThreads;
            if (executions == null) {
               executingThreads = Thread.currentThread();
               return true;
            }
            final Map<Thread, Integer> threads;
            if (executions instanceof Thread) {
               // Promote even for same-thread reentry: inner cleanup must preserve the outer registration.
               // Identity keys also protect against a custom Thread overriding equals().
               threads = new IdentityHashMap<>(2);
               threads.put((Thread) executions, 1);
               executingThreads = threads;
            } else {
               @SuppressWarnings("unchecked") // Only the promotion above installs a map in this private slot.
               final var registeredThreads = (Map<Thread, Integer>) executions;
               threads = registeredThreads;
            }
            threads.merge(Thread.currentThread(), 1, Integer::sum);
            return true;
         }
      }

      private void unregisterExecutingThread() {
         synchronized (executingThreadLock) {
            // Another execution may have promoted the slot while this callback ran; cleanup must use its current representation.
            final var executions = Objects.requireNonNull(executingThreads);
            if (executions instanceof Thread) {
               executingThreads = null;
               return;
            }
            @SuppressWarnings("unchecked") // A non-null, non-Thread state is always the counted map.
            final var threads = (Map<Thread, Integer>) executions;
            final var thread = Thread.currentThread();
            final int registrations = Objects.requireNonNull(threads.get(thread));
            if (registrations > 1) {
               threads.put(thread, registrations - 1);
            } else {
               threads.remove(thread);
               if (threads.isEmpty()) {
                  // Completed stages must not retain workers or the registry allocated only for their execution.
                  executingThreads = null;
               }
               // Keep a nonempty promoted map to avoid repeated allocation as overlapping executions start and finish.
            }
         }
      }
   }

   private static final class InterruptibleWrappingFuture<T> extends WrappingFuture<T> {

      private InterruptibleWrappingFuture(final CompletableFuture<T> /*InterruptibleWrappingFuture<T>|InterruptibleFuture<T>*/ wrapped,
            final boolean cancellableByDependents, final boolean interruptibleStages, final @Nullable Executor defaultExecutor) {
         super(wrapped, cancellableByDependents, interruptibleStages, defaultExecutor);
      }

      @Override
      public boolean isInterruptible() {
         return true;
      }
   }

   /**
    * Binds one callback to its native dependent during construction, without a global registry or GC cleanup queue.
    * The scope restores enclosing operations after reentry or failure; only its designated source factory may supply the owner.
    */
   static final class ExecutionBinding implements AutoCloseable {
      static final ThreadLocal<@Nullable ExecutionBinding> ACTIVE = new ThreadLocal<>();

      private @Nullable ExecutionBinding previous;
      private @Nullable ExtendedFuture<?> factorySource;
      // Factory assignment precedes the JDK's publication of the callback, so an async worker needs no separate volatile handoff.
      @Nullable
      InterruptibleFuture<?> owner;

      private ExecutionBinding(final @Nullable ExtendedFuture<?> factorySource) {
         previous = ACTIVE.get();
         this.factorySource = factorySource;
         ACTIVE.set(this);
      }

      static ExecutionBinding open(final ExtendedFuture<?> factorySource) {
         return new ExecutionBinding(factorySource);
      }

      @SuppressWarnings("resource") // Checking for an enclosing scope does not take ownership of it.
      static @Nullable ExecutionBinding suspend() {
         // A null factory isolates known-owner operations. Ordinary private observations need no extra scope allocation.
         return ACTIVE.get() == null ? null : new ExecutionBinding(null);
      }

      @SuppressWarnings("resource") // Borrowed from the calling stage method; only that method closes its construction scope.
      static void store(final ExtendedFuture<?> factorySource, final InterruptibleFuture<?> owner) {
         final var binding = ACTIVE.get();
         if (binding != null && binding.factorySource == factorySource) {
            // Consume before factory cleanup can invoke other code. An unrelated factory must not steal this callback.
            // Keep the empty entry reusable; removing it makes the next operation allocate another ThreadLocalMap entry.
            ACTIVE.set(null);
            binding.factorySource = null;
            binding.owner = owner;
         }
      }

      InterruptibleFuture<?> takeOwner() {
         final var result = Objects.requireNonNull(owner, "Callback has no factory owner");
         discard();
         return result;
      }

      void discard() {
         // Once execution starts, its stack owns the future; a retained callback must not extend that ownership.
         owner = null;
      }

      @Override
      public void close() {
         // Restore reentrant scopes, retaining only an empty entry at the outer boundary, never a binding or future.
         ACTIVE.set(previous);
         // A queued callback must retain neither the source nor a reentrant outer operation after construction returns.
         previous = null;
         factorySource = null;
      }
   }

   /**
    * Remembers the original {@code mayInterruptIfRunning} intent on the concrete stage instance being cancelled (useful when a
    * non-interruptible wrapper masks the flag for its own cancellation but we still want upstream propagation to honor the caller's
    * intent). Retained after cancellation so late wrappers and completion observers see the same intent.
    * A null value means no intent was recorded; false must remain distinct from that unset state.
    */
   private volatile @Nullable Boolean cancelInterruptIntent;

   /**
    * Record the caller's cancel intent (mayInterruptIfRunning) if it has not been set yet.
    * Useful when a non-interruptible wrapper needs to preserve the original interrupt intent for upstream propagation.
    */
   private void rememberCancelInterruptIntentIfAbsent(final boolean mayInterruptIfRunning) {
      // Preserve the first recorded request, including false, when cancellation attempts race or reenter.
      CANCEL_INTERRUPT_INTENT.compareAndSet(this, null, Boolean.valueOf(mayInterruptIfRunning));
   }

   /**
    * Peek at the stored caller cancel interrupt intent without clearing it, or return the provided default if none was recorded.
    */
   boolean getCancelInterruptIntentOrDefault(final boolean defaultMayInterruptIfRunning) {
      final var intent = cancelInterruptIntent;
      return intent == null ? defaultMayInterruptIfRunning : intent;
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

   /** A configurable view that delegates mutations but retains its own completion state for inherited reads and stage methods. */
   static class WrappingFuture<T> extends ExtendedFuture<T> {

      protected final CompletableFuture<T> wrapped;

      @SuppressWarnings("synthetic-access")
      private WrappingFuture(final CompletableFuture<T> wrapped, final boolean cancellableByDependents, final boolean interruptibleStages,
            final @Nullable Executor defaultExecutor) {
         super(cancellableByDependents, interruptibleStages, defaultExecutor);
         this.wrapped = wrapped;
         wrapped.whenComplete((result, ex) -> {
            if (ex == null) {
               super.complete(result);
            } else {
               // If the wrapped future was cancelled, reflect cancellation on this wrapper as well
               final var cause = ex instanceof CompletionException && ex.getCause() != null ? ex.getCause() : ex;
               if (cause instanceof CancellationException) {
                  // Do not forward cancellation again to the wrapped instance; only mark this wrapper as cancelled
                  WrappingFuture.super.cancel(false);
               } else {
                  super.completeExceptionally(ex);
               }
            }
         });
      }

      @Override
      public boolean cancel(final boolean mayInterruptIfRunning) {
         // Intermediate views can still appear pending after the original backing future has completed.
         // Recording intent then would let a redundant cancel(true) change the cancellation being mirrored.
         CompletableFuture<?> cancellationSource = wrapped;
         while (cancellationSource instanceof WrappingFuture) {
            cancellationSource = ((WrappingFuture<?>) cancellationSource).wrapped;
         }
         if (!cancellationSource.isDone()) {
            // Preserve the caller's original intent for upstream propagation even if this wrapper masks interrupts
            super.rememberCancelInterruptIntentIfAbsent(mayInterruptIfRunning);
            if (wrapped instanceof ExtendedFuture) {
               // Read our own stored intent: an outer view may already have masked the argument passed to this wrapper.
               final boolean requestedMayInterrupt = super.getCancelInterruptIntentOrDefault(mayInterruptIfRunning);
               ((ExtendedFuture<?>) wrapped).rememberCancelInterruptIntentIfAbsent(requestedMayInterrupt);
            }
         }
         // Still delegate to preserve read-only mutation checks and the backing future's completion notification behavior.
         return wrapped.cancel(mayInterruptIfRunning && isInterruptible());
      }

      @Override
      public boolean complete(final T value) {
         return wrapped.complete(value);
      }

      @Override
      public ExtendedFuture<T> completeAsync(final Supplier<? extends T> supplier) {
         // Choose this view's executor, but keep the backing future as the completion owner.
         return completeAsync(supplier, defaultExecutor());
      }

      @Override
      public ExtendedFuture<T> completeAsync(final Supplier<? extends T> supplier, final Executor executor) {
         wrapped.completeAsync(supplier, executor);
         return this;
      }

      @Override
      public ExtendedFuture<T> completeAsync(final ThrowingSupplier<? extends T, ?> supplier) {
         // The Supplier cast avoids dispatching back to this more-specific overload.
         return completeAsync((Supplier<? extends T>) supplier);
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
      public ExtendedFuture<T> completeOnTimeout(final T value, final long timeout, final TimeUnit unit) {
         wrapped.completeOnTimeout(value, timeout, unit);
         return this;
      }

      @Override
      public ExtendedFuture<T> completeWith(final CompletableFuture<? extends T> future) {
         future.whenComplete((result, ex) -> {
            if (ex == null) {
               wrapped.complete(result);
            } else {
               final var cause = ex instanceof CompletionException && ex.getCause() != null ? ex.getCause() : ex;
               if (cause instanceof CancellationException) {
                  final boolean mayInterrupt = future instanceof ExtendedFuture //
                        && ((ExtendedFuture<?>) future).getCancelInterruptIntentOrDefault(false);
                  cancel(mayInterrupt);
               } else {
                  wrapped.completeExceptionally(ex);
               }
            }
         });
         return this;
      }

      @Override
      boolean getCancelInterruptIntentOrDefault(final boolean defaultMayInterruptIfRunning) {
         // The backing future owns the actual cancellation. Rejected read-only mutation attempts must not override its intent.
         if (wrapped instanceof ExtendedFuture)
            return ((ExtendedFuture<?>) wrapped).getCancelInterruptIntentOrDefault(defaultMayInterruptIfRunning);
         return super.getCancelInterruptIntentOrDefault(defaultMayInterruptIfRunning);
      }

      @Override
      public void obtrudeException(final Throwable ex) {
         // Delegate before touching our state so validation and read-only rejection remain authoritative.
         wrapped.obtrudeException(ex);
         syncObtrudedOutcome();
      }

      @Override
      public void obtrudeValue(final T value) {
         wrapped.obtrudeValue(value);
         syncObtrudedOutcome();
      }

      private <U> CompletableFuture<U> observeWrappedOutcome(final BiFunction<? super T, @Nullable Throwable, ? extends U> action) {
         // Observe the backing instance, not this view's potentially stale outcome.
         if (wrapped instanceof ExtendedFuture)
            return ((ExtendedFuture<T>) wrapped).handleWithoutExecutionTracking(action);
         // Other CompletableFuture implementations do not expose our private bypass; retain their normal dispatch.
         return wrapped.handle(action);
      }

      @Override
      public ExtendedFuture<T> orTimeout(final long timeout, final TimeUnit unit) {
         wrapped.orTimeout(timeout, unit);
         return this;
      }

      private void syncObtrudedOutcome() {
         // IGNORE_MUTATION can return normally without completing the backing future. Do not leave a pending observer in that case.
         if (!wrapped.isDone())
            return;

         // Observe the actual outcome: delegation may have been ignored, or an inline callback may have replaced the requested outcome.
         // handle preserves the original throwable; joining the backing future itself would wrap some failures in CompletionException.
         boolean copiedCurrentOutcome;
         do {
            copiedCurrentOutcome = observeWrappedOutcome((value, ex) -> {
               if (ex == null) {
                  super.obtrudeValue(value);
               } else {
                  // Preserve the raw exception, including cancellation, rather than applying the constructor's cancellation normalization.
                  super.obtrudeException(ex);
               }
               // Another call can change the backing future after this snapshot was taken but before we copied it.
               // Compare identity: equal values can still be distinct results, and equals() must not run user code here.
               return observeWrappedOutcome((currentValue, currentEx) -> currentValue == value && currentEx == ex).join();
            }).join();
            // These completed-source handles run inline; their joins retrieve our verification result, not the backing failure.
            // Retry only the copy, never the original mutation. Locks would run completion callbacks under a shared lock.
         }
         while (!copiedCurrentOutcome);
         // This repairs the forwarding chain after overlapping calls settle, not sibling views mutated through other references.
      }
   }

   // Share the atomic field accessor instead of allocating an AtomicReference for every future.
   private static final VarHandle CANCEL_INTERRUPT_INTENT;
   static {
      try {
         CANCEL_INTERRUPT_INTENT = MethodHandles.lookup().findVarHandle(ExtendedFuture.class, "cancelInterruptIntent", Boolean.class);
      } catch (final ReflectiveOperationException ex) {
         throw new ExceptionInInitializerError(ex);
      }
   }

   // Initialize atomic access first: a user-supplied LoggerFinder can reenter this class during logger lookup.
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
      if (source instanceof ExtendedFuture) {
         final var extended = (ExtendedFuture<V>) source;
         // Single-input anyOf can use our stage factory without passing through an instance stage method.
         extended.clearCancellablePrecedingStagesOnCompletion();
         return extended.asCancellableByDependents(false);
      }
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

   // One observer owns the whole queue, including links discovered after stage construction.
   private volatile boolean cancellationCleanupRegistered;
   // Under cancellablePrecedingStages: overlapping cancel calls retain ownership even if callbacks obtrude another outcome.
   private int cancellationForwarders;
   // Also under the queue lock: retain accepted requests for late inputs, independently of an obtruded visible outcome.
   private boolean cancellationAccepted;
   private volatile @Nullable FactoryRegistrations factoryDependents;
   private volatile @Nullable FactoryRegistration factoryPredecessor;

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
      Objects.requireNonNull(action);
      try (var operand = prepareEitherOperand(other)) {
         if (interruptibleStages) {
            try (var binding = ExecutionBinding.open(this)) {
               return withSecondPrecedingStage(super.acceptEither(operand == null ? other : operand, result -> interruptiblyAccept(binding,
                  result, action)), other);
            }
         }
         return withSecondPrecedingStage(super.acceptEither(operand == null ? other : operand, action), other);
      }
   }

   public ExtendedFuture<@Nullable Void> acceptEither(final CompletionStage<? extends T> other,
         final ThrowingConsumer<? super T, ?> action) {
      return acceptEither(other, (Consumer<? super T>) action);
   }

   @Override
   public ExtendedFuture<@Nullable Void> acceptEitherAsync(final CompletionStage<? extends T> other, final Consumer<? super T> action) {
      Objects.requireNonNull(action);
      try (var operand = prepareEitherOperand(other)) {
         if (interruptibleStages) {
            try (var binding = ExecutionBinding.open(this)) {
               return withSecondPrecedingStage(super.acceptEitherAsync(operand == null ? other : operand, result -> interruptiblyAccept(
                  binding, result, action)), other);
            }
         }
         return withSecondPrecedingStage(super.acceptEitherAsync(operand == null ? other : operand, action), other);
      }
   }

   @Override
   public ExtendedFuture<@Nullable Void> acceptEitherAsync(final CompletionStage<? extends T> other, final Consumer<? super T> action,
         final Executor executor) {
      Objects.requireNonNull(action);
      Objects.requireNonNull(executor);
      try (var operand = prepareEitherOperand(other)) {
         if (interruptibleStages) {
            try (var binding = ExecutionBinding.open(this)) {
               return withSecondPrecedingStage(super.acceptEitherAsync(operand == null ? other : operand, result -> interruptiblyAccept(
                  binding, result, action), executor), other);
            }
         }
         return withSecondPrecedingStage(super.acceptEitherAsync(operand == null ? other : operand, action, executor), other);
      }
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
      Objects.requireNonNull(fn);
      try (var operand = prepareEitherOperand(other)) {
         if (interruptibleStages) {
            try (var binding = ExecutionBinding.open(this)) {
               return withSecondPrecedingStage(super.applyToEither(operand == null ? other : operand, result -> interruptiblyApply(binding,
                  result, fn)), other);
            }
         }
         return withSecondPrecedingStage(super.applyToEither(operand == null ? other : operand, fn), other);
      }
   }

   public <U> ExtendedFuture<U> applyToEither(final CompletionStage<? extends T> other, final ThrowingFunction<? super T, U, ?> fn) {
      return applyToEither(other, (Function<? super T, U>) fn);
   }

   @Override
   public <U> ExtendedFuture<U> applyToEitherAsync(final CompletionStage<? extends T> other, final Function<? super T, U> fn) {
      Objects.requireNonNull(fn);
      try (var operand = prepareEitherOperand(other)) {
         if (interruptibleStages) {
            try (var binding = ExecutionBinding.open(this)) {
               return withSecondPrecedingStage(super.applyToEitherAsync(operand == null ? other : operand, result -> interruptiblyApply(
                  binding, result, fn)), other);
            }
         }
         return withSecondPrecedingStage(super.applyToEitherAsync(operand == null ? other : operand, fn), other);
      }
   }

   @Override
   public <U> ExtendedFuture<U> applyToEitherAsync(final CompletionStage<? extends T> other, final Function<? super T, U> fn,
         final Executor executor) {
      Objects.requireNonNull(fn);
      Objects.requireNonNull(executor);
      try (var operand = prepareEitherOperand(other)) {
         if (interruptibleStages) {
            try (var binding = ExecutionBinding.open(this)) {
               return withSecondPrecedingStage(super.applyToEitherAsync(operand == null ? other : operand, result -> interruptiblyApply(
                  binding, result, fn), executor), other);
            }
         }
         return withSecondPrecedingStage(super.applyToEitherAsync(operand == null ? other : operand, fn, executor), other);
      }
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
            ? new InterruptibleWrappingFuture<>(this, isCancellableByDependents, interruptibleStages, defaultExecutor)
            : new WrappingFuture<>(this, isCancellableByDependents, interruptibleStages, defaultExecutor);
   }

   /**
    * Returns an {@link ExtendedFuture} that shares the result with this future but ensures
    * that this future's task cannot be interrupted, i.e., calling {@code cancel(true)} on the returned view will not
    * interrupt the thread executing this task.
    * Cancellation still forwards the caller's original interrupt intent to preceding stages that allow cancellation by dependents.
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
    * The returned future is backed by this future, allowing only read operations such as {@link ExtendedFuture#get()},
    * {@link ExtendedFuture#join()}, and other non-mutating methods.
    * Any attempt to invoke mutating operations such as {@link ExtendedFuture#cancel(boolean)}, {@link ExtendedFuture#complete(Object)},
    * {@link ExtendedFuture#completeExceptionally(Throwable)}, or {@link ExtendedFuture#obtrudeValue(Object)} will result in an
    * {@link UnsupportedOperationException} or be silently ignored, depending on the specified {@link ReadOnlyMode}.
    * <p>
    * Cancellation semantics of the read-only view:
    * <ul>
    * <li>The returned read-only future has {@link #isCancellableByDependents()} set to {@code false} to uphold the read-only contract.</li>
    * <li>Dependent stages created from the read-only view cannot cancel this future (or its upstream stages); their cancellation will not
    * propagate upstream.</li>
    * <li>Direct mutation attempts on the read-only view (e.g., {@code cancel(...)}, {@code complete(...)}) are handled according to the
    * chosen {@link ReadOnlyMode}.</li>
    * </ul>
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
    * Each stage applies its own interruption policy without changing the caller's interrupt intent for preceding stages.
    * Replacing the outcome through obtrusion does not retract an accepted cancellation request, including for late composed inputs.
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

      rememberCancelInterruptIntentIfAbsent(mayInterruptIfRunning);
      synchronized (cancellablePrecedingStages) {
         cancellationForwarders++;
      }
      try {
         // Publish outside the lock: callbacks may reenter cancellation or replace its outcome through obtrusion.
         final boolean cancelled = super.cancel(mayInterruptIfRunning && isInterruptible());
         if (cancelled) {
            synchronized (cancellablePrecedingStages) {
               cancellationAccepted = true;
            }
            forwardPendingCancellation();
         }
         return cancelled;
      } finally {
         synchronized (cancellablePrecedingStages) {
            cancellationForwarders--;
         }
         // A losing attempt may have deferred the winner's cleanup too. The last forwarder must finish it in either case.
         if (isDone()) {
            cleanupAfterCompletion();
         }
      }
   }

   /**
    * Completes this future with the given value if not already completed.
    *
    * @param value the value to complete this future with
    * @return {@code true} if this invocation caused this future to transition to a completed state, otherwise {@code false}
    */
   @Override
   public boolean complete(final T value) {
      final boolean completed = super.complete(value);
      // A losing attempt can run inside a cancellation observer, before cancel() has traversed the links.
      if (completed) {
         cleanupAfterCompletion();
      }
      return completed;
   }

   /**
    * Completes this future with the result of the given supplier function, running it asynchronously using the default executor.
    *
    * @param supplier the supplier function to produce the completion value
    * @return this {@code ExtendedFuture} for method chaining
    * @see #completeAsync(Supplier, Executor)
    */
   @Override
   public ExtendedFuture<T> completeAsync(final Supplier<? extends T> supplier) {
      super.completeAsync(supplier);
      return this;
   }

   /**
    * Completes this future with the result of the given supplier function, running it asynchronously using the specified executor.
    * <p>
    * If this future is interruptible, {@code cancel(true)} can interrupt all running suppliers completing it.
    * Concurrent completion attempts retain their first-result-wins behavior.
    *
    * @param supplier the supplier function to produce the completion value
    * @param executor the executor to use for asynchronous execution
    * @return this {@code ExtendedFuture} for method chaining
    */
   @Override
   public ExtendedFuture<T> completeAsync(final Supplier<? extends T> supplier, final Executor executor) {
      super.completeAsync(supplier, executor);
      // A directly requested newIncompleteFuture can be completed asynchronously without passing through a stage method.
      clearCancellablePrecedingStagesOnCompletion();
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
      final boolean completed = super.completeExceptionally(ex);
      // Preserve links on both a losing attempt and immediate null rejection; neither completes this future.
      if (completed) {
         cleanupAfterCompletion();
      }
      return completed;
   }

   private static <U> CompletionStage<U> composeWithCancellation(final AtomicReference<@Nullable ExtendedFuture<?>> handoff,
         final CompletionStage<U> stage) {
      if (stage instanceof ExtendedFuture) {
         final var nested = (ExtendedFuture<?>) stage;
         if (nested.isCancellableByDependents() && !nested.isDone()) {
            // Each side arrives once. A non-null exchange here is the result; in registerComposedResult it is the nested stage.
            // This handles inline mappers as well as callbacks that run after thenCompose has returned, without waiting.
            final var result = handoff.getAndSet(nested);
            if (result != null) {
               handoff.set(null);
               registerCancellablePrecedingStage(result, nested);
            }
         }
      }
      return stage;
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
            final var cause = ex instanceof CompletionException && ex.getCause() != null ? ex.getCause() : ex;
            if (cause instanceof CancellationException) {
               final boolean mayInterrupt = future instanceof ExtendedFuture && ((ExtendedFuture<?>) future)
                  .getCancelInterruptIntentOrDefault(false);
               cancel(mayInterrupt);
            } else {
               completeExceptionally(ex);
            }
         }
      });
      return this;
   }

   @Override
   public ExtendedFuture<T> copy() {
      try (var ignored = ExecutionBinding.suspend()) {
         return toExtendedFuture(super.copy());
      }
   }

   @Override
   public Executor defaultExecutor() {
      return defaultExecutor;
   }

   /**
    * {@inheritDoc}
    * <p>
    * When interruptible stages are enabled, cancelling the returned stage with {@code cancel(true)} can interrupt its running recovery
    * callback. Recovery still executes synchronously.
    */
   @Override
   public ExtendedFuture<T> exceptionally(final Function<Throwable, ? extends T> fn) {
      // Preserve immediate argument rejection before the non-null callback wrapper hides the original handler.
      Objects.requireNonNull(fn);
      if (interruptibleStages) {
         try (var binding = ExecutionBinding.open(this)) {
            // An already failed source can invoke the callback inline, so associate its owning stage before delegating.
            // handle also observes success, unlike exceptionally. This releases the unused association without an extra dependent.
            return toExtendedFuture(super.handle((result, ex) -> {
               if (ex == null) {
                  // No user code runs on success, so there is no execution to register or wait for GC to reclaim.
                  binding.discard();
                  return result;
               }
               return interruptiblyApply(binding, ex, fn);
            }));
         }
      }
      return toExtendedFuture(super.exceptionally(fn));
   }

   public ExtendedFuture<T> exceptionally(final ThrowingFunction<Throwable, ? extends T, ?> fn) {
      return exceptionally((Function<Throwable, ? extends T>) fn);
   }

   /**
    * This method emulates the {@link CompletableFuture}'s exceptionallyAsync method introduced in Java 12.
    *
    * @see #exceptionallyAsync(Function, Executor)
    */
   // @Override
   public ExtendedFuture<T> exceptionallyAsync(final Function<Throwable, ? extends T> fn) {
      // emulate exceptionallyAsync introduced in Java 12
      return exceptionallyAsync(fn, defaultExecutor());
   }

   /**
    * This method emulates the {@link CompletableFuture}'s exceptionallyAsync method introduced in Java 12.
    * <p>
    * Successful completion passes through synchronously. Cancellation of the returned future cancels its private recovery work;
    * when interruptible stages are enabled, {@code cancel(true)} can interrupt a running recovery callback.
    */
   // @Override
   public ExtendedFuture<T> exceptionallyAsync(final Function<Throwable, ? extends T> fn, final Executor executor) {
      // Validate before deferring stage creation, including when recovery will be skipped on success.
      Objects.requireNonNull(fn);
      Objects.requireNonNull(executor);
      // completeAsync does not screen executors. Super supplies the JDK's common-pool fallback, not our configured default.
      final var recoveryExecutor = executor == ForkJoinPool.commonPool() ? super.defaultExecutor() : executor;
      return recover((result, error) -> {
         // Private work carries cancellation, but callback interruption belongs to the public result and its views.
         final var task = new ExtendedFuture<T>(true, false, defaultExecutor);
         registerCancellablePrecedingStage(result, task);
         if (!result.isDone()) {
            final Supplier<T> recovery = () -> result.applyRecovery(error, fn);
            task.completeAsync(recovery, recoveryExecutor);
         }
         return task;
      });
   }

   public ExtendedFuture<T> exceptionallyAsync(final ThrowingFunction<Throwable, ? extends T, ?> fn) {
      return exceptionallyAsync((Function<Throwable, ? extends T>) fn);
   }

   public ExtendedFuture<T> exceptionallyAsync(final ThrowingFunction<Throwable, ? extends T, ?> fn, final Executor executor) {
      return exceptionallyAsync((Function<Throwable, ? extends T>) fn, executor);
   }

   /**
    * This method emulates the {@link CompletableFuture}'s exceptionallyCompose method introduced in Java 12.
    * <p>
    * Recovery executes synchronously, but its callback belongs to the returned future for interruption and cancellation,
    * with the same nested-stage cancellation policy as {@link #exceptionallyComposeAsync(Function, Executor)}.
    */
   // @Override
   public ExtendedFuture<T> exceptionallyCompose(final Function<Throwable, ? extends CompletionStage<T>> fn) {
      Objects.requireNonNull(fn);
      return recover((result, error) -> {
         // Synchronous recovery has no queued private task: the public result owns both the mapper and its nested-stage link.
         final CompletionStage<T> nested = result.applyRecovery(error, fn);
         // The mapper may have cancelled its owner before returning. Registration still forwards that intent to opted-in stages.
         registerCancellablePrecedingStage(result, nested);
         return nested;
      });
   }

   public ExtendedFuture<T> exceptionallyCompose(final ThrowingFunction<Throwable, ? extends CompletionStage<T>, ?> fn) {
      return exceptionallyCompose((Function<Throwable, ? extends CompletionStage<T>>) fn);
   }

   /**
    * This method emulates the {@link CompletableFuture}'s exceptionallyComposeAsync method introduced in Java 12.
    *
    * @see #exceptionallyComposeAsync(Function, Executor)
    */
   // @Override
   public ExtendedFuture<T> exceptionallyComposeAsync(final Function<Throwable, ? extends CompletionStage<T>> fn) {
      // emulate exceptionallyComposeAsync introduced in Java 12
      return exceptionallyComposeAsync(fn, defaultExecutor());
   }

   /**
    * This method emulates the {@link CompletableFuture}'s exceptionallyComposeAsync method introduced in Java 12.
    * <p>
    * Successful completion passes through synchronously. Cancellation of the returned future cancels its private recovery work;
    * when interruptible stages are enabled, {@code cancel(true)} can interrupt a running recovery callback.
    * A stage returned by the callback is cancelled only if it is an {@link ExtendedFuture} that permits cancellation by dependents.
    */
   // @Override
   public ExtendedFuture<T> exceptionallyComposeAsync(final Function<Throwable, ? extends CompletionStage<T>> fn, final Executor executor) {
      // Keep the same immediate validation as value recovery; wrapping a null callback would otherwise hide it.
      Objects.requireNonNull(fn);
      Objects.requireNonNull(executor);
      return recover((result, error) -> {
         // The public result tracks the mapper. Private composition only forwards cancellation to opted-in nested stages,
         // preserving the caller's original intent even when a view masks interruption of the mapper itself.
         final var trigger = new ExtendedFuture<Throwable>(true, false, defaultExecutor);
         final Function<Throwable, CompletionStage<T>> mapper = failure -> result.applyRecovery(failure, fn);
         final ExtendedFuture<T> recovery = trigger.thenComposeAsync(mapper, executor);
         registerCancellablePrecedingStage(result, recovery);
         // The exception is the mapper's input, not the trigger's failure. Even an inline executor must see the cancellation link.
         if (!result.isDone()) {
            trigger.complete(error);
         }
         return recovery;
      });
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
    * Attempts to retrieve the result of this future within the specified timeout.
    *
    * @return an {@link Optional} containing the result of the future if completed normally within the timeout,
    *         or an empty {@link Optional} otherwise
    */
   public Optional<T> getOptional() {
      return Futures.getOptional(this);
   }

   /**
    * Attempts to retrieve the result of this future within the specified timeout.
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
         try (var binding = ExecutionBinding.open(this)) {
            return toExtendedFuture(super.handle((result, ex) -> interruptiblyHandle(binding, result, ex, fn)));
         }
      }
      return toExtendedFuture(super.handle(fn));
   }

   @Override
   public <U> ExtendedFuture<U> handleAsync(final BiFunction<? super T, @Nullable Throwable, ? extends U> fn) {
      if (interruptibleStages) {
         try (var binding = ExecutionBinding.open(this)) {
            return toExtendedFuture(super.handleAsync((result, ex) -> interruptiblyHandle(binding, result, ex, fn)));
         }
      }
      return toExtendedFuture(super.handleAsync(fn));
   }

   @Override
   public <U> ExtendedFuture<U> handleAsync(final BiFunction<? super T, @Nullable Throwable, ? extends U> fn, final Executor executor) {
      if (interruptibleStages) {
         try (var binding = ExecutionBinding.open(this)) {
            return toExtendedFuture(super.handleAsync((result, ex) -> interruptiblyHandle(binding, result, ex, fn), executor));
         }
      }
      return toExtendedFuture(super.handleAsync(fn, executor));
   }

   /**
    * Observes this future's outcome without tracking the callback for interruption.
    * Only for internal observations whose dependent stage is not exposed to callers for cancellation.
    */
   private <U> ExtendedFuture<U> handleWithoutExecutionTracking(final BiFunction<? super T, @Nullable Throwable, ? extends U> fn) {
      try (var ignored = ExecutionBinding.suspend()) {
         // Super bypasses handle overrides but retains the virtual stage factory and its allocation and policy choices.
         return toExtendedFuture(super.handle(fn));
      }
   }

   private void interruptiblyAccept(final ExecutionBinding binding, final T result, final Consumer<? super T> action) {
      final var f = binding.takeOwner();
      f.registerExecutingThread();
      try {
         action.accept(result);
      } finally {
         f.unregisterExecutingThread();
      }
   }

   private <U> void interruptiblyAcceptBoth(final ExecutionBinding binding, final T result, final U otherResult,
         final BiConsumer<? super T, ? super U> action) {
      final var f = binding.takeOwner();
      f.registerExecutingThread();
      try {
         action.accept(result, otherResult);
      } finally {
         f.unregisterExecutingThread();
      }
   }

   // Recovery consumes Throwable while normal mapping consumes T; execution tracking does not depend on the input type.
   private <I, U> U interruptiblyApply(final ExecutionBinding binding, final I result, final Function<? super I, ? extends U> fn) {
      return interruptiblyApply(binding.takeOwner(), result, fn);
   }

   private <I, U> U interruptiblyApply(final InterruptibleFuture<?> f, final I result, final Function<? super I, ? extends U> fn) {
      f.registerExecutingThread();
      try {
         return fn.apply(result);
      } finally {
         f.unregisterExecutingThread();
      }
   }

   private <U, V> V interruptiblyCombine(final ExecutionBinding binding, final T result, final U otherResult,
         final BiFunction<? super T, ? super U, ? extends V> fn) {
      final var f = binding.takeOwner();
      f.registerExecutingThread();
      try {
         return fn.apply(result, otherResult);
      } finally {
         f.unregisterExecutingThread();
      }
   }

   private <U> U interruptiblyHandle(final ExecutionBinding binding, final T result, final @Nullable Throwable ex,
         final BiFunction<? super T, @Nullable Throwable, ? extends U> fn) {
      final var f = binding.takeOwner();
      f.registerExecutingThread();
      try {
         return fn.apply(result, ex);
      } finally {
         f.unregisterExecutingThread();
      }
   }

   private void interruptiblyRun(final ExecutionBinding binding, final Runnable action) {
      final var f = binding.takeOwner();
      f.registerExecutingThread();
      try {
         action.run();
      } finally {
         f.unregisterExecutingThread();
      }
   }

   private <U> CompletionStage<U> interruptiblyThenCompose(final ExecutionBinding binding, final T result,
         final Function<? super T, ? extends CompletionStage<U>> fn) {
      final var f = binding.takeOwner();
      f.registerExecutingThread();

      try {
         final var stage = fn.apply(result);
         // Only async composition uses this path: the JDK conditionally completes its result, even with a direct executor.
         // Synchronous composition instead delays inline linking until its factory returns, preserving an earlier cancellation.
         registerCancellablePrecedingStage(f, stage);
         return stage;
      } finally {
         f.unregisterExecutingThread();
      }
   }

   private void interruptiblyWhenComplete(final ExecutionBinding binding, final @Nullable T result, final @Nullable Throwable ex,
         final BiConsumer<? super @Nullable T, ? super @Nullable Throwable> action) {
      final var f = binding.takeOwner();
      if (!f.tryRegisterExecutingThread())
         // The JDK suppresses callback failures on the source exception even if the returned stage is already done.
         // Skipping must not add our internal abort exception; failures from user code below still propagate.
         return;
      try {
         action.accept(result, ex);
      } finally {
         f.unregisterExecutingThread();
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
         ExecutionBinding.store(this, newInterruptibleFuture);
         newFuture = newInterruptibleFuture;
      } else {
         newFuture = new ExtendedFuture<>(cancellableByDependents, interruptibleStages, defaultExecutor);
      }
      if (cancellableByDependents && !isDone()) {
         // This result is not exposed yet, so only links discovered later need registration's cancellation recheck.
         newFuture.cancellablePrecedingStages.add(this);
         // Do not observe it here: native construction fast paths can publish a result without notifying existing dependents.
         // Observe the source instead, so plain JDK entry points such as single-input anyOf also release their source links.
         registerFactoryDependent(newFuture);
      }
      return newFuture;
   }

   @Override
   public void obtrudeException(final Throwable ex) {
      // Delegate first: rejected null exceptions must not discard a pending factory result's cancellation link.
      super.obtrudeException(ex);
      cleanupAfterCompletion();
   }

   @Override
   public void obtrudeValue(final T value) {
      super.obtrudeValue(value);
      // A bare factory result has no terminal observer of its own; forced completion must release its registration too.
      cleanupAfterCompletion();
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
         try (var binding = ExecutionBinding.open(this)) {
            return withSecondPrecedingStage(super.runAfterBoth(toExtendedFuture(other), () -> interruptiblyRun(binding, action)), other);
         }
      }
      return withSecondPrecedingStage(super.runAfterBoth(other, action), other);
   }

   public ExtendedFuture<@Nullable Void> runAfterBoth(final CompletionStage<?> other, final ThrowingRunnable<?> action) {
      return runAfterBoth(other, (Runnable) action);
   }

   @Override
   public ExtendedFuture<@Nullable Void> runAfterBothAsync(final CompletionStage<?> other, final Runnable action) {
      if (interruptibleStages) {
         try (var binding = ExecutionBinding.open(this)) {
            return withSecondPrecedingStage(super.runAfterBothAsync(toExtendedFuture(other), () -> interruptiblyRun(binding, action)),
               other);
         }
      }
      return withSecondPrecedingStage(super.runAfterBothAsync(other, action), other);
   }

   @Override
   public ExtendedFuture<@Nullable Void> runAfterBothAsync(final CompletionStage<?> other, final Runnable action, final Executor executor) {
      if (interruptibleStages) {
         try (var binding = ExecutionBinding.open(this)) {
            return withSecondPrecedingStage(super.runAfterBothAsync(toExtendedFuture(other), () -> interruptiblyRun(binding, action),
               executor), other);
         }
      }
      return withSecondPrecedingStage(super.runAfterBothAsync(other, action, executor), other);
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
      Objects.requireNonNull(action);
      try (var operand = prepareEitherOperand(other)) {
         if (interruptibleStages) {
            try (var binding = ExecutionBinding.open(this)) {
               return withSecondPrecedingStage(super.runAfterEither(operand == null ? other : operand, () -> interruptiblyRun(binding,
                  action)), other);
            }
         }
         return withSecondPrecedingStage(super.runAfterEither(operand == null ? other : operand, action), other);
      }
   }

   public ExtendedFuture<@Nullable Void> runAfterEither(final CompletionStage<?> other, final ThrowingRunnable<?> action) {
      return runAfterEither(other, (Runnable) action);
   }

   @Override
   public ExtendedFuture<@Nullable Void> runAfterEitherAsync(final CompletionStage<?> other, final Runnable action) {
      Objects.requireNonNull(action);
      try (var operand = prepareEitherOperand(other)) {
         if (interruptibleStages) {
            try (var binding = ExecutionBinding.open(this)) {
               return withSecondPrecedingStage(super.runAfterEitherAsync(operand == null ? other : operand, () -> interruptiblyRun(binding,
                  action)), other);
            }
         }
         return withSecondPrecedingStage(super.runAfterEitherAsync(operand == null ? other : operand, action), other);
      }
   }

   @Override
   public ExtendedFuture<@Nullable Void> runAfterEitherAsync(final CompletionStage<?> other, final Runnable action,
         final Executor executor) {
      Objects.requireNonNull(action);
      Objects.requireNonNull(executor);
      try (var operand = prepareEitherOperand(other)) {
         if (interruptibleStages) {
            try (var binding = ExecutionBinding.open(this)) {
               return withSecondPrecedingStage(super.runAfterEitherAsync(operand == null ? other : operand, () -> interruptiblyRun(binding,
                  action), executor), other);
            }
         }
         return withSecondPrecedingStage(super.runAfterEitherAsync(operand == null ? other : operand, action, executor), other);
      }
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
         try (var binding = ExecutionBinding.open(this)) {
            return toExtendedFuture(super.thenAccept(result -> interruptiblyAccept(binding, result, action)));
         }
      }
      return toExtendedFuture(super.thenAccept(action));
   }

   public ExtendedFuture<@Nullable Void> thenAccept(final ThrowingConsumer<? super T, ?> action) {
      return thenAccept((Consumer<? super T>) action);
   }

   @Override
   public ExtendedFuture<@Nullable Void> thenAcceptAsync(final Consumer<? super T> action) {
      if (interruptibleStages) {
         try (var binding = ExecutionBinding.open(this)) {
            return toExtendedFuture(super.thenAcceptAsync(result -> interruptiblyAccept(binding, result, action)));
         }
      }
      return toExtendedFuture(super.thenAcceptAsync(action));
   }

   @Override
   public ExtendedFuture<@Nullable Void> thenAcceptAsync(final Consumer<? super T> action, final Executor executor) {
      if (interruptibleStages) {
         try (var binding = ExecutionBinding.open(this)) {
            return toExtendedFuture(super.thenAcceptAsync(result -> interruptiblyAccept(binding, result, action), executor));
         }
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
         try (var binding = ExecutionBinding.open(this)) {
            return withSecondPrecedingStage(super.thenAcceptBoth(toExtendedFuture(other), (result, otherResult) -> interruptiblyAcceptBoth(
               binding, result, otherResult, action)), other);
         }
      }
      return withSecondPrecedingStage(super.thenAcceptBoth(other, action), other);
   }

   public <U> ExtendedFuture<@Nullable Void> thenAcceptBoth(final CompletionStage<? extends U> other,
         final ThrowingBiConsumer<? super T, ? super U, ?> action) {
      return thenAcceptBoth(other, (BiConsumer<? super T, ? super U>) action);
   }

   @Override
   public <U> ExtendedFuture<@Nullable Void> thenAcceptBothAsync(final CompletionStage<? extends U> other,
         final BiConsumer<? super T, ? super U> action) {
      if (interruptibleStages) {
         try (var binding = ExecutionBinding.open(this)) {
            return withSecondPrecedingStage(super.thenAcceptBothAsync(toExtendedFuture(other), (result,
                  otherResult) -> interruptiblyAcceptBoth(binding, result, otherResult, action)), other);
         }
      }
      return withSecondPrecedingStage(super.thenAcceptBothAsync(other, action), other);
   }

   @Override
   public <U> ExtendedFuture<@Nullable Void> thenAcceptBothAsync(final CompletionStage<? extends U> other,
         final BiConsumer<? super T, ? super U> action, final Executor executor) {
      if (interruptibleStages) {
         try (var binding = ExecutionBinding.open(this)) {
            return withSecondPrecedingStage(super.thenAcceptBothAsync(toExtendedFuture(other), (result,
                  otherResult) -> interruptiblyAcceptBoth(binding, result, otherResult, action), executor), other);
         }
      }
      return withSecondPrecedingStage(super.thenAcceptBothAsync(other, action, executor), other);
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
         try (var binding = ExecutionBinding.open(this)) {
            return toExtendedFuture(super.thenApply(result -> interruptiblyApply(binding, result, fn)));
         }
      }
      return toExtendedFuture(super.thenApply(fn));
   }

   public <U> ExtendedFuture<U> thenApply(final ThrowingFunction<? super T, ? extends U, ?> fn) {
      return thenApply((Function<? super T, ? extends U>) fn);
   }

   @Override
   public <U> ExtendedFuture<U> thenApplyAsync(final Function<? super T, ? extends U> fn) {
      if (interruptibleStages) {
         try (var binding = ExecutionBinding.open(this)) {
            return toExtendedFuture(super.thenApplyAsync(result -> interruptiblyApply(binding, result, fn)));
         }
      }
      return toExtendedFuture(super.thenApplyAsync(fn));
   }

   @Override
   public <U> ExtendedFuture<U> thenApplyAsync(final Function<? super T, ? extends U> fn, final Executor executor) {
      if (interruptibleStages) {
         try (var binding = ExecutionBinding.open(this)) {
            return toExtendedFuture(super.thenApplyAsync(result -> interruptiblyApply(binding, result, fn), executor));
         }
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
         try (var binding = ExecutionBinding.open(this)) {
            return withSecondPrecedingStage(super.thenCombine(toExtendedFuture(other), (result, otherResult) -> interruptiblyCombine(
               binding, result, otherResult, fn)), other);
         }
      }
      return withSecondPrecedingStage(super.thenCombine(other, fn), other);
   }

   public <U, V> ExtendedFuture<V> thenCombine(final CompletionStage<? extends U> other,
         final ThrowingBiFunction<? super T, ? super U, ? extends V, ?> fn) {
      return thenCombine(other, (BiFunction<? super T, ? super U, ? extends V>) fn);
   }

   @Override
   public <U, V> ExtendedFuture<V> thenCombineAsync(final CompletionStage<? extends U> other,
         final BiFunction<? super T, ? super U, ? extends V> fn) {
      if (interruptibleStages) {
         try (var binding = ExecutionBinding.open(this)) {
            return withSecondPrecedingStage(super.thenCombineAsync(toExtendedFuture(other), (result, otherResult) -> interruptiblyCombine(
               binding, result, otherResult, fn)), other);
         }
      }
      return withSecondPrecedingStage(super.thenCombineAsync(other, fn), other);
   }

   @Override
   public <U, V> ExtendedFuture<V> thenCombineAsync(final CompletionStage<? extends U> other,
         final BiFunction<? super T, ? super U, ? extends V> fn, final Executor executor) {
      if (interruptibleStages) {
         try (var binding = ExecutionBinding.open(this)) {
            return withSecondPrecedingStage(super.thenCombineAsync(toExtendedFuture(other), (result, otherResult) -> interruptiblyCombine(
               binding, result, otherResult, fn), executor), other);
         }
      }
      return withSecondPrecedingStage(super.thenCombineAsync(other, fn, executor), other);
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
      // Wrapping must not turn immediate null rejection into a deferred callback failure.
      Objects.requireNonNull(fn);
      // The JDK's completed-source fast path can overwrite a cancelled result if we cancel the nested stage before it returns.
      // Both interruption policies need this handoff; the non-interruptible path does not need an execution binding.
      final var handoff = new AtomicReference<@Nullable ExtendedFuture<?>>();
      if (interruptibleStages) {
         try (var binding = ExecutionBinding.open(this)) {
            // Track only user mapper execution; the handoff owns linking its returned stage after that execution has ended.
            return registerComposedResult(toExtendedFuture(super.thenCompose(result -> composeWithCancellation(handoff, interruptiblyApply(
               binding, result, fn)))), handoff);
         }
      }
      return registerComposedResult(toExtendedFuture(super.thenCompose(result -> composeWithCancellation(handoff, fn.apply(result)))),
         handoff);
   }

   public <U> ExtendedFuture<U> thenCompose(final ThrowingFunction<? super T, ? extends CompletionStage<U>, ?> fn) {
      return thenCompose((Function<? super T, ? extends CompletionStage<U>>) fn);
   }

   @Override
   public <U> ExtendedFuture<U> thenComposeAsync(final Function<? super T, ? extends CompletionStage<U>> fn) {
      // Validate before substituting the callback, as in the synchronous overload.
      Objects.requireNonNull(fn);
      if (interruptibleStages) {
         try (var binding = ExecutionBinding.open(this)) {
            return toExtendedFuture(super.thenComposeAsync(result -> interruptiblyThenCompose(binding, result, fn)));
         }
      }
      final var handoff = new AtomicReference<@Nullable ExtendedFuture<?>>();
      return registerComposedResult(toExtendedFuture(super.thenComposeAsync(result -> composeWithCancellation(handoff, fn.apply(result)))),
         handoff);
   }

   @Override
   public <U> ExtendedFuture<U> thenComposeAsync(final Function<? super T, ? extends CompletionStage<U>> fn, final Executor executor) {
      // Validate before substituting the callback, as in the synchronous overload.
      Objects.requireNonNull(fn);
      if (interruptibleStages) {
         try (var binding = ExecutionBinding.open(this)) {
            return toExtendedFuture(super.thenComposeAsync(result -> interruptiblyThenCompose(binding, result, fn), executor));
         }
      }
      final var handoff = new AtomicReference<@Nullable ExtendedFuture<?>>();
      return registerComposedResult(toExtendedFuture(super.thenComposeAsync(result -> composeWithCancellation(handoff, fn.apply(result)),
         executor)), handoff);
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
         try (var binding = ExecutionBinding.open(this)) {
            return toExtendedFuture(super.thenRun(() -> interruptiblyRun(binding, action)));
         }
      }
      return toExtendedFuture(super.thenRun(action));
   }

   public ExtendedFuture<@Nullable Void> thenRun(final ThrowingRunnable<?> action) {
      return thenRun((Runnable) action);
   }

   @Override
   public ExtendedFuture<@Nullable Void> thenRunAsync(final Runnable action) {
      if (interruptibleStages) {
         try (var binding = ExecutionBinding.open(this)) {
            return toExtendedFuture(super.thenRunAsync(() -> interruptiblyRun(binding, action)));
         }
      }
      return toExtendedFuture(super.thenRunAsync(action));
   }

   @Override
   public ExtendedFuture<@Nullable Void> thenRunAsync(final Runnable action, final Executor executor) {
      if (interruptibleStages) {
         try (var binding = ExecutionBinding.open(this)) {
            return toExtendedFuture(super.thenRunAsync(() -> interruptiblyRun(binding, action), executor));
         }
      }
      return toExtendedFuture(super.thenRunAsync(action, executor));
   }

   public ExtendedFuture<@Nullable Void> thenRunAsync(final ThrowingRunnable<?> action) {
      return thenRunAsync((Runnable) action);
   }

   public ExtendedFuture<@Nullable Void> thenRunAsync(final ThrowingRunnable<?> action, final Executor executor) {
      return thenRunAsync((Runnable) action, executor);
   }

   private void clearCancellablePrecedingStagesOnCompletion() {
      if (factoryDependents == null && cancellablePrecedingStages.isEmpty())
         return;
      if (isDone()) {
         cleanupAfterCompletion();
         return;
      }
      if (cancellationCleanupRegistered)
         return;
      synchronized (cancellablePrecedingStages) {
         // Late nested-stage registration can race the stage-return boundary. Claim the shared observer only once.
         if (cancellationCleanupRegistered)
            return;
         cancellationCleanupRegistered = true;
      }
      // Attaching an observer can drain other callbacks, so do not hold the registration lock during attachment.
      // Minimal stages avoid our virtual factory, interruption tracking and ExtendedFuture's per-stage policy state.
      super.minimalCompletionStage().whenComplete((value, ex) -> cleanupAfterCompletion());
   }

   private void cleanupAfterCompletion() {
      if (factoryPredecessor == null && factoryDependents == null && cancellablePrecedingStages.isEmpty())
         return;
      final FactoryRegistrations dependents;
      final FactoryRegistration predecessor;
      final boolean forwardRemaining;
      synchronized (cancellablePrecedingStages) {
         // Outgoing edges point to this completed source and can be released independently of incoming cancellation forwarding.
         dependents = factoryDependents;
         factoryDependents = null;
         // isCancelled describes the current outcome, not whether an accepted cancellation is still forwarding upstream.
         if (cancellationForwarders == 0) {
            forwardRemaining = cancellationAccepted;
            if (!forwardRemaining) {
               cancellablePrecedingStages.clear();
            }
            predecessor = factoryPredecessor;
            factoryPredecessor = null;
         } else {
            forwardRemaining = false;
            predecessor = null;
         }
      }
      if (forwardRemaining) {
         // A handoff may have arrived after cancel's first traversal. Resolve it even when the last active attempt lost.
         forwardPendingCancellation();
      }
      if (predecessor != null) {
         predecessor.owner.remove(predecessor);
      }
      if (dependents != null) {
         dependents.close(this);
      }
      FactoryRegistration.purgeStale();
   }

   private void forwardPendingCancellation() {
      final Future<?>[] preceding;
      synchronized (cancellablePrecedingStages) {
         if (cancellablePrecedingStages.isEmpty())
            return;
         // Transfer ownership before calling other futures. Concurrent handoffs either remain for the final drain or
         // observe cancellationAccepted and forward themselves; completion cleanup cannot erase this private snapshot.
         preceding = cancellablePrecedingStages.toArray(Future<?>[]::new);
         cancellablePrecedingStages.clear();
      }
      final boolean requestedMayInterrupt = getCancelInterruptIntentOrDefault(false);
      for (final var stage : preceding) {
         if (!stage.isDone()) {
            // Each input applies its own interruption policy while retaining the caller's original intent for earlier stages.
            stage.cancel(requestedMayInterrupt);
         }
      }
   }

   private void registerFactoryDependent(final ExtendedFuture<?> dependent) {
      FactoryRegistration.purgeStale();
      final FactoryRegistrations registrations;
      synchronized (cancellablePrecedingStages) {
         // Completion can win after the factory's pending check. Do not reopen a detached, completed source's registry.
         if (isDone()) {
            registrations = null;
         } else {
            var current = factoryDependents;
            if (current == null) {
               current = new FactoryRegistrations();
               factoryDependents = current;
            }
            registrations = current;
         }
      }
      if (registrations == null) {
         dependent.cancellablePrecedingStages.removeIf(stage -> stage == this);
         return;
      }
      final var registration = new FactoryRegistration(registrations, dependent);
      // Publish the reverse handle before the source observer can drain it; the child is not otherwise exposed yet.
      dependent.factoryPredecessor = registration;
      if (!registrations.add(registration)) {
         dependent.cancellablePrecedingStages.removeIf(stage -> stage == this);
         dependent.factoryPredecessor = null;
         registration.clear();
         return;
      }
      // An unused factory result may become unreachable before this method returns. Keep it alive until insertion, otherwise
      // another registration could purge its GC-enqueued reference before it is linked, leaving an unpurgeable stale entry.
      Reference.reachabilityFence(dependent);
      // Reuse any existing incoming-link observer. Attachment may run callbacks, so neither registry lock is held here.
      clearCancellablePrecedingStagesOnCompletion();
   }

   /** Executes a recovery callback on its public owner, independently of the private stages that carry its outcome. */
   private <U> U applyRecovery(final Throwable error, final Function<Throwable, ? extends U> fn) {
      if (this instanceof InterruptibleFuture)
         // A non-interruptible view masks cancellation of this owner, but not the intent forwarded to other stages.
         return interruptiblyApply((InterruptibleFuture<?>) this, error, fn);
      if (isDone())
         // Explicit completion can win while private work is queued, even though it does not cancel that work's future.
         throw new CancellationException("Future already completed");
      return fn.apply(error);
   }

   /** Creates the public recovery owner and relays the selected outcome through native, stack-safe dependent stages. */
   private ExtendedFuture<T> recover(final BiFunction<ExtendedFuture<T>, Throwable, CompletionStage<T>> recover) {
      // Establish the public owner first, preserving the source's stage factory, flags, executor and upstream opt-in.
      final ExtendedFuture<T> result;
      try (var ignored = ExecutionBinding.suspend()) {
         // This callback already knows its owner; reentrant recovery must not consume an enclosing mapper's binding.
         result = this.<T>newIncompleteFuture();
      }
      final var selected = this.<CompletionStage<T>>handleWithoutExecutionTracking((value, error) -> {
         if (result.isDone())
            return result;
         if (error == null)
            // Preserve this observation's value, rather than rereading a source that can be obtruded concurrently.
            return CompletableFuture.completedFuture(value);
         return recover.apply(result, error);
      });
      // Let the JDK propagate both success and failure iteratively. Calling result.complete from an observer recurses
      // through long chains; completeWith also changes wrapped CancellationExceptions into direct cancellation.
      new CompletionRelay<>(result).completeFrom(selected.thenComposeWithoutExecutionTracking(Function.identity()));
      return toExtendedFuture(result);
   }

   /** Only for private flattening: execution tracking and cancellation ownership are established by the recovery operation. */
   private <U> ExtendedFuture<U> thenComposeWithoutExecutionTracking(final Function<? super T, ? extends CompletionStage<U>> fn) {
      try (var ignored = ExecutionBinding.suspend()) {
         return toExtendedFuture(super.thenCompose(fn));
      }
   }

   private static void registerCancellablePrecedingStage(final ExtendedFuture<?> result, final CompletionStage<?> preceding) {
      if (!(preceding instanceof ExtendedFuture))
         return;
      final var stage = (ExtendedFuture<?>) preceding;
      if (stage == result || !stage.isCancellableByDependents() || stage.isDone())
         return;

      boolean added = false;
      if (!result.isDone()) {
         synchronized (result.cancellablePrecedingStages) {
            // Serialize insertion with cancellation's ownership transfer, not with calls into the preceding stage.
            result.cancellablePrecedingStages.add(stage);
         }
         added = true;
         // Cancellation can finish traversing the queue before this insertion. Rechecking closes that missed-link race.
         if (!result.isDone()) {
            // Reuse the result's observer when another input already requested cleanup.
            result.clearCancellablePrecedingStagesOnCompletion();
            return;
         }
      }
      final boolean cancelled;
      synchronized (result.cancellablePrecedingStages) {
         if (result.cancellationForwarders != 0) {
            // super.cancel may still be inside an obtruding callback, so its acceptance is not yet known. Keep the handoff
            // until the active attempts resolve it; do not infer cancellation ownership from the replacement outcome.
            if (!added) {
               result.cancellablePrecedingStages.add(stage);
            }
            return;
         }
         // Identity matters for equal-but-distinct inputs. A missing inserted link is already owned by a cleanup/forwarding snapshot.
         if (added && !result.cancellablePrecedingStages.removeIf(candidate -> candidate == stage))
            return;
         cancelled = result.cancellationAccepted;
      }
      if (cancelled && !stage.isDone()) {
         // An accepted cancel request survives obtrusion; merely completing exceptionally with CancellationException is not a request.
         stage.cancel(result.getCancelInterruptIntentOrDefault(false));
      }
   }

   private static <V> ExtendedFuture<V> registerComposedResult(final ExtendedFuture<V> result,
         final AtomicReference<@Nullable ExtendedFuture<?>> handoff) {
      // The mapper may already have returned its nested stage before the superclass call returns the actual result.
      final var nested = handoff.getAndSet(result);
      if (nested != null) {
         handoff.set(null);
         registerCancellablePrecedingStage(result, nested);
      }
      return result;
   }

   private <V> ExtendedFuture<V> toExtendedFuture(final CompletionStage<V> source) {
      if (source instanceof ExtendedFuture) {
         final var result = (ExtendedFuture<V>) source;
         // The JDK has finished constructing a returned stage, so observing it here cannot strand the cleanup callback.
         result.clearCancellablePrecedingStagesOnCompletion();
         return result;
      }
      final var cf = source.toCompletableFuture();
      return new WrappingFuture<>(cf, cancellableByDependents, interruptibleStages, cf.defaultExecutor());
   }

   private <V> @Nullable EitherOperand<V> prepareEitherOperand(final CompletionStage<V> other) {
      // A natively completed receiver always supplies the either-stage factory; only a pending receiver needs a bridge.
      // Use the inherited state, not a subclass's possibly overridden isDone(), to make this allocation decision.
      if (super.isDone())
         return null;
      // A pending other input can complete before the native call selects its factory, so it cannot safely bypass the bridge.
      // Conversion is argument validation. Do it before the relay can turn an invocation error into an exceptional outcome.
      return new EitherOperand<>(this, Objects.requireNonNull(other.toCompletableFuture()));
   }

   private <V> ExtendedFuture<V> withSecondPrecedingStage(final CompletionStage<V> source, final CompletionStage<?> other) {
      final var result = toExtendedFuture(source);
      // The receiver is already linked by newIncompleteFuture. Only the original other input can grant cancellation permission;
      // an internal conversion wrapper must not opt an ordinary CompletionStage into cancellation.
      if (other != this) {
         registerCancellablePrecedingStage(result, other);
      }
      return result;
   }

   @Override
   public ExtendedFuture<T> whenComplete(final BiConsumer<? super @Nullable T, ? super @Nullable Throwable> action) {
      if (interruptibleStages) {
         try (var binding = ExecutionBinding.open(this)) {
            return toExtendedFuture(super.whenComplete((result, ex) -> interruptiblyWhenComplete(binding, result, ex, action)));
         }
      }
      return toExtendedFuture(super.whenComplete(action));
   }

   public ExtendedFuture<T> whenComplete(final ThrowingBiConsumer<? super @Nullable T, ? super @Nullable Throwable, ?> action) {
      return whenComplete((BiConsumer<? super @Nullable T, ? super @Nullable Throwable>) action);
   }

   @Override
   public ExtendedFuture<T> whenCompleteAsync(final BiConsumer<? super @Nullable T, ? super @Nullable Throwable> action) {
      if (interruptibleStages) {
         try (var binding = ExecutionBinding.open(this)) {
            return toExtendedFuture(super.whenCompleteAsync((result, ex) -> interruptiblyWhenComplete(binding, result, ex, action)));
         }
      }
      return toExtendedFuture(super.whenCompleteAsync(action));
   }

   @Override
   public ExtendedFuture<T> whenCompleteAsync(final BiConsumer<? super @Nullable T, ? super @Nullable Throwable> action,
         final Executor executor) {
      if (interruptibleStages) {
         try (var binding = ExecutionBinding.open(this)) {
            return toExtendedFuture(super.whenCompleteAsync((result, ex) -> interruptiblyWhenComplete(binding, result, ex, action),
               executor));
         }
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
    * specified {@link Executor} as the default for asynchronous operations of the returned future and its subsequent stages.
    *
    * @param defaultExecutor the default {@link Executor} for async tasks, must not be {@code null}
    * @return a new {@code ExtendedFuture} with the specified executor, or {@code this} if the
    *         executor is unchanged
    */
   public ExtendedFuture<T> withDefaultExecutor(final Executor defaultExecutor) {
      if (defaultExecutor == this.defaultExecutor)
         return this;

      return isInterruptible() //
            ? new InterruptibleWrappingFuture<>(this, cancellableByDependents, interruptibleStages, defaultExecutor)
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
            ? new InterruptibleWrappingFuture<>(this, cancellableByDependents, interruptibleStages, defaultExecutor)
            : new WrappingFuture<>(this, cancellableByDependents, interruptibleStages, defaultExecutor);
   }
}
