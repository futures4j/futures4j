/*
 * SPDX-FileCopyrightText: © Sebastian Thomschke
 * SPDX-License-Identifier: EPL-2.0
 * SPDX-FileContributor: Sebastian Thomschke: initial API and implementation
 */
package io.github.futures4j;

import static io.github.futures4j.AbstractFutureTest.TaskState.*;
import static io.github.futures4j.CompletionState.CANCELLED;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.Supplier;

import org.eclipse.jdt.annotation.Nullable;
import org.junit.jupiter.api.MethodOrderer.MethodName;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.Timeout;

import io.github.futures4j.ExtendedFuture.WrappingFuture;
import net.sf.jstuff.core.concurrent.Threads;
import net.sf.jstuff.core.ref.MutableObservableRef;
import net.sf.jstuff.core.ref.MutableRef;
import net.sf.jstuff.core.ref.Ref;

/**
 * @author <a href="https://sebthom.de/">Sebastian Thomschke</a>
 */
@TestMethodOrder(MethodName.class)
@Timeout(10)
abstract class AbstractFutureTest {

   enum TaskState {
      NEW,
      RUNNING,
      INTERRUPTED,
      DONE
   }

   static final int MAX_WAIT_SECS = 3;

   void assertTaskState(final Ref<TaskState> actualState, final TaskState expectedState) {
      assertThat(actualState.get()).isEqualTo(expectedState);
   }

   boolean await(final BooleanSupplier condition) throws InterruptedException {
      return await(condition, 50, MAX_WAIT_SECS, TimeUnit.SECONDS);
   }

   boolean await(final BooleanSupplier condition, final int checkIntervalMS, final long timeout, final TimeUnit unit)
         throws InterruptedException {
      final long timeoutMS = unit.toMillis(timeout);
      final long startTime = System.currentTimeMillis();

      while (!condition.getAsBoolean()) {
         if (System.currentTimeMillis() - startTime >= timeoutMS)
            return false;
         Thread.sleep(checkIntervalMS);
      }
      return true;
   }

   void awaitFutureState(final @Nullable Future<?> future, final CompletionState expectedState) throws InterruptedException {
      assert future != null;
      await(() -> CompletionState.of(future) == expectedState);
      assertThat(CompletionState.of(future)).isEqualTo(expectedState);
   }

   void awaitTaskState(final Ref<TaskState> stateHolder, final TaskState expectedState) throws InterruptedException {
      await(() -> stateHolder.get() == expectedState);
      assertTaskState(stateHolder, expectedState);
   }

   void awaitTaskStateNOT(final Ref<TaskState> stateHolder, final TaskState expectedState) throws InterruptedException {
      await(() -> stateHolder.get() != expectedState);
      assertThat(stateHolder.get()).isNotEqualTo(expectedState);
   }

   Runnable createTask(final MutableRef<TaskState> state, final long waitMS) {
      return () -> {
         state.set(RUNNING);
         try {
            Thread.sleep(waitMS);
         } catch (final InterruptedException e) {
            Thread.interrupted();
            state.set(INTERRUPTED);
            return;
         }
         state.set(DONE);
      };
   }

   private <U> void testCompletedFuture(final CompletableFuture<U> completedFuture, final U value) {
      assertThat(completedFuture).isCompletedWithValue(value);

      final var result = MutableRef.create();
      completedFuture.whenComplete((r, ex) -> {
         if (ex != null) {
            result.set(ex);
         } else {
            result.set(r);
         }
      });
      assertThat(result.get()).isEqualTo(value);

      assertThat(completedFuture.cancel(true)).isFalse();

      if (completedFuture instanceof WrappingFuture) { // CHECKSTYLE:IGNORE .*
         testCompletedFuture(((WrappingFuture<U>) completedFuture).wrapped, value);
      }
   }

   void testCompletedFuture(final Supplier<CompletableFuture<@Nullable String>> newFutureFactory,
         final Function<@Nullable String, CompletableFuture<@Nullable String>> completedFutureFactory) throws InterruptedException {
      testCompletedFuture(completedFutureFactory.apply("value"), "value");
      testCompletedFuture(completedFutureFactory.apply(null), null);

      var completableFuture = newFutureFactory.get();
      completableFuture.complete("value");
      testCompletedFuture(completableFuture, "value");

      completableFuture = newFutureFactory.get();
      completableFuture.complete(null);
      testCompletedFuture(completableFuture, null);

      completableFuture = newFutureFactory.get();
      completableFuture.completeOnTimeout("value", 100, TimeUnit.MILLISECONDS);
      awaitFutureState(completableFuture, CompletionState.SUCCESS);
      testCompletedFuture(completableFuture, "value");

      completableFuture = newFutureFactory.get();
      completableFuture.completeAsync(() -> "value");
      awaitFutureState(completableFuture, CompletionState.SUCCESS);
      testCompletedFuture(completableFuture, "value");

      completableFuture = newFutureFactory.get();
      completableFuture.completeExceptionally(new RuntimeException());
      awaitFutureState(completableFuture, CompletionState.FAILED);
   }

   void testCopy(final Supplier<CompletableFuture<@Nullable String>> newFutureFactory) {
      final var future = newFutureFactory.get();
      final var copy = future.copy();
      final var minimalStage = future.minimalCompletionStage().toCompletableFuture();

      assertThat(copy).isNotEqualTo(future);
      assertThat(minimalStage).isNotEqualTo(future);

      assertThat(future).isNotCompleted();
      assertThat(copy).isNotCompleted();
      assertThat(minimalStage).isNotCompleted();

      future.complete("foo");

      assertThat(future).isCompleted();
      assertThat(copy).isCompleted();
      assertThat(minimalStage).isCompleted();

      assertThat(future).isCompletedWithValue("foo");
      assertThat(copy).isCompletedWithValue("foo");
      assertThat(minimalStage).isCompletedWithValue("foo");
   }

   void testMultipleStagesCancelDownstream(final Function<Runnable, CompletableFuture<?>> futureFactory, final boolean supportsInterrupt)
         throws InterruptedException {
      testMultipleStagesCancelDownstream(futureFactory, supportsInterrupt, false);
      testMultipleStagesCancelDownstream(futureFactory, supportsInterrupt, true);
   }

   private void testMultipleStagesCancelDownstream(final Function<Runnable, CompletableFuture<?>> futureFactory,
         final boolean supportsInterrupt, final boolean tryCancelWithInterrupt) throws InterruptedException {
      final MutableRef<TaskState> stage1State = MutableRef.of(NEW);
      final var stage1 = futureFactory.apply(createTask(stage1State, 500));

      final MutableRef<TaskState> stage2State = MutableRef.of(NEW);
      final var stage2 = stage1.thenRun(createTask(stage2State, 500));

      final MutableRef<TaskState> stage3State = MutableRef.of(NEW);
      final var stage3 = stage2.thenRun(createTask(stage2State, 500));

      awaitTaskState(stage1State, RUNNING);

      assertThat(stage1).isNotCompleted();
      assertThat(stage2).isNotCompleted();
      assertThat(stage3).isNotCompleted();
      assertTaskState(stage1State, RUNNING);
      assertTaskState(stage2State, NEW);
      assertTaskState(stage3State, NEW);

      stage1.cancel(tryCancelWithInterrupt); // cancels this and subsequent stages

      awaitFutureState(stage1, CANCELLED);
      assertThat(stage2).isCompletedExceptionally(); // through CancellationException
      assertThat(stage3).isCompletedExceptionally(); // through CancellationException

      awaitTaskStateNOT(stage1State, RUNNING);
      if (tryCancelWithInterrupt && supportsInterrupt) {
         assertTaskState(stage1State, INTERRUPTED);
      } else {
         assertTaskState(stage1State, DONE); // stage1 was execute (but because it was cancelled the result is not accessible)
      }
      assertTaskState(stage2State, NEW); // stage2 was cancelled
      assertTaskState(stage3State, NEW); // stage3 was cancelled
   }

   void testMultipleStagesCancelUpstream(final Function<Runnable, CompletableFuture<?>> futureFactory, final boolean supportsUpstreamCancel,
         final boolean supportsInterrupt) throws InterruptedException {
      testMultipleStagesCancelUpstream(futureFactory, supportsUpstreamCancel, supportsInterrupt, false);
      testMultipleStagesCancelUpstream(futureFactory, supportsUpstreamCancel, supportsInterrupt, true);
   }

   void testMultipleStagesCancelUpstream(final Function<Runnable, CompletableFuture<?>> futureFactory, final boolean cancelsPrecedingStages,
         final boolean supportsInterrupt, final boolean tryCancelWithInterrupt) throws InterruptedException {
      final MutableRef<TaskState> stage1State = MutableObservableRef.of(NEW);
      final var stage1 = futureFactory.apply(createTask(stage1State, 500));

      final MutableRef<TaskState> stage2State = MutableObservableRef.of(NEW);
      final var stage2 = stage1.thenRun(createTask(stage2State, 500));

      final MutableRef<TaskState> stage3State = MutableRef.of(NEW);
      final var stage3 = stage2.thenRun(createTask(stage2State, 500));

      awaitTaskState(stage1State, RUNNING);

      assertThat(stage1).isNotCompleted();
      assertThat(stage2).isNotCompleted();
      assertThat(stage3).isNotCompleted();
      assertTaskState(stage1State, RUNNING);
      assertTaskState(stage2State, NEW);
      assertTaskState(stage3State, NEW);

      stage3.cancel(tryCancelWithInterrupt);
      awaitFutureState(stage3, CANCELLED);

      if (cancelsPrecedingStages) {
         assertThat(stage1).isCancelled();
         assertThat(stage2).isCancelled();

         awaitTaskStateNOT(stage1State, RUNNING);

         assertTaskState(stage1State, tryCancelWithInterrupt && supportsInterrupt //
               ? INTERRUPTED // stage1 was interrupted
               : DONE); // stage1 was execute (but because it was cancelled the result is not accessible)
         assertTaskState(stage2State, NEW); // was cancelled
      } else {
         assertThat(stage1).isNotCompleted();
         assertThat(stage2).isNotCompleted();

         awaitTaskStateNOT(stage1State, RUNNING);
         awaitTaskState(stage2State, DONE);

         assertThat(stage1).isCompleted();
         assertThat(stage2).isCompleted();
         assertThat(stage3).isCancelled();

         assertTaskState(stage1State, DONE); // stage1 was execute
         assertTaskState(stage2State, DONE); // stage2 was execute
      }
      assertTaskState(stage3State, NEW); // stage3 was cancelled
   }

   void testSingleStageCancel(final Function<Runnable, Future<?>> futureFactory, final boolean supportsInterrupt)
         throws InterruptedException {
      // test cancel
      {
         final MutableRef<TaskState> stage1State = MutableObservableRef.of(NEW);
         final var stage1 = futureFactory.apply(createTask(stage1State, 500));

         awaitTaskState(stage1State, RUNNING);

         stage1.cancel(false);

         awaitFutureState(stage1, CANCELLED);
         assertTaskState(stage1State, RUNNING);

         awaitTaskStateNOT(stage1State, RUNNING);
         assertTaskState(stage1State, DONE);
      }
      // test interrupt
      {
         final MutableRef<TaskState> stage1State = MutableObservableRef.of(NEW);
         final var stage1 = futureFactory.apply(createTask(stage1State, 500));

         awaitTaskState(stage1State, RUNNING);

         stage1.cancel(true); // interrupt
         awaitFutureState(stage1, CANCELLED);

         awaitTaskStateNOT(stage1State, RUNNING);
         assertTaskState(stage1State, supportsInterrupt ? INTERRUPTED : DONE);
      }
      // test interrupt after cancel
      {
         final MutableRef<TaskState> stage1State = MutableObservableRef.of(NEW);
         final var stage1 = futureFactory.apply(createTask(stage1State, 500));

         awaitTaskState(stage1State, RUNNING);

         stage1.cancel(false);

         awaitFutureState(stage1, CANCELLED);
         assertTaskState(stage1State, RUNNING);

         stage1.cancel(true); // ignored because already cancelled
         Threads.sleep(100);

         awaitTaskStateNOT(stage1State, RUNNING);
         assertTaskState(stage1State, DONE);
      }
   }
}
