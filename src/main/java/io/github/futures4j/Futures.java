/*
 * SPDX-FileCopyrightText: © Sebastian Thomschke
 * SPDX-License-Identifier: EPL-2.0
 * SPDX-FileContributor: Sebastian Thomschke: initial API and implementation
 */
package io.github.futures4j;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
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
import java.util.stream.Stream;
import java.util.stream.Stream.Builder;
import java.util.stream.StreamSupport;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;

/**
 * Utility class for working with {@link Future} instances.
 *
 * @author <a href="https://sebthom.de/">Sebastian Thomschke</a>
 */
public abstract class Futures {

   /**
    * A builder interface for combining multiple {@link Future} instances into a single
    * {@link ExtendedFuture} that aggregates their results.
    *
    * <p>
    * The {@code Combiner} allows for optional configurations, such as forwarding cancellations to the underlying futures,
    * before producing a combined future in a desired collection format.
    * </p>
    *
    * <p>
    * Usage example:
    * </p>
    *
    * <pre>{@code
    * Combiner<String> combiner = Futures.combine(future1, future2, future3);
    * ExtendedFuture<List<String>> combinedFuture = combiner.forwardCancellation().toList();
    * }</pre>
    *
    * @param <T> the type of the results of the futures being combined
    */
   public interface Combiner<T> {
      /**
       * Enables forwarding of cancellation of the combined future to its underlying futures.
       *
       * @return this {@code Combiner} instance for method chaining
       */
      Combiner<T> forwardCancellation();

      /**
       * Combines the futures into a single future that returns a {@link List} of results.
       *
       * @return an {@link ExtendedFuture} containing a list of results
       */
      ExtendedFuture<List<T>> toList();

      /**
       * Combines the futures into a single future that returns a {@link Set} of results.
       *
       * @return an {@link ExtendedFuture} containing a set of results
       */
      ExtendedFuture<Set<T>> toSet();

      /**
       * Combines the futures into a single future that returns a {@link Stream} of results.
       *
       * @return an {@link ExtendedFuture} containing a stream of results
       */
      ExtendedFuture<Stream<T>> toStream();
   }

   public interface CombinerWithToMap<T> extends Combiner<T> {
      /**
       * Enables forwarding of cancellation of the combined future to its underlying futures.
       *
       * @return this {@code Combiner} instance for method chaining
       */
      @Override
      CombinerWithToMap<T> forwardCancellation();

      /**
       * Combines the futures into a single future that returns a {@link List} of results.
       *
       * @return an {@link ExtendedFuture} containing a list of results
       */
      ExtendedFuture<Map<Future<T>, T>> toMap();
   }

   /**
    * Represents the results of multiple {@link Future} computations, capturing both successful results and exceptions from futures that
    * failed, were cancelled, or timed out.
    *
    * @param <T> the type of the result of the futures
    * @param results a map of {@link Future} instances that completed normally, mapped to their results
    * @param exceptions a map of {@link Future} instances that failed, were cancelled, or timed out, mapped to the corresponding exceptions
    */
   public record Results<T>(Map<Future<? extends T>, T> results, Map<Future<? extends T>, Exception> exceptions) {
      private static final Results<?> EMPTY = new Results<>(Collections.emptyMap(), Collections.emptyMap());

      @SuppressWarnings("unchecked")
      private static <V> Results<V> empty() {
         return (Results<V>) EMPTY;
      }

      /**
       * Ensures that all {@link Future} instances within this {@code Results} object have completed successfully.
       *
       * <p>
       * If any of the futures encountered an issue (e.g., failed, was interrupted, canceled, or did not finish),
       * the method throws a {@link CompletionException} containing the first encountered exception as its cause.
       * </p>
       *
       * @return this {@code Results} instance for method chaining
       * @throws CompletionException if at least one {@link Future} did not complete normally
       */
      public Results<T> assertCompletedNormally() {
         if (!exceptions.isEmpty())
            throw new CompletionException("", exceptions.values().iterator().next());
         return this;
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
    * Cancels all incomplete futures without interrupting running tasks.
    *
    * @param futuresToCancel the array of futures to cancel
    * @return the number of futures that are now cancelled
    */
   public static int cancelAll(final @NonNullByDefault({}) Future<?> @Nullable... futuresToCancel) {
      return cancelAll(futuresToCancel, false);
   }

   /**
    * Cancels all incomplete futures.
    *
    * @param futuresToCancel the array of futures to cancel
    * @param mayInterruptIfRunning {@code true} if the thread executing this task should be interrupted; otherwise, in-progress tasks are
    *           allowed to complete
    * @return the number of futures that are now cancelled
    */
   public static int cancelAll(final @NonNullByDefault({}) Future<?> @Nullable [] futuresToCancel, final boolean mayInterruptIfRunning) {
      if (futuresToCancel == null || futuresToCancel.length == 0)
         return 0;
      int cancelled = 0;
      for (final Future<?> futureToCancel : futuresToCancel) {
         if (cancel(futureToCancel, mayInterruptIfRunning)) {
            cancelled++;
         }
      }
      return cancelled;
   }

   /**
    * Cancels all incomplete futures without interrupting running tasks.
    *
    * @param futuresToCancel the iterable of futures to cancel
    * @return the number of futures that are now cancelled
    */
   public static int cancelAll(final @Nullable Iterable<? extends @Nullable Future<?>> futuresToCancel) {
      return cancelAll(futuresToCancel, false);
   }

   /**
    * Cancels all incomplete futures.
    *
    * @param futuresToCancel the iterable of futures to cancel
    * @param mayInterruptIfRunning {@code true} if the thread executing this task should be interrupted; otherwise, in-progress tasks are
    *           allowed to complete
    * @return the number of futures that are now cancelled
    */
   public static int cancelAll(final @Nullable Iterable<? extends @Nullable Future<?>> futuresToCancel,
         final boolean mayInterruptIfRunning) {
      if (futuresToCancel == null)
         return 0;
      int cancelled = 0;
      for (final Future<?> futureToCancel : futuresToCancel) {
         if (cancel(futureToCancel, mayInterruptIfRunning)) {
            cancelled++;
         }
      }
      return cancelled;
   }

   /**
    * Cancels all incomplete futures, interrupting running tasks.
    *
    * @param futuresToCancel the array of futures to cancel
    * @return the number of futures that are now cancelled
    */
   public static int cancelAllInterruptibly(final @NonNullByDefault({}) Future<?> @Nullable... futuresToCancel) {
      return cancelAll(futuresToCancel, true);
   }

   /**
    * Cancels all incomplete futures, interrupting running tasks.
    *
    * @param futuresToCancel the iterable of futures to cancel
    * @return the number of futures that are now cancelled
    */
   public static int cancelAllInterruptibly(final @Nullable Iterable<? extends @Nullable Future<?>> futuresToCancel) {
      return cancelAll(futuresToCancel, true);
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

   @SafeVarargs
   @SuppressWarnings("null")
   public static <T> CombinerWithToMap<T> combine(final @NonNullByDefault({}) Future<? extends T> @Nullable... futures) {
      if (futures == null || futures.length == 0)
         return combineInternal(List.of());
      return combineInternal(Arrays.asList(futures));
   }

   public static <T> CombinerWithToMap<T> combine(final @Nullable Iterable<? extends @Nullable Future<? extends T>> futures) {
      if (futures == null)
         return combineInternal(List.of());
      final var futuresInNewList = futures instanceof final Collection<? extends @Nullable Future<? extends T>> coll //
            ? new ArrayList<>(coll)
            : StreamSupport.stream(futures.spliterator(), false).toList();
      return combineInternal(futuresInNewList);
   }

   @SafeVarargs
   @SuppressWarnings("null")
   public static <T> Combiner<T> combineFlattened(
         final @NonNullByDefault({}) Future<? extends @Nullable Collection<T>> @Nullable... futures) {
      if (futures == null || futures.length == 0)
         return combineFlattenedInternal(List.of());
      return combineFlattenedInternal(Arrays.asList(futures));
   }

   public static <T> Combiner<T> combineFlattened(
         final @Nullable Iterable<? extends @Nullable Future<? extends @Nullable Collection<? extends T>>> futures) {
      if (futures == null)
         return combineFlattenedInternal(List.of());
      final var futuresInNewList = futures instanceof final Collection<? extends @Nullable Future<? extends @Nullable Collection<? extends T>>> coll
            ? new ArrayList<>(coll)
            : StreamSupport.stream(futures.spliterator(), false).toList();
      return combineFlattenedInternal(futuresInNewList);
   }

   private static <T> Combiner<T> combineFlattenedInternal(
         final Collection<? extends @Nullable Future<? extends @Nullable Collection<? extends T>>> futures) {
      return new Combiner<>() {
         private boolean forwardCancellation = false;

         @Override
         public Combiner<T> forwardCancellation() {
            forwardCancellation = true;
            return this;
         }

         @Override
         public ExtendedFuture<List<T>> toList() {
            if (futures.isEmpty())
               return ExtendedFuture.completedFuture(List.of());

            CompletableFuture<List<T>> combinedFuture = CompletableFuture.completedFuture(new ArrayList<>());

            for (final var future : futures) {
               if (future != null) {
                  combinedFuture = combinedFuture.thenCombine(toCompletableFuture(future), (combined, result) -> {
                     if (result != null) {
                        combined.addAll(result);
                     }
                     return combined;
                  });
               }
            }

            final var result = ExtendedFuture.from(combinedFuture);
            if (forwardCancellation) {
               result.forwardCancellationTo(futures);
            }
            return result;
         }

         @Override
         public ExtendedFuture<Set<T>> toSet() {
            if (futures.isEmpty())
               return ExtendedFuture.completedFuture(Set.of());

            CompletableFuture<Set<T>> combinedFuture = CompletableFuture.completedFuture(new HashSet<>());

            for (final var future : futures) {
               if (future != null) {
                  combinedFuture = combinedFuture.thenCombine(toCompletableFuture(future), (combined, result) -> {
                     if (result != null) {
                        combined.addAll(result);
                     }
                     return combined;
                  });
               }
            }

            final var result = ExtendedFuture.from(combinedFuture);
            if (forwardCancellation) {
               result.forwardCancellationTo(futures);
            }
            return result;
         }

         @Override
         public ExtendedFuture<Stream<T>> toStream() {
            if (futures.isEmpty())
               return ExtendedFuture.completedFuture(Stream.of());

            CompletableFuture<Stream<T>> combinedFuture = CompletableFuture.completedFuture(Stream.of());

            for (final var future : futures) {
               if (future != null) {
                  combinedFuture = combinedFuture.thenCombine(toCompletableFuture(future), (combined, result) -> result == null ? combined
                        : Stream.concat(combined, result.stream()));
               }
            }

            final var result = ExtendedFuture.from(combinedFuture);
            if (forwardCancellation) {
               result.forwardCancellationTo(futures);
            }
            return result;
         }
      };
   }

   private static <T> CombinerWithToMap<T> combineInternal(final Collection<? extends @Nullable Future<? extends T>> futures) {
      return new CombinerWithToMap<>() {
         private boolean forwardCancellation = false;

         @Override
         public CombinerWithToMap<T> forwardCancellation() {
            forwardCancellation = true;
            return this;
         }

         @Override
         public ExtendedFuture<List<T>> toList() {
            if (futures.isEmpty())
               return ExtendedFuture.completedFuture(List.of());

            CompletableFuture<List<T>> combinedFuture = CompletableFuture.completedFuture(new ArrayList<>());

            for (final var future : futures) {
               if (future != null) {
                  combinedFuture = combinedFuture.thenCombine(toCompletableFuture(future), (combined, result) -> {
                     combined.add(result);
                     return combined;
                  });
               }
            }

            final var result = ExtendedFuture.from(combinedFuture);
            if (forwardCancellation) {
               result.forwardCancellationTo(futures);
            }
            return result;
         }

         @Override
         @SuppressWarnings("unchecked")
         public ExtendedFuture<Map<Future<T>, T>> toMap() {
            if (futures.isEmpty())
               return ExtendedFuture.completedFuture(Map.of());

            CompletableFuture<Map<Future<T>, T>> combinedFuture = CompletableFuture.completedFuture(new HashMap<>());

            for (final var future : futures) {
               if (future != null) {
                  combinedFuture = combinedFuture.thenCombine(toCompletableFuture(future), (combined, result) -> {
                     if (result != null) {
                        combined.put((Future<T>) future, result);
                     }
                     return combined;
                  });
               }
            }

            final var result = ExtendedFuture.from(combinedFuture);
            if (forwardCancellation) {
               result.forwardCancellationTo(futures);
            }
            return result;
         }

         @Override
         public ExtendedFuture<Set<T>> toSet() {
            if (futures.isEmpty())
               return ExtendedFuture.completedFuture(Set.of());

            CompletableFuture<Set<T>> combinedFuture = CompletableFuture.completedFuture(new HashSet<>());

            for (final var future : futures) {
               if (future != null) {
                  combinedFuture = combinedFuture.thenCombine(toCompletableFuture(future), (combined, result) -> {
                     combined.add(result);
                     return combined;
                  });
               }
            }

            final var result = ExtendedFuture.from(combinedFuture);
            if (forwardCancellation) {
               result.forwardCancellationTo(futures);
            }
            return result;
         }

         @Override
         public ExtendedFuture<Stream<T>> toStream() {
            if (futures.isEmpty())
               return ExtendedFuture.completedFuture(Stream.of());

            CompletableFuture<Stream.Builder<T>> combinedFuture = CompletableFuture.completedFuture(Stream.builder());

            for (final var future : futures) {
               if (future != null) {
                  combinedFuture = combinedFuture.thenCombine(toCompletableFuture(future), (combined, result) -> { //
                     combined.add(result);
                     return combined;
                  });
               }
            }

            final var result = ExtendedFuture.from(combinedFuture.thenApply(Builder::build));
            if (forwardCancellation) {
               result.forwardCancellationTo(futures);
            }
            return result;
         }
      };
   }

   /**
    * Propagates the cancellation of a {@link CompletableFuture} to other {@link Future}s.
    * <p>
    * If the specified {@code from} future is cancelled, all futures in the provided {@code to} collection will be cancelled too.
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
    * Waits for all futures to complete and returns a list of results from all normally completed futures.
    * <p>
    * If at least one future was cancelled or failed, this method will throw the corresponding exception.
    *
    * @param futures the futures to wait for
    * @return a list of results from all completed futures
    *
    * @throws CancellationException if any future was cancelled
    * @throws ExecutionException if any future failed
    * @throws InterruptedException if the current thread was interrupted while waiting
    */
   @SafeVarargs
   public static <T> List<T> getAll(final @Nullable Future<? extends T> @Nullable... futures) throws ExecutionException,
         InterruptedException {
      if (futures == null || futures.length == 0)
         return List.of();

      final var result = new ArrayList<T>();
      for (final var future : futures) {
         if (future != null) {
            result.add(future.get());
         }
      }
      return result;
   }

   /**
    * Waits up to the specified timeout for all futures to complete and returns a list of results from all normally completed futures.
    * <p>
    * If at least one future was cancelled, failed, or did not complete within the specified time, this method will throw
    * the corresponding exception.
    *
    * @param futures the futures to wait for
    * @param timeout the maximum time to wait
    * @param unit the time unit of the timeout argument
    * @return a list of results from all completed futures
    *
    * @throws CancellationException if any future was cancelled
    * @throws ExecutionException if any future failed
    * @throws InterruptedException if the current thread was interrupted while waiting
    * @throws TimeoutException if the wait timed out before all futures completed
    */
   public static <T> List<T> getAll(final @Nullable Future<? extends T> @Nullable [] futures, final long timeout, final TimeUnit unit)
         throws ExecutionException, InterruptedException, TimeoutException {
      if (futures == null || futures.length == 0)
         return List.of();

      final var result = new ArrayList<T>();
      final var timeoutMS = unit.toMillis(timeout);
      final var startAt = System.currentTimeMillis();
      for (final var future : futures) {
         if (future != null) {
            final var maxWaitMS = Math.max(0, timeoutMS - (System.currentTimeMillis() - startAt));
            result.add(future.get(maxWaitMS, TimeUnit.MILLISECONDS));
         }
      }
      return result;
   }

   /**
    * Waits for all futures to complete and returns a list of results from all normally completed futures.
    * <p>
    * If at least one future was cancelled or failed, this method will throw the corresponding exception.
    *
    * @param futures the futures to wait for
    * @return a list of results from all completed futures
    *
    * @throws CancellationException if any future was cancelled
    * @throws ExecutionException if any future failed
    * @throws InterruptedException if the current thread was interrupted while waiting
    */
   public static <T> List<T> getAll(final @Nullable Iterable<? extends @Nullable Future<? extends T>> futures) throws ExecutionException,
         InterruptedException {
      if (futures == null)
         return List.of();

      final var result = new ArrayList<T>();
      for (final var future : futures) {
         if (future != null) {
            result.add(future.get());
         }
      }
      return result;
   }

   /**
    * Waits up to the specified timeout for all futures to complete and returns a list of results from all normally completed futures.
    * <p>
    * If at least one future was cancelled, failed, or did not complete within the specified time, this method will throw
    * the corresponding exception.
    *
    * @param futures the futures to wait for
    * @param timeout the maximum time to wait
    * @param unit the time unit of the timeout argument
    * @return a list of results from all completed futures
    *
    * @throws CancellationException if any future was cancelled
    * @throws ExecutionException if any future failed
    * @throws InterruptedException if the current thread was interrupted while waiting
    * @throws TimeoutException if the wait timed out before all futures completed
    */
   public static <T> List<T> getAll(final @Nullable Iterable<? extends @Nullable Future<? extends T>> futures, final long timeout,
         final TimeUnit unit) throws ExecutionException, InterruptedException, TimeoutException {
      if (futures == null)
         return List.of();

      final var result = new ArrayList<T>();
      final var timeoutMS = unit.toMillis(timeout);
      final var startAt = System.currentTimeMillis();
      for (final var future : futures) {
         if (future != null) {
            final var maxWaitMS = Math.max(0, timeoutMS - (System.currentTimeMillis() - startAt));
            result.add(future.get(maxWaitMS, TimeUnit.MILLISECONDS));
         }
      }
      return result;
   }

   /**
    * Returns a {@link Results} object containing the results from all futures that have already completed normally.
    * <p>
    * This method processes only futures that have completed by the time of invocation. Futures that were cancelled or completed
    * exceptionally are captured and recorded in the exceptions map of the {@link Results} object. Incomplete futures are ignored and will
    * not be present in the {@link Results} object.
    * </p>
    *
    * @param futures the futures to check
    * @return a {@link Results} object containing completed results and exceptions
    */
   @SafeVarargs
   @SuppressWarnings("null")
   public static <T> Results<T> getAllNow(final @NonNullByDefault({}) Future<? extends T> @Nullable... futures) {
      if (futures == null || futures.length == 0)
         return Results.empty();
      return getAllNow(Arrays.stream(futures));
   }

   /**
    * Returns a {@link Results} object containing the results from all futures that have already completed normally.
    * <p>
    * This method processes only futures that have completed by the time of invocation. Futures that were cancelled or completed
    * exceptionally are captured and recorded in the exceptions map of the {@link Results} object. Incomplete futures are ignored and will
    * not be present in the {@link Results} object.
    * </p>
    *
    * @param futures the futures to check
    * @return a {@link Results} object containing completed results and exceptions
    */
   public static <T> Results<T> getAllNow(final @Nullable Iterable<? extends @Nullable Future<? extends T>> futures) {
      if (futures == null)
         return Results.empty();

      if (futures instanceof final Collection<? extends @Nullable Future<? extends T>> coll) {
         if (coll.isEmpty())
            return Results.empty();
         return getAllNow(coll.stream());
      }

      return getAllNow(StreamSupport.stream(futures.spliterator(), false));
   }

   /**
    * Returns a {@link Results} object containing the results from all futures that have already completed normally.
    * <p>
    * This method processes only futures that have completed by the time of invocation. Futures that were cancelled or completed
    * exceptionally are captured and recorded in the exceptions map of the {@link Results} object. Incomplete futures are ignored and will
    * not be present in the {@link Results} object.
    * </p>
    *
    * @param futures the futures to check
    * @return a {@link Results} object containing completed results and exceptions
    */
   public static <T> Results<T> getAllNow(final @Nullable Stream<? extends @Nullable Future<? extends T>> futures) {
      if (futures == null)
         return Results.empty();

      final var results = new HashMap<Future<? extends T>, T>();
      final var exceptions = new HashMap<Future<? extends T>, Exception>();

      futures.forEach(future -> {
         if (future != null && future.isDone()) {
            try {
               final var result = future.get(0, TimeUnit.SECONDS);
               results.put(future, result);
            } catch (final Exception ex) {
               if (ex instanceof InterruptedException) {
                  Thread.interrupted();
               }
               LOG.log(Level.DEBUG, ex.getMessage(), ex);
               exceptions.put(future, ex);
            }
         }
      });
      return results.isEmpty() && exceptions.isEmpty() //
            ? Results.empty()
            : new Results<>(results, exceptions);
   }

   /**
    * Returns the result of the given {@link Future} if it is already completed, wrapped in an {@link Optional},
    * or an empty {@link Optional} if the future is incomplete, cancelled, or failed.
    *
    * @param future the future to get the result from
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
    * @return the result if completed normally, or the computed fallback value
    */
   public static <T> T getNowOrComputeFallback(final Future<T> future,
         final BiFunction<Future<T>, @Nullable Exception, T> fallbackComputer) {
      if (future.isDone())
         return getOrComputeFallback(future, fallbackComputer, 0, TimeUnit.SECONDS);
      return fallbackComputer.apply(future, null);
   }

   /**
    * Returns the result of the given {@link Future} if it is already completed, or the specified
    * {@code fallback} if the future is incomplete or failed.
    *
    * @param future the future to get the result from
    * @param fallback the fallback value to return if the future is incomplete or failed
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
    * @return an {@link Optional} containing the result of the future if it completes normally, or an empty {@link Optional} otherwise
    */
   public static <T> Optional<T> getOptional(final Future<T> future) {
      try {
         return Optional.ofNullable(future.get());
      } catch (final InterruptedException ex) {
         Thread.interrupted();
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
         Thread.interrupted();
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
    * @return the result if completed normally, or the computed fallback value
    */
   public static <T> T getOrComputeFallback(final Future<T> future, final BiFunction<Future<T>, @Nullable Exception, T> fallbackComputer) {
      try {
         return future.get();
      } catch (final InterruptedException ex) {
         Thread.interrupted();
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
         Thread.interrupted();
         LOG.log(Level.DEBUG, ex.getMessage(), ex);
         return fallbackComputer.apply(future, ex);
      } catch (final Exception ex) {
         LOG.log(Level.DEBUG, ex.getMessage(), ex);
         return fallbackComputer.apply(future, ex);
      }
   }

   /**
    * Waits up to the specified timeout for the given {@link Future} to complete and returns its result if it completes normally.
    * If the future is interrupted, timed out, cancelled, or failed, the provided fallback value is returned instead.
    *
    * @param future the future to get the result from
    * @param fallback the fallback value to return if the future completes with an exception
    * @param timeout the maximum time to wait
    * @param unit the time unit of the timeout argument
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
         Thread.interrupted();
         LOG.log(Level.DEBUG, ex.getMessage(), ex);
      } catch (final Exception ex) {
         LOG.log(Level.DEBUG, ex.getMessage(), ex);
      }
      return fallback;
   }

   /**
    * Waits for the given {@link Future} to complete and returns its result if it completes normally.
    * If the future is interrupted, cancelled, or failed, the provided fallback value is returned instead.
    *
    * @param future the future to get the result from
    * @param fallback the fallback value to return if the future completes with an exception
    * @return the result if completed normally, or the fallback value
    */
   public static <T> T getOrFallback(final Future<T> future, final T fallback) {
      try {
         return future.get();
      } catch (final InterruptedException ex) {
         Thread.interrupted();
         LOG.log(Level.DEBUG, ex.getMessage(), ex);
      } catch (final Exception ex) {
         LOG.log(Level.DEBUG, ex.getMessage(), ex);
      }
      return fallback;
   }

   /**
    * Waits for all futures to complete and returns a {@link Results} object containing results from normally completed futures
    * and exceptions from futures that were cancelled or failed.
    *
    * @param futures the futures to wait for
    * @return a {@link Results} object containing completed results and exceptions
    */
   @SafeVarargs
   @SuppressWarnings("null")
   public static <T> Results<T> joinAll(final @NonNullByDefault({}) Future<? extends T> @Nullable... futures) {
      if (futures == null || futures.length == 0)
         return Results.empty();

      return joinAll(Arrays.stream(futures));
   }

   /**
    * Waits up to the specified timeout for all futures to complete and returns a {@link Results} object containing results
    * from normally completed futures and exceptions from futures that were cancelled, interrupted, timed out, or failed.
    *
    * @param futures the futures to wait for
    * @param timeout the maximum time to wait
    * @param unit the time unit of the timeout argument
    * @return a {@link Results} object containing completed results and exceptions
    */
   public static <T> Results<T> joinAll(final @Nullable Future<? extends T> @Nullable [] futures, final long timeout, final TimeUnit unit) {
      if (futures == null || futures.length == 0)
         return Results.empty();

      return joinAll(Arrays.stream(futures), timeout, unit);
   }

   /**
    * Waits for all futures to complete and returns a {@link Results} object containing results from normally completed futures
    * and exceptions from futures that were cancelled or failed.
    *
    * @param futures the futures to wait for
    * @return a {@link Results} object containing completed results and exceptions
    */
   public static <T> Results<T> joinAll(final @Nullable Iterable<? extends @Nullable Future<? extends T>> futures) {
      if (futures == null)
         return Results.empty();

      if (futures instanceof final Collection<? extends @Nullable Future<? extends T>> coll) {
         if (coll.isEmpty())
            return Results.empty();
         return joinAll(coll.stream());
      }

      return joinAll(StreamSupport.stream(futures.spliterator(), false));
   }

   /**
    * Waits up to the specified timeout for all futures to complete and returns a {@link Results} object containing results
    * from normally completed futures and exceptions from futures that were cancelled, interrupted, timed out, or failed.
    *
    * @param futures the futures to wait for
    * @param timeout the maximum time to wait
    * @param unit the time unit of the timeout argument
    * @return a {@link Results} object containing completed results and exceptions
    */
   public static <T> Results<T> joinAll(final @Nullable Iterable<? extends @Nullable Future<? extends T>> futures, final long timeout,
         final TimeUnit unit) {
      if (futures == null)
         return Results.empty();

      if (futures instanceof final Collection<? extends @Nullable Future<? extends T>> coll) {
         if (coll.isEmpty())
            return Results.empty();
         return joinAll(coll.stream(), timeout, unit);
      }

      return joinAll(StreamSupport.stream(futures.spliterator(), false), timeout, unit);
   }

   /**
    * Waits for all futures to complete and returns a {@link Results} object containing results from normally completed futures
    * and exceptions from futures that were cancelled or failed.
    *
    * @param futures the futures to wait for
    * @return a {@link Results} object containing completed results and exceptions
    */
   public static <T> Results<T> joinAll(final @Nullable Stream<? extends @Nullable Future<? extends T>> futures) {
      if (futures == null)
         return Results.empty();

      final var results = new HashMap<Future<? extends T>, T>();
      final var exceptions = new HashMap<Future<? extends T>, Exception>();

      futures.forEach(future -> {
         if (future != null) {
            try {
               final var result = future.get();
               results.put(future, result);
            } catch (final Exception ex) {
               if (ex instanceof InterruptedException) {
                  Thread.interrupted();
               }
               LOG.log(Level.DEBUG, ex.getMessage(), ex);
               exceptions.put(future, ex);
            }
         }
      });
      return results.isEmpty() && exceptions.isEmpty() //
            ? Results.empty()
            : new Results<>(results, exceptions);
   }

   /**
    * Waits up to the specified timeout for all futures to complete and returns a {@link Results} object containing results
    * from normally completed futures and exceptions from futures that were cancelled, interrupted, timed out, or failed.
    *
    * @param futures the futures to wait for
    * @param timeout the maximum time to wait
    * @param unit the time unit of the timeout argument
    * @return a {@link Results} object containing completed results and exceptions
    */
   public static <T> Results<T> joinAll(final @Nullable Stream<? extends @Nullable Future<? extends T>> futures, final long timeout,
         final TimeUnit unit) {
      if (futures == null)
         return Results.empty();

      final var results = new HashMap<Future<? extends T>, T>();
      final var exceptions = new HashMap<Future<? extends T>, Exception>();

      final var timeoutMS = unit.toMillis(timeout);
      final var startAt = System.currentTimeMillis();

      futures.forEach(future -> {
         if (future != null) {
            try {
               final var maxWaitMS = Math.max(0, timeoutMS - (System.currentTimeMillis() - startAt));
               final var result = future.get(maxWaitMS, TimeUnit.MILLISECONDS);
               results.put(future, result);
            } catch (final Exception ex) {
               if (ex instanceof InterruptedException) {
                  Thread.interrupted();
               }
               LOG.log(Level.DEBUG, ex.getMessage(), ex);
               exceptions.put(future, ex);
            }
         }
      });

      return results.isEmpty() && exceptions.isEmpty() //
            ? Results.empty()
            : new Results<>(results, exceptions);
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
