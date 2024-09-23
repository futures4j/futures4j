/*
 * SPDX-FileCopyrightText: © Sebastian Thomschke
 * SPDX-License-Identifier: EPL-2.0
 * SPDX-FileContributor: Sebastian Thomschke: initial API and implementation
 */
package io.github.futures4j;

import static org.assertj.core.api.Assertions.*;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

import org.eclipse.jdt.annotation.Nullable;
import org.junit.jupiter.api.Test;

import net.sf.jstuff.core.concurrent.Threads;

/**
 * @author <a href="https://sebthom.de/">Sebastian Thomschke</a>
 */
class FuturesTest extends AbstractFutureTest {

   @Test
   void testCancel() {
      Futures.cancel((Future<?>) null);
      Futures.cancelAll((Future<?>[]) null);
      Futures.cancelAll((Collection<? extends Future<?>>) null);

      final var future1 = ExtendedFuture.create();
      final var future2 = ExtendedFuture.create();
      final var future3 = ExtendedFuture.create();
      final var future4 = ExtendedFuture.create();
      final var future5 = ExtendedFuture.create();

      Futures.cancel(future1);
      Futures.cancelAll(future2, future3, null);
      Futures.cancelAll(List.of(future4, future5));

      assertThat(future1).isCancelled();
      assertThat(future2).isCancelled();
      assertThat(future3).isCancelled();
      assertThat(future4).isCancelled();
      assertThat(future5).isCancelled();
   }

   @Test
   @SuppressWarnings("null")
   void testCombine_ToList() throws InterruptedException, ExecutionException {
      final var fut1 = ExtendedFuture.supplyAsync(() -> {
         Threads.sleep(100);
         return List.of("a", "b");
      });
      final var fut2 = ExtendedFuture.supplyAsync(() -> new TreeSet<>(Set.of("c", "d")));
      final var fut3 = ExtendedFuture.supplyAsync(() -> List.of("e", "f"));
      final var fut4 = ExtendedFuture.supplyAsync(() -> List.of("g", "h"));
      final var fut5 = ExtendedFuture.failedFuture(new RuntimeException("oh no!"));

      assertThat(Futures.combine(fut1, fut2).toList().isCompleted()).isFalse();
      assertThat(Futures.combine(fut1, fut2).toList().isInterruptible()).isFalse();
      assertThat(Futures.combine(fut1, fut2).toList().isInterruptibleStages()).isTrue();
      assertThat(Futures.combine(fut1, fut2).toList().isReadOnly()).isFalse();

      assertThat(Futures.combine(fut1).toList().get()).containsExactly(fut1.get());
      assertThat(Futures.combine(fut1, fut2).toList().get()).containsExactly(fut1.get(), fut2.get());
      assertThat(Futures.combine(fut1, fut2, fut3).toList().get()).containsExactly(fut1.get(), fut2.get(), fut3.get());
      assertThat(Futures.combine(fut1, fut2, fut3, fut4).toList().get()).containsExactly(fut1.get(), fut2.get(), fut3.get(), fut4.get());
      assertThatThrownBy(() -> {
         Futures.combine(fut1, fut5).toList().get();
      }).isExactlyInstanceOf(ExecutionException.class) //
         .hasRootCauseInstanceOf(RuntimeException.class) //
         .hasRootCauseMessage("oh no!");

      assertThat(Futures.getAll(fut1)).containsExactly(fut1.get());
      assertThat(Futures.getAll(fut1, fut2)).containsExactly(fut1.get(), fut2.get());
      assertThat(Futures.getAll(fut1, fut2, fut3)).containsExactly(fut1.get(), fut2.get(), fut3.get());
      assertThat(Futures.getAll(fut1, fut2, fut3, fut4)).containsExactly(fut1.get(), fut2.get(), fut3.get(), fut4.get());
      assertThatThrownBy(() -> {
         Futures.getAll(fut1, fut5);
      }).isExactlyInstanceOf(ExecutionException.class) //
         .hasRootCauseInstanceOf(RuntimeException.class) //
         .hasRootCauseMessage("oh no!");
   }

   @Test
   @SuppressWarnings("null")
   void testCombine_ToMap() throws InterruptedException, ExecutionException {
      final var fut1 = ExtendedFuture.supplyAsync(() -> {
         Threads.sleep(100);
         return List.of("a", "b");
      });
      final var fut2 = ExtendedFuture.supplyAsync(() -> Set.of("a", "c"));
      final var fut3 = ExtendedFuture.supplyAsync(() -> List.of("a", "b"));
      final var fut4 = ExtendedFuture.supplyAsync(() -> Set.of("a", "e"));

      assertThat(Futures.combine(fut1, fut2).toMap().isCompleted()).isFalse();
      assertThat(Futures.combine(fut1, fut2).toMap().isInterruptible()).isFalse();
      assertThat(Futures.combine(fut1, fut2).toMap().isInterruptibleStages()).isTrue();
      assertThat(Futures.combine(fut1, fut2).toMap().isReadOnly()).isFalse();

      assertThat(Futures.combine(fut1).toMap().get()).isEqualTo(Map.of(fut1, fut1.get()));
      assertThat(Futures.combine(fut1, fut2).toMap().get()).isEqualTo(Map.of(fut1, fut1.get(), fut2, fut2.get()));
      assertThat(Futures.combine(fut1, fut2, fut3).toMap().get()).isEqualTo(Map.of(fut1, fut1.get(), fut2, fut2.get(), fut3, fut3.get()));
      assertThat(Futures.combine(fut1, fut2, fut3, fut4).toMap().get()).isEqualTo(Map.of(fut1, fut1.get(), fut2, fut2.get(), fut3, fut3
         .get(), fut4, fut4.get()));
   }

   @Test
   void testCombine_ToSet() throws InterruptedException, ExecutionException {
      final var fut1 = ExtendedFuture.supplyAsync(() -> {
         Threads.sleep(100);
         return List.of("a", "b");
      });
      final var fut2 = ExtendedFuture.supplyAsync(() -> Set.of("a", "c"));
      final var fut3 = ExtendedFuture.supplyAsync(() -> List.of("a", "b"));
      final var fut4 = ExtendedFuture.supplyAsync(() -> Set.of("a", "e"));

      assertThat(Futures.combine(fut1, fut2).toSet().isCompleted()).isFalse();
      assertThat(Futures.combine(fut1, fut2).toSet().isInterruptible()).isFalse();
      assertThat(Futures.combine(fut1, fut2).toSet().isInterruptibleStages()).isTrue();
      assertThat(Futures.combine(fut1, fut2).toSet().isReadOnly()).isFalse();

      assertThat(Futures.combine(fut1).toSet().get()).containsExactlyInAnyOrder(fut1.get());
      assertThat(Futures.combine(fut1, fut2).toSet().get()).containsExactlyInAnyOrder(fut1.get(), fut2.get());
      assertThat(Futures.combine(fut1, fut3).toSet().get()).containsExactlyInAnyOrder(fut1.get()); // Since fut1 and fut3 are the same
      assertThat(Futures.combine(fut1, fut2, fut3).toSet().get()).containsExactlyInAnyOrder(fut1.get(), fut2.get());
      assertThat(Futures.combine(fut1, fut2, fut3, fut4).toSet().get()).containsExactlyInAnyOrder(fut1.get(), fut2.get(), fut4.get());
   }

   @Test
   @SuppressWarnings("null")
   void testCombine_ToStream() throws InterruptedException, ExecutionException {
      final var fut1 = ExtendedFuture.supplyAsync(() -> {
         Threads.sleep(100);
         return List.of("a", "b");
      });
      final var fut2 = ExtendedFuture.supplyAsync(() -> new TreeSet<>(Set.of("c", "d")));
      final var fut3 = ExtendedFuture.supplyAsync(() -> List.of("e", "f"));
      final var fut4 = ExtendedFuture.supplyAsync(() -> List.of("g", "h"));
      final var fut5 = ExtendedFuture.failedFuture(new RuntimeException("oh no!"));

      assertThat(Futures.combine(fut1, fut2).toStream().isCompleted()).isFalse();
      assertThat(Futures.combine(fut1, fut2).toStream().isInterruptible()).isFalse();
      assertThat(Futures.combine(fut1, fut2).toStream().isInterruptibleStages()).isTrue();
      assertThat(Futures.combine(fut1, fut2).toStream().isReadOnly()).isFalse();

      assertThat(Futures.combine(fut1).toStream().get()).containsExactly(fut1.get());
      assertThat(Futures.combine(fut1, fut2).toStream().get()).containsExactly(fut1.get(), fut2.get());
      assertThat(Futures.combine(fut1, fut2, fut3).toStream().get()).containsExactly(fut1.get(), fut2.get(), fut3.get());
      assertThat(Futures.combine(fut1, fut2, fut3, fut4).toStream().get()).containsExactly(fut1.get(), fut2.get(), fut3.get(), fut4.get());
      assertThatThrownBy(() -> {
         Futures.combine(fut1, fut5).toStream().get();
      }).isExactlyInstanceOf(ExecutionException.class) //
         .hasRootCauseInstanceOf(RuntimeException.class) //
         .hasRootCauseMessage("oh no!");
   }

   @Test
   void testCombine_WithCancel() throws InterruptedException {
      final var fut1 = ExtendedFuture.supplyAsync(() -> List.of("a", "b"));
      final var fut2 = ExtendedFuture.supplyAsync(() -> {
         try {
            Thread.sleep(2_000);
         } catch (final InterruptedException ex) {
            throw new RuntimeException(ex);
         }
         return List.of("c", "d");
      });
      final var fut3 = ExtendedFuture.supplyAsync(() -> {
         try {
            Thread.sleep(2_000);
         } catch (final InterruptedException ex) {
            throw new RuntimeException(ex);
         }
         return List.of("e", "f");
      });

      Thread.sleep(500);

      Futures.combine(fut1, fut2, fut3).toList().cancel(true);
      assertThat(fut1).isCompleted();
      assertThat(fut2).isNotCompleted();
      assertThat(fut3).isNotCompleted();

      Futures.combine(fut1, fut2, fut3).forwardCancellation().toList().cancel(true);
      assertThat(fut1).isCompleted();
      assertThat(fut2).isCancelled();
      assertThat(fut3).isCancelled();
   }

   @Test
   void testCombineFlattened_ToList() throws InterruptedException, ExecutionException {
      final var fut1 = ExtendedFuture.supplyAsync(() -> List.of("a", "b"));
      final var fut2 = ExtendedFuture.supplyAsync(() -> new TreeSet<>(Set.of("c", "d")));
      final var fut3 = ExtendedFuture.supplyAsync(() -> List.of("e", "f"));
      final var fut4 = ExtendedFuture.supplyAsync(() -> new TreeSet<>(Set.of("g", "h")));

      assertThat(Futures.combineFlattened(fut1).toList().get()).containsSequence("a", "b");
      assertThat(Futures.combineFlattened(fut1, fut2).toList().get()).containsSequence("a", "b", "c", "d");
      assertThat(Futures.combineFlattened(fut1, fut2, fut3).toList().get()).containsSequence("a", "b", "c", "d", "e", "f");
      assertThat(Futures.combineFlattened(fut1, fut2, fut3, fut4).toList().get()).containsSequence("a", "b", "c", "d", "e", "f", "g", "h");

      // test future with null list result
      final var fut5 = ExtendedFuture.supplyAsync(() -> Collections.singletonList((@Nullable String) null));
      assertThat(Futures.combineFlattened(fut1, fut5).toList().get()).containsSequence("a", "b");
   }

   @Test
   void testCombineFlattened_ToSet() throws InterruptedException, ExecutionException {
      final var fut1 = ExtendedFuture.supplyAsync(() -> List.of("a", "b"));
      final var fut2 = ExtendedFuture.supplyAsync(() -> Set.of("a", "c"));
      final var fut3 = ExtendedFuture.supplyAsync(() -> List.of("a", "d"));
      final var fut4 = ExtendedFuture.supplyAsync(() -> Set.of("a", "e"));

      assertThat(Futures.combineFlattened(fut1).toSet().get()).containsExactlyInAnyOrder("a", "b");
      assertThat(Futures.combineFlattened(fut1, fut2).toSet().get()).containsExactlyInAnyOrder("a", "b", "c");
      assertThat(Futures.combineFlattened(fut1, fut2, fut3).toSet().get()).containsExactlyInAnyOrder("a", "b", "c", "d");
      assertThat(Futures.combineFlattened(fut1, fut2, fut3, fut4).toSet().get()).containsExactlyInAnyOrder("a", "b", "c", "d", "e");
   }

   @Test
   void testCombineFlattened_ToStream() throws InterruptedException, ExecutionException {
      final var fut1 = ExtendedFuture.supplyAsync(() -> List.of("a", "b"));
      final var fut2 = ExtendedFuture.supplyAsync(() -> new TreeSet<>(Set.of("c", "d")));
      final var fut3 = ExtendedFuture.supplyAsync(() -> List.of("e", "f"));
      final var fut4 = ExtendedFuture.supplyAsync(() -> new TreeSet<>(Set.of("g", "h")));

      assertThat(Futures.combineFlattened(fut1).toStream().get()).containsSequence("a", "b");
      assertThat(Futures.combineFlattened(fut1).toStream().get()).containsSequence("a", "b");
      assertThat(Futures.combineFlattened(fut1, fut2, fut3).toStream().get()).containsSequence("a", "b", "c", "d", "e", "f");
      assertThat(Futures.combineFlattened(fut1, fut2, fut3, fut4).toStream().get()).containsSequence("a", "b", "c", "d", "e", "f", "g",
         "h");
   }

   @Test
   void testCombineFlattened_WithCancel() throws InterruptedException {
      final var fut1 = ExtendedFuture.supplyAsync(() -> List.of("a", "b"));
      final var fut2 = ExtendedFuture.supplyAsync(() -> {
         try {
            Thread.sleep(2_000);
         } catch (final InterruptedException ex) {
            throw new RuntimeException(ex);
         }
         return List.of("c", "d");
      });
      final var fut3 = ExtendedFuture.supplyAsync(() -> {
         try {
            Thread.sleep(2_000);
         } catch (final InterruptedException ex) {
            throw new RuntimeException(ex);
         }
         return List.of("e", "f");
      });

      Thread.sleep(500);

      Futures.combineFlattened(fut1, fut2, fut3).toList().cancel(true);
      assertThat(fut1).isCompleted();
      assertThat(fut2).isNotCompleted();
      assertThat(fut3).isNotCompleted();

      Futures.combineFlattened(fut1, fut2, fut3).forwardCancellation().toList().cancel(true);
      assertThat(fut1).isCompleted();
      assertThat(fut2).isCancelled();
      assertThat(fut3).isCancelled();
   }

   @Test
   void testForwardCancellation_Array() {
      final var sourceFuture = ExtendedFuture.create();
      final var futures = new ArrayList<ExtendedFuture<?>>();
      for (int i = 0; i < 3; i++) {
         futures.add(ExtendedFuture.create());
      }
      Futures.forwardCancellation(sourceFuture, futures.toArray(ExtendedFuture[]::new));

      sourceFuture.cancel(true);
      for (final var future : futures) {
         assertThat(future).isCancelled();
      }
   }

   @Test
   void testForwardCancellation_List() {
      final var sourceFuture = ExtendedFuture.create();
      final var futures = new ArrayList<ExtendedFuture<?>>();
      for (int i = 0; i < 3; i++) {
         futures.add(ExtendedFuture.create());
      }
      Futures.forwardCancellation(sourceFuture, futures);

      sourceFuture.cancel(true);
      for (final var future : futures) {
         assertThat(future).isCancelled();
      }
   }

   @Test
   void testGet() {
      // Test with successfully completed future
      {
         final var future = ExtendedFuture.completedFuture("Success");

         final var result = Futures.getOrFallback(future, "Fallback", 100, TimeUnit.MILLISECONDS);
         assertThat(result).isEqualTo("Success");

         assertThat(Futures.getOptional(future, 100, TimeUnit.MILLISECONDS)).hasValue("Success");
      }
      // Test with incomplete future
      {
         final var future = ExtendedFuture.create();

         final var result = Futures.getOrFallback(future, "Fallback", 100, TimeUnit.MILLISECONDS);
         assertThat(result).isEqualTo("Fallback");

         assertThat(Futures.getOptional(future, 100, TimeUnit.MILLISECONDS)).isNotPresent();
      }
      // Test with cancelled future
      {
         final var future = ExtendedFuture.create();
         future.cancel(true);

         final var result = Futures.getOrFallback(future, "Fallback", 100, TimeUnit.MILLISECONDS);
         assertThat(result).isEqualTo("Fallback");

         assertThat(Futures.getOptional(future, 100, TimeUnit.MILLISECONDS)).isNotPresent();
      }
      // Test with exceptionally completed future
      {
         final var future = ExtendedFuture.create();
         future.completeExceptionally(new RuntimeException());

         final var result = Futures.getOrFallback(future, "Fallback", 100, TimeUnit.MILLISECONDS);
         assertThat(result).isEqualTo("Fallback");

         assertThat(Futures.getOptional(future, 100, TimeUnit.MILLISECONDS)).isNotPresent();
      }
   }

   @Test
   void testGetAllNow_Array() {
      final var future1 = ExtendedFuture.completedFuture("First");

      // Test case with an empty array
      {
         final var futures = new ExtendedFuture<?>[0];

         final var result = Futures.getAllNow(futures);
         assertThat(result.results()).isEmpty();
         assertThat(result.exceptions()).isEmpty();
      }
      // Test case with some futures completed
      {
         final var future2 = ExtendedFuture.completedFuture("Second");
         final var futures = new ExtendedFuture<?>[] {future1, future2};

         final var result = Futures.getAllNow(futures);
         assertThat(result.results().values()).containsOnly("First", "Second");
         assertThat(result.exceptions()).isEmpty();
      }
      // Test with cancelled future
      {
         final var future2 = ExtendedFuture.create();
         future2.cancel(true);
         final var futures = new ExtendedFuture<?>[] {future1, future2};

         final var result = Futures.getAllNow(futures);
         assertThat(result.results().values()).containsOnly("First");
         assertThat(result.exceptions()).isNotEmpty();
      }
      // Test case with a future that completes exceptionally
      {
         final var future2 = ExtendedFuture.create();
         future2.completeExceptionally(new RuntimeException());
         final var futures = new ExtendedFuture<?>[] {future1, future2};

         final var result = Futures.getAllNow(futures);
         assertThat(result.results().values()).containsOnly("First");
         assertThat(result.exceptions()).isNotEmpty();
      }
   }

   @Test
   void testGetAllNow_Collection() {
      final var future1 = ExtendedFuture.completedFuture("First");

      // Test case with an empty collection
      {
         final Collection<Future<String>> futures = Collections.emptyList();

         final var result = Futures.getAllNow(futures);
         assertThat(result.results().values()).isEmpty();
         assertThat(result.exceptions()).isEmpty();
      }
      // Test case with some futures completed
      {
         final var future2 = ExtendedFuture.completedFuture("Second");
         final var futures = List.of(future1, future2);

         final var result = Futures.getAllNow(futures);
         assertThat(result.results().values()).containsOnly("First", "Second");
         assertThat(result.exceptions()).isEmpty();
      }
      // Test with cancelled future
      {
         final var future2 = ExtendedFuture.create();
         future2.cancel(true);
         final var futures = List.of(future1, future2);

         final var result = Futures.getAllNow(futures);
         assertThat(result.results().values()).containsOnly("First");
         assertThat(result.exceptions()).isNotEmpty();
      }
      // Test case with a future that completes exceptionally
      {
         final var future2 = ExtendedFuture.create();
         future2.completeExceptionally(new RuntimeException());
         final var futures = List.of(future1, future2);

         final var result = Futures.getAllNow(futures);
         assertThat(result.results().values()).containsOnly("First");
         assertThat(result.exceptions()).isNotEmpty();
      }
   }

   @Test
   void testGetAllNow_CompletableFuture() {
      // Test with successfully completed future
      {
         final var future = CompletableFuture.completedFuture("Success");

         final var result = Futures.getNowOrFallback(future, "Fallback");
         assertThat(result).isEqualTo("Success");
         final var result2 = Futures.getNowOrComputeFallback(future, (f, ex) -> "Fallback");
         assertThat(result2).isEqualTo("Success");
      }
      // Test with incomplete future
      {
         final var future = new CompletableFuture<>();

         final var result = Futures.getNowOrFallback(future, "Fallback");
         assertThat(result).isEqualTo("Fallback");
         final var result2 = Futures.getNowOrComputeFallback(future, (f, ex) -> "Fallback");
         assertThat(result2).isEqualTo("Fallback");
      }
      // Test with cancelled future
      {
         final var future = new CompletableFuture<>();
         future.cancel(true);

         final var result = Futures.getNowOrFallback(future, "Fallback");
         assertThat(result).isEqualTo("Fallback");
         final var result2 = Futures.getNowOrComputeFallback(future, (f, ex) -> "Fallback");
         assertThat(result2).isEqualTo("Fallback");
      }
      // Test with exceptionally completed future
      {
         final var future = new CompletableFuture<>();
         future.completeExceptionally(new RuntimeException());

         final var result = Futures.getNowOrFallback(future, "Fallback");
         assertThat(result).isEqualTo("Fallback");
         final var result2 = Futures.getNowOrComputeFallback(future, (f, ex) -> "Fallback");
         assertThat(result2).isEqualTo("Fallback");
      }
   }

   @Test
   void testGetAllNow_ExtendedFuture() {
      // Test with successfully completed future
      {
         final var future = ExtendedFuture.completedFuture("Success");

         final var result = Futures.getNowOrFallback(future, "Fallback");
         assertThat(result).isEqualTo("Success");
         final var result2 = Futures.getNowOrComputeFallback(future, (f, ex) -> "Fallback");
         assertThat(result2).isEqualTo("Success");
      }
      // Test with incomplete future
      {
         final var future = ExtendedFuture.create();

         final var result = Futures.getNowOrFallback(future, "Fallback");
         assertThat(result).isEqualTo("Fallback");
         final var result2 = Futures.getNowOrComputeFallback(future, (f, ex) -> "Fallback");
         assertThat(result2).isEqualTo("Fallback");
      }
      // Test with cancelled future
      {
         final var future = ExtendedFuture.create();
         future.cancel(true);

         final var result = Futures.getNowOrFallback(future, "Fallback");
         assertThat(result).isEqualTo("Fallback");
         final var result2 = Futures.getNowOrComputeFallback(future, (f, ex) -> "Fallback");
         assertThat(result2).isEqualTo("Fallback");
      }
      // Test with exceptionally completed future
      {
         final var future = ExtendedFuture.create();
         future.completeExceptionally(new RuntimeException());

         final var result = Futures.getNowOrFallback(future, "Fallback");
         assertThat(result).isEqualTo("Fallback");
         final var result2 = Futures.getNowOrComputeFallback(future, (f, ex) -> "Fallback");
         assertThat(result2).isEqualTo("Fallback");
      }
   }

   @Test
   void testGetAllNow_Java5Future() {
      // Test with successfully completed future
      {
         final var future = new FutureTask<>(() -> { /**/ }, "Success");
         future.run();

         final var result = Futures.getNowOrFallback(future, "Fallback");
         assertThat(result).isEqualTo("Success");
         final var result2 = Futures.getNowOrComputeFallback(future, (f, ex) -> "Fallback");
         assertThat(result2).isEqualTo("Success");
      }
      // Test with incomplete future
      {
         final var future = new FutureTask<>(() -> { /**/ }, "Success");

         final var result = Futures.getNowOrFallback(future, "Fallback");
         assertThat(result).isEqualTo("Fallback");
         final var result2 = Futures.getNowOrComputeFallback(future, (f, ex) -> "Fallback");
         assertThat(result2).isEqualTo("Fallback");
      }

      // Test with cancelled future
      {
         final var future = new FutureTask<>(() -> { /**/ }, "Success");
         future.cancel(true);

         final var result = Futures.getNowOrFallback(future, "Fallback");
         assertThat(result).isEqualTo("Fallback");
         final var result2 = Futures.getNowOrComputeFallback(future, (f, ex) -> "Fallback");
         assertThat(result2).isEqualTo("Fallback");
      }
   }

   /**
    * This method is also used to test Eclipse compiler's null checks and type interference
    */
   @Test
   void testNullArguments() {
      final @Nullable ExtendedFuture<List<?>> nullFuture = null;

      final @Nullable ExtendedFuture<List<?>> @Nullable [] nullArray = null;
      @SuppressWarnings("unchecked")
      final @Nullable ExtendedFuture<List<?>> @Nullable [] arrayWithNulls = new @Nullable ExtendedFuture[] {null};

      final @Nullable List<@Nullable ExtendedFuture<List<?>>> nullList = null;
      final List<@Nullable ExtendedFuture<List<?>>> listWithNulls = Collections.singletonList(null);

      assertThat(Futures.cancel(nullFuture)).isFalse();
      assertThat(Futures.cancel(nullFuture, true)).isFalse();
      assertThat(Futures.cancelInterruptibly(nullFuture)).isFalse();

      assertThat(Futures.cancelAll(nullArray)).isZero();
      assertThat(Futures.cancelAll(nullList)).isZero();
      assertThat(Futures.cancelAll(arrayWithNulls)).isZero();
      assertThat(Futures.cancelAll(listWithNulls)).isZero();

      assertThat(Futures.cancelAll(nullArray, true)).isZero();
      assertThat(Futures.cancelAll(nullList, true)).isZero();
      assertThat(Futures.cancelAll(arrayWithNulls, true)).isZero();
      assertThat(Futures.cancelAll(listWithNulls, true)).isZero();

      assertThat(Futures.cancelAllInterruptibly(nullArray)).isZero();
      assertThat(Futures.cancelAllInterruptibly(nullList)).isZero();
      assertThat(Futures.cancelAllInterruptibly(arrayWithNulls)).isZero();
      assertThat(Futures.cancelAllInterruptibly(listWithNulls)).isZero();

      assertThat(Futures.combine(nullFuture).toList()).isCompleted();
      assertThat(Futures.combine(nullArray).toList()).isCompleted();
      assertThat(Futures.combine(nullList).toList()).isCompleted();
      assertThat(Futures.combine(arrayWithNulls).toList()).isCompleted();
      assertThat(Futures.combine(listWithNulls).toList()).isCompleted();

      assertThat(Futures.combineFlattened(nullArray).toList()).isCompleted();
      assertThat(Futures.combineFlattened(nullList).toList()).isCompleted();
      assertThat(Futures.combineFlattened(arrayWithNulls).toList()).isCompleted();
      assertThat(Futures.combineFlattened(listWithNulls).toList()).isCompleted();

      Futures.forwardCancellation(ExtendedFuture.completedFuture(null), nullFuture);
      Futures.forwardCancellation(ExtendedFuture.completedFuture(null), nullArray);
      Futures.forwardCancellation(ExtendedFuture.completedFuture(null), nullList);
      Futures.forwardCancellation(ExtendedFuture.completedFuture(null), arrayWithNulls);
      Futures.forwardCancellation(ExtendedFuture.completedFuture(null), listWithNulls);

      assertThat(Futures.getAllNow(nullArray).results()).isEmpty();
      assertThat(Futures.getAllNow(nullList).results()).isEmpty();
      assertThat(Futures.getAllNow(arrayWithNulls).results()).isEmpty();
      assertThat(Futures.getAllNow(listWithNulls).results()).isEmpty();
   }
}
