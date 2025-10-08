/*
 * SPDX-FileCopyrightText: © Sebastian Thomschke
 * SPDX-License-Identifier: EPL-2.0
 * SPDX-FileContributor: Sebastian Thomschke: initial API and implementation
 */
package io.github.futures4j;

import static io.github.futures4j.AbstractFutureTest.TaskState.*;
import static io.github.futures4j.CompletionState.CANCELLED;
import static net.sf.jstuff.core.validation.NullAnalysisHelper.asNonNull;
import static org.assertj.core.api.Assertions.*;

import java.io.IOException;
import java.lang.ref.WeakReference;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

import org.eclipse.jdt.annotation.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.github.futures4j.ExtendedFuture.InterruptibleFuturesTracker;
import io.github.futures4j.ExtendedFuture.ReadOnlyMode;
import io.github.futures4j.util.ThrowingBiConsumer;
import io.github.futures4j.util.ThrowingBiFunction;
import io.github.futures4j.util.ThrowingConsumer;
import io.github.futures4j.util.ThrowingFunction;
import io.github.futures4j.util.ThrowingRunnable;
import net.sf.jstuff.core.concurrent.Threads;
import net.sf.jstuff.core.ref.MutableObservableRef;
import net.sf.jstuff.core.ref.MutableRef;

/**
 * @author <a href="https://sebthom.de/">Sebastian Thomschke</a>
 */
class ExtendedFutureTest extends AbstractFutureTest {

   private static final class TrackingExecutor implements Executor {
      final AtomicInteger executions = new AtomicInteger();
      final ExecutorService delegate = Executors.newSingleThreadExecutor();

      @Override
      public void execute(final Runnable command) {
         executions.incrementAndGet();
         delegate.execute(command);
      }

      int getExecutions() {
         return executions.getAndSet(0);
      }

      boolean hasExecuted() {
         return executions.getAndSet(0) > 0;
      }

      void shutdown() {
         delegate.shutdown();
      }
   }

   final TrackingExecutor executor = new TrackingExecutor();
   int trackedFuturesCountBeforeTest;

   @BeforeEach
   void setup() {
      InterruptibleFuturesTracker.purgeStaleEntries();
      trackedFuturesCountBeforeTest = InterruptibleFuturesTracker.BY_ID.size();
   }

   @AfterEach
   void tearDown() throws InterruptedException {
      executor.shutdown();

      final long maxTime = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
      while (!InterruptibleFuturesTracker.BY_ID.isEmpty() && System.nanoTime() < maxTime) {
         System.gc(); // encourage the GC to clear WeakReferences
         Thread.sleep(200); // short back-off before re-checking
         InterruptibleFuturesTracker.purgeStaleEntries();
      }

      assertThat(InterruptibleFuturesTracker.BY_ID).as("InterruptibleFuturesTracker.BY_ID must not have new entries").hasSize(
         trackedFuturesCountBeforeTest);
   }

   @Test
   void testAcceptEither() {
      for (final var interruptibleStages : List.of(true, false)) {
         final var future1 = new ExtendedFuture<String>().withInterruptibleStages(interruptibleStages);
         final CompletableFuture<String> future2 = CompletableFuture.completedFuture("Result from future2");

         /*
          * test right side
          */
         final AtomicInteger executions = new AtomicInteger();
         ThrowingConsumer<String, ?> consumer = str -> {
            assertThat(str).isEqualTo("Result from future2");
            executions.incrementAndGet();
         };
         var resultFuture = Futures.combine( //
            future1.acceptEither(future2, consumer), //
            future1.acceptEither(future2, (Consumer<String>) consumer), //
            future1.acceptEitherAsync(future2, consumer), //
            future1.acceptEitherAsync(future2, (Consumer<String>) consumer), //
            future1.acceptEitherAsync(future2, consumer, executor), //
            future1.acceptEitherAsync(future2, (Consumer<String>) consumer, executor) //
         ).toSet();
         resultFuture.join();
         assertThat(executions).hasValue(6);
         assertThat(executor.getExecutions()).isEqualTo(2);

         /*
          * test left side
          */
         future1.complete("Result from future1");

         executions.set(0);
         consumer = str -> {
            assertThat(str).isEqualTo(future1.get());
            executions.incrementAndGet();
         };
         resultFuture = Futures.combine( //
            future1.acceptEither(future2, consumer), //
            future1.acceptEither(future2, (Consumer<String>) consumer), //
            future1.acceptEitherAsync(future2, consumer), //
            future1.acceptEitherAsync(future2, (Consumer<String>) consumer), //
            future1.acceptEitherAsync(future2, consumer, executor), //
            future1.acceptEitherAsync(future2, (Consumer<String>) consumer, executor) //
         ).toSet();
         resultFuture.join();
         assertThat(executions).hasValue(6);
         assertThat(executor.getExecutions()).isEqualTo(2);
      }
   }

   @Test
   void testApplyToEither() throws InterruptedException, ExecutionException {
      for (final var interruptibleStages : List.of(true, false)) {
         final var future1 = new ExtendedFuture<String>().withInterruptibleStages(interruptibleStages);
         final CompletableFuture<String> future2 = CompletableFuture.completedFuture("Result from future2");

         /*
          * test right side
          */
         final AtomicInteger executions = new AtomicInteger();
         ThrowingFunction<String, String, ?> fn = str -> {
            assertThat(str).isEqualTo("Result from future2");
            executions.incrementAndGet();
            return "-> " + str;
         };
         var resultFuture = Futures.combine( //
            future1.applyToEither(future2, fn), //
            future1.applyToEither(future2, (Function<String, String>) fn), //
            future1.applyToEitherAsync(future2, fn), //
            future1.applyToEitherAsync(future2, (Function<String, String>) fn), //
            future1.applyToEitherAsync(future2, fn, executor), //
            future1.applyToEitherAsync(future2, (Function<String, String>) fn, executor) //
         ).toSet();
         assertThat(resultFuture.get()).containsExactly("-> Result from future2");
         assertThat(executions).hasValue(6);
         assertThat(executor.getExecutions()).isEqualTo(2);

         /*
          * test left side
          */
         future1.complete("Result from future1");

         executions.set(0);
         fn = str -> {
            assertThat(str).isEqualTo(future1.get());
            executions.incrementAndGet();
            return "-> " + str;
         };
         resultFuture = Futures.combine( //
            future1.applyToEither(future2, fn), //
            future1.applyToEither(future2, (Function<String, String>) fn), //
            future1.applyToEitherAsync(future2, fn), //
            future1.applyToEitherAsync(future2, (Function<String, String>) fn), //
            future1.applyToEitherAsync(future2, fn, executor), //
            future1.applyToEitherAsync(future2, (Function<String, String>) fn, executor) //
         ).toSet();
         assertThat(resultFuture.get()).containsExactly("-> Result from future1");
         assertThat(executions).hasValue(6);
         assertThat(executor.getExecutions()).isEqualTo(2);
      }
   }

   @Test
   void testAsReadOnly_withIgnoreMutationAttempt() {
      final var originalFuture = new ExtendedFuture<>();
      final var readOnlyFuture = originalFuture.asReadOnly(ReadOnlyMode.IGNORE_MUTATION);

      assertThat(readOnlyFuture.complete("Hello")).isFalse();
      assertThat(readOnlyFuture.cancel(true)).isFalse();
      assertThat(readOnlyFuture.completeExceptionally(new RuntimeException("Test Exception"))).isFalse();

      readOnlyFuture.completeWith(CompletableFuture.completedFuture("Hello"));
      Threads.sleep(50);
      assertThat(readOnlyFuture).isNotCompleted();

      readOnlyFuture.orTimeout(10, TimeUnit.MILLISECONDS);
      Threads.sleep(50);
      assertThat(readOnlyFuture).isNotCompleted();

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

      assertThatThrownBy(() -> readOnlyFuture.completeWith(CompletableFuture.completedFuture("Hello"))) //
         .isInstanceOf(UnsupportedOperationException.class) //
         .hasMessageContaining("is read-only");

      assertThatThrownBy(() -> readOnlyFuture.orTimeout(10, TimeUnit.MILLISECONDS)) //
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
   void testCancelTrueOnNonInterruptibleDownstreamShouldStillInterruptUpstream() throws Exception {
      // upstream (stage1): interruptible task
      final MutableRef<TaskState> stage1State = MutableObservableRef.of(NEW);
      final var stage1 = ExtendedFuture.runAsync(createTask(stage1State, 5_000));

      // ensure downstream stages can cancel preceding stages
      final var stage1Cancellable = stage1.asCancellableByDependents(true);

      // create a dependent stage (stage2) and then a non-interruptible view (stage2NonInt)
      final var stage2 = stage1Cancellable.thenRunAsync(() -> { /* noop */ });
      final var stage2NonInt = stage2.asNonInterruptible();

      // wait for upstream to start running
      awaitTaskState(stage1State, RUNNING);

      // cancel the non-interruptible downstream stage with interrupt request
      stage2NonInt.cancel(true);

      // expect the upstream task to be interrupted (caller requested interruption)
      awaitTaskStateNOT(stage1State, RUNNING);
      assertTaskState(stage1State, INTERRUPTED);

      // sanity: the downstream was cancelled
      assertThat(stage2NonInt).isCancelled();
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
         future.completeAsync((Supplier<String>) () -> "Result");
         assertThat(future.join()).isEqualTo("Result");

         future = new ExtendedFuture<String>().withInterruptibleStages(interruptibleStages);
         future.completeAsync(() -> "Result", executor);
         assertThat(future.join()).isEqualTo("Result");
         assertThat(executor.hasExecuted()).isTrue();

         future = new ExtendedFuture<String>().withInterruptibleStages(interruptibleStages);
         future.completeAsync((Supplier<String>) () -> "Result", executor);
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
         final var future1 = new ExtendedFuture<String>().withInterruptibleStages(interruptibleStages);
         final var future2 = new ExtendedFuture<String>().withInterruptibleStages(interruptibleStages);

         final AtomicInteger executions = new AtomicInteger();
         final ThrowingRunnable<?> action = executions::incrementAndGet;
         final var resultFuture = Futures.combine( //
            future1.runAfterBoth(future2, action), //
            future1.runAfterBoth(future2, (Runnable) action), //
            future1.runAfterBothAsync(future2, action), //
            future1.runAfterBothAsync(future2, (Runnable) action), //
            future1.runAfterBothAsync(future2, action, executor), //
            future1.runAfterBothAsync(future2, (Runnable) action, executor) //
         ).toSet();

         future1.complete("Complete 1");
         future2.complete("Complete 2");

         resultFuture.join();
         assertThat(executions).hasValue(6);
         assertThat(executor.getExecutions()).isEqualTo(2);
      }
   }

   @Test
   void testRunAfterEither() {
      for (final var interruptibleStages : List.of(true, false)) {

         /*
          * test left side
          */
         final var future1 = new ExtendedFuture<String>().withInterruptibleStages(interruptibleStages);
         var future2 = new ExtendedFuture<String>().withInterruptibleStages(interruptibleStages);
         final AtomicInteger executions = new AtomicInteger();
         final ThrowingRunnable<?> action = executions::incrementAndGet;
         var resultFuture = Futures.combine( //
            future1.runAfterEither(future2, action), //
            future1.runAfterEither(future2, (Runnable) action), //
            future1.runAfterEitherAsync(future2, action), //
            future1.runAfterEitherAsync(future2, (Runnable) action), //
            future1.runAfterEitherAsync(future2, action, executor), //
            future1.runAfterEitherAsync(future2, (Runnable) action, executor) //
         ).toSet();

         future2.complete("Complete 2");

         resultFuture.join();
         assertThat(executions).hasValue(6);
         assertThat(executor.getExecutions()).isEqualTo(2);

         /*
          * test right side
          */
         future2 = new ExtendedFuture<String>().withInterruptibleStages(interruptibleStages);
         executions.set(0);
         resultFuture = Futures.combine( //
            future1.runAfterEither(future2, action), //
            future1.runAfterEither(future2, (Runnable) action), //
            future1.runAfterEitherAsync(future2, action), //
            future1.runAfterEitherAsync(future2, (Runnable) action), //
            future1.runAfterEitherAsync(future2, action, executor), //
            future1.runAfterEitherAsync(future2, (Runnable) action, executor) //
         ).toSet();

         future1.complete("Complete 1");

         resultFuture.join();
         assertThat(executions).hasValue(6);
         assertThat(executor.getExecutions()).isEqualTo(2);
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

      final AtomicInteger executions = new AtomicInteger();
      final ThrowingConsumer<String, ?> consumer = str -> {
         assertThat(str).isEqualTo("Initial");
         executions.incrementAndGet();
      };
      @SuppressWarnings("null")
      final var resultFuture = Futures.combine( //
         completedFuture.thenAccept(consumer), //
         completedFuture.thenAccept((Consumer<String>) consumer), //
         completedFuture.thenAcceptAsync(consumer), //
         completedFuture.thenAcceptAsync((Consumer<String>) consumer), //
         completedFuture.thenAcceptAsync(consumer, executor), //
         completedFuture.thenAcceptAsync((Consumer<String>) consumer, executor) //
      ).toSet();
      resultFuture.join();
      assertThat(executions).hasValue(6);
      assertThat(executor.getExecutions()).isEqualTo(2);
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
         final var future1 = new ExtendedFuture<String>().withInterruptibleStages(interruptibleStages);
         final var future2 = new ExtendedFuture<String>().withInterruptibleStages(interruptibleStages);

         final AtomicInteger executions = new AtomicInteger();
         final ThrowingBiConsumer<String, String, ?> consumer = (s1, s2) -> {
            assertThat(s1).isEqualTo("First");
            assertThat(s2).isEqualTo("Second");
            executions.incrementAndGet();
         };
         final var resultFuture = Futures.combine( //
            future1.thenAcceptBoth(future2, consumer), //
            future1.thenAcceptBoth(future2, (BiConsumer<String, String>) consumer), //
            future1.thenAcceptBothAsync(future2, consumer), //
            future1.thenAcceptBothAsync(future2, (BiConsumer<String, String>) consumer), //
            future1.thenAcceptBothAsync(future2, consumer, executor), //
            future1.thenAcceptBothAsync(future2, (BiConsumer<String, String>) consumer, executor) //
         ).toSet();

         future1.complete("First");
         future2.complete("Second");

         resultFuture.join();
         assertThat(executions).hasValue(6);
         assertThat(executor.getExecutions()).isEqualTo(2);
      }
   }

   @Test
   void testThenApply() throws InterruptedException, ExecutionException {
      for (final var interruptibleStages : List.of(true, false)) {
         final var future1 = new ExtendedFuture<String>().withInterruptibleStages(interruptibleStages);

         final AtomicInteger executions = new AtomicInteger();
         final ThrowingFunction<String, String, ?> fn = str -> {
            assertThat(str).isEqualTo("Result 1");
            executions.incrementAndGet();
            return "-> " + str;
         };
         final var resultFuture = Futures.combine( //
            future1.thenApply(fn), //
            future1.thenApply((Function<String, String>) fn), //
            future1.thenApplyAsync(fn), //
            future1.thenApplyAsync((Function<String, String>) fn), //
            future1.thenApplyAsync(fn, executor), //
            future1.thenApplyAsync((Function<String, String>) fn, executor) //
         ).toSet();

         future1.complete("Result 1");

         assertThat(resultFuture.get()).containsExactly("-> Result 1");
         assertThat(executions).hasValue(6);
         assertThat(executor.getExecutions()).isEqualTo(2);
      }
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
   void testThenCombine() throws InterruptedException, ExecutionException {
      for (final var interruptibleStages : List.of(true, false)) {
         final var future1 = new ExtendedFuture<String>().withInterruptibleStages(interruptibleStages);
         final var future2 = new ExtendedFuture<String>().withInterruptibleStages(interruptibleStages);

         final AtomicInteger executions = new AtomicInteger();
         final ThrowingBiFunction<String, String, String, ?> fn = (s1, s2) -> {
            assertThat(s1).isEqualTo("First");
            assertThat(s2).isEqualTo("Second");
            executions.incrementAndGet();
            return s1 + " AND " + s2;
         };
         final var resultFuture = Futures.combine( //
            future1.thenCombine(future2, fn), //
            future1.thenCombine(future2, (BiFunction<String, String, String>) fn), //
            future1.thenCombineAsync(future2, fn), //
            future1.thenCombineAsync(future2, (BiFunction<String, String, String>) fn), //
            future1.thenCombineAsync(future2, fn, executor), //
            future1.thenCombineAsync(future2, (BiFunction<String, String, String>) fn, executor) //
         ).toSet();

         future1.complete("First");
         future2.complete("Second");

         assertThat(resultFuture.get()).containsExactly("First AND Second");
         assertThat(executions).hasValue(6);
         assertThat(executor.getExecutions()).isEqualTo(2);
      }
   }

   @Test
   void testThenCompose() throws InterruptedException, ExecutionException {
      for (final var interruptibleStages : List.of(true, false)) {
         final var future1 = new ExtendedFuture<String>().withInterruptibleStages(interruptibleStages);

         final AtomicInteger executions = new AtomicInteger();
         final ThrowingFunction<String, CompletableFuture<String>, ?> fn = str -> {
            assertThat(str).isEqualTo("Initial");
            executions.incrementAndGet();
            return CompletableFuture.completedFuture(str + " -> Composed");
         };
         final var resultFuture = Futures.combine( //
            future1.thenCompose(fn), //
            future1.thenCompose((Function<String, CompletableFuture<String>>) fn), //
            future1.thenComposeAsync(fn), //
            future1.thenComposeAsync((Function<String, CompletableFuture<String>>) fn), //
            future1.thenComposeAsync(fn, executor), //
            future1.thenComposeAsync((Function<String, CompletableFuture<String>>) fn, executor) //
         ).toSet();

         future1.complete("Initial");

         assertThat(resultFuture.get()).containsExactly("Initial -> Composed");
         assertThat(executions).hasValue(6);
         assertThat(executor.getExecutions()).isEqualTo(2);
      }
   }

   @Test
   void testThenRun() {
      for (final var interruptibleStages : List.of(true, false)) {
         final var future1 = new ExtendedFuture<String>().withInterruptibleStages(interruptibleStages);

         final AtomicInteger executions = new AtomicInteger();
         final ThrowingRunnable<?> fn = executions::incrementAndGet;
         final var resultFuture = Futures.combine( //
            future1.thenRun(fn), //
            future1.thenRun((Runnable) fn), //
            future1.thenRunAsync(fn), //
            future1.thenRunAsync((Runnable) fn), //
            future1.thenRunAsync(fn, executor), //
            future1.thenRunAsync((Runnable) fn, executor) //
         ).toSet();

         future1.complete("Initial");

         resultFuture.join();
         assertThat(executions).hasValue(6);
         assertThat(executor.getExecutions()).isEqualTo(2);
      }
   }

   @Test
   void testLongRunningStages() throws Exception {
      final AtomicInteger counter = new AtomicInteger();
      final long t0 = System.nanoTime();

      ExtendedFuture.runAsync(() -> { // Stage 1
         assertThat(counter.getAndIncrement()).isZero(); // must be 0 → first
         Thread.sleep(5_000);
      }).thenRun(() -> { // Stage 2
         assertThat(counter.getAndIncrement()).isOne(); // must be 1 → second
         Thread.sleep(5_000);
      }).thenRun(() -> { // Stage 3
         assertThat(counter.getAndIncrement()).isEqualTo(2); // third
      }).get();

      final long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0);
      assertThat(elapsedMs).isBetween(10_000L, 11_500L);
      assertThat(counter).hasValue(3);
   }

   @Test
   void trackerEntryIsRemovedWhenStageCancelledEarly() throws Exception {
      assertThat(InterruptibleFuturesTracker.BY_ID).isEmpty();

      final ExecutorService exec = Executors.newSingleThreadExecutor();
      final CountDownLatch latch = new CountDownLatch(1);

      // stage 0 blocks on the latch
      final var fut0 = ExtendedFuture.runAsync(latch::await, exec).withInterruptibleStages(true);

      // stage 1 will never start because we cancel it immediately
      final var fut1 = fut0.thenRunAsync(() -> { /* never reached */ });

      assertThat(InterruptibleFuturesTracker.BY_ID).hasSize(trackedFuturesCountBeforeTest + 1);

      // cancel before stage 1 gets scheduled
      fut1.cancel(true);

      // give cancel-propagation a moment
      Thread.sleep(200);

      latch.countDown();
      exec.shutdownNow();
   }

   @Test
   void testTimeoutCompletion() throws Exception {
      final var future = new ExtendedFuture<String>().completeOnTimeout("Timeout Result", 100, TimeUnit.MILLISECONDS);

      assertThat(future.get(200, TimeUnit.MILLISECONDS)).isEqualTo("Timeout Result");
   }

   @Test
   void testWhenComplete() {
      for (final var interruptibleStages : List.of(true, false)) {
         final var future1 = new ExtendedFuture<String>().withInterruptibleStages(interruptibleStages);

         final AtomicInteger executions = new AtomicInteger();
         final ThrowingBiConsumer<String, Throwable, ?> fn = (result, ex) -> {
            executions.incrementAndGet();
            assertThat(result).isEqualTo("Initial");
         };
         final var resultFuture = Futures.combine( //
            future1.whenComplete(fn), //
            future1.whenComplete((BiConsumer<String, Throwable>) fn), //
            future1.whenCompleteAsync(fn), //
            future1.whenCompleteAsync((BiConsumer<String, Throwable>) fn), //
            future1.whenCompleteAsync(fn, executor), //
            future1.whenCompleteAsync((BiConsumer<String, Throwable>) fn, executor) //
         ).toSet();

         future1.complete("Initial");

         resultFuture.join();
         assertThat(executions).hasValue(6);
         assertThat(executor.getExecutions()).isEqualTo(2);
      }
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

   @Test
   void testRewrappingInterruptibleWrapperKeepsSemantics() {
      // Create an interruptible base instance (InterruptibleFuture)
      final ExtendedFuture<String> base = ExtendedFuture.builder(String.class).build();
      assertThat(base.isInterruptible()).isTrue();

      // First wrap into an InterruptibleWrappingFuture by changing default executor
      final ExtendedFuture<String> interruptibleWrapper = base.withDefaultExecutor(Runnable::run);
      assertThat(interruptibleWrapper.isInterruptible()).isTrue();
      assertThat(interruptibleWrapper).isNotInstanceOf(ExtendedFuture.InterruptibleFuture.class);

      // Re-wrap: should NOT throw and must preserve expected flags
      final ExtendedFuture<String> cbd = interruptibleWrapper.asCancellableByDependents(true);
      assertThat(cbd.isInterruptible()).isTrue();
      assertThat(cbd.isCancellableByDependents()).isTrue();

      final ExtendedFuture<String> sameExec = interruptibleWrapper.withDefaultExecutor(Runnable::run);
      assertThat(sameExec.isInterruptible()).isTrue();

      final ExtendedFuture<String> nonIntStages = interruptibleWrapper.withInterruptibleStages(false);
      assertThat(nonIntStages.isInterruptible()).isTrue();
      assertThat(nonIntStages.isInterruptibleStages()).isFalse();

      // Rewrap on top of a wrapper again to ensure stability over sequences
      final ExtendedFuture<String> again = nonIntStages.withInterruptibleStages(true) //
         .asCancellableByDependents(false) //
         .withDefaultExecutor(Runnable::run);
      assertThat(again.isInterruptible()).isTrue();
      assertThat(again.isInterruptibleStages()).isTrue();
      assertThat(again.isCancellableByDependents()).isFalse();
   }
}
