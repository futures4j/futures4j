/*
 * SPDX-FileCopyrightText: © Sebastian Thomschke
 * SPDX-License-Identifier: EPL-2.0
 * SPDX-FileContributor: Sebastian Thomschke: initial API and implementation
 */
package io.github.futures4j;

import static io.github.futures4j.AbstractFutureTest.TaskState.DONE;
import static io.github.futures4j.CompletionState.*;

import java.util.concurrent.CompletableFuture;

import org.eclipse.jdt.annotation.Nullable;
import org.junit.jupiter.api.Test;

import net.sf.jstuff.core.ref.MutableRef;

/**
 * Tests to verify the cancellation behaviour of Java's {@link CompletableFuture}.
 *
 * @author <a href="https://sebthom.de/">Sebastian Thomschke</a>
 */
class CompletableFutureTest extends AbstractFutureTest {

   @Test
   void testCompletedFuture() throws InterruptedException {
      testCompletedFuture(CompletableFuture::new, CompletableFuture::completedFuture);
   }

   @Test
   void testCopy() {
      testCopy(CompletableFuture::new);
   }

   @Test
   void testMultipleStagesCancelDownstream() throws InterruptedException {
      testMultipleStagesCancelDownstream(CompletableFuture::runAsync, false);
   }

   /**
    * Upstream cancellation is not supported by {@link CompletableFuture}
    */
   @Test
   void testMultipleStagesCancelUpstream() throws InterruptedException {
      testMultipleStagesCancelUpstream(CompletableFuture::runAsync, false, false);
   }

   @Test
   void testNestedStageCancel() throws InterruptedException {
      final MutableRef<TaskState> stage1NestedState = MutableRef.of(TaskState.NEW);
      final MutableRef<@Nullable CompletableFuture<?>> stage1Nested = MutableRef.create();
      final var stage1 = CompletableFuture //
         .completedFuture(null) //
         .thenCompose(result -> {
            final var nestedStage = CompletableFuture //
               .runAsync(createTask(stage1NestedState, 1_000));
            stage1Nested.set(nestedStage);
            return nestedStage;
         });

      awaitTaskStateNOT(stage1NestedState, TaskState.NEW);

      stage1.cancel(true);

      awaitFutureState(stage1, CANCELLED);

      // nested future is not affected by cancellation of outer chain
      awaitFutureState(stage1Nested.get(), SUCCESS);
      awaitTaskState(stage1NestedState, DONE);
   }

   @Test
   void testSingleStageCancel() throws InterruptedException {
      testSingleStageCancel(CompletableFuture::runAsync, false);
   }
}
