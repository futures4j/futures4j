/*
 * SPDX-FileCopyrightText: © Sebastian Thomschke
 * SPDX-License-Identifier: EPL-2.0
 * SPDX-FileContributor: Sebastian Thomschke: initial API and implementation
 */
package io.github.futures4j;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;

/**
 * Utility class for working with {@link Future} instances.
 * <p>
 * Provides methods and nested classes for combining, flattening, and manipulating multiple futures,
 * including support for cancellation, aggregation, and transformation of results.
 * </p>
 *
 * @author <a href="https://sebthom.de/">Sebastian Thomschke</a>
 */
public abstract class Futures {

   /**
    * Represents a future that combines multiple futures into a single future.
    * <p>
    * The {@code CombinedFuture} allows operations on the collection of combined futures,
    * such as cancelling all combined futures together.
    * </p>
    *
    * @param <FROM> the type of the results of the combined futures
    * @param <TO> the type of the result of this combined future
    */
   public static final class CombinedFuture<FROM, TO> extends ExtendedFuture<TO> {

      private final Collection<? extends Future<? extends FROM>> combinedFutures;

      private CombinedFuture(final Collection<? extends Future<? extends FROM>> combinedFutures) {
         super(false, true, null);
         this.combinedFutures = combinedFutures;
      }

      /**
       * Cancels this future and all combined futures.
       *
       * @param mayInterruptIfRunning {@code true} if the thread executing this task should be interrupted; otherwise, in-progress tasks are
       *           allowed to complete
       * @return {@code true} if this task is now cancelled
       */
      public boolean cancelAll(final boolean mayInterruptIfRunning) {
         if (isDone())
            return isCancelled();

         final var cancelled = super.cancel(mayInterruptIfRunning && isInterruptible());
         if (cancelled) {
            combinedFutures.forEach(f -> f.cancel(mayInterruptIfRunning && isInterruptible()));
         }
         return cancelled;
      }

      /**
       * @return a stream of the combined futures
       */
      public Stream<? extends Future<? extends FROM>> getCombinedFutures() {
         return combinedFutures.stream();
      }
   }

   /**
    * Builder class for aggregating multiple {@link Future} instances that return type {@code T}.
    * <p>
    * The {@code Combiner} allows adding futures and provides methods to combine them into a single future
    * that aggregates their results in various ways, such as into a list, set, map, or stream.
    * </p>
    *
    * @param <T> the type of the future results
    */
   public static class Combiner<T> {

      private final Set<Future<? extends T>> futures = new LinkedHashSet<>();

      /**
       * Adds a future to the combiner. Does nothing if the future was already added.
       *
       * @param future the future to add
       * @return this {@code Combiner} instance for method chaining
       */
      public Combiner<T> add(final @Nullable Future<? extends T> future) {
         if (future != null) {
            futures.add(future);
         }
         return this;
      }

      /**
       * @return a stream of futures added to this combiner.
       */
      public Stream<Future<? extends T>> getFutures() {
         return futures.stream();
      }

      /**
       * Returns a new {@link ExtendedFuture} that completes when any of the combined futures complete,
       * with the result or exception of the first future that completes.
       *
       * @return a future that completes when any of the combined futures complete
       */
      @SuppressWarnings("unchecked")
      public CombinedFuture<T, T> toAnyOf() {
         if (futures.isEmpty()) {
            final var combined = new CombinedFuture<T, T>(List.of());
            return combined;
         }

         final var futures = new ArrayList<>(this.futures);
         final var combinedFuture = new CombinedFuture<T, T>(futures);
         for (final var f : futures) {
            final var cf = toCompletableFuture((Future<T>) f);
            combinedFuture.completeWith(cf);
            if (combinedFuture.isDone())
               return combinedFuture;
         }

         return combinedFuture;
      }

      /**
       * Returns a new {@link ExtendedFuture} that completes when any of the combined futures complete successfully.
       * If all futures complete exceptionally, the returned future completes exceptionally with the last exception.
       *
       * @return a future that completes when any of the combined futures complete successfully, or exceptionally if all fail
       */
      @SuppressWarnings("unchecked")
      public CombinedFuture<T, T> toAnyOfDeferringExceptions() {
         if (futures.isEmpty()) {
            final var combined = new CombinedFuture<T, T>(List.of());
            return combined;
         }

         final var futures = new ArrayList<>(this.futures);
         final var combinedFuture = new CombinedFuture<T, T>(futures);

         final var incompleteFutures = new ArrayList<CompletableFuture<T>>();
         for (final var future : futures) {
            final var cf = toCompletableFuture((Future<T>) future);
            if (CompletionState.of(cf) == CompletionState.SUCCESS) {
               combinedFuture.complete(cf.join());
               return combinedFuture;
            }
            incompleteFutures.add(cf);
         }

         synchronized (incompleteFutures) {
            for (final var incompleteFuture : incompleteFutures) {
               if (combinedFuture.isDone())
                  return combinedFuture;

               incompleteFuture.whenComplete((res, ex) -> {
                  if (!combinedFuture.isDone()) {
                     if (ex == null) {
                        combinedFuture.complete(res);
                     } else {
                        synchronized (incompleteFutures) {
                           incompleteFutures.remove(incompleteFuture);
                           if (incompleteFutures.isEmpty()) {
                              combinedFuture.completeExceptionally(ex);
                           }
                        }
                     }
                  }
               });
            }
         }

         return combinedFuture;
      }

      /**
       * Combines the results of the added futures into a single future returning a {@link List} of results.
       *
       * @return a future containing a list of results from the combined futures
       */
      public CombinedFuture<T, List<T>> toList() {
         if (futures.isEmpty()) {
            final var combined = new CombinedFuture<T, List<T>>(List.of());
            combined.complete(List.of());
            return combined;
         }

         final var futures = new ArrayList<>(this.futures);
         var combiningFuture = CompletableFuture.completedFuture(new ArrayList<T>());

         for (final var future : futures) {
            combiningFuture = combiningFuture.thenCombine(Futures.toCompletableFuture(future), (combined, result) -> {
               combined.add(result);
               return combined;
            });
         }

         final var combinedFuture = new CombinedFuture<T, List<T>>(futures);
         combinedFuture.completeWith(combiningFuture);
         return combinedFuture;
      }

      /**
       * Combines the results of the added futures into a single future returning a {@link Map} of futures to results.
       *
       * @return a future containing a map of futures to their results
       */
      @SuppressWarnings("unchecked")
      public CombinedFuture<T, Map<Future<T>, T>> toMap() {
         if (futures.isEmpty()) {
            final var combined = new CombinedFuture<T, Map<Future<T>, T>>(List.of());
            combined.complete(Map.of());
            return combined;
         }

         final var futures = new ArrayList<>(this.futures);
         var combiningFuture = CompletableFuture.completedFuture(new HashMap<Future<T>, T>());

         for (final var future : futures) {
            combiningFuture = combiningFuture.thenCombine(Futures.toCompletableFuture(future), (combined, result) -> {
               if (result != null) {
                  combined.put((Future<T>) future, result);
               }
               return combined;
            });
         }

         final var combinedFuture = new CombinedFuture<T, Map<Future<T>, T>>(futures);
         combinedFuture.completeWith(combiningFuture);
         return combinedFuture;
      }

      /**
       * Combines the results of the added futures into a single future returning a {@link Set} of results.
       *
       * @return a future containing a set of results from the combined futures
       */
      public CombinedFuture<T, Set<T>> toSet() {
         if (futures.isEmpty()) {
            final var combined = new CombinedFuture<T, Set<T>>(List.of());
            combined.complete(Set.of());
            return combined;
         }

         final var futures = new ArrayList<>(this.futures);
         var combiningFuture = CompletableFuture.completedFuture(new HashSet<T>());

         for (final var future : futures) {
            combiningFuture = combiningFuture.thenCombine(Futures.toCompletableFuture(future), (combined, result) -> {
               combined.add(result);
               return combined;
            });
         }

         final var combinedFuture = new CombinedFuture<T, Set<T>>(futures);
         combinedFuture.completeWith(combiningFuture);
         return combinedFuture;
      }

      /**
       * Combines the results of the added futures into a single future returning a {@link Stream} of results.
       *
       * @return a future containing a stream of results from the combined futures
       */
      public CombinedFuture<T, Stream<T>> toStream() {
         if (futures.isEmpty()) {
            final var combined = new CombinedFuture<T, Stream<T>>(List.of());
            combined.complete(Stream.empty());
            return combined;
         }

         final var futures = new ArrayList<>(this.futures);
         CompletableFuture<Stream.Builder<T>> combiningFuture = CompletableFuture.completedFuture(Stream.builder());

         for (final var future : futures) {
            combiningFuture = combiningFuture.thenCombine(Futures.toCompletableFuture(future), (combined, result) -> {
               combined.add(result);
               return combined;
            });
         }

         final var combinedFuture = new CombinedFuture<T, Stream<T>>(futures);
         combiningFuture.whenComplete((result, ex) -> {
            if (ex == null) {
               combinedFuture.complete(result.build());
            } else {
               combinedFuture.completeExceptionally(ex);
            }
         });
         return combinedFuture;
      }
   }

   /**
    * Builder class for aggregating multiple {@link Future} instances that return collections of type {@code T},
    * and flattening the results into a single collection.
    * <p>
    * The {@code FlatteningCombiner} allows adding futures that produce collections (e.g., {@link Iterable}), and provides methods
    * to combine them into a single future that aggregates and flattens their results into a single collection like a list, set, or stream.
    * </p>
    *
    * @param <T> the type of the future results
    */
   public static class FlatteningCombiner<T> {

      private final Set<Future<? extends @Nullable Iterable<? extends T>>> futures = new LinkedHashSet<>();

      /**
       * Adds a future to the combiner. Does nothing if the future was already added.
       *
       * @param future the future to add
       * @return this {@code FlatteningCombiner} instance for method chaining
       */
      public FlatteningCombiner<T> add(final @Nullable Future<? extends @Nullable Iterable<? extends T>> future) {
         if (future != null) {
            futures.add(future);
         }
         return this;
      }

      /**
       * @return a stream of futures added to this combiner.
       */
      public Stream<Future<? extends @Nullable Iterable<? extends T>>> getFutures() {
         return futures.stream();
      }

      /**
       * Combines the results of the added futures into a single future returning a {@link List} of results.
       * The collections returned by the futures are flattened into a single list.
       *
       * @return a future containing a list of results from the combined futures
       */
      public CombinedFuture<Iterable<? extends T>, List<T>> toList() {
         if (futures.isEmpty()) {
            final var combined = new CombinedFuture<Iterable<? extends T>, List<T>>(List.of());
            combined.complete(List.of());
            return combined;
         }

         final var futures = new ArrayList<>(this.futures);
         var combiningFuture = CompletableFuture.completedFuture(new ArrayList<T>());

         for (final var future : futures) {
            combiningFuture = combiningFuture.thenCombine(Futures.toCompletableFuture(future), (combined, result) -> {
               if (result != null) {
                  result.forEach(combined::add);
               }
               return combined;
            });
         }

         final var combinedFuture = new CombinedFuture<Iterable<? extends T>, List<T>>(futures);
         combinedFuture.completeWith(combiningFuture);
         return combinedFuture;
      }

      /**
       * Combines the results of the added futures into a single future returning a {@link Set} of results.
       * The collections returned by the futures are flattened into a single set.
       *
       * @return a future containing a set of results from the combined futures
       */
      public CombinedFuture<Iterable<? extends T>, Set<T>> toSet() {
         if (futures.isEmpty()) {
            final var combined = new CombinedFuture<Iterable<? extends T>, Set<T>>(List.of());
            combined.complete(Set.of());
            return combined;
         }

         final var futures = new ArrayList<>(this.futures);
         var combiningFuture = CompletableFuture.completedFuture(new HashSet<T>());

         for (final var future : futures) {
            combiningFuture = combiningFuture.thenCombine(Futures.toCompletableFuture(future), (combined, result) -> {
               if (result != null) {
                  result.forEach(combined::add);
               }
               return combined;
            });
         }

         final var combinedFuture = new CombinedFuture<Iterable<? extends T>, Set<T>>(futures);
         combinedFuture.completeWith(combiningFuture);
         return combinedFuture;
      }

      /**
       * Combines the results of the added futures into a single future returning a {@link Stream} of results.
       * The collections returned by the futures are flattened into a single stream.
       *
       * @return a future containing a stream of results from the combined futures
       */
      public CombinedFuture<Iterable<? extends T>, Stream<T>> toStream() {
         if (futures.isEmpty()) {
            final var combined = new CombinedFuture<Iterable<? extends T>, Stream<T>>(List.of());
            combined.complete(Stream.empty());
            return combined;
         }

         final var futures = new ArrayList<>(this.futures);
         CompletableFuture<Stream<T>> combiningFuture = CompletableFuture.completedFuture(Stream.of());

         for (final var future : futures) {
            combiningFuture = combiningFuture.thenCombine(Futures.toCompletableFuture(future), (combined, result) -> result == null
                  ? combined
                  : Stream.concat(combined, StreamSupport.stream(result.spliterator(), false)));
         }

         final var combinedFuture = new CombinedFuture<Iterable<? extends T>, Stream<T>>(futures);
         combinedFuture.completeWith(combiningFuture);
         return combinedFuture;
      }
   }

   private static final Logger LOG = System.getLogger(Futures.class.getName());

   /**
    * Cancels the future if it is incomplete, without interrupting running tasks.
    *
    * @param futureToCancel the future to cancel
    * @return {@code true} if the future is now cancelled
    */
   public static boolean cancel(final @Nullable Future<?> futureToCancel) {
      return cancel(futureToCancel, false);
   }

   /**
    * Cancels the future if it is incomplete.
    *
    * @param futureToCancel the future to cancel
    * @param mayInterruptIfRunning {@code true} if the thread executing this task should be interrupted; otherwise, in-progress tasks are
    *           allowed to complete
    * @return {@code true} if the future is now cancelled
    */
   public static boolean cancel(final @Nullable Future<?> futureToCancel, final boolean mayInterruptIfRunning) {
      if (futureToCancel == null)
         return false;

      if (!futureToCancel.isDone()) {
         futureToCancel.cancel(mayInterruptIfRunning);
      }
      return futureToCancel.isCancelled();
   }

   /**
    * Cancels the future if it is incomplete, interrupting if running.
    *
    * @param futureToCancel the future to cancel
    * @return {@code true} if the future is now cancelled
    */
   public static boolean cancelInterruptibly(final @Nullable Future<?> futureToCancel) {
      return cancel(futureToCancel, true);
   }

   /**
    * Creates a new {@link Combiner} and adds the specified futures to it.
    *
    * @param futures the futures to combine
    * @param <T> the type of the future results
    * @return a new {@code Combiner} containing the specified futures
    */
   @SafeVarargs
   public static <T> Combiner<T> combine(final @NonNullByDefault({}) Future<? extends T> @Nullable... futures) {
      final var combiner = new Combiner<T>();
      if (futures != null) {
         for (final var future : futures)
            if (future != null) {
               combiner.add(future);
            }
      }
      return combiner;
   }

   /**
    * Creates a new {@link Combiner} and adds the specified futures to it.
    *
    * @param futures the futures to combine
    * @param <T> the type of the future results
    * @return a new {@code Combiner} containing the specified futures
    */
   public static <T> Combiner<T> combine(final @Nullable Iterable<? extends @Nullable Future<? extends T>> futures) {
      final var combiner = new Combiner<T>();
      if (futures != null) {
         for (final var future : futures)
            if (future != null) {
               combiner.add(future);
            }
      }
      return combiner;
   }

   /**
    * Creates a new {@link FlatteningCombiner} and adds the specified futures to it.
    *
    * @param futures the futures to combine
    * @param <T> the type of the future results
    * @return a new {@code FlatteningCombiner} containing the specified futures
    */
   @SafeVarargs
   public static <T> FlatteningCombiner<T> combineFlattened(
         final @NonNullByDefault({}) Future<? extends @Nullable Iterable<T>> @Nullable... futures) {
      final var combiner = new FlatteningCombiner<T>();
      if (futures != null) {
         for (final var future : futures)
            if (future != null) {
               combiner.add(future);
            }
      }
      return combiner;
   }

   /**
    * Creates a new {@link FlatteningCombiner} and adds the specified futures to it.
    *
    * @param futures the futures to combine
    * @param <T> the type of the future results
    * @return a new {@code FlatteningCombiner} containing the specified futures
    */
   public static <T> FlatteningCombiner<T> combineFlattened(
         final @Nullable Iterable<? extends @Nullable Future<? extends @Nullable Iterable<? extends T>>> futures) {
      final var combiner = new FlatteningCombiner<T>();
      if (futures != null) {
         for (final var future : futures)
            if (future != null) {
               combiner.add(future);
            }
      }
      return combiner;
   }

   /**
    * Propagates the cancellation of a {@link CompletableFuture} to other {@link Future}s.
    * <p>
    * If the specified {@code from} future is cancelled, all futures in the provided {@code to} collection will be cancelled too.
    * </p>
    *
    * @param from the {@link CompletableFuture} whose cancellation should be propagated
    * @param to the collection of {@link Future} instances that should be cancelled if {@code from} is cancelled
    */
   public static void forwardCancellation(final CompletableFuture<?> from, final @Nullable Collection<? extends @Nullable Future<?>> to) {
      if (to == null || to.isEmpty())
         return;
      from.whenComplete((result, t) -> {
         if (t instanceof CancellationException) {
            for (final var f : to) {
               if (f != null) {
                  try {
                     f.cancel(true);
                  } catch (final Exception ex) {
                     LOG.log(Level.ERROR, ex.getMessage(), ex);
                  }
               }
            }
         }
      });
   }

   /**
    * Propagates the cancellation of a {@link CompletableFuture} to another {@link Future}.
    * <p>
    * If the specified {@code from} future is cancelled, the {@code to} future will be cancelled too.
    * </p>
    *
    * @param from the {@link CompletableFuture} whose cancellation should be propagated
    * @param to the {@link Future} instance that should be cancelled if {@code from} is cancelled
    */
   public static void forwardCancellation(final CompletableFuture<?> from, final @Nullable Future<?> to) {
      if (to == null)
         return;
      from.whenComplete((result, ex) -> {
         if (ex instanceof CancellationException) {
            to.cancel(true);
         }
      });
   }

   /**
    * Propagates the cancellation of a {@link CompletableFuture} to other {@link Future}s.
    * <p>
    * If the specified {@code from} future is cancelled, all futures in the provided {@code to} array will be cancelled too.
    * </p>
    *
    * @param from the {@link CompletableFuture} whose cancellation should be propagated
    * @param to the array of {@link Future} instances that should be cancelled if {@code from} is cancelled
    */
   public static void forwardCancellation(final CompletableFuture<?> from, final @NonNullByDefault({}) Future<?> @Nullable... to) {
      if (to == null || to.length == 0)
         return;
      from.whenComplete((result, t) -> {
         if (t instanceof CancellationException) {
            for (final var f : to) {
               if (f != null) {
                  try {
                     f.cancel(true);
                  } catch (final Exception ex) {
                     LOG.log(Level.ERROR, ex.getMessage(), ex);
                  }
               }
            }
         }
      });
   }

   /**
    * Returns the result of the given {@link Future} if it is already completed, wrapped in an {@link Optional},
    * or an empty {@link Optional} if the future is incomplete, cancelled, or failed.
    *
    * @param future the future to get the result from
    * @param <T> the type of the future result
    * @return an {@link Optional} containing the result if completed normally, or empty otherwise
    */
   public static <T> Optional<T> getNowOptional(final Future<T> future) {
      if (future.isDone())
         return getOptional(future, 0, TimeUnit.SECONDS);
      return Optional.empty();
   }

   /**
    * Returns the result of the given {@link Future} if it is already completed, or the value computed by
    * {@code fallbackComputer} if the future is incomplete or failed.
    *
    * @param future the future to get the result from
    * @param fallbackComputer a function to compute the fallback value
    * @param <T> the type of the future result
    * @return the result if completed normally, or the computed fallback value
    */
   public static <T> T getNowOrComputeFallback(final Future<T> future,
         final BiFunction<Future<T>, @Nullable Exception, T> fallbackComputer) {
      if (future.isDone())
         return getOrComputeFallback(future, fallbackComputer, 0, TimeUnit.SECONDS);
      return fallbackComputer.apply(future, null);
   }

   /**
    * Returns the result of the given {@link Future} if it is already completed, or the value computed by
    * {@code fallbackComputer} if the future is incomplete or failed.
    *
    * @param future the future to get the result from
    * @param fallbackComputer a function to compute the fallback value
    * @param <T> the type of the future result
    * @return the result if completed normally, or the computed fallback value
    */
   public static <T> T getNowOrComputeFallback(final Future<T> future, final Function<@Nullable Exception, T> fallbackComputer) {
      if (future.isDone())
         return getOrComputeFallback(future, fallbackComputer, 0, TimeUnit.SECONDS);
      return fallbackComputer.apply(null);
   }

   /**
    * Returns the result of the given {@link Future} if it is already completed, or the specified
    * {@code fallback} if the future is incomplete or failed.
    *
    * @param future the future to get the result from
    * @param fallback the fallback value to return if the future is incomplete or failed
    * @param <T> the type of the future result
    * @return the result if completed normally, or the fallback value
    */
   public static <T> T getNowOrFallback(final Future<T> future, final T fallback) {
      if (future.isDone())
         return getOrFallback(future, fallback, 0, TimeUnit.SECONDS);
      return fallback;
   }

   /**
    * Attempts to retrieve the result of the given {@link Future}.
    *
    * @param future the future to get the result from
    * @param <T> the type of the future result
    * @return an {@link Optional} containing the result if completed normally, or empty otherwise
    */
   public static <T> Optional<T> getOptional(final Future<T> future) {
      try {
         return Optional.ofNullable(future.get());
      } catch (final InterruptedException ex) {
         Thread.currentThread().interrupt();
         LOG.log(Level.DEBUG, ex.getMessage(), ex);
      } catch (final Exception ex) {
         LOG.log(Level.DEBUG, ex.getMessage(), ex);
      }
      return Optional.empty();
   }

   /**
    * Attempts to retrieve the result of the given {@link Future} within the specified timeout.
    *
    * @param future the future to get the result from
    * @param timeout the maximum time to wait
    * @param unit the time unit of the timeout argument
    * @param <T> the type of the future result
    * @return an {@link Optional} containing the result if completed within the timeout, or empty otherwise
    */
   public static <T> Optional<T> getOptional(final Future<T> future, final long timeout, final TimeUnit unit) {
      try {
         return Optional.ofNullable(future.get(timeout, unit));
      } catch (final TimeoutException ex) {
         if (LOG.isLoggable(Level.DEBUG)) {
            LOG.log(Level.DEBUG, "Could not get result within " + timeout + " " + unit.toString().toLowerCase() + "(s)", ex);
         }
      } catch (final InterruptedException ex) {
         Thread.currentThread().interrupt();
         LOG.log(Level.DEBUG, ex.getMessage(), ex);
      } catch (final Exception ex) {
         LOG.log(Level.DEBUG, ex.getMessage(), ex);
      }
      return Optional.empty();
   }

   /**
    * Waits for the given {@link Future} to complete and returns its result if it completes normally.
    * If the future is interrupted, cancelled, or failed, the value computed by {@code fallbackComputer} is returned instead.
    *
    * @param future the future to get the result from
    * @param fallbackComputer a function to compute the fallback value
    * @param <T> the type of the future result
    * @return the result if completed normally, or the computed fallback value
    */
   public static <T> T getOrComputeFallback(final Future<T> future, final BiFunction<Future<T>, @Nullable Exception, T> fallbackComputer) {
      try {
         return future.get();
      } catch (final InterruptedException ex) {
         Thread.currentThread().interrupt();
         LOG.log(Level.DEBUG, ex.getMessage(), ex);
         return fallbackComputer.apply(future, ex);
      } catch (final Exception ex) {
         LOG.log(Level.DEBUG, ex.getMessage(), ex);
         return fallbackComputer.apply(future, ex);
      }
   }

   /**
    * Waits up to the specified timeout for the given {@link Future} to complete and returns its result if it completes normally.
    * If the future is interrupted, timed out, cancelled, or failed, the value computed by {@code fallbackComputer} is returned instead.
    *
    * @param future the future to get the result from
    * @param fallbackComputer a function to compute the fallback value
    * @param timeout the maximum time to wait
    * @param unit the time unit of the timeout argument
    * @param <T> the type of the future result
    * @return the result if completed within the timeout, or the computed fallback value
    */
   public static <T> T getOrComputeFallback(final Future<T> future, final BiFunction<Future<T>, @Nullable Exception, T> fallbackComputer,
         final long timeout, final TimeUnit unit) {
      try {
         return future.get(timeout, unit);
      } catch (final TimeoutException ex) {
         if (LOG.isLoggable(Level.DEBUG)) {
            LOG.log(Level.DEBUG, "Could not get result within " + timeout + " " + unit.toString().toLowerCase() + "(s)", ex);
         }
         return fallbackComputer.apply(future, ex);
      } catch (final InterruptedException ex) {
         Thread.currentThread().interrupt();
         LOG.log(Level.DEBUG, ex.getMessage(), ex);
         return fallbackComputer.apply(future, ex);
      } catch (final Exception ex) {
         LOG.log(Level.DEBUG, ex.getMessage(), ex);
         return fallbackComputer.apply(future, ex);
      }
   }

   /**
    * Waits for the given {@link Future} to complete and returns its result if it completes normally.
    * If the future is interrupted, cancelled, or failed, the value computed by {@code fallbackComputer} is returned instead.
    *
    * @param future the future to get the result from
    * @param fallbackComputer a function to compute the fallback value
    * @param <T> the type of the future result
    * @return the result if completed normally, or the computed fallback value
    */
   public static <T> T getOrComputeFallback(final Future<T> future, final Function<@Nullable Exception, T> fallbackComputer) {
      try {
         return future.get();
      } catch (final InterruptedException ex) {
         Thread.currentThread().interrupt();
         LOG.log(Level.DEBUG, ex.getMessage(), ex);
         return fallbackComputer.apply(ex);
      } catch (final Exception ex) {
         LOG.log(Level.DEBUG, ex.getMessage(), ex);
         return fallbackComputer.apply(ex);
      }
   }

   /**
    * Waits up to the specified timeout for the given {@link Future} to complete and returns its result if it completes normally.
    * If the future is interrupted, timed out, cancelled, or failed, the value computed by {@code fallbackComputer} is returned instead.
    *
    * @param future the future to get the result from
    * @param fallbackComputer a function to compute the fallback value
    * @param timeout the maximum time to wait
    * @param unit the time unit of the timeout argument
    * @param <T> the type of the future result
    * @return the result if completed within the timeout, or the computed fallback value
    */
   public static <T> T getOrComputeFallback(final Future<T> future, final Function<@Nullable Exception, T> fallbackComputer,
         final long timeout, final TimeUnit unit) {
      try {
         return future.get(timeout, unit);
      } catch (final TimeoutException ex) {
         if (LOG.isLoggable(Level.DEBUG)) {
            LOG.log(Level.DEBUG, "Could not get result within " + timeout + " " + unit.toString().toLowerCase() + "(s)", ex);
         }
         return fallbackComputer.apply(ex);
      } catch (final InterruptedException ex) {
         Thread.currentThread().interrupt();
         LOG.log(Level.DEBUG, ex.getMessage(), ex);
         return fallbackComputer.apply(ex);
      } catch (final Exception ex) {
         LOG.log(Level.DEBUG, ex.getMessage(), ex);
         return fallbackComputer.apply(ex);
      }
   }

   /**
    * Waits for the given {@link Future} to complete and returns its result if it completes normally.
    * If the future is interrupted, cancelled, or failed, the provided fallback value is returned instead.
    *
    * @param future the future to get the result from
    * @param fallback the fallback value to return if the future completes with an exception
    * @param <T> the type of the future result
    * @return the result if completed normally, or the fallback value
    */
   public static <T> T getOrFallback(final Future<T> future, final T fallback) {
      try {
         return future.get();
      } catch (final InterruptedException ex) {
         Thread.currentThread().interrupt();
         LOG.log(Level.DEBUG, ex.getMessage(), ex);
      } catch (final Exception ex) {
         LOG.log(Level.DEBUG, ex.getMessage(), ex);
      }
      return fallback;
   }

   /**
    * Waits up to the specified timeout for the given {@link Future} to complete and returns its result if it completes normally.
    * If the future is interrupted, timed out, cancelled, or failed, the provided fallback value is returned instead.
    *
    * @param future the future to get the result from
    * @param fallback the fallback value to return if the future completes with an exception
    * @param timeout the maximum time to wait
    * @param unit the time unit of the timeout argument
    * @param <T> the type of the future result
    * @return the result if completed within the timeout, or the fallback value
    */
   public static <T> T getOrFallback(final Future<T> future, final T fallback, final long timeout, final TimeUnit unit) {
      try {
         return future.get(timeout, unit);
      } catch (final TimeoutException ex) {
         if (LOG.isLoggable(Level.DEBUG)) {
            LOG.log(Level.DEBUG, "Could not get result within " + timeout + " " + unit.toString().toLowerCase() + "(s)", ex);
         }
      } catch (final InterruptedException ex) {
         Thread.currentThread().interrupt();
         LOG.log(Level.DEBUG, ex.getMessage(), ex);
      } catch (final Exception ex) {
         LOG.log(Level.DEBUG, ex.getMessage(), ex);
      }
      return fallback;
   }

   private static <T> CompletableFuture<T> toCompletableFuture(final Future<T> future) {
      if (future instanceof final CompletableFuture<T> cf)
         return cf;
      final var cf = CompletableFuture.supplyAsync(() -> {
         try {
            return future.get();
         } catch (final InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new CompletionException(ex);
         } catch (final ExecutionException ex) {
            throw new CompletionException(ex.getCause());
         }
      });
      forwardCancellation(cf, future);
      return cf;
   }
}
