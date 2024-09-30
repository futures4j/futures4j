/*
 * SPDX-FileCopyrightText: © Sebastian Thomschke
 * SPDX-License-Identifier: EPL-2.0
 * SPDX-FileContributor: Sebastian Thomschke: initial API and implementation
 */
package io.github.futures4j;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.FutureTask;

import org.junit.jupiter.api.Test;

/**
 * @author <a href="https://sebthom.de/">Sebastian Thomschke</a>
 */
class CompletionStateTest {

   @Test
   void testCancelledFuture() {
      final var cancelledFuture = new FutureTask<>(() -> "Success");
      cancelledFuture.cancel(true);

      assertThat(CompletionState.of(cancelledFuture)).isEqualTo(CompletionState.CANCELLED);
   }

   @Test
   void testCompletedExceptionallyFuture() {
      final var exceptionallyCompletedFuture = new CompletableFuture<>();
      exceptionallyCompletedFuture.completeExceptionally(new RuntimeException("Error"));

      assertThat(CompletionState.of(exceptionallyCompletedFuture)).isEqualTo(CompletionState.FAILED);
   }

   @Test
   void testCompletedFuture() {
      final var completedFuture = CompletableFuture.completedFuture("Success");

      assertThat(CompletionState.of(completedFuture)).isEqualTo(CompletionState.SUCCESS);
   }

   @Test
   void testIncompleteFuture() {
      final var incompleteFuture = new FutureTask<>(() -> "Success");

      assertThat(CompletionState.of(incompleteFuture)).isEqualTo(CompletionState.INCOMPLETE);
   }
}
