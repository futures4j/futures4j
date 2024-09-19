/*
 * SPDX-FileCopyrightText: © Sebastian Thomschke
 * SPDX-License-Identifier: EPL-2.0
 * SPDX-FileContributor: Sebastian Thomschke: initial API and implementation
 */
package io.github.futures4j;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * @author <a href="https://sebthom.de/">Sebastian Thomschke</a>
 */
@Timeout(5)
class FutureManagerTest {

   private FutureManager<String> mgr = new FutureManager<>();

   @Test
   void testDeregisterFuture() {
      final CompletableFuture<String> future1 = new CompletableFuture<>();

      mgr.register(future1);
      mgr.deregister(future1);

      assertThat(mgr.hasAnyIncomplete()).isFalse();
   }

   @Test
   void testJoinAll() {
      final ExecutorService executor = Executors.newCachedThreadPool();

      try {
         final CompletableFuture<String> future1 = new CompletableFuture<>();
         final CompletableFuture<String> future2 = new CompletableFuture<>();

         mgr.register(future1);
         mgr.register(future2);

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

         assertThat(mgr.joinAll().results()).containsValues("result1", "result2");
      } finally {
         executor.shutdown();
      }
   }

   @Test
   void testGetAllNow() {
      final CompletableFuture<String> future1 = CompletableFuture.completedFuture("result1");
      final CompletableFuture<String> future2 = CompletableFuture.completedFuture("result2");

      mgr.register(future1);
      mgr.register(future2);

      assertThat(mgr.getAllNow().results()).containsValues("result1", "result2");
   }

   @Test
   void testHasAnyIncomplete() {
      final CompletableFuture<String> future1 = new CompletableFuture<>();

      mgr.register(future1);
      assertThat(mgr.hasAnyIncomplete()).isTrue();

      future1.complete("result");
      assertThat(mgr.hasAnyIncomplete()).isFalse();
   }

   @Test
   void testRegisterAndCancelAllFutures() {
      final CompletableFuture<String> future1 = new CompletableFuture<>();
      final CompletableFuture<String> future2 = new CompletableFuture<>();

      mgr.register(future1);
      mgr.register(future2);

      mgr.cancelAll(true);

      assertThat(future1.isCancelled()).isTrue();
      assertThat(future2.isCancelled()).isTrue();
      assertThat(mgr.isCancelled()).isTrue();
   }
}
