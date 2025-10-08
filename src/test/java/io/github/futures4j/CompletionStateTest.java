/*
 * SPDX-FileCopyrightText: © Sebastian Thomschke
 * SPDX-License-Identifier: EPL-2.0
 * SPDX-FileContributor: Sebastian Thomschke: initial API and implementation
 */
package io.github.futures4j;

import static io.github.futures4j.CompletionState.*;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

/**
 * @author <a href="https://sebthom.de/">Sebastian Thomschke</a>
 */
class CompletionStateTest extends AbstractFutureTest {

   @Test
   void testCompletableFutureStates() {
      { // incomplete future
         final var future = new CompletableFuture<>();
         assertThat(CompletionState.of(future)).isEqualTo(INCOMPLETE);
      }

      { // completed future
         final var future = CompletableFuture.completedFuture("Success");
         assertThat(CompletionState.of(future)).isEqualTo(SUCCESS);
      }

      { // cancelled future
         final var future = new CompletableFuture<>();
         future.cancel(true);
         assertThat(CompletionState.of(future)).isEqualTo(CANCELLED);
      }

      { // failed future
         final var future = new CompletableFuture<>();
         future.completeExceptionally(new RuntimeException("Error"));
         assertThat(CompletionState.of(future)).isEqualTo(FAILED);
      }
   }

   @Test
   void testFutureJoinTaskStates() throws InterruptedException {
      final var pool = new ForkJoinPool();

      { // incomplete future
         final var future = pool.submit(() -> {
            Thread.sleep(200);
            return "Success";
         });
         assertThat(CompletionState.of(future)).isEqualTo(INCOMPLETE);
      }

      { // completed future
         final var future = pool.submit(() -> "Success");
         await(() -> CompletionState.of(future) != INCOMPLETE, 50, 5, TimeUnit.SECONDS);
         assertThat(CompletionState.of(future)).isEqualTo(SUCCESS);
      }

      { // cancelled future
         final var future = pool.submit(() -> {
            Thread.sleep(200);
            return "Success";
         });
         future.cancel(true);
         await(() -> CompletionState.of(future) != INCOMPLETE, 50, 5, TimeUnit.SECONDS);
         assertThat(CompletionState.of(future)).isEqualTo(CANCELLED);
      }

      { // failed future
         final var future = pool.submit(() -> {
            throw new RuntimeException();
         });
         await(() -> CompletionState.of(future) != INCOMPLETE, 50, 5, TimeUnit.SECONDS);
         assertThat(CompletionState.of(future)).isEqualTo(FAILED);
      }

      pool.shutdown();
   }

   @Test
   void testJava8FutureStates() throws Exception {
      final var executor = Executors.newSingleThreadExecutor();

      { // incomplete future
         final var future = executor.submit(() -> {
            Thread.sleep(200);
            return "Success";
         });
         assertThat(CompletionState.of(future)).isEqualTo(INCOMPLETE);
      }

      { // completed future
         final var future = executor.submit(() -> "Success");
         future.get(5, TimeUnit.SECONDS);
         assertThat(CompletionState.of(future)).isEqualTo(SUCCESS);
      }

      { // cancelled future
         final var future = executor.submit(() -> {
            Thread.sleep(200);
            return "Success";
         });
         future.cancel(true);
         assertThat(CompletionState.of(future)).isEqualTo(CANCELLED);
      }

      { // failed future
         final var future = executor.submit(() -> {
            throw new RuntimeException();
         });
         await(() -> CompletionState.of(future) != INCOMPLETE, 50, 5, TimeUnit.SECONDS);
         assertThat(CompletionState.of(future)).isEqualTo(FAILED);
      }

      executor.shutdown();
   }
}
