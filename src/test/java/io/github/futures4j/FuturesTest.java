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
      final var future1 = new ExtendedFuture<>();
      final var future2 = new ExtendedFuture<>();
      final var future3 = new ExtendedFuture<>();

      Futures.cancel(future1);
      Futures.cancel(future2, true);
      Futures.cancelInterruptibly(future3);

      assertThat(future1).isCancelled();
      assertThat(future2).isCancelled();
      assertThat(future3).isCancelled();

      /* test with null arguments */
      assertThat(Futures.cancel((Future<?>) null)).isFalse();
      assertThat(Futures.cancel((Future<?>) null, true)).isFalse();
      assertThat(Futures.cancelInterruptibly((Future<?>) null)).isFalse();
   }

   @Test
   void testCombine_Cancel() throws InterruptedException {
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

      Futures.combine(fut1, fut2, fut3).toList().cancelAll(true);
      assertThat(fut1).isCompleted();
      assertThat(fut2).isCancelled();
      assertThat(fut3).isCancelled();
   }

   @Test
   @SuppressWarnings("null")
   void testCombine_ToAnyOf() throws InterruptedException, ExecutionException {
      final var fut1 = ExtendedFuture.supplyAsync(() -> {
         Threads.sleep(200);
         return List.of("a", "b");
      });
      final var fut2 = ExtendedFuture.completedFuture(new TreeSet<>(Set.of("c", "d")));

      assertThat(Futures.combine(fut1).toAnyOf().isSuccess()).isFalse();
      assertThat(Futures.combine(fut1, fut2).toAnyOf().isSuccess()).isTrue();
      assertThat(Futures.combine(fut1, fut2).toAnyOf().isInterruptible()).isFalse();
      assertThat(Futures.combine(fut1, fut2).toAnyOf().isInterruptibleStages()).isTrue();
      assertThat(Futures.combine(fut1, fut2).toAnyOf().isReadOnly()).isFalse();

      assertThat(Futures.combine(fut1, fut2).toAnyOf().get()).isEqualTo(fut2.get());
      fut1.join();
      assertThat(Futures.combine(fut1, fut2).toAnyOf().get()).isEqualTo(fut1.get());

      final var fut5 = ExtendedFuture.failedFuture(new RuntimeException("oh no!"));
      assertThat(Futures.combine(fut1, fut5).toAnyOf().get()).isEqualTo(fut1.get());
      assertThatThrownBy(() -> {
         Futures.combine(fut5, fut1).toAnyOf().get();
      }).isExactlyInstanceOf(ExecutionException.class) //
         .hasRootCauseInstanceOf(RuntimeException.class) //
         .hasRootCauseMessage("oh no!");
   }

   @Test
   @SuppressWarnings("null")
   void testCombine_ToAnyOfDeferringExceptions() throws InterruptedException, ExecutionException {
      final var fut1 = ExtendedFuture.supplyAsync(() -> {
         Threads.sleep(200);
         return List.of("a", "b");
      });
      final var fut2 = ExtendedFuture.completedFuture(new TreeSet<>(Set.of("c", "d")));

      assertThat(Futures.combine(fut1).toAnyOfDeferringExceptions().isSuccess()).isFalse();
      assertThat(Futures.combine(fut1, fut2).toAnyOfDeferringExceptions().isSuccess()).isTrue();
      assertThat(Futures.combine(fut1, fut2).toAnyOfDeferringExceptions().isInterruptible()).isFalse();
      assertThat(Futures.combine(fut1, fut2).toAnyOfDeferringExceptions().isInterruptibleStages()).isTrue();
      assertThat(Futures.combine(fut1, fut2).toAnyOfDeferringExceptions().isReadOnly()).isFalse();

      assertThat(Futures.combine(fut1, fut2).toAnyOfDeferringExceptions().get()).isEqualTo(fut2.get());
      fut1.join();
      assertThat(Futures.combine(fut1, fut2).toAnyOfDeferringExceptions().get()).isEqualTo(fut1.get());

      final var fut5 = ExtendedFuture.failedFuture(new RuntimeException("oh no!"));
      assertThat(Futures.combine(fut1, fut5).toAnyOfDeferringExceptions().get()).isEqualTo(fut1.get());
      assertThat(Futures.combine(fut5, fut1).toAnyOfDeferringExceptions().get()).isEqualTo(fut1.get());
   }

   @Test
   @SuppressWarnings("null")
   void testCombine_ToList() throws InterruptedException, ExecutionException {
      final var fut1 = ExtendedFuture.supplyAsync(() -> {
         Threads.sleep(200);
         return List.of("a", "b");
      });
      final var fut2 = ExtendedFuture.supplyAsync(() -> new TreeSet<>(Set.of("c", "d")));
      final var fut3 = ExtendedFuture.supplyAsync(() -> List.of("e", "f"));
      final var fut4 = ExtendedFuture.supplyAsync(() -> List.of("g", "h"));

      assertThat(Futures.combine(fut1, fut2).toList().isSuccess()).isFalse();
      assertThat(Futures.combine(fut1, fut2).toList().isInterruptible()).isFalse();
      assertThat(Futures.combine(fut1, fut2).toList().isInterruptibleStages()).isTrue();
      assertThat(Futures.combine(fut1, fut2).toList().isReadOnly()).isFalse();

      assertThat(Futures.combine(fut1).toList().get()).containsExactly(fut1.get());
      assertThat(Futures.combine(fut1, fut2).toList().get()).containsExactly(fut1.get(), fut2.get());
      assertThat(Futures.combine(fut1, fut2, fut3).toList().get()).containsExactly(fut1.get(), fut2.get(), fut3.get());
      assertThat(Futures.combine(fut1, fut2, fut3, fut4).toList().get()).containsExactly(fut1.get(), fut2.get(), fut3.get(), fut4.get());

      final var fut5 = ExtendedFuture.failedFuture(new RuntimeException("oh no!"));
      assertThatThrownBy(() -> {
         Futures.combine(fut1, fut5).toList().get();
      }).isExactlyInstanceOf(ExecutionException.class) //
         .hasRootCauseInstanceOf(RuntimeException.class) //
         .hasRootCauseMessage("oh no!");
   }

   @Test
   @SuppressWarnings("null")
   void testCombine_ToMap() throws InterruptedException, ExecutionException {
      final var fut1 = ExtendedFuture.supplyAsync(() -> {
         Threads.sleep(200);
         return List.of("a", "b");
      });
      final var fut2 = ExtendedFuture.supplyAsync(() -> Set.of("a", "c"));
      final var fut3 = ExtendedFuture.supplyAsync(() -> List.of("a", "b"));
      final var fut4 = ExtendedFuture.supplyAsync(() -> Set.of("a", "e"));

      assertThat(Futures.combine(fut1, fut2).toMap().isSuccess()).isFalse();
      assertThat(Futures.combine(fut1, fut2).toMap().isInterruptible()).isFalse();
      assertThat(Futures.combine(fut1, fut2).toMap().isInterruptibleStages()).isTrue();
      assertThat(Futures.combine(fut1, fut2).toMap().isReadOnly()).isFalse();

      assertThat(Futures.combine(fut1).toMap().get()).isEqualTo(Map.of(fut1, fut1.get()));
      assertThat(Futures.combine(fut1, fut2).toMap().get()).isEqualTo(Map.of(fut1, fut1.get(), fut2, fut2.get()));
      assertThat(Futures.combine(fut1, fut2, fut3).toMap().get()).isEqualTo(Map.of(fut1, fut1.get(), fut2, fut2.get(), fut3, fut3.get()));
      assertThat(Futures.combine(fut1, fut2, fut3, fut4).toMap().get()).isEqualTo(Map.of(fut1, fut1.get(), fut2, fut2.get(), fut3, fut3
         .get(), fut4, fut4.get()));

      final var fut5 = ExtendedFuture.failedFuture(new RuntimeException("oh no!"));
      assertThatThrownBy(() -> {
         Futures.combine(fut1, fut5).toMap().get();
      }).isExactlyInstanceOf(ExecutionException.class) //
         .hasRootCauseInstanceOf(RuntimeException.class) //
         .hasRootCauseMessage("oh no!");
   }

   @Test
   @SuppressWarnings("null")
   void testCombine_ToSet() throws InterruptedException, ExecutionException {
      final var fut1 = ExtendedFuture.supplyAsync(() -> {
         Threads.sleep(200);
         return List.of("a", "b");
      });
      final var fut2 = ExtendedFuture.supplyAsync(() -> Set.of("a", "c"));
      final var fut3 = ExtendedFuture.supplyAsync(() -> List.of("a", "b"));
      final var fut4 = ExtendedFuture.supplyAsync(() -> Set.of("a", "e"));

      assertThat(Futures.combine(fut1, fut2).toSet().isSuccess()).isFalse();
      assertThat(Futures.combine(fut1, fut2).toSet().isInterruptible()).isFalse();
      assertThat(Futures.combine(fut1, fut2).toSet().isInterruptibleStages()).isTrue();
      assertThat(Futures.combine(fut1, fut2).toSet().isReadOnly()).isFalse();

      assertThat(Futures.combine(fut1).toSet().get()).containsExactlyInAnyOrder(fut1.get());
      assertThat(Futures.combine(fut1, fut2).toSet().get()).containsExactlyInAnyOrder(fut1.get(), fut2.get());
      assertThat(Futures.combine(fut1, fut3).toSet().get()).containsExactlyInAnyOrder(fut1.get()); // Since fut1 and fut3 are the same
      assertThat(Futures.combine(fut1, fut2, fut3).toSet().get()).containsExactlyInAnyOrder(fut1.get(), fut2.get());
      assertThat(Futures.combine(fut1, fut2, fut3, fut4).toSet().get()).containsExactlyInAnyOrder(fut1.get(), fut2.get(), fut4.get());

      final var fut5 = ExtendedFuture.failedFuture(new RuntimeException("oh no!"));
      assertThatThrownBy(() -> {
         Futures.combine(fut1, fut5).toSet().get();
      }).isExactlyInstanceOf(ExecutionException.class) //
         .hasRootCauseInstanceOf(RuntimeException.class) //
         .hasRootCauseMessage("oh no!");
   }

   @Test
   @SuppressWarnings("null")
   void testCombine_ToStream() throws InterruptedException, ExecutionException {
      final var fut1 = ExtendedFuture.supplyAsync(() -> {
         Threads.sleep(200);
         return List.of("a", "b");
      });
      final var fut2 = ExtendedFuture.supplyAsync(() -> new TreeSet<>(Set.of("c", "d")));
      final var fut3 = ExtendedFuture.supplyAsync(() -> List.of("e", "f"));
      final var fut4 = ExtendedFuture.supplyAsync(() -> List.of("g", "h"));
      final var fut5 = ExtendedFuture.failedFuture(new RuntimeException("oh no!"));

      assertThat(Futures.combine(fut1, fut2).toStream().isSuccess()).isFalse();
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
   void testCombineFlattened_Cancel() throws InterruptedException {
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

      Futures.combineFlattened(fut1, fut2, fut3).toList().cancelAll(true);
      assertThat(fut1).isCompleted();
      assertThat(fut2).isCancelled();
      assertThat(fut3).isCancelled();
   }

   @Test
   @SuppressWarnings("null")
   void testCombineFlattened_ToList() throws InterruptedException, ExecutionException {
      final var fut1 = ExtendedFuture.supplyAsync(() -> List.of("a", "b"));
      final var fut2 = ExtendedFuture.supplyAsync(() -> new TreeSet<>(Set.of("c", "d")));
      final var fut3 = ExtendedFuture.supplyAsync(() -> List.of("e", "f"));
      final var fut4 = ExtendedFuture.supplyAsync(() -> new TreeSet<>(Set.of("g", "h")));

      assertThat(Futures.combineFlattened(fut1).toList().get()).containsSequence("a", "b");
      assertThat(Futures.combineFlattened(fut1, fut2).toList().get()).containsSequence("a", "b", "c", "d");
      assertThat(Futures.combineFlattened(fut1, fut2, fut3).toList().get()).containsSequence("a", "b", "c", "d", "e", "f");
      assertThat(Futures.combineFlattened(fut1, fut2, fut3, fut4).toList().get()).containsSequence("a", "b", "c", "d", "e", "f", "g", "h");

      final Future<List<String>> fut5 = ExtendedFuture.failedFuture(new RuntimeException("oh no!"));
      assertThatThrownBy(() -> {
         Futures.combineFlattened(fut1, fut5).toList().get();
      }).isExactlyInstanceOf(ExecutionException.class) //
         .hasRootCauseInstanceOf(RuntimeException.class) //
         .hasRootCauseMessage("oh no!");

      // test future with null list result
      final var fut6 = ExtendedFuture.supplyAsync(() -> Collections.singletonList((@Nullable String) null));
      assertThat(Futures.combineFlattened(fut1, fut6).toList().get()).containsSequence("a", "b");
   }

   @Test
   @SuppressWarnings("null")
   void testCombineFlattened_ToSet() throws InterruptedException, ExecutionException {
      final var fut1 = ExtendedFuture.supplyAsync(() -> List.of("a", "b"));
      final var fut2 = ExtendedFuture.supplyAsync(() -> Set.of("a", "c"));
      final ExtendedFuture<Iterable<String>> fut3 = ExtendedFuture.supplyAsync(() -> List.of("a", "d"));
      final var fut4 = ExtendedFuture.supplyAsync(() -> Set.of("a", "e"));

      assertThat(Futures.combineFlattened(fut1).toSet().get()).containsExactlyInAnyOrder("a", "b");
      assertThat(Futures.combineFlattened(fut1, fut2).toSet().get()).containsExactlyInAnyOrder("a", "b", "c");
      assertThat(Futures.combineFlattened(fut1, fut2, fut3).toSet().get()).containsExactlyInAnyOrder("a", "b", "c", "d");
      assertThat(Futures.combineFlattened(fut1, fut2, fut3, fut4).toSet().get()).containsExactlyInAnyOrder("a", "b", "c", "d", "e");

      final Future<List<String>> fut5 = ExtendedFuture.failedFuture(new RuntimeException("oh no!"));
      assertThatThrownBy(() -> {
         Futures.combineFlattened(fut1, fut5).toSet().get();
      }).isExactlyInstanceOf(ExecutionException.class) //
         .hasRootCauseInstanceOf(RuntimeException.class) //
         .hasRootCauseMessage("oh no!");
   }

   @Test
   @SuppressWarnings("null")
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

      final Future<List<String>> fut5 = ExtendedFuture.failedFuture(new RuntimeException("oh no!"));
      assertThatThrownBy(() -> {
         Futures.combineFlattened(fut1, fut5).toStream().get();
      }).isExactlyInstanceOf(ExecutionException.class) //
         .hasRootCauseInstanceOf(RuntimeException.class) //
         .hasRootCauseMessage("oh no!");
   }

   @Test
   void testCombinedFutureCancelBehavior() {
      final var future1 = new ExtendedFuture<>();
      final var future2 = new ExtendedFuture<>();

      final var combined = Futures.combine(future1, future2).toList();

      assertThat(combined.cancel(false)).isTrue();
      assertThat(combined).isCancelled();
      assertThat(future1.isCancelled()).isFalse();
      assertThat(future2.isCancelled()).isFalse();

      final var future3 = new ExtendedFuture<>();
      final var future4 = new ExtendedFuture<>();
      final var combinedAll = Futures.combine(future3, future4).toList();

      assertThat(combinedAll.cancelAll(true)).isTrue();
      assertThat(combinedAll).isCancelled();
      assertThat(future3).isCancelled();
      assertThat(future4).isCancelled();
   }

   @Test
   void testForwardCancellation() {
      final var sourceFuture = new ExtendedFuture<>();
      final var targetFuture = new ExtendedFuture<>();

      Futures.forwardCancellation(sourceFuture, targetFuture);

      sourceFuture.cancel(true);
      assertThat(targetFuture).isCancelled();

      /* test with null arguments */
      Futures.forwardCancellation(ExtendedFuture.completedFuture(null), (Future<?>) null);
   }

   @Test
   void testForwardCancellation_PreservesMayInterruptFlag() throws InterruptedException {
      class TrackingFuture implements Future<Object> {

         volatile @Nullable Boolean mayInterrupt;
         private volatile boolean cancelled;

         @Override
         public boolean cancel(final boolean mayInterruptIfRunning) {
            cancelled = true;
            mayInterrupt = Boolean.valueOf(mayInterruptIfRunning);
            return true;
         }

         @Override
         public boolean isCancelled() {
            return cancelled;
         }

         @Override
         public boolean isDone() {
            return cancelled;
         }

         @Override
         public Object get() {
            throw new UnsupportedOperationException("Not implemented");
         }

         @Override
         public Object get(final long timeout, final TimeUnit unit) {
            throw new UnsupportedOperationException("Not implemented");
         }
      }

      final var sourceFuture = new ExtendedFuture<>();
      final var trackingFuture = new TrackingFuture();

      Futures.forwardCancellation(sourceFuture, trackingFuture);

      sourceFuture.cancel(false);

      for (int i = 0; i < 50 && trackingFuture.mayInterrupt == null; i++) {
         Thread.sleep(10);
      }

      assertThat(trackingFuture.isCancelled()).isTrue();
      assertThat(trackingFuture.mayInterrupt).isFalse();
   }

   @Test
   void testForwardCancellation_Array() {
      final var sourceFuture = new ExtendedFuture<>();
      final var futures = new ArrayList<ExtendedFuture<?>>();
      for (int i = 0; i < 3; i++) {
         futures.add(new ExtendedFuture<>());
      }
      Futures.forwardCancellation(sourceFuture, futures.toArray(ExtendedFuture[]::new));

      sourceFuture.cancel(true);
      for (final var future : futures) {
         assertThat(future).isCancelled();
      }

      /* test with null arguments */
      Futures.forwardCancellation(ExtendedFuture.completedFuture(null), null, null);
      Futures.forwardCancellation(ExtendedFuture.completedFuture(null), new @Nullable Future<?>[] {null});
   }

   @Test
   void testForwardCancellation_List() {
      final var sourceFuture = new ExtendedFuture<>();
      final var futures = new ArrayList<ExtendedFuture<?>>();
      for (int i = 0; i < 3; i++) {
         futures.add(new ExtendedFuture<>());
      }
      Futures.forwardCancellation(sourceFuture, futures);

      sourceFuture.cancel(true);
      for (final var future : futures) {
         assertThat(future).isCancelled();
      }

      /* test with null arguments */
      Futures.forwardCancellation(ExtendedFuture.completedFuture(null), (Collection<Future<?>>) null);
      Futures.forwardCancellation(ExtendedFuture.completedFuture(null), Collections.singletonList(null));
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
         final var future = new ExtendedFuture<>();

         final var result = Futures.getOrFallback(future, "Fallback", 100, TimeUnit.MILLISECONDS);
         assertThat(result).isEqualTo("Fallback");

         assertThat(Futures.getOptional(future, 100, TimeUnit.MILLISECONDS)).isNotPresent();
      }
      // Test with cancelled future
      {
         final var future = new ExtendedFuture<>();
         future.cancel(true);

         final var result = Futures.getOrFallback(future, "Fallback", 100, TimeUnit.MILLISECONDS);
         assertThat(result).isEqualTo("Fallback");

         assertThat(Futures.getOptional(future, 100, TimeUnit.MILLISECONDS)).isNotPresent();
      }
      // Test with exceptionally completed future
      {
         final var future = new ExtendedFuture<>();
         future.completeExceptionally(new RuntimeException());

         final var result = Futures.getOrFallback(future, "Fallback", 100, TimeUnit.MILLISECONDS);
         assertThat(result).isEqualTo("Fallback");

         assertThat(Futures.getOptional(future, 100, TimeUnit.MILLISECONDS)).isNotPresent();
      }
   }

   @Test
   void testGetNow_CompletableFuture() {
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
   void testGetNow_ExtendedFuture() {
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
         final var future = new ExtendedFuture<>();

         final var result = Futures.getNowOrFallback(future, "Fallback");
         assertThat(result).isEqualTo("Fallback");
         final var result2 = Futures.getNowOrComputeFallback(future, (f, ex) -> "Fallback");
         assertThat(result2).isEqualTo("Fallback");
      }
      // Test with cancelled future
      {
         final var future = new ExtendedFuture<>();
         future.cancel(true);

         final var result = Futures.getNowOrFallback(future, "Fallback");
         assertThat(result).isEqualTo("Fallback");
         final var result2 = Futures.getNowOrComputeFallback(future, (f, ex) -> "Fallback");
         assertThat(result2).isEqualTo("Fallback");
      }
      // Test with exceptionally completed future
      {
         final var future = new ExtendedFuture<>();
         future.completeExceptionally(new RuntimeException());

         final var result = Futures.getNowOrFallback(future, "Fallback");
         assertThat(result).isEqualTo("Fallback");
         final var result2 = Futures.getNowOrComputeFallback(future, (f, ex) -> "Fallback");
         assertThat(result2).isEqualTo("Fallback");
      }
   }

   @Test
   void testGetNow_Java5Future() {
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

      assertThat(Futures.combine(nullFuture).toList()).isCompleted();
      assertThat(Futures.combine(nullArray).toList()).isCompleted();
      assertThat(Futures.combine(nullList).toList()).isCompleted();
      assertThat(Futures.combine(arrayWithNulls).toList()).isCompleted();
      assertThat(Futures.combine(listWithNulls).toList()).isCompleted();

      assertThat(Futures.combineFlattened(nullArray).toList()).isCompleted();
      assertThat(Futures.combineFlattened(nullList).toList()).isCompleted();
      assertThat(Futures.combineFlattened(arrayWithNulls).toList()).isCompleted();
      assertThat(Futures.combineFlattened(listWithNulls).toList()).isCompleted();
   }
}
