/*
 * SPDX-FileCopyrightText: © Sebastian Thomschke
 * SPDX-License-Identifier: EPL-2.0
 * SPDX-FileContributor: Sebastian Thomschke: initial API and implementation
 */
package io.github.futures4j;

import java.util.List;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.stream.Stream;

/**
 * A context that manages multiple {@link Future futures}/stages.
 *
 * @author <a href="https://sebthom.de/">Sebastian Thomschke</a>
 */
public class FuturesContext<T> {

   private enum CancellationMode {
      NONE,
      CANCELLING,
      CANCELLING_WITH_INTERRUPT
   }

   private final Set<CompletableFuture<? extends T>> futures = ConcurrentHashMap.newKeySet();
   private CancellationMode mode = CancellationMode.NONE;

   /**
    * Cancels all registered futures that are not yet complete.
    *
    * Once this method was invoked further futures added via {@link #register(CompletableFuture)} are cancelled too.
    *
    * @param mayInterruptIfRunning if {@code true}, ongoing tasks will be interrupted if supported;
    *           otherwise, they will be allowed to complete.
    */
   public void cancelAll(final boolean mayInterruptIfRunning) {
      synchronized (this) {
         if (mayInterruptIfRunning) {
            mode = CancellationMode.CANCELLING;
         } else {
            mode = CancellationMode.CANCELLING_WITH_INTERRUPT;
         }
      }

      futures.forEach(f -> {
         if (!f.isDone()) {
            f.cancel(mayInterruptIfRunning);
         }
      });
   }

   /**
    * Creates a future combiner from the current list of registered futures.
    */
   public Futures.Combiner<? extends T> combineAll() {
      return Futures.combine(futures);
   }

   public void deregister(final CompletableFuture<?> future) {
      futures.remove(future);
   }

   /**
    * Waits for all futures to complete and returns a list of results from all normally completed futures.
    *
    * @return a list of results from all normally completed futures; an empty list if no futures are completed normally
    */
   public List<T> getAll() {
      return Futures.getAll(futures);
   }

   /**
    * Returns a list of results from all normally completed futures.
    * <p>
    * Futures that are incomplete, canceled, or completed exceptionally are ignored.
    *
    * @return a list of results from all normally completed futures; an empty list if no futures are completed
    */
   public List<T> getAllNow() {
      return Futures.getAllNow(futures);
   }

   /**
    * Returns a list of results from all successfully completed futures.
    * <p>
    * If at least one future was canceled or completed exceptionally, this method will throw the corresponding exception.
    * Futures that are incomplete are ignored.
    *
    * @return a list of results from all completed futures; an empty list if no futures are completed
    *
    * @throws CancellationException if any future was canceled
    * @throws ExecutionException if any future completed exceptionally
    * @throws InterruptedException if the current thread was interrupted while waiting
    */
   public List<T> getAllNowOrThrow() throws ExecutionException, InterruptedException {
      return Futures.getAllNowOrThrow(futures);
   }

   /**
    * Waits for all futures to complete and returns a list of results from all normally completed futures.
    * <p>
    * If at least one future was cancelled or completed exceptionally, this method will throw the corresponding exception.
    * Futures that are incomplete are ignored.
    *
    * @return a list of results from all completed futures; an empty list if no futures are completed normally
    *
    * @throws CancellationException if any future was cancelled
    * @throws ExecutionException if any future completed exceptionally
    * @throws InterruptedException if the current thread was interrupted while waiting
    */
   public List<T> getAllOrThrow() throws ExecutionException, InterruptedException {
      return Futures.getAllOrThrow(futures);
   }

   /**
    * @return a stream of all registered futures
    */
   public Stream<CompletableFuture<? extends T>> getFutures() {
      return futures.stream();
   }

   /**
    * Checks whether there are any incomplete futures still being tracked.
    *
    * @return {@code true} if there are incomplete futures, {@code false} otherwise.
    */
   public boolean hasAnyIncomplete() {
      return futures.stream().anyMatch(f -> !f.isDone());
   }

   /**
    * Checks whether the manager is in a cancelled state, meaning that it is no longer in
    * the process of registering new futures.
    *
    * @return {@code true} if futures are being cancelled or have been cancelled, {@code false} otherwise.
    */
   public boolean isCancelled() {
      synchronized (this) {
         return mode != CancellationMode.NONE;
      }
   }

   public void register(final CompletableFuture<? extends T> future) {
      futures.add(future);

      synchronized (this) {
         switch (mode) {
            case CANCELLING -> future.cancel(false);
            case CANCELLING_WITH_INTERRUPT -> future.cancel(true);
            case NONE -> {
               /*nothing to do*/ }
         }
      }
   }
}
