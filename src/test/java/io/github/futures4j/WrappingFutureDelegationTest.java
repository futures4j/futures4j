/*
 * SPDX-FileCopyrightText: © Sebastian Thomschke
 * SPDX-License-Identifier: EPL-2.0
 * SPDX-FileContributor: Sebastian Thomschke: initial API and implementation
 */
package io.github.futures4j;

import static net.sf.jstuff.core.validation.NullAnalysisHelper.sneakyNull;
import static org.assertj.core.api.Assertions.*;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Supplier;

import org.eclipse.jdt.annotation.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import io.github.futures4j.ExtendedFuture.ReadOnlyMode;
import io.github.futures4j.util.ThrowingSupplier;

/**
 * Verifies mutation delegation, obtruded outcomes and executor selection of {@link ExtendedFuture.WrappingFuture},
 * including nested and read-only views, overlapping mutations and interruption-tracking overhead of private observations.
 *
 * @author <a href="https://sebthom.de/">Sebastian Thomschke</a>
 */
@SuppressWarnings("javadoc")
class WrappingFutureDelegationTest extends AbstractFutureTest {

   /** Records stage creation on the test thread to distinguish private observations from tracked public callbacks. */
   private static final class RegistrationObservingFuture extends ExtendedFuture<String> {
      boolean recordStageCreation;
      int createdStages;
      int trackedStages;

      @Override
      @SuppressWarnings("resource") // This recorder borrows the construction scope; it must not close it.
      public <U> ExtendedFuture<U> newIncompleteFuture() {
         // The factory consumes ACTIVE, so this observation cannot move into the later recording block.
         final var binding = ExecutionBinding.ACTIVE.get(); // CHECKSTYLE:IGNORE MoveVariableInsideIf
         final ExtendedFuture<U> stage = super.newIncompleteFuture();
         if (recordStageCreation) {
            createdStages++;
            // Completed-source callbacks consume registrations before handle returns; inspect before the callback can run.
            // Capture before super consumes the scope, then match the owner to reject suspended/private observations.
            if (binding != null && binding.owner == stage) {
               trackedStages++;
            }
         }
         return stage;
      }
   }

   /** Pauses one captured outcome before the wrapper can copy it, without holding a lock around user callbacks. */
   private static final class PausingFuture extends CompletableFuture<String> {
      final AtomicInteger mutations = new AtomicInteger();
      final AtomicBoolean snapshotPaused = new AtomicBoolean();
      final CountDownLatch snapshotOrCompletion = new CountDownLatch(1);
      final CountDownLatch releaseSnapshot = new CountDownLatch(1);
      volatile @Nullable Thread pausedThread;

      @Override
      public <U> CompletableFuture<U> handle(final BiFunction<? super String, @Nullable Throwable, ? extends U> action) {
         return super.handle((value, error) -> {
            if (Thread.currentThread() == pausedThread && snapshotPaused.compareAndSet(false, true)) {
               // The JDK has already captured value/error. A competing mutation must finish before that snapshot is copied.
               snapshotOrCompletion.countDown();
               try {
                  assertThat(releaseSnapshot.await(MAX_WAIT_SECS * 3, TimeUnit.SECONDS)).isTrue();
               } catch (final InterruptedException ex) {
                  Thread.currentThread().interrupt();
                  throw new AssertionError("Snapshot observation interrupted", ex);
               }
            }
            return action.apply(value, error);
         });
      }

      @Override
      public void obtrudeException(final Throwable error) {
         mutations.incrementAndGet();
         super.obtrudeException(error);
      }

      @Override
      public void obtrudeValue(final String value) {
         mutations.incrementAndGet();
         super.obtrudeValue(value);
      }
   }

   private static void assertFailure(final CompletableFuture<?> future, final Throwable failure) {
      // A CompletionException caused by cancellation is still a failure when explicitly obtruded, not a raw cancellation.
      assertThat(CompletionState.of(future)).isEqualTo(failure instanceof CancellationException //
            ? CompletionState.CANCELLED
            : CompletionState.FAILED);
      assertThat(future.handle((value, error) -> error).join()).isSameAs(failure);
      assertThatThrownBy(future::get).isInstanceOf(failure instanceof CancellationException //
            ? CancellationException.class
            : ExecutionException.class);
      assertThatThrownBy(future::join).isInstanceOf(failure instanceof CancellationException //
            ? CancellationException.class
            : CompletionException.class);
      assertThatThrownBy(() -> future.thenApply(Function.identity()).join()).isInstanceOf(CompletionException.class);
   }

   private static void assertSuccess(final CompletableFuture<@Nullable String> future, final @Nullable String value) throws Exception {
      assertThat(CompletionState.of(future)).isEqualTo(CompletionState.SUCCESS);
      assertThat(future.get(MAX_WAIT_SECS, TimeUnit.SECONDS)).isSameAs(value);
      assertThat(future.join()).isSameAs(value);
      assertThat(future.getNow("absent")).isSameAs(value);
      assertThat(future.thenApply(Function.identity()).join()).isSameAs(value);
   }

   private static void completeInitially(final CompletableFuture<@Nullable String> future, final CompletionState state) {
      switch (state) {
         case SUCCESS:
            future.complete("initial");
            break;
         case FAILED:
            future.completeExceptionally(new IllegalStateException("initial"));
            break;
         case CANCELLED:
            future.cancel(false);
            break;
         case INCOMPLETE:
            break;
      }
   }

   private static Executor countingExecutor(final AtomicInteger calls) {
      return task -> {
         calls.incrementAndGet();
         task.run();
      };
   }

   @Test
   void cancel_delegates_to_wrapped() throws Exception {
      final var base = new ExtendedFuture<String>();
      final var wrapper = newWrapper(base);

      final boolean res = wrapper.cancel(true);
      assertThat(res).isTrue();

      // Wrapped and wrapper must both become CANCELLED
      awaitFutureState(base, CompletionState.CANCELLED);
      assertThat(base).isCancelled();
      awaitFutureState(wrapper, CompletionState.CANCELLED);
      assertThat(wrapper).isCancelled();
   }

   @Test
   void complete_delegates_to_wrapped() throws Exception {
      final var base = new ExtendedFuture<String>();
      final var wrapper = newWrapper(base);

      final boolean res = wrapper.complete("complete");
      assertThat(res).isTrue();

      awaitFutureState(base, CompletionState.SUCCESS);
      assertThat(base).isCompletedWithValue("complete");
      awaitFutureState(wrapper, CompletionState.SUCCESS);
      assertThat(wrapper).isCompletedWithValue("complete");
   }

   @Test
   void completeAsync_supplier_delegates_to_wrapped() throws Exception {
      final var base = new ExtendedFuture<String>();
      final var wrapper = newWrapper(base);

      // An untyped lambda selects ThrowingSupplier and would leave this standard overload untested.
      final var ret = wrapper.completeAsync((Supplier<String>) () -> "completeAsync");
      assertThat(ret).isSameAs(wrapper);

      awaitFutureState(base, CompletionState.SUCCESS);
      assertThat(base).isCompletedWithValue("completeAsync");
      awaitFutureState(wrapper, CompletionState.SUCCESS);
      assertThat(wrapper).isCompletedWithValue("completeAsync");
   }

   @Test
   void completeAsync_supplier_executor_delegates_to_wrapped() throws Exception {
      final var base = new ExtendedFuture<String>();
      final var wrapper = newWrapper(base);

      // Keep this distinct from the ThrowingSupplier test below.
      final var ret = wrapper.completeAsync((Supplier<String>) () -> "completeAsync2", Runnable::run);
      assertThat(ret).isSameAs(wrapper);

      awaitFutureState(base, CompletionState.SUCCESS);
      assertThat(base).isCompletedWithValue("completeAsync2");
      awaitFutureState(wrapper, CompletionState.SUCCESS);
      assertThat(wrapper).isCompletedWithValue("completeAsync2");
   }

   @Test
   void completeAsync_throwingSupplier_delegates_to_wrapped() throws Exception {
      final var base = new ExtendedFuture<String>();
      final var wrapper = newWrapper(base);

      final var ret = wrapper.completeAsync((io.github.futures4j.util.ThrowingSupplier<String, ?>) () -> "completeAsync3");
      assertThat(ret).isSameAs(wrapper);

      awaitFutureState(base, CompletionState.SUCCESS);
      assertThat(base).isCompletedWithValue("completeAsync3");
      awaitFutureState(wrapper, CompletionState.SUCCESS);
      assertThat(wrapper).isCompletedWithValue("completeAsync3");
   }

   @Test
   void completeAsync_throwingSupplier_executor_delegates_to_wrapped() throws Exception {
      final var base = new ExtendedFuture<String>();
      final var wrapper = newWrapper(base);

      final var ret = wrapper.completeAsync((io.github.futures4j.util.ThrowingSupplier<String, ?>) () -> "completeAsync4", Runnable::run);
      assertThat(ret).isSameAs(wrapper);

      awaitFutureState(base, CompletionState.SUCCESS);
      assertThat(base).isCompletedWithValue("completeAsync4");
      awaitFutureState(wrapper, CompletionState.SUCCESS);
      assertThat(wrapper).isCompletedWithValue("completeAsync4");
   }

   @ParameterizedTest
   @CsvSource({"false, false", "false, true", "true, false", "true, true"})
   void completeAsync_selects_the_configured_executor(final boolean throwing, final boolean explicit) throws Exception {
      for (final boolean plainBacking : List.of(false, true)) {
         for (final boolean nested : List.of(false, true)) {
            final var oldCalls = new AtomicInteger();
            final var defaultCalls = new AtomicInteger();
            final var explicitCalls = new AtomicInteger();
            final var oldExecutor = countingExecutor(oldCalls);
            final var defaultExecutor = countingExecutor(defaultCalls);
            final var explicitExecutor = countingExecutor(explicitCalls);
            final CompletableFuture<String> base = plainBacking ? new CompletableFuture<>() //
                  : ExtendedFuture.builder(String.class).withDefaultExecutor(oldExecutor).build();
            final var configured = plainBacking //
                  ? ExtendedFuture.builder(String.class).withWrapped(base).withDefaultExecutor(defaultExecutor).build()
                  : ((ExtendedFuture<String>) base).withDefaultExecutor(defaultExecutor);
            final var wrapper = nested ? configured.withInterruptibleStages(false) : configured;

            // Explicit types are required: otherwise both branches would call the throwing overload.
            final Supplier<String> supplier = () -> "value";
            final ThrowingSupplier<String, ?> throwingSupplier = () -> "value";
            final var returned = throwing //
                  ? explicit ? wrapper.completeAsync(throwingSupplier, explicitExecutor) : wrapper.completeAsync(throwingSupplier)
                  : explicit ? wrapper.completeAsync(supplier, explicitExecutor) : wrapper.completeAsync(supplier);

            assertThat(returned).isSameAs(wrapper);
            assertThat(wrapper.defaultExecutor()).isSameAs(defaultExecutor);
            assertThat(oldCalls).hasValue(0);
            assertThat(defaultCalls).hasValue(explicit ? 0 : 1);
            assertThat(explicitCalls).hasValue(explicit ? 1 : 0);
            assertThat(base.get(MAX_WAIT_SECS, TimeUnit.SECONDS)).isEqualTo("value");
            assertThat(configured.get(MAX_WAIT_SECS, TimeUnit.SECONDS)).isEqualTo("value");
            assertThat(wrapper.get(MAX_WAIT_SECS, TimeUnit.SECONDS)).isEqualTo("value");
         }
      }
   }

   @ParameterizedTest
   @ValueSource(booleans = {false, true})
   void completeAsync_preserves_checked_supplier_failures(final boolean explicit) {
      final var base = new ExtendedFuture<String>();
      final var calls = new AtomicInteger();
      final var executor = countingExecutor(calls);
      final var wrapper = base.withDefaultExecutor(executor);
      final var failure = new IOException("checked failure");
      final ThrowingSupplier<String, Exception> supplier = () -> {
         throw failure;
      };

      final var returned = explicit ? wrapper.completeAsync(supplier, executor) : wrapper.completeAsync(supplier);

      assertThat(returned).isSameAs(wrapper);
      assertThat(calls).hasValue(1);
      for (final var future : List.of(base, wrapper)) {
         assertThatThrownBy(future::join).isInstanceOf(CompletionException.class).rootCause().isSameAs(failure);
      }
   }

   @Test
   void completeAsync_preserves_validation_and_executor_rejection() {
      final var base = new ExtendedFuture<String>();
      final var rejection = new RejectedExecutionException("rejected");
      final var wrapper = base.withDefaultExecutor(task -> {
         throw rejection;
      });
      final Supplier<String> supplier = () -> "unused";
      final ThrowingSupplier<String, ?> throwingSupplier = () -> "unused";
      final Supplier<String> nullSupplier = sneakyNull();
      final ThrowingSupplier<String, ?> nullThrowingSupplier = sneakyNull();

      assertThatNullPointerException().isThrownBy(() -> wrapper.completeAsync(nullSupplier));
      assertThatNullPointerException().isThrownBy(() -> wrapper.completeAsync(nullThrowingSupplier));
      assertThatNullPointerException().isThrownBy(() -> wrapper.completeAsync(supplier, sneakyNull()));
      assertThatNullPointerException().isThrownBy(() -> wrapper.completeAsync(throwingSupplier, sneakyNull()));
      assertThatThrownBy(() -> wrapper.completeAsync(supplier)).isSameAs(rejection);
      assertThatThrownBy(() -> wrapper.completeAsync(throwingSupplier)).isSameAs(rejection);
      assertThat(base).isNotCompleted();
      assertThat(wrapper).isNotCompleted();
   }

   @ParameterizedTest
   @EnumSource(ReadOnlyMode.class)
   void completeAsync_preserves_read_only_views(final ReadOnlyMode mode) {
      final var base = new ExtendedFuture<String>();
      final var calls = new AtomicInteger();
      final var executor = countingExecutor(calls);
      final var wrapper = base.asReadOnly(mode).withDefaultExecutor(executor).withInterruptibleStages(false);
      final Supplier<String> supplier = () -> "forbidden";
      final ThrowingSupplier<String, ?> throwingSupplier = () -> "forbidden";
      final List<Runnable> attempts = List.of(() -> wrapper.completeAsync(supplier), () -> wrapper.completeAsync(throwingSupplier),
         () -> wrapper.completeAsync(supplier, executor), () -> wrapper.completeAsync(throwingSupplier, executor));
      for (final var attempt : attempts) {
         if (mode == ReadOnlyMode.THROW_ON_MUTATION) {
            assertThatThrownBy(attempt::run).isInstanceOf(UnsupportedOperationException.class);
         } else {
            attempt.run();
         }
      }
      assertThat(calls).hasValue(0);
      assertThat(base).isNotCompleted();
      assertThat(wrapper).isNotCompleted();
   }

   @Test
   void completeExceptionally_delegates_to_wrapped() throws Exception {
      final var base = new ExtendedFuture<String>();
      final var wrapper = newWrapper(base);

      final boolean res = wrapper.completeExceptionally(new RuntimeException("completeExceptionally"));
      assertThat(res).isTrue();

      awaitFutureState(base, CompletionState.FAILED);
      assertThat(base).isCompletedExceptionally();
      awaitFutureState(wrapper, CompletionState.FAILED);
      assertThat(wrapper).isCompletedExceptionally();
   }

   @Test
   void completeOnTimeout_delegates_to_wrapped() throws Exception {
      final var base = new ExtendedFuture<String>();
      final var wrapper = newWrapper(base);

      final var order = new java.util.concurrent.CopyOnWriteArrayList<String>();
      base.whenComplete((r, ex) -> order.add("base:" + (ex == null)));
      wrapper.whenComplete((r, ex) -> order.add("wrapper:" + (ex == null)));

      // If delegation is missing, wrapper completes first, then base (via overridden complete)
      // If delegation exists, base completes first, then wrapper (via whenComplete)
      wrapper.completeOnTimeout("value", 0, TimeUnit.MILLISECONDS);

      await(() -> order.size() == 2);
      assertThat(order).containsExactly("base:true", "wrapper:true");
   }

   @Test
   void completeWith_delegates_to_wrapped() throws Exception {
      final var base = new ExtendedFuture<String>();
      final var wrapper = newWrapper(base);

      final var src = new java.util.concurrent.CompletableFuture<String>();
      final var ret = wrapper.completeWith(src);
      assertThat(ret).isSameAs(wrapper);

      src.complete("completeWith");

      awaitFutureState(base, CompletionState.SUCCESS);
      assertThat(base).isCompletedWithValue("completeWith");
      awaitFutureState(wrapper, CompletionState.SUCCESS);
      assertThat(wrapper).isCompletedWithValue("completeWith");
   }

   private ExtendedFuture<String> newWrapper(final ExtendedFuture<String> base) {
      // Force creation of a WrappingFuture by changing the default executor
      final var wrapper = base.withDefaultExecutor(Runnable::run);
      assertThat(wrapper).isNotSameAs(base);
      return wrapper;
   }

   @Test
   void obtrudeException_delegates_to_wrapped() throws Exception {
      final var base = new ExtendedFuture<String>();
      final var wrapper = newWrapper(base);

      wrapper.obtrudeException(new RuntimeException("obtrudeException"));

      awaitFutureState(base, CompletionState.FAILED);
      assertThat(base).isCompletedExceptionally();
      assertThat(wrapper).isCompletedExceptionally();
   }

   @Test
   void obtrudeValue_delegates_to_wrapped() throws Exception {
      final var base = new ExtendedFuture<String>();
      final var wrapper = newWrapper(base);

      wrapper.obtrudeValue("obtrudeValue");

      awaitFutureState(base, CompletionState.SUCCESS);
      assertThat(base).isCompletedWithValue("obtrudeValue");
      assertThat(wrapper).isCompletedWithValue("obtrudeValue");
   }

   @ParameterizedTest
   @ValueSource(booleans = {false, true})
   void obtrusion_does_not_register_private_observations(final boolean exceptional) {
      final var base = new RegistrationObservingFuture();
      base.complete("initial");
      final var wrapper = newWrapper(base);
      final var failure = new CompletionException(new CancellationException("replacement"));

      // The constructor's completion observer and the outcome assertions legitimately create tracked stages.
      base.recordStageCreation = true;
      try {
         if (exceptional) {
            wrapper.obtrudeException(failure);
         } else {
            wrapper.obtrudeValue("replacement");
         }
      } finally {
         base.recordStageCreation = false;
      }

      assertThat(base.createdStages).as("the backing stage factory must still observe the private handles").isPositive();
      assertThat(base.trackedStages).as("private observations must bypass interruption registration").isZero();
      for (final var future : List.of(base, wrapper)) {
         if (exceptional) {
            assertFailure(future, failure);
         } else {
            assertThat(future.join()).isEqualTo("replacement");
         }
      }
   }

   @Test
   void public_handle_still_registers_interruptible_stages() {
      final var base = new RegistrationObservingFuture();
      base.complete("initial");
      base.recordStageCreation = true;
      final ExtendedFuture<String> stage;
      try {
         stage = base.handle((value, error) -> "handled");
      } finally {
         base.recordStageCreation = false;
      }

      // A separate positive control proves the recorder sees short-lived registrations even before the bypass is implemented.
      assertThat(base.createdStages).isEqualTo(1);
      assertThat(base.trackedStages).isEqualTo(1);
      assertThat(stage.isInterruptible()).isTrue();
      assertThat(stage.join()).isEqualTo("handled");
   }

   @ParameterizedTest
   @EnumSource(CompletionState.class)
   void obtrusion_replaces_completed_outcomes_and_new_dependents(final CompletionState initialState) throws Exception {
      final var base = new CompletableFuture<@Nullable String>();
      completeInitially(base, initialState);
      final var inner = ExtendedFuture.from(base);
      final var middle = inner.withInterruptibleStages(false);
      final var wrapper = middle.withDefaultExecutor(Runnable::run);
      final var views = List.of(base, inner, middle, wrapper);
      final var oldCallbackCalls = new AtomicInteger();
      final var oldDependent = wrapper.handle((value, error) -> oldCallbackCalls.incrementAndGet());

      wrapper.obtrudeValue("replacement");
      for (final var view : views) {
         assertSuccess(view, "replacement");
      }
      for (final var failure : List.of(new IllegalStateException("replacement"), new CompletionException(new IOException("replacement")),
         new CancellationException("replacement"), new CompletionException(new CancellationException("wrapped cancellation")))) {
         wrapper.obtrudeException(failure);
         for (final var view : views) {
            assertFailure(view, failure);
         }
      }
      wrapper.obtrudeValue(null);
      for (final var view : views) {
         assertSuccess(view, null);
      }
      // Obtrusion replaces the receiver, not dependents that have already consumed an earlier outcome.
      assertThat(oldDependent.join()).isEqualTo(1);
      assertThat(oldCallbackCalls).hasValue(1);
   }

   @Test
   void obtrudeException_rejects_null_before_changing_the_wrapper() {
      final var base = CompletableFuture.completedFuture("initial");
      final var wrapper = ExtendedFuture.from(base).withDefaultExecutor(Runnable::run);

      assertThatNullPointerException().isThrownBy(() -> wrapper.obtrudeException(sneakyNull()));

      assertThat(base).isCompletedWithValue("initial");
      assertThat(wrapper).isCompletedWithValue("initial");
   }

   @ParameterizedTest
   @EnumSource(ReadOnlyMode.class)
   void obtrusion_preserves_read_only_views(final ReadOnlyMode mode) throws Exception {
      for (final var initialState : CompletionState.values()) {
         final var base = new ExtendedFuture<@Nullable String>();
         completeInitially(base, initialState);
         final var readOnly = base.asReadOnly(mode);
         final var wrapper = readOnly.withDefaultExecutor(Runnable::run).withInterruptibleStages(false);
         final var dependentsBefore = readOnly.getNumberOfDependents();
         final List<Runnable> attempts = List.of(() -> wrapper.obtrudeValue("forbidden"), () -> wrapper.obtrudeException(
            new IllegalStateException("forbidden")), () -> wrapper.obtrudeException(sneakyNull()));
         for (final var attempt : attempts) {
            if (mode == ReadOnlyMode.THROW_ON_MUTATION) {
               assertThatThrownBy(attempt::run).isInstanceOf(UnsupportedOperationException.class);
            } else {
               attempt.run();
            }
            for (final var view : List.of(base, readOnly, wrapper)) {
               assertThat(CompletionState.of(view)).isEqualTo(initialState);
            }
         }
         // Ignored mutations must not attach observers that wait for a later, unrelated completion.
         assertThat(readOnly.getNumberOfDependents()).isEqualTo(dependentsBefore);
         if (initialState == CompletionState.INCOMPLETE) {
            base.complete("actual");
            assertSuccess(wrapper, "actual");
         } else if (initialState == CompletionState.SUCCESS) {
            assertSuccess(wrapper, "initial");
         } else if (initialState == CompletionState.FAILED) {
            assertThat(wrapper.handle((value, error) -> error).join()).isSameAs(base.handle((value, error) -> error).join());
         }
      }
   }

   @ParameterizedTest
   @CsvSource({"false, false", "false, true", "true, false", "true, true"})
   void obtrusion_preserves_reentrant_replacement(final boolean firstExceptional, final boolean replacementExceptional) throws Exception {
      final var base = new CompletableFuture<@Nullable String>();
      final var inner = ExtendedFuture.from(base);
      final var wrapper = inner.withDefaultExecutor(Runnable::run);
      final var replacementFailure = new IllegalArgumentException("replacement");
      final var observer = wrapper.handle((value, error) -> {
         if (replacementExceptional) {
            wrapper.obtrudeException(replacementFailure);
         } else {
            wrapper.obtrudeValue("replacement");
         }
         return "observed";
      });

      if (firstExceptional) {
         wrapper.obtrudeException(new IllegalStateException("first"));
      } else {
         wrapper.obtrudeValue("first");
      }

      assertThat(observer.join()).isEqualTo("observed");
      for (final var view : List.of(base, inner, wrapper)) {
         if (replacementExceptional) {
            assertFailure(view, replacementFailure);
         } else {
            assertSuccess(view, "replacement");
         }
      }
   }

   @ParameterizedTest
   @CsvSource({"false, false", "false, true", "true, false", "true, true"})
   void obtrusion_rechecks_a_snapshot_after_a_competing_mutation(final boolean firstExceptional, final boolean secondExceptional)
         throws Exception {
      for (final boolean nested : List.of(false, true)) {
         final var base = new PausingFuture();
         base.complete("initial");
         final var inner = ExtendedFuture.from(base);
         final var wrapper = nested ? inner.withDefaultExecutor(Runnable::run) : inner;
         // Equal but distinct values expose an equals-based check that would keep the wrong result instance.
         final var firstValue = new String("replacement");
         final var secondValue = new String("replacement");
         final var secondFailure = new CompletionException(new CancellationException("second"));
         final var pool = Executors.newFixedThreadPool(2);
         try {
            final var first = pool.submit(() -> {
               base.pausedThread = Thread.currentThread();
               try {
                  if (firstExceptional) {
                     wrapper.obtrudeException(new IllegalStateException("first"));
                  } else {
                     wrapper.obtrudeValue(firstValue);
                  }
               } finally {
                  // Before the fix there is no observation to pause; report that missing ordering without waiting for a timeout.
                  base.snapshotOrCompletion.countDown();
               }
            });
            assertThat(base.snapshotOrCompletion.await(MAX_WAIT_SECS, TimeUnit.SECONDS)).isTrue();
            assertThat(base.snapshotPaused).as("the first captured outcome must be held before the competing mutation").isTrue();
            final var second = pool.submit(() -> {
               if (secondExceptional) {
                  wrapper.obtrudeException(secondFailure);
               } else {
                  wrapper.obtrudeValue(secondValue);
               }
            });
            second.get(MAX_WAIT_SECS, TimeUnit.SECONDS);
            base.releaseSnapshot.countDown();
            first.get(MAX_WAIT_SECS, TimeUnit.SECONDS);

            for (final var view : List.of(base, inner, wrapper)) {
               if (secondExceptional) {
                  assertFailure(view, secondFailure);
               } else {
                  assertThat(view.join()).isSameAs(secondValue);
                  assertThat(view.thenApply(Function.identity()).join()).isSameAs(secondValue);
               }
            }
            // Retrying synchronization must not replay the older mutation against the backing future.
            assertThat(base.mutations).hasValue(2);
         } finally {
            base.releaseSnapshot.countDown();
            pool.shutdownNow();
            assertThat(pool.awaitTermination(MAX_WAIT_SECS, TimeUnit.SECONDS)).isTrue();
         }
      }
   }

   @Test
   void orTimeout_delegates_to_wrapped() throws Exception {
      final var base = new ExtendedFuture<String>();
      final var wrapper = newWrapper(base);

      final var order = new java.util.concurrent.CopyOnWriteArrayList<String>();
      base.whenComplete((r, ex) -> order.add("base:" + (ex == null)));
      wrapper.whenComplete((r, ex) -> order.add("wrapper:" + (ex == null)));

      wrapper.orTimeout(0, TimeUnit.MILLISECONDS);

      await(() -> order.size() == 2);
      assertThat(order).containsExactly("base:false", "wrapper:false");
   }
}
