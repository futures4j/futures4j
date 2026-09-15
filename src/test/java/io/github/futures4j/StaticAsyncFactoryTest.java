/*
 * SPDX-FileCopyrightText: © Sebastian Thomschke
 * SPDX-License-Identifier: EPL-2.0
 */
package io.github.futures4j;

import static net.sf.jstuff.core.validation.NullAnalysisHelper.sneakyNull;
import static org.assertj.core.api.Assertions.*;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.stream.Stream;

import org.eclipse.jdt.annotation.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

import io.github.futures4j.util.ThrowingRunnable;
import io.github.futures4j.util.ThrowingSupplier;

/**
 * Verifies static async factories' argument validation, result policies, execution ownership and failure encoding.
 *
 * @author futures4j contributors
 */
class StaticAsyncFactoryTest extends AbstractFutureTest {

   enum Execution {
      DEFAULT,
      EXPLICIT,
      CONFIGURED
   }

   /** Explicit callback types prevent bare lambdas from silently testing only the more-specific throwing overloads. */
   enum Factory {
      RUN(false, false, Execution.DEFAULT),
      RUN_EXPLICIT(false, false, Execution.EXPLICIT),
      RUN_THROWING(false, true, Execution.DEFAULT),
      RUN_EXPLICIT_THROWING(false, true, Execution.EXPLICIT),
      RUN_CONFIGURED(false, true, Execution.CONFIGURED),
      SUPPLY(true, false, Execution.DEFAULT),
      SUPPLY_EXPLICIT(true, false, Execution.EXPLICIT),
      SUPPLY_THROWING(true, true, Execution.DEFAULT),
      SUPPLY_EXPLICIT_THROWING(true, true, Execution.EXPLICIT),
      SUPPLY_CONFIGURED(true, true, Execution.CONFIGURED);

      final boolean supplying;
      final boolean throwing;
      final Execution execution;

      Factory(final boolean supplying, final boolean throwing, final Execution execution) {
         this.supplying = supplying;
         this.throwing = throwing;
         this.execution = execution;
      }

      ExtendedFuture<?> start(final @Nullable Supplier<String> task, final Executor executor) {
         // Null tests must pass an actual null callback to the API, not a non-null adapter that fails inside user code.
         if (supplying) {
            final Supplier<String> supplier = task == null ? sneakyNull() : task;
            final ThrowingSupplier<String, ?> throwingSupplier = task == null ? sneakyNull() : task::get;
            if (execution == Execution.CONFIGURED)
               return ExtendedFuture.supplyAsyncWithDefaultExecutor(throwingSupplier, executor);
            if (throwing)
               return execution == Execution.DEFAULT ? ExtendedFuture.supplyAsync(throwingSupplier)
                     : ExtendedFuture.supplyAsync(throwingSupplier, executor);
            return execution == Execution.DEFAULT ? ExtendedFuture.supplyAsync(supplier) : ExtendedFuture.supplyAsync(supplier, executor);
         }
         final Runnable runnable = task == null ? sneakyNull() : task::get;
         final ThrowingRunnable<?> throwingRunnable = task == null ? sneakyNull() : task::get;
         if (execution == Execution.CONFIGURED)
            return ExtendedFuture.runAsyncWithDefaultExecutor(throwingRunnable, executor);
         if (throwing)
            return execution == Execution.DEFAULT ? ExtendedFuture.runAsync(throwingRunnable)
                  : ExtendedFuture.runAsync(throwingRunnable, executor);
         return execution == Execution.DEFAULT ? ExtendedFuture.runAsync(runnable) : ExtendedFuture.runAsync(runnable, executor);
      }
   }

   static Stream<Factory> executorFactories() {
      return Arrays.stream(Factory.values()).filter(factory -> factory.execution != Execution.DEFAULT);
   }

   private static Throwable failureOf(final ExtendedFuture<?> future) throws Exception {
      assertThat(future.isCancelled()).as("callback or submission failure is not direct cancellation").isFalse();
      return Objects.requireNonNull(future.handle((value, error) -> error).get(MAX_WAIT_SECS, TimeUnit.SECONDS));
   }

   private static void assertEncodedFailure(final ExtendedFuture<?> future, final Throwable expected) throws Exception {
      final var actual = failureOf(future);
      if (expected instanceof CompletionException) {
         assertThat(actual).isSameAs(expected);
      } else {
         assertThat(actual).isInstanceOf(CompletionException.class);
         assertThat(actual.getCause()).isSameAs(expected);
      }
   }

   @ParameterizedTest
   @EnumSource(Factory.class)
   void testSuccessAndPolicies(final Factory factory) throws Exception {
      final var submissions = new AtomicInteger();
      final Executor executor = command -> {
         submissions.incrementAndGet();
         assertThat(command).isInstanceOf(CompletableFuture.AsynchronousCompletionTask.class);
         command.run();
      };
      final var calls = new AtomicInteger();
      final var future = factory.start(() -> {
         calls.incrementAndGet();
         return "value";
      }, executor);
      assertThat(future.get(MAX_WAIT_SECS, TimeUnit.SECONDS)).isEqualTo(factory.supplying ? "value" : null);
      assertThat(calls).hasValue(1);
      assertThat(future.isInterruptible()).isTrue();
      assertThat(future.isInterruptibleStages()).isTrue();
      assertThat(future.isCancellableByDependents()).isFalse();
      assertThat(future.isReadOnly()).isFalse();
      assertThat(future.cancellablePrecedingStages).isEmpty();
      final var expectedDefault = factory.execution == Execution.CONFIGURED ? executor : new CompletableFuture<>().defaultExecutor();
      assertThat(future.defaultExecutor()).isSameAs(expectedDefault);
      assertThat(submissions).hasValue(factory.execution == Execution.DEFAULT ? 0 : 1);

      // Check execution as well as the accessor: a one-off executor must not become the descendants' default.
      final var descendant = future.thenRunAsync((Runnable) calls::incrementAndGet);
      descendant.get(MAX_WAIT_SECS, TimeUnit.SECONDS);
      assertThat(descendant.defaultExecutor()).isSameAs(expectedDefault);
      assertThat(calls).hasValue(2);
      assertThat(submissions).hasValue(factory.execution == Execution.CONFIGURED ? 2 : factory.execution == Execution.EXPLICIT ? 1 : 0);
   }

   @ParameterizedTest
   @EnumSource(Factory.class)
   void testNullCallbackRejectedBeforeSubmission(final Factory factory) {
      final var submissions = new AtomicInteger();
      final Executor executor = command -> submissions.incrementAndGet();
      assertThatNullPointerException().isThrownBy(() -> factory.start(null, executor));
      assertThat(submissions).hasValue(0);
   }

   @ParameterizedTest
   @EnumSource(value = Factory.class, names = {"RUN_EXPLICIT", "RUN_EXPLICIT_THROWING", "SUPPLY_EXPLICIT", "SUPPLY_EXPLICIT_THROWING"})
   void testNullExplicitExecutorRejected(final Factory factory) {
      assertThatNullPointerException().isThrownBy(() -> factory.start(() -> "unused", sneakyNull()));
   }

   @ParameterizedTest
   @EnumSource(value = Factory.class, names = {"RUN_CONFIGURED", "SUPPLY_CONFIGURED"})
   void testNullConfiguredExecutorRetainsDefault(final Factory factory) throws Exception {
      // The configurable factories already treat null like the constructor's unspecified default; do not broaden null rejection.
      final var future = factory.start(() -> "value", sneakyNull());
      assertThat(future.get(MAX_WAIT_SECS, TimeUnit.SECONDS)).isEqualTo(factory.supplying ? "value" : null);
      assertThat(future.defaultExecutor()).isSameAs(new CompletableFuture<>().defaultExecutor());
   }

   @ParameterizedTest
   @EnumSource(Factory.class)
   void testNullResultIsAllowed(final Factory factory) throws Exception {
      final var future = factory.start(() -> sneakyNull(), Runnable::run);
      assertThat(future.get(MAX_WAIT_SECS, TimeUnit.SECONDS)).isNull();
   }

   @ParameterizedTest
   @EnumSource(Factory.class)
   void testTaskFailures(final Factory factory) throws Exception {
      for (final var failure : List.of(new IllegalStateException("task"), new CancellationException("task"), new CompletionException(
         new IOException("task")), new AssertionError("task"))) {
         final var future = factory.start(() -> {
            if (failure instanceof Error)
               throw (Error) failure;
            throw (RuntimeException) failure;
         }, Runnable::run);
         if ((factory.throwing || factory.supplying) && failure instanceof Error) {
            // Even standard supplyAsync used a ThrowingFunction internally; only plain runAsync preserves a raw Error.
            final var cause = Objects.requireNonNull(failureOf(future).getCause());
            assertThat(cause).isExactlyInstanceOf(RuntimeException.class);
            assertThat(cause.getCause()).isSameAs(failure);
         } else {
            assertEncodedFailure(future, failure);
         }
      }
   }

   @Test
   void testCheckedFailuresKeepTheirAdapter() throws Exception {
      final var failure = new IOException("task");
      final ThrowingRunnable<IOException> runnable = () -> {
         throw failure;
      };
      final ThrowingSupplier<String, IOException> supplier = () -> {
         throw failure;
      };
      for (final var future : List.of(ExtendedFuture.runAsync(runnable), ExtendedFuture.runAsync(runnable, Runnable::run), ExtendedFuture
         .runAsyncWithDefaultExecutor(runnable, Runnable::run), ExtendedFuture.supplyAsync(supplier), ExtendedFuture.supplyAsync(supplier,
            Runnable::run), ExtendedFuture.supplyAsyncWithDefaultExecutor(supplier, Runnable::run))) {
         final var cause = Objects.requireNonNull(failureOf(future).getCause());
         assertThat(cause).isExactlyInstanceOf(RuntimeException.class);
         assertThat(cause.getCause()).isSameAs(failure);
      }
   }

   @ParameterizedTest
   @MethodSource("executorFactories")
   void testSubmissionFailuresBecomeFailedFutures(final Factory factory) throws Exception {
      for (final var failure : List.of(new RejectedExecutionException("executor"), new CancellationException("executor"),
         new CompletionException(new IOException("executor")), new AssertionError("executor"))) {
         final var calls = new AtomicInteger();
         final var future = factory.start(() -> {
            calls.incrementAndGet();
            return "unused";
         }, command -> {
            if (failure instanceof Error)
               throw (Error) failure;
            throw (RuntimeException) failure;
         });
         assertEncodedFailure(future, failure);
         assertThat(calls).hasValue(0);
         assertThat(future.isInterruptible()).isTrue();
      }
   }

   @ParameterizedTest
   @MethodSource("executorFactories")
   void testSubmissionFailureWinsAfterInlineExecution(final Factory factory) throws Exception {
      final var failure = new RejectedExecutionException("after execution");
      final var calls = new AtomicInteger();
      final var future = factory.start(() -> {
         calls.incrementAndGet();
         return "value";
      }, command -> {
         command.run();
         throw failure;
      });
      // Completed-source JDK stages publish the submission error even if an executor already ran the task inline.
      assertEncodedFailure(future, failure);
      assertThat(calls).hasValue(1);
   }

   @ParameterizedTest
   @MethodSource("executorFactories")
   void testQueuedCancellationSkipsUserCode(final Factory factory) {
      for (final boolean mayInterrupt : List.of(false, true)) {
         final var queue = new ArrayList<Runnable>();
         final var calls = new AtomicInteger();
         final var future = factory.start(() -> {
            calls.incrementAndGet();
            return "unused";
         }, queue::add);
         assertThat(queue).hasSize(1);
         assertThat(queue.get(0)).isInstanceOf(CompletableFuture.AsynchronousCompletionTask.class);
         assertThat(future.cancel(mayInterrupt)).isTrue();
         queue.get(0).run();
         assertThat(calls).hasValue(0);
         assertThat(future.isCancelled()).isTrue();
      }
   }

   @ParameterizedTest
   @EnumSource(Factory.class)
   void testNestedFactoryPreservesEnclosingCallbackOwnership(final Factory factory) {
      final var source = new ExtendedFuture<String>() {
         @Override
         public <V> ExtendedFuture<V> newIncompleteFuture() {
            final var nested = factory.start(() -> {
               // A known-owner task must not let this unrelated allocation consume the enclosing mapper's pending binding.
               super.<String>newIncompleteFuture().complete("unrelated");
               return "nested";
            }, Runnable::run);
            assertThat(nested.join()).isEqualTo(factory.supplying ? "nested" : null);
            return super.newIncompleteFuture();
         }
      };
      final var result = source.thenApply(value -> value + " mapped");
      source.complete("outer");
      // Binding to the already-completed unrelated future would skip this callback with a CancellationException.
      assertThat(result).isCompletedWithValue("outer mapped");
   }

   @ParameterizedTest
   @EnumSource(Factory.class)
   void testRunningCancellationHonorsInterruptFlag(final Factory factory) throws Exception {
      for (final boolean mayInterrupt : List.of(false, true)) {
         final var executor = Executors.newSingleThreadExecutor();
         final var started = new CountDownLatch(1);
         final var release = new CountDownLatch(1);
         final var finished = new CountDownLatch(1);
         final var interrupted = new AtomicBoolean();
         try {
            final var future = factory.start(() -> {
               started.countDown();
               try {
                  assertThat(release.await(10, TimeUnit.SECONDS)).as("test must release the task").isTrue();
               } catch (final InterruptedException ex) {
                  interrupted.set(true);
               } finally {
                  finished.countDown();
               }
               return "value";
            }, executor);
            assertThat(started.await(MAX_WAIT_SECS, TimeUnit.SECONDS)).isTrue();
            assertThat(future.cancel(mayInterrupt)).isTrue();
            if (!mayInterrupt) {
               assertThat(finished.getCount()).as("cancel(false) must leave running work alone").isOne();
               release.countDown();
            }
            // A cancelled future is already terminal; wait on user-code exit to prove actual interruption.
            assertThat(finished.await(MAX_WAIT_SECS, TimeUnit.SECONDS)).isTrue();
            assertThat(interrupted.get()).isEqualTo(mayInterrupt);
         } finally {
            release.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(MAX_WAIT_SECS, TimeUnit.SECONDS)).isTrue();
         }
      }
   }
}
