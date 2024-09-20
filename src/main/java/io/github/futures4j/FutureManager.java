/*
 * SPDX-FileCopyrightText: © Sebastian Thomschke
 * SPDX-License-Identifier: EPL-2.0
 * SPDX-FileContributor: Sebastian Thomschke: initial API and implementation
 */
package io.github.futures4j;

import java.util.List;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Stream;

import io.github.futures4j.Futures.Results;

/**
 * Manages a set of {@link Future} instances, offering functionality for registration, cancellation,
 * and result retrieval.
 * <p>
 * This class is thread-safe for managing futures and handling cancellations.
 * </p>
 *
 * @author <a href="https://sebthom.de/">Sebastian Thomschke</a>
 *
 * @param <T> the result type of the managed futures
 */
public class FutureManager<T> {

   private enum CancellationMode {
      NONE,
      CANCELLING,
      CANCELLING_WITH_INTERRUPT
   }

   private final Set<Future<T>> futures = ConcurrentHashMap.newKeySet();
   private CancellationMode mode = CancellationMode.NONE;

   /**
    * Cancels all registered futures that are not yet complete.
    *
    * Once this method was invoked further futures added via {@link #register(Future)} are cancelled too.
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

   public void deregister(final Future<?> future) {
      futures.remove(future);
   }

   public void deregisterAll() {
      futures.clear();
   }

   /**
    * Waits for all currently registered futures to complete and returns a list of results from all normally completed futures.
    * <p>
    * If at least one future was cancelled or failed, this method will throw the corresponding exception.
    *
    * @return a list of results from all completed futures; an empty list if no futures are completed normally
    *
    * @throws CancellationException if any future was cancelled
    * @throws ExecutionException if any future failed
    * @throws InterruptedException if the current thread was interrupted while waiting
    */
   public List<T> getAll() throws ExecutionException, InterruptedException {
      return Futures.getAll(futures);
   }

   /**
    * Waits up to the specified timeout for all currently registered futures to complete and returns a list of results from all normally
    * completed futures.
    * <p>
    * If at least one future was cancelled or failed or did not complete within the specified time, this method will throw
    * the corresponding exception.
    *
    * @return a list of results from all completed futures; an empty list if no futures are completed
    *
    * @throws CancellationException if any future was cancelled
    * @throws ExecutionException if any future failed
    * @throws InterruptedException if the current thread was interrupted while waiting
    * @throws TimeoutException if the wait timed out before all futures completed
    */
   public List<T> getAll(final long timeout, final TimeUnit unit) throws ExecutionException, InterruptedException, TimeoutException {
      return Futures.getAll(futures, timeout, unit);
   }

   /**
    * Returns a {@link Results} object containing the results from all currently registered futures that have already completed normally.
    * <p>
    * This method processes only futures that have completed by the time of invocation. Futures that were cancelled or completed
    * exceptionally are captured and recorded in the exceptions map of the {@link Results} object. Incomplete futures are ignored and will
    * not be present in the {@link Results} object.
    * </p>
    *
    * @return a {@link Results} object containing a map of completed futures with their results and a map of futures that
    *         were cancelled or failed with their corresponding exceptions.
    */
   public Futures.Results<T> getAllNow() {
      return Futures.getAllNow(futures);
   }

   /**
    * @return a stream of all registered futures
    */
   public Stream<Future<T>> getFutures() {
      return futures.stream();
   }

   /**
    * Checks whether any of the registered futures is incomplete.
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

   /**
    * Waits for all currently registered futures to complete and returns the results of all normally completed futures.
    *
    * @return a list of results from all normally completed futures; an empty list if no futures are completed normally
    */
   public Futures.Results<T> joinAll() {
      return Futures.joinAll(futures);
   }

   /**
    * Waits up to the specified timeout for all currently registered futures to complete and returns the results of all normally completed
    * futures.
    *
    * @return a list of results from all normally completed futures; an empty list if no futures are completed normally
    */
   public Futures.Results<T> joinAll(final long timeout, final TimeUnit unit) {
      return Futures.joinAll(futures, timeout, unit);
   }

   public void register(final Future<T> future) {
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
