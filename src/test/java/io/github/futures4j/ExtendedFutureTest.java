/*
 * SPDX-FileCopyrightText: © Sebastian Thomschke
 * SPDX-License-Identifier: EPL-2.0
 * SPDX-FileContributor: Sebastian Thomschke: initial API and implementation
 */
package io.github.futures4j;

import static io.github.futures4j.AbstractFutureTest.TaskState.INTERRUPTED;
import static io.github.futures4j.CompletionState.CANCELLED;
import static org.assertj.core.api.Assertions.*;

import java.lang.ref.WeakReference;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.eclipse.jdt.annotation.Nullable;
import org.junit.jupiter.api.Test;

import io.github.futures4j.ExtendedFuture.ReadOnlyMode;
import net.sf.jstuff.core.ref.MutableRef;

/**
 * @author <a href="https://sebthom.de/">Sebastian Thomschke</a>
 */
class ExtendedFutureTest extends AbstractFutureTest {

   @Test
   void testAsReadOnly_withIgnoreMutationAttempt() {
      final var originalFuture = ExtendedFuture.create();
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
      final var originalFuture = ExtendedFuture.create();
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
   void testCompletedFuture() {
      testCompletedFuture(ExtendedFuture::create, ExtendedFuture::completedFuture);
      testCompletedFuture(ExtendedFuture::create, value -> ExtendedFuture.from(CompletableFuture.completedFuture(value))
         .asCancellableByDependents(true));

      assertThat(ExtendedFuture.from(CompletableFuture.completedFuture(null)).isInterruptible()).isFalse();
      assertThat(ExtendedFuture.from(CompletableFuture.completedFuture(null)).isInterruptibleStages()).isTrue();
   }

   @Test
   void testCopy() {
      testCopy(ExtendedFuture::create);
   }

   @Test
   void testGetCompletionState() {
      final var cancelledFuture = ExtendedFuture.create();
      cancelledFuture.cancel(true);
      assertThat(cancelledFuture.getCompletionState()).isEqualTo(CANCELLED);

      final var exceptionallyCompletedFuture = ExtendedFuture.create();
      exceptionallyCompletedFuture.completeExceptionally(new RuntimeException("Error"));
      assertThat(exceptionallyCompletedFuture.getCompletionState()).isEqualTo(CompletionState.FAILED);

      final var completedFuture = ExtendedFuture.completedFuture("Success");
      assertThat(completedFuture.getCompletionState()).isEqualTo(CompletionState.COMPLETED);

      final var incompleteFuture = ExtendedFuture.create();
      assertThat(incompleteFuture.getCompletionState()).isEqualTo(CompletionState.INCOMPLETE);
   }

   @Test
   void testMultipleStagesCancelDownstream() throws InterruptedException {
      testMultipleStagesCancelDownstream(ExtendedFuture::runAsync, true);
      testMultipleStagesCancelDownstream(runnable -> ExtendedFuture.from(CompletableFuture.completedFuture(null)) //
         .asCancellableByDependents(true) //
         .thenRunAsync(runnable), true);
   }

   @Test
   void testBuilder() {

      {
         final var future = ExtendedFuture.builder().build();

         assertThat(future).isInstanceOf(ExtendedFuture.InterruptibleFuture.class);
         assertThat(future.isInterruptible()).isTrue();
         assertThat(future.isInterruptibleStages()).isTrue();
         assertThat(future.isCancellableByDependents()).isFalse();
         assertThat(future.defaultExecutor()).isNotNull();
         assertThat(future.getCompletionState()).isEqualTo(CompletionState.INCOMPLETE);
      }

      {
         final Executor customExecutor = Executors.newSingleThreadExecutor();
         final var wrappedFuture = CompletableFuture.completedFuture("Hello");

         final var future = ExtendedFuture.builder() //
            .withInterruptible(false) //
            .withInterruptibleStages(false) //
            .withCancellableByDependents(true) //
            .withDefaultExecutor(customExecutor) //
            .withWrapped(wrappedFuture) //
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
   void testMultipleStagesCancelUpstream() throws InterruptedException {
      final var parent = ExtendedFuture.create();
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
   void testSingleStageCancel() throws InterruptedException {
      testSingleStageCancel(ExtendedFuture::runAsync, true);
      testSingleStageCancel(runnable -> ExtendedFuture.from(CompletableFuture.completedFuture(null)) //
         .asCancellableByDependents(true) //
         .thenRunAsync(runnable), true);
   }

   @Test
   void testWithDefaultExecutor() {
      final var defaultExecutor1 = Executors.newSingleThreadExecutor();
      final var defaultExecutor2 = Executors.newSingleThreadExecutor();
      final var originalFuture = ExtendedFuture.builder().withDefaultExecutor(defaultExecutor1).build();
      final var sameFuture = originalFuture.withDefaultExecutor(defaultExecutor1);
      final var newFuture = originalFuture.withDefaultExecutor(defaultExecutor2);

      assertThat(sameFuture).isSameAs(originalFuture);
      assertThat(newFuture).isNotSameAs(originalFuture);
      assertThat(newFuture.defaultExecutor()).isSameAs(defaultExecutor2); // Assuming a getter

      defaultExecutor1.shutdown();
      defaultExecutor2.shutdown();
   }
}
