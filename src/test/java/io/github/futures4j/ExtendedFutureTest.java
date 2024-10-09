/*
 * SPDX-FileCopyrightText: © Sebastian Thomschke
 * SPDX-License-Identifier: EPL-2.0
 * SPDX-FileContributor: Sebastian Thomschke: initial API and implementation
 */
package io.github.futures4j;

import static io.github.futures4j.AbstractFutureTest.TaskState.INTERRUPTED;
import static io.github.futures4j.CompletionState.CANCELLED;
import static net.sf.jstuff.core.validation.NullAnalysisHelper.asNonNull;
import static org.assertj.core.api.Assertions.*;

import java.io.IOException;
import java.lang.ref.WeakReference;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.eclipse.jdt.annotation.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import io.github.futures4j.ExtendedFuture.ReadOnlyMode;
import net.sf.jstuff.core.ref.MutableRef;

/**
 * @author <a href="https://sebthom.de/">Sebastian Thomschke</a>
 */
class ExtendedFutureTest extends AbstractFutureTest {

   private static final class TrackingExecutor implements Executor {
      private final AtomicBoolean executed = new AtomicBoolean(false);
      private final ExecutorService delegate = Executors.newSingleThreadExecutor();

      @Override
      public void execute(final Runnable command) {
         executed.set(true);
         delegate.execute(command);
      }

      boolean hasExecuted() {
         try {
            return executed.get();
         } finally {
            executed.set(false);
         }
      }

      void shutdown() {
         delegate.shutdown();
      }
   }

   final TrackingExecutor executor = new TrackingExecutor();

   @AfterEach
   void tearDown() {
      executor.shutdown();
   }

   @Test
   void testAcceptEither() {
      for (final var interruptibleStages : List.of(true, false)) {
         final var ref = MutableRef.of(null);
         final var future1 = new ExtendedFuture<String>().withInterruptibleStages(interruptibleStages);
         final CompletableFuture<String> future2 = CompletableFuture.completedFuture("Result from future2");

         var resultFuture = future1.acceptEither(future2, ref::set);
         resultFuture.join();
         assertThat(ref.get()).isEqualTo("Result from future2");

         resultFuture = future1.acceptEitherAsync(future2, ref::set);
         resultFuture.join();
         assertThat(ref.get()).isEqualTo("Result from future2");

         future1.complete("Result from future1");

         resultFuture = future1.acceptEither(future2, ref::set);
         resultFuture.join();
         assertThat(ref.get()).isEqualTo("Result from future1");

         resultFuture = future1.acceptEitherAsync(future2, ref::set);
         resultFuture.join();
         assertThat(ref.get()).isEqualTo("Result from future1");

         resultFuture = future1.acceptEitherAsync(future2, ref::set, executor);
         resultFuture.join();
         assertThat(ref.get()).isEqualTo("Result from future1");
         assertThat(executor.hasExecuted()).isTrue();
      }
   }

   @Test
   void testApplyToEither() {
      for (final var interruptibleStages : List.of(true, false)) {
         final var future1 = new ExtendedFuture<String>().withInterruptibleStages(interruptibleStages);
         final CompletableFuture<String> future2 = CompletableFuture.completedFuture("Result from future2");

         var resultFuture = future1.applyToEither(future2, result -> "First completed: " + result);
         assertThat(resultFuture.join()).isEqualTo("First completed: Result from future2");

         resultFuture = future1.applyToEitherAsync(future2, result -> "First completed: " + result);
         assertThat(resultFuture.join()).isEqualTo("First completed: Result from future2");

         future1.complete("Result from future1");

         resultFuture = future1.applyToEither(future2, result -> "First completed: " + result);
         assertThat(resultFuture.join()).isEqualTo("First completed: Result from future1");

         resultFuture = future1.applyToEitherAsync(future2, result -> "First completed: " + result);
         assertThat(resultFuture.join()).isEqualTo("First completed: Result from future1");

         resultFuture = future1.applyToEitherAsync(future2, result -> "First completed: " + result, executor);
         assertThat(resultFuture.join()).isEqualTo("First completed: Result from future1");
         assertThat(executor.hasExecuted()).isTrue();
      }
   }

   @Test
   void testAsReadOnly_withIgnoreMutationAttempt() {
      final var originalFuture = new ExtendedFuture<>();
      final var readOnlyFuture = originalFuture.asReadOnly(ReadOnlyMode.IGNORE_MUTATION);

      assertThat(readOnlyFuture.complete("Hello")).isFalse();
      assertThat(readOnlyFuture.cancel(true)).isFalse();
      assertThat(readOnlyFuture.completeExceptionally(new RuntimeException("Test Exception"))).isFalse();

      // ensure read-only future can still perform non-mutating operations like get() or join()
      originalFuture.complete("World");
      assertThat(readOnlyFuture.join()).isEqualTo("World");
   }

   @Test
   @SuppressWarnings("null")
   void testAsReadOnly_withThrowOnMutationAttempt() {
      final var originalFuture = new ExtendedFuture<>();
      final var readOnlyFuture = originalFuture.asReadOnly(ReadOnlyMode.THROW_ON_MUTATION);

      assertThatThrownBy(() -> readOnlyFuture.complete("Hello")) //
         .isInstanceOf(UnsupportedOperationException.class) //
         .hasMessageContaining("is read-only");

      assertThatThrownBy(() -> readOnlyFuture.cancel(true)) //
         .isInstanceOf(UnsupportedOperationException.class) //
         .hasMessageContaining("is read-only");

      assertThatThrownBy(() -> readOnlyFuture.completeExceptionally(new RuntimeException("Test Exception"))) //
         .isInstanceOf(UnsupportedOperationException.class) //
         .hasMessageContaining("is read-only");

      // ensure read-only future can still perform non-mutating operations like get() or join()
      originalFuture.complete("Hello");
      assertThat(readOnlyFuture.join()).isEqualTo("Hello");
   }

   @Test
   void testBuilder() {

      {
         final var future = ExtendedFuture.builder(String.class).build();

         assertThat(future).isInstanceOf(ExtendedFuture.InterruptibleFuture.class);
         assertThat(future.isInterruptible()).isTrue();
         assertThat(future.isInterruptibleStages()).isTrue();
         assertThat(future.isCancellableByDependents()).isFalse();
         assertThat(future.defaultExecutor()).isNotNull();
         assertThat(future.isIncomplete()).isTrue();
      }

      {
         final Executor customExecutor = Executors.newSingleThreadExecutor();
         final CompletableFuture<String> wrappedFuture = CompletableFuture.completedFuture("Hello");

         final var future = ExtendedFuture.builder(String.class) //
            .withInterruptible(false) //
            .withInterruptibleStages(false) //
            .withCancellableByDependents(true) //
            .withDefaultExecutor(customExecutor) //
            .withWrapped(wrappedFuture) //
            .withCompletedValue("Hey") //
            .build();

         assertThat(future).isNotInstanceOf(ExtendedFuture.InterruptibleFuture.class);
         assertThat(future.isInterruptible()).isFalse();
         assertThat(future.isInterruptibleStages()).isFalse();
         assertThat(future.isCancellableByDependents()).isTrue();
         assertThat(future.defaultExecutor()).isEqualTo(customExecutor);
         assertThat(future).isCompletedWithValue("Hello");
      }
   }

   @Test
   void testComplete() {
      for (final var interruptibleStages : List.of(true, false)) {
         var future = new ExtendedFuture<String>().withInterruptibleStages(interruptibleStages);
         future.complete("Result");
         assertThat(future.join()).isEqualTo("Result");

         future = new ExtendedFuture<String>().withInterruptibleStages(interruptibleStages);
         future.completeAsync(() -> "Result");
         assertThat(future.join()).isEqualTo("Result");

         future = new ExtendedFuture<String>().withInterruptibleStages(interruptibleStages);
         future.completeAsync(() -> "Result", executor);
         assertThat(future.join()).isEqualTo("Result");
         assertThat(executor.hasExecuted()).isTrue();
      }
   }

   @Test
   void testCompletedFuture() throws InterruptedException {
      testCompletedFuture(ExtendedFuture::new, ExtendedFuture::completedFuture);
      testCompletedFuture(ExtendedFuture::new, value -> ExtendedFuture.from(CompletableFuture.completedFuture(value))
         .asCancellableByDependents(true));

      testCompletedFuture(() -> ExtendedFuture.from(new CompletableFuture<String>()), value -> ExtendedFuture.from(CompletableFuture
         .completedFuture(value)));

      assertThat(ExtendedFuture.from(CompletableFuture.completedFuture(null)).isInterruptible()).isFalse();
      assertThat(ExtendedFuture.from(CompletableFuture.completedFuture(null)).isInterruptibleStages()).isTrue();
   }

   @Test
   void testCompleteWith() {
      final var future1 = new ExtendedFuture<String>();
      final CompletableFuture<String> future2 = CompletableFuture.completedFuture("Result");
      future1.completeWith(future2);

      assertThat(future1.join()).isEqualTo("Result");
   }

   @Test
   void testCopy() {
      testCopy(ExtendedFuture::new);
   }

   @Test
   void testCustomDefaultExecutor() {
      final var customExecutor = Executors.newSingleThreadExecutor();
      final var future = ExtendedFuture.supplyAsyncWithDefaultExecutor(() -> Thread.currentThread().getName(), customExecutor);

      final var threadName = future.join();
      assertThat(threadName).contains("pool-");
      customExecutor.shutdown();
   }

   @Test
   void testExceptionally() {
      final var failedFuture = ExtendedFuture.failedFuture(new RuntimeException("Initial failure"));

      var future = failedFuture.exceptionally(ex -> "Recovered from " + ex.getMessage());
      assertThat(future.join()).isEqualTo("Recovered from Initial failure");

      future = failedFuture.exceptionallyAsync(ex -> "Recovered from " + ex.getMessage());
      assertThat(future.join()).isEqualTo("Recovered from Initial failure");

      future = failedFuture.exceptionallyAsync(ex -> "Recovered from " + ex.getMessage(), executor);
      assertThat(future.join()).isEqualTo("Recovered from Initial failure");
      assertThat(executor.hasExecuted()).isTrue();
   }

   @Test
   void testExceptionallyCompose() {
      final var failedFuture = ExtendedFuture.failedFuture(new RuntimeException("Initial failure"));

      var future = failedFuture.exceptionallyCompose(ex -> CompletableFuture.completedFuture("Recovered from " + ex.getMessage()));
      assertThat(future.join()).isEqualTo("Recovered from Initial failure");

      future = failedFuture.exceptionallyComposeAsync(ex -> CompletableFuture.completedFuture("Recovered from " + ex.getMessage()));
      assertThat(future.join()).isEqualTo("Recovered from Initial failure");

      future = failedFuture.exceptionallyComposeAsync(ex -> CompletableFuture.completedFuture("Recovered from " + ex.getMessage()),
         executor);
      assertThat(future.join()).isEqualTo("Recovered from Initial failure");
      assertThat(executor.hasExecuted()).isTrue();
   }

   @Test
   void testExceptionNow() {
      final var incompleteFuture = new ExtendedFuture<String>();
      final var completedFuture = ExtendedFuture.completedFuture("Success");
      final var failedFuture = ExtendedFuture.failedFuture(new IOException());
      final var cancelledFuture = new ExtendedFuture<String>();
      cancelledFuture.cancel(true);

      assertThatThrownBy(completedFuture::exceptionNow).isInstanceOf(IllegalStateException.class);
      assertThatThrownBy(incompleteFuture::exceptionNow).isInstanceOf(IllegalStateException.class);
      assertThat(failedFuture.exceptionNow()).isInstanceOf(IOException.class);
      assertThatThrownBy(cancelledFuture::exceptionNow).isInstanceOf(IllegalStateException.class);
   }

   @Test
   void testForwardCancellation() {
      final var future1 = new ExtendedFuture<>();
      final var future2 = new ExtendedFuture<>();
      future1.forwardCancellation(future2);

      future1.cancel(true);

      assertThat(future1.isCancelled()).isTrue();
      assertThat(future2.isCancelled()).isTrue();
   }

   @Test
   void testCompletionState() {
      final var cancelledFuture = new ExtendedFuture<>();
      cancelledFuture.cancel(true);
      assertThat(CompletionState.of(cancelledFuture)).isEqualTo(CANCELLED);

      final var exceptionallyCompletedFuture = new ExtendedFuture<>();
      exceptionallyCompletedFuture.completeExceptionally(new RuntimeException("Error"));
      assertThat(CompletionState.of(exceptionallyCompletedFuture)).isEqualTo(CompletionState.FAILED);

      final var completedFuture = ExtendedFuture.completedFuture("Success");
      assertThat(CompletionState.of(completedFuture)).isEqualTo(CompletionState.SUCCESS);

      final var incompleteFuture = new ExtendedFuture<>();
      assertThat(CompletionState.of(incompleteFuture)).isEqualTo(CompletionState.INCOMPLETE);
   }

   @Test
   void testGetNowOptional() {
      final var future = new ExtendedFuture<String>();

      var result = future.getNowOptional();
      assertThat(result).isEmpty();

      future.complete("Completed");
      result = future.getNowOptional();
      assertThat(result).contains("Completed");

      final ExtendedFuture<String> failed = ExtendedFuture.failedFuture(new RuntimeException());
      result = failed.getNowOptional();
      assertThat(result).isEmpty();
   }

   @Test
   void testGetNowOrComputeFallback() {
      final var future = new ExtendedFuture<String>();
      var result = future.getNowOrComputeFallback(ex -> "Fallback");
      assertThat(result).isEqualTo("Fallback");

      result = future.getNowOrComputeFallback((f, ex) -> "Fallback");
      assertThat(result).isEqualTo("Fallback");

      future.complete("Completed");
      result = future.getNowOrComputeFallback(ex -> "Fallback");
      assertThat(result).isEqualTo("Completed");
      result = future.getNowOrComputeFallback((f, ex) -> "Fallback");
      assertThat(result).isEqualTo("Completed");

      final ExtendedFuture<String> failed = ExtendedFuture.failedFuture(new RuntimeException());
      result = failed.getNowOrComputeFallback(ex -> "Fallback");
      assertThat(result).isEqualTo("Fallback");
      result = failed.getNowOrComputeFallback((f, ex) -> "Fallback");
      assertThat(result).isEqualTo("Fallback");
   }

   @Test
   void testGetNowOrFallback() {
      final var future = new ExtendedFuture<String>();
      var result = future.getNowOrFallback("Fallback");
      assertThat(result).isEqualTo("Fallback");

      future.complete("Completed");
      result = future.getNowOrFallback("Fallback");
      assertThat(result).isEqualTo("Completed");

      final ExtendedFuture<String> failed = ExtendedFuture.failedFuture(new RuntimeException());
      result = failed.getNowOrFallback("Fallback");
      assertThat(result).isEqualTo("Fallback");
   }

   @Test
   void testGetOptional() {
      final var future = new ExtendedFuture<String>();

      var result = future.getOptional(10, TimeUnit.MILLISECONDS);
      assertThat(result).isEmpty();

      future.complete("Completed");
      result = future.getOptional(10, TimeUnit.MILLISECONDS);
      assertThat(result).contains("Completed");

      result = future.getOptional();
      assertThat(result).contains("Completed");

      final ExtendedFuture<String> failed = ExtendedFuture.failedFuture(new RuntimeException());

      result = failed.getOptional(10, TimeUnit.MILLISECONDS);
      assertThat(result).isEmpty();

      result = failed.getOptional();
      assertThat(result).isEmpty();
   }

   @Test
   void testGetOrComputeFallback() {
      final var future = new ExtendedFuture<String>();
      var result = future.getOrComputeFallback(ex -> "Fallback", 10, TimeUnit.MILLISECONDS);
      assertThat(result).isEqualTo("Fallback");

      result = future.getOrComputeFallback((f, ex) -> "Fallback", 10, TimeUnit.MILLISECONDS);
      assertThat(result).isEqualTo("Fallback");

      future.complete("Completed");
      result = future.getOrComputeFallback(ex -> "Fallback", 10, TimeUnit.MILLISECONDS);
      assertThat(result).isEqualTo("Completed");
      result = future.getOrComputeFallback((f, ex) -> "Fallback", 10, TimeUnit.MILLISECONDS);
      assertThat(result).isEqualTo("Completed");
      result = future.getOrComputeFallback(ex -> "Fallback");
      assertThat(result).isEqualTo("Completed");
      result = future.getOrComputeFallback((f, ex) -> "Fallback");
      assertThat(result).isEqualTo("Completed");

      final ExtendedFuture<String> failed = ExtendedFuture.failedFuture(new RuntimeException());
      result = failed.getOrComputeFallback(ex -> "Fallback", 10, TimeUnit.MILLISECONDS);
      assertThat(result).isEqualTo("Fallback");
      result = failed.getOrComputeFallback((f, ex) -> "Fallback", 10, TimeUnit.MILLISECONDS);
      assertThat(result).isEqualTo("Fallback");
      result = failed.getOrComputeFallback(ex -> "Fallback");
      assertThat(result).isEqualTo("Fallback");
      result = failed.getOrComputeFallback((f, ex) -> "Fallback");
      assertThat(result).isEqualTo("Fallback");
   }

   @Test
   void testGetOrFallback() {
      final var future = new ExtendedFuture<String>();

      var result = future.getOrFallback("Fallback", 10, TimeUnit.MILLISECONDS);
      assertThat(result).isEqualTo("Fallback");

      future.complete("Completed");
      result = future.getOrFallback("Fallback");
      assertThat(result).isEqualTo("Completed");

      result = future.getOrFallback("Fallback", 10, TimeUnit.MILLISECONDS);
      assertThat(result).isEqualTo("Completed");

      final ExtendedFuture<String> failed = ExtendedFuture.failedFuture(new RuntimeException());
      result = failed.getOrFallback("Fallback");
      assertThat(result).isEqualTo("Fallback");

      result = failed.getOrFallback("Fallback", 10, TimeUnit.MILLISECONDS);
      assertThat(result).isEqualTo("Fallback");
   }

   @Test
   void testHandle() {
      final var completedFuture = ExtendedFuture.completedFuture("Initial");
      final var future = completedFuture.handle((result, ex) -> {
         if (ex == null)
            return result + " -> Handled";
         return "Recovered from exception: " + ex.getMessage();
      });

      assertThat(future.join()).isEqualTo("Initial -> Handled");
   }

   @Test
   void testHandle_withException() {
      final var failedFuture = ExtendedFuture.failedFuture(new RuntimeException("Initial failure"));
      final var future = failedFuture.handle((result, ex) -> {
         if (ex == null)
            return result + " -> Handled";
         return "Recovered from exception: " + ex.getMessage();
      });

      assertThat(future.join()).isEqualTo("Recovered from exception: Initial failure");
   }

   @Test
   void testHandlingCheckedExceptions() {
      final var future = ExtendedFuture.runAsync(() -> {
         if (true)
            throw new IOException("Checked exception");
      }).exceptionally(ex -> {
         assertThat(ex).isInstanceOf(CompletionException.class);
         ex = asNonNull(ex.getCause());
         assertThat(ex).isInstanceOf(RuntimeException.class);
         ex = asNonNull(ex.getCause());
         assertThat(ex).isInstanceOf(IOException.class);
         assertThat(ex).hasMessage("Checked exception");
         return null;
      });

      future.join();
   }

   @Test
   void testInterruptibility() throws InterruptedException {
      final var interrupted = new CountDownLatch(1);

      final var start1 = new CountDownLatch(1);
      final var nonInterruptibleFuture = ExtendedFuture.runAsync(() -> {
         try {
            start1.countDown();
            Thread.sleep(2_000);
         } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            interrupted.countDown();
         }
      }).asNonInterruptible();
      assertThat(nonInterruptibleFuture.isInterruptible()).isFalse();
      start1.await();
      nonInterruptibleFuture.cancel(true);

      assertThat(interrupted.await(2, TimeUnit.SECONDS)).isFalse();
      assertThat(nonInterruptibleFuture.isCancelled()).isTrue();

      final var start2 = new CountDownLatch(1);
      final var interruptibleFuture = ExtendedFuture.runAsync(() -> {
         try {
            start2.countDown();
            Thread.sleep(2_000);
         } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            interrupted.countDown();
         }
      });
      assertThat(interruptibleFuture.isInterruptible()).isTrue();
      start2.await();
      interruptibleFuture.cancel(true);

      assertThat(interrupted.await(2, TimeUnit.SECONDS)).isTrue();
      assertThat(interruptibleFuture.isCancelled()).isTrue();
   }

   @Test
   void testIsFailed() {
      final var future = new ExtendedFuture<String>();
      assertThat(future.isFailed()).isFalse();

      future.completeExceptionally(new RuntimeException("Failure"));
      assertThat(future.isFailed()).isTrue();
   }

   @Test
   void testIsIncomplete() {
      final var future = new ExtendedFuture<String>();
      assertThat(future.isIncomplete()).isTrue();

      future.complete("Done");
      assertThat(future.isIncomplete()).isFalse();
   }

   @Test
   void testMultipleStagesCancelDownstream() throws InterruptedException {
      testMultipleStagesCancelDownstream(ExtendedFuture::runAsync, true);
      testMultipleStagesCancelDownstream(runnable -> ExtendedFuture.from(CompletableFuture.completedFuture(null)) //
         .asCancellableByDependents(true) //
         .thenRunAsync(runnable), true);
   }

   @Test
   void testMultipleStagesCancelUpstream() throws InterruptedException {
      final var parent = new ExtendedFuture<>();
      final var parentCancellable = parent.asCancellableByDependents(true);
      final var parentUncancellable = parentCancellable.asCancellableByDependents(false);
      assertThat(parent.isCancellableByDependents()).isFalse();
      assertThat(parentCancellable.isCancellableByDependents()).isTrue();
      assertThat(parentUncancellable.isCancellableByDependents()).isFalse();

      var dependent = parent.thenRun(() -> { /**/ });
      assertThat(dependent.isInterruptible()).isTrue();
      dependent.cancel(true);
      awaitFutureState(dependent, CANCELLED);
      awaitFutureState(parent, CompletionState.INCOMPLETE);

      dependent = parentUncancellable.thenRun(() -> { /**/ });
      assertThat(dependent.isInterruptible()).isTrue();
      dependent.cancel(true);
      awaitFutureState(dependent, CANCELLED);
      awaitFutureState(parent, CompletionState.INCOMPLETE);
      awaitFutureState(parentUncancellable, CompletionState.INCOMPLETE);

      dependent = parentCancellable.thenRun(() -> { /**/ });
      assertThat(dependent.isInterruptible()).isTrue();
      dependent.cancel(true);
      awaitFutureState(dependent, CANCELLED);
      awaitFutureState(parentCancellable, CANCELLED);
      awaitFutureState(parent, CANCELLED);

      testMultipleStagesCancelUpstream(r -> ExtendedFuture.runAsync(r).asCancellableByDependents(true), true, true);
      testMultipleStagesCancelUpstream(runnable -> ExtendedFuture.from(CompletableFuture.completedFuture(null)) //
         .asCancellableByDependents(true) //
         .thenRunAsync(runnable), true, true);

      testMultipleStagesCancelUpstream(r -> ExtendedFuture.runAsync(r).asCancellableByDependents(false), false, true);
      testMultipleStagesCancelUpstream(runnable -> ExtendedFuture.from(CompletableFuture.completedFuture(null)).thenRunAsync(runnable),
         false, true);
   }

   @Test
   void testNestedStageCancel() throws InterruptedException {
      final MutableRef<TaskState> stage1NestedState = MutableRef.of(TaskState.NEW);
      final MutableRef<@Nullable ExtendedFuture<?>> stage1Nested = MutableRef.create();
      final var stage1 = ExtendedFuture //
         .completedFuture(null) //
         .thenCompose(result -> {
            final var nestedStage = ExtendedFuture //
               .runAsync(createTask(stage1NestedState, 5_000)) //
               .asCancellableByDependents(true);
            assertThat(nestedStage.isInterruptible()).isTrue();
            stage1Nested.set(nestedStage);
            return nestedStage;
         });

      awaitTaskStateNOT(stage1NestedState, TaskState.NEW);

      assertThat(stage1.isInterruptible()).isTrue();
      stage1.cancel(true);

      awaitFutureState(stage1, CANCELLED);

      // nested future is affected by cancellation of outer chain
      awaitFutureState(stage1Nested.get(), CANCELLED);
      awaitTaskState(stage1NestedState, INTERRUPTED);
   }

   @Test
   void testNoMemoryLeak() throws Exception {
      ExtendedFuture<String> stage1 = ExtendedFuture.supplyAsync(() -> "Stage 1");
      final var stage1Ref = new WeakReference<>(stage1);

      ExtendedFuture<String> stage2 = stage1.thenApply(result -> result + " -> Stage 2");
      final var stage2Ref = new WeakReference<>(stage2);

      ExtendedFuture<String> stage3 = stage2.thenApply(result -> result + " -> Stage 3");
      final var stage3Ref = new WeakReference<>(stage3);

      ExtendedFuture<String> stage4 = stage3.thenApply(result -> result + " -> Stage 4");
      final var stage4Ref = new WeakReference<>(stage4);

      ExtendedFuture<String> stage5 = stage4.thenApply(result -> result + " -> Stage 5");
      final var stage5Ref = new WeakReference<>(stage5);

      // Ensure the last future in the chain completes normally
      final String result = stage5.get(2, TimeUnit.SECONDS);
      assertThat(result).isEqualTo("Stage 1 -> Stage 2 -> Stage 3 -> Stage 4 -> Stage 5");

      stage5 = null;

      System.gc();
      await(() -> stage5Ref.get() == null);

      assertThat(stage5Ref.get()).isNull();
      assertThat(stage4Ref.get()).isNotNull();
      assertThat(stage3Ref.get()).isNotNull();
      assertThat(stage2Ref.get()).isNotNull();
      assertThat(stage1Ref.get()).isNotNull();

      stage3 = null;
      stage4 = null;

      System.gc();
      await(() -> stage3Ref.get() == null);

      assertThat(stage4Ref.get()).isNull();
      assertThat(stage3Ref.get()).isNull();
      assertThat(stage2Ref.get()).isNotNull();
      assertThat(stage1Ref.get()).isNotNull();

      stage1 = null;
      stage2 = null;

      System.gc();
      await(() -> stage1Ref.get() == null);

      assertThat(stage2Ref.get()).isNull();
      assertThat(stage1Ref.get()).isNull();
   }

   @Test
   void testResultNow() {
      final var incompleteFuture = new ExtendedFuture<String>();
      final var completedFuture = ExtendedFuture.completedFuture("Success");
      final var failedFuture = ExtendedFuture.failedFuture(new IOException());
      final var cancelledFuture = new ExtendedFuture<String>();
      cancelledFuture.cancel(true);

      assertThatThrownBy(incompleteFuture::resultNow).isInstanceOf(IllegalStateException.class);
      assertThat(completedFuture.resultNow()).isEqualTo("Success");
      assertThatThrownBy(failedFuture::resultNow).isInstanceOf(IllegalStateException.class);
      assertThatThrownBy(cancelledFuture::resultNow).isInstanceOf(IllegalStateException.class);
   }

   @Test
   void testRunAsync() {
      final var ref = MutableRef.of(null);

      ref.set(null);
      var future = ExtendedFuture.runAsync(() -> ref.set("Task completed asynchronously"));
      future.join();
      assertThat(ref.get()).isEqualTo("Task completed asynchronously");

      ref.set(null);
      future = ExtendedFuture.runAsync(() -> ref.set("Task completed with executor"), executor);
      future.join();
      assertThat(ref.get()).isEqualTo("Task completed with executor");
      assertThat(executor.hasExecuted()).isTrue();
   }

   @Test
   void testRunAfterBoth() {
      for (final var interruptibleStages : List.of(true, false)) {
         final var ref1 = MutableRef.of(null);
         final var ref2 = MutableRef.of(null);

         final var future1 = new ExtendedFuture<String>().withInterruptibleStages(interruptibleStages);
         final var future2 = new ExtendedFuture<String>().withInterruptibleStages(interruptibleStages);

         final var resultFuture = future1.runAfterBoth(future2, () -> ref1.set("Both completed"));
         final var asyncResultFuture = future2.runAfterBothAsync(future1, () -> ref2.set("Both completed async"));
         final var asyncResultFuture2 = future2.runAfterBothAsync(future1, () -> ref2.set("Both completed async"), executor);

         future1.complete("Complete 1");
         future2.complete("Complete 2");

         resultFuture.join();
         assertThat(ref1.get()).isEqualTo("Both completed");

         asyncResultFuture.join();
         assertThat(ref2.get()).isEqualTo("Both completed async");

         asyncResultFuture2.join();
         assertThat(ref2.get()).isEqualTo("Both completed async");
         assertThat(executor.hasExecuted()).isTrue();
      }
   }

   @Test
   void testRunAfterEither() {
      for (final var interruptibleStages : List.of(true, false)) {
         final var ref1 = MutableRef.of(null);
         final var ref2 = MutableRef.of(null);

         final var future1 = new ExtendedFuture<String>().withInterruptibleStages(interruptibleStages);
         final var future2 = new ExtendedFuture<String>().withInterruptibleStages(interruptibleStages);
         final var resultFuture = future1.runAfterEither(future2, () -> ref1.set("Either completed"));
         final var asyncResultFuture = future2.runAfterEitherAsync(future1, () -> ref2.set("Either completed async"));
         final var asyncResultFuture2 = future2.runAfterEitherAsync(future1, () -> ref2.set("Either completed async"), executor);

         future1.complete("Completed 1");
         resultFuture.join();
         assertThat(ref1.get()).isEqualTo("Either completed");

         asyncResultFuture.join();
         assertThat(ref2.get()).isEqualTo("Either completed async");

         asyncResultFuture2.join();
         assertThat(ref2.get()).isEqualTo("Either completed async");
         assertThat(executor.hasExecuted()).isTrue();
      }
   }

   @Test
   void testSingleStageCancel() throws InterruptedException {
      testSingleStageCancel(ExtendedFuture::runAsync, true);
      testSingleStageCancel(runnable -> ExtendedFuture.from(CompletableFuture.completedFuture(null)) //
         .asCancellableByDependents(true) //
         .thenRunAsync(runnable), true);
   }

   @Test
   void testThenAccept() {
      final var completedFuture = ExtendedFuture.completedFuture("Initial");

      final var ref = MutableRef.of(null);

      var future = completedFuture.thenAccept(ref::set);
      future.join();
      assertThat(ref.get()).isEqualTo("Initial");

      ref.set(null);
      future = completedFuture.thenAcceptAsync(ref::set);
      future.join();
      assertThat(ref.get()).isEqualTo("Initial");

      ref.set(null);
      future = completedFuture.thenAcceptAsync(ref::set, executor);
      future.join();
      assertThat(ref.get()).isEqualTo("Initial");
      assertThat(executor.hasExecuted()).isTrue();
   }

   @Test
   @SuppressWarnings("null")
   void testThenAccept_withException() {
      final var failedFuture = ExtendedFuture.failedFuture(new RuntimeException("Initial failure"));

      final var isThenAcceptExecuted = new AtomicBoolean(false);
      final var future = failedFuture.thenAccept(value -> isThenAcceptExecuted.set(true));

      assertThatThrownBy(future::join) //
         .isInstanceOf(CompletionException.class) //
         .cause() //
         .isInstanceOf(RuntimeException.class) //
         .hasMessage("Initial failure");

      assertThat(isThenAcceptExecuted).isFalse();
   }

   @Test
   void testThenAcceptBoth() {
      for (final var interruptibleStages : List.of(true, false)) {
         final var ref = MutableRef.of(null);
         final var future1 = new ExtendedFuture<String>().withInterruptibleStages(interruptibleStages);
         final var future2 = new ExtendedFuture<String>().withInterruptibleStages(interruptibleStages);

         final var resultFuture = future1.thenAcceptBoth(future2, (res1, res2) -> ref.set(res1 + " and " + res2));
         final var asyncResultFuture = future1.thenAcceptBothAsync(future2, (res1, res2) -> ref.set(res1 + " and " + res2));
         final var asyncResultFuture2 = future1.thenAcceptBothAsync(future2, (res1, res2) -> ref.set(res1 + " and " + res2), executor);

         future1.complete("First");
         future2.complete("Second");

         resultFuture.join();
         assertThat(ref.get()).isEqualTo("First and Second");

         asyncResultFuture.join();
         assertThat(ref.get()).isEqualTo("First and Second");

         asyncResultFuture2.join();
         assertThat(ref.get()).isEqualTo("First and Second");
         assertThat(executor.hasExecuted()).isTrue();
      }
   }

   @Test
   void testThenApply() {
      final var completedFuture = ExtendedFuture.completedFuture("Initial");

      var future = completedFuture.thenApply(s -> s + " -> Applied");
      assertThat(future.join()).isEqualTo("Initial -> Applied");

      future = completedFuture.thenApplyAsync(s -> s + " -> Applied");
      assertThat(future.join()).isEqualTo("Initial -> Applied");

      future = completedFuture.thenApplyAsync(s -> s + " -> Applied", executor);
      assertThat(future.join()).isEqualTo("Initial -> Applied");
      assertThat(executor.hasExecuted()).isTrue();
   }

   @Test
   @SuppressWarnings("null")
   void testThenApply_withException() {
      final var failedFuture = ExtendedFuture.failedFuture(new RuntimeException("Initial failure"));

      var future = failedFuture.thenApply(s -> s + " -> Applied");
      assertThatThrownBy(future::join) //
         .isInstanceOf(CompletionException.class) //
         .hasCauseInstanceOf(RuntimeException.class) //
         .hasMessageContaining("Initial failure");

      future = failedFuture.thenApplyAsync(s -> s + " -> Applied");
      assertThatThrownBy(future::join) //
         .isInstanceOf(CompletionException.class) //
         .hasCauseInstanceOf(RuntimeException.class) //
         .hasMessageContaining("Initial failure");
   }

   @Test
   void testThenCombine() {
      for (final var interruptibleStages : List.of(true, false)) {
         final var future1 = new ExtendedFuture<String>().withInterruptibleStages(interruptibleStages);
         final var future2 = new ExtendedFuture<String>().withInterruptibleStages(interruptibleStages);

         final var resultFuture = future1.thenCombine(future2, (res1, res2) -> res1 + " combined with " + res2);
         final var resultFutureAsync = future1.thenCombineAsync(future2, (res1, res2) -> res1 + " async combined with " + res2);
         final var resultFutureAsync2 = future1.thenCombineAsync(future2, (res1, res2) -> res1 + " async combined with " + res2, executor);
         future1.complete("First");
         future2.complete("Second");

         assertThat(resultFuture.join()).isEqualTo("First combined with Second");
         assertThat(resultFutureAsync.join()).isEqualTo("First async combined with Second");
         assertThat(resultFutureAsync2.join()).isEqualTo("First async combined with Second");
         assertThat(executor.hasExecuted()).isTrue();
      }
   }

   @Test
   void testThenCompose() {
      final var completedFuture = ExtendedFuture.completedFuture("Initial");

      var future = completedFuture.thenCompose(result -> CompletableFuture.completedFuture(result + " -> Composed"));
      assertThat(future.join()).isEqualTo("Initial -> Composed");

      future = completedFuture.thenComposeAsync(result -> CompletableFuture.completedFuture(result + " -> Composed"));
      assertThat(future.join()).isEqualTo("Initial -> Composed");

      future = completedFuture.thenComposeAsync(result -> CompletableFuture.completedFuture(result + " -> Composed"), executor);
      assertThat(future.join()).isEqualTo("Initial -> Composed");
      assertThat(executor.hasExecuted()).isTrue();
   }

   @Test
   void testThenRun() {
      final var completedFuture = ExtendedFuture.completedFuture("Initial");

      final var ref = MutableRef.of(null);

      var future = completedFuture.thenRun(() -> ref.set("Run"));
      future.join();
      assertThat(ref.get()).isEqualTo("Run");

      ref.set(null);
      future = completedFuture.thenRunAsync(() -> ref.set("Run"));
      future.join();
      assertThat(ref.get()).isEqualTo("Run");

      ref.set(null);
      future = completedFuture.thenRunAsync(() -> ref.set("Run"), executor);
      future.join();
      assertThat(ref.get()).isEqualTo("Run");
      assertThat(executor.hasExecuted()).isTrue();
   }

   @Test
   void testTimeoutCompletion() throws Exception {
      final var future = new ExtendedFuture<String>().completeOnTimeout("Timeout Result", 100, TimeUnit.MILLISECONDS);

      assertThat(future.get(200, TimeUnit.MILLISECONDS)).isEqualTo("Timeout Result");
   }

   @Test
   void testWhenComplete() {
      final var completedFuture = ExtendedFuture.completedFuture("Initial");

      final var ref = MutableRef.of(null);
      var future = completedFuture.whenComplete((result, ex) -> ref.set(result));
      future.join();
      assertThat(ref.get()).isEqualTo("Initial");

      ref.set("");
      future = completedFuture.whenCompleteAsync((result, ex) -> ref.set(result));
      future.join();
      assertThat(ref.get()).isEqualTo("Initial");

      ref.set("");
      future = completedFuture.whenCompleteAsync((result, ex) -> ref.set(result), executor);
      future.join();
      assertThat(ref.get()).isEqualTo("Initial");
      assertThat(executor.hasExecuted()).isTrue();
   }

   @Test
   void testWithDefaultExecutor() {
      final var defaultExecutor1 = Executors.newSingleThreadExecutor();
      final var defaultExecutor2 = Executors.newSingleThreadExecutor();
      final var originalFuture = ExtendedFuture.builder(String.class).withDefaultExecutor(defaultExecutor1).build();
      final var sameFuture = originalFuture.withDefaultExecutor(defaultExecutor1);
      final var newFuture = originalFuture.withDefaultExecutor(defaultExecutor2);

      assertThat(sameFuture).isSameAs(originalFuture);
      assertThat(newFuture).isNotSameAs(originalFuture);
      assertThat(newFuture.defaultExecutor()).isSameAs(defaultExecutor2);

      defaultExecutor1.shutdown();
      defaultExecutor2.shutdown();
   }
}
