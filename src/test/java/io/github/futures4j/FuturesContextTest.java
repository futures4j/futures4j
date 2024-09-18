/*
 * SPDX-FileCopyrightText: © Sebastian Thomschke
 * SPDX-License-Identifier: EPL-2.0
 * SPDX-FileContributor: Sebastian Thomschke: initial API and implementation
 */
package io.github.futures4j;

import static org.assertj.core.api.Assertions.*;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * @author <a href="https://sebthom.de/">Sebastian Thomschke</a>
 */
@Timeout(5)
class FuturesContextTest {

   private FuturesContext<String> ctx = new FuturesContext<>();

   @Test
   void testDeregisterFuture() {
      final CompletableFuture<String> future1 = new CompletableFuture<>();

      ctx.register(future1);
      ctx.deregister(future1);

      assertThat(ctx.hasAnyIncomplete()).isFalse();
   }

   @Test
   void testGetAll() {
      final ExecutorService executor = Executors.newCachedThreadPool();

      try {
         final CompletableFuture<String> future1 = new CompletableFuture<>();
         final CompletableFuture<String> future2 = new CompletableFuture<>();

         ctx.register(future1);
         ctx.register(future2);

         executor.submit(() -> {
            try {
               Thread.sleep(100);
               future1.complete("result1");
            } catch (final InterruptedException e) {
               Thread.currentThread().interrupt();
            }
         });

         executor.submit(() -> {
            try {
               Thread.sleep(100);
               future2.complete("result2");
            } catch (final InterruptedException e) {
               Thread.currentThread().interrupt();
            }
         });

         assertThat(ctx.getAll()).contains("result1", "result2");
      } finally {
         executor.shutdown();
      }
   }

   @Test
   void testGetAllNow() {
      final CompletableFuture<String> future1 = CompletableFuture.completedFuture("result1");
      final CompletableFuture<String> future2 = CompletableFuture.completedFuture("result2");

      ctx.register(future1);
      ctx.register(future2);

      assertThat(ctx.getAllNow()).contains("result1", "result2");
   }

   @Test
   void testGetAllNowOrThrow() {
      final CompletableFuture<String> future1 = new CompletableFuture<>();
      future1.completeExceptionally(new RuntimeException("Test exception"));

      ctx.register(future1);
      assertThatThrownBy(() -> ctx.getAllNowOrThrow()).hasMessageContaining("Test exception");
   }

   @Test
   void testHasAnyIncomplete() {
      final CompletableFuture<String> future1 = new CompletableFuture<>();

      ctx.register(future1);
      assertThat(ctx.hasAnyIncomplete()).isTrue();

      future1.complete("result");
      assertThat(ctx.hasAnyIncomplete()).isFalse();
   }

   @Test
   void testRegisterAndCancelAllFutures() {
      final CompletableFuture<String> future1 = new CompletableFuture<>();
      final CompletableFuture<String> future2 = new CompletableFuture<>();

      ctx.register(future1);
      ctx.register(future2);

      ctx.cancelAll(true);

      assertThat(future1.isCancelled()).isTrue();
      assertThat(future2.isCancelled()).isTrue();
      assertThat(ctx.isCancelled()).isTrue();
   }
}
