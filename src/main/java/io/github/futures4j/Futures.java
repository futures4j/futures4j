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
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BiFunction;
import java.util.stream.Stream;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;

/**
 * @author <a href="https://sebthom.de/">Sebastian Thomschke</a>
 */
public abstract class Futures {

   public interface Combiner<T> {
      /**
       * Enables forwarding of cancellation of the combined future to it's underlying futures.
       */
      Combiner<T> forwardCancellation();

      ExtendedFuture<List<T>> toList();

      ExtendedFuture<Set<T>> toSet();

      ExtendedFuture<Stream<T>> toStream();
   }

   private static final Logger LOG = System.getLogger(Futures.class.getName());

   /**
    * @return true if the future is now cancelled
    */
   public static boolean cancel(final @Nullable Future<?> futureToCancel) {
      return cancel(futureToCancel, false);
   }

   /**
    * @return true if the future is now cancelled
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
    * @return the number of futures that are now cancelled
    */
   public static int cancelAll(final @Nullable Collection<? extends @Nullable Future<?>> futuresToCancel) {
      return cancelAll(futuresToCancel, false);
   }

   /**
    * @return the number of futures that are now cancelled
    */
   public static int cancelAll(final @Nullable Collection<? extends @Nullable Future<?>> futuresToCancel,
         final boolean mayInterruptIfRunning) {
      if (futuresToCancel == null || futuresToCancel.isEmpty())
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
    * @return the number of futures that are now cancelled
    */
   public static int cancelAll(final @NonNullByDefault({}) Future<?> @Nullable... futuresToCancel) {
      return cancelAll(futuresToCancel, false);
   }

   /**
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
    * @return the number of futures that are now cancelled
    */
   public static int cancelAllInterruptibly(final @Nullable Collection<? extends @Nullable Future<?>> futuresToCancel) {
      return cancelAll(futuresToCancel, true);
   }

   /**
    * @return the number of futures that are now cancelled
    */
   public static int cancelAllInterruptibly(final @NonNullByDefault({}) Future<?> @Nullable... futuresToCancel) {
      return cancelAll(futuresToCancel, false);
   }

   /**
    * @return true if the future is now cancelled
    */
   public static boolean cancelInterruptibly(final @Nullable Future<?> futureToCancel) {
      return cancel(futureToCancel, true);
   }

   public static <T> Combiner<T> combine(@Nullable final Collection<? extends @Nullable CompletableFuture<? extends T>> futures) {
      if (futures == null || futures.isEmpty())
         return combineInternal(Collections.emptyList());
      return combineInternal(new ArrayList<>(futures));
   }

   @SafeVarargs
   @SuppressWarnings("null")
   public static <T> Combiner<T> combine(final @NonNullByDefault({}) CompletableFuture<? extends T> @Nullable... futures) {
      if (futures == null || futures.length == 0)
         return combineInternal(Collections.emptyList());
      return combineInternal(Arrays.asList(futures));
   }

   public static <T> Combiner<T> combineFlattened(
         final @Nullable Collection<? extends @Nullable CompletableFuture<? extends @Nullable Collection<T>>> futures) {
      if (futures == null || futures.isEmpty())
         return combineInternal(Collections.emptyList());
      return combineFlattenedInternal(new ArrayList<>(futures));
   }

   @SafeVarargs
   @SuppressWarnings("null")
   public static <T> Combiner<T> combineFlattened(
         final @NonNullByDefault({}) CompletableFuture<? extends @Nullable Collection<T>> @Nullable... futures) {
      if (futures == null || futures.length == 0)
         return combineFlattenedInternal(Collections.emptyList());
      return combineFlattenedInternal(Arrays.asList(futures));
   }

   private static <T> Combiner<T> combineFlattenedInternal(
         final Collection<@Nullable CompletableFuture<? extends @Nullable Collection<T>>> futures) {
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

            ExtendedFuture<List<T>> combinedFuture = ExtendedFuture.completedFuture(new ArrayList<>());

            for (final var future : futures) {
               if (future != null) {
                  combinedFuture = combinedFuture.thenCombine(future, (combined, result) -> {
                     if (result != null) {
                        combined.addAll(result);
                     }
                     return combined;
                  });
               }
            }
            if (forwardCancellation) {
               Futures.forwardCancellation(combinedFuture, futures);
            }
            return combinedFuture;
         }

         @Override
         public ExtendedFuture<Set<T>> toSet() {
            if (futures.isEmpty())
               return ExtendedFuture.completedFuture(Set.of());

            ExtendedFuture<Set<T>> combinedFuture = ExtendedFuture.completedFuture(new HashSet<>());

            for (final var future : futures) {
               if (future != null) {
                  combinedFuture = combinedFuture.thenCombine(future, (combined, result) -> {
                     if (result != null) {
                        combined.addAll(result);
                     }
                     return combined;
                  });
               }
            }
            if (forwardCancellation) {
               Futures.forwardCancellation(combinedFuture, futures);
            }
            return combinedFuture;
         }

         @Override
         public ExtendedFuture<Stream<T>> toStream() {
            if (futures.isEmpty())
               return ExtendedFuture.completedFuture(Stream.of());

            ExtendedFuture<Stream<T>> combinedFuture = ExtendedFuture.completedFuture(Stream.of());

            for (final var future : futures) {
               if (future != null) {
                  combinedFuture = combinedFuture.thenCombine(future, (combined, result) -> result == null ? combined
                        : Stream.concat(combined, result.stream()));
               }
            }
            if (forwardCancellation) {
               Futures.forwardCancellation(combinedFuture, futures);
            }
            return combinedFuture;
         }
      };
   }

   private static <T> Combiner<T> combineInternal(final Collection<@Nullable CompletableFuture<? extends T>> futures) {
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

            ExtendedFuture<List<T>> combinedFuture = ExtendedFuture.completedFuture(new ArrayList<>());

            for (final var future : futures) {
               if (future != null) {
                  combinedFuture = combinedFuture.thenCombine(future, (combined, result) -> {
                     combined.add(result);
                     return combined;
                  });
               }
            }
            if (forwardCancellation) {
               Futures.forwardCancellation(combinedFuture, futures);
            }
            return combinedFuture;
         }

         @Override
         public ExtendedFuture<Set<T>> toSet() {
            if (futures.isEmpty())
               return ExtendedFuture.completedFuture(Set.of());

            ExtendedFuture<Set<T>> combinedFuture = ExtendedFuture.completedFuture(new HashSet<>());

            for (final var future : futures) {
               if (future != null) {
                  combinedFuture = combinedFuture.thenCombine(future, (combined, result) -> {
                     combined.add(result);
                     return combined;
                  });
               }
            }
            if (forwardCancellation) {
               Futures.forwardCancellation(combinedFuture, futures);
            }
            return combinedFuture;
         }

         @Override
         public ExtendedFuture<Stream<T>> toStream() {
            return toList().thenApply(List::stream);
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
    * Propagates the cancellation of a {@link CompletableFuture} to other {@link Future}.
    * <p>
    * If the specified {@code from} future is cancelled, all futures in the provided {@code to} array will be cancelled too.
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
    * Returns a list of results from all normally completed futures.
    * <p>
    * Futures that are incomplete, canceled, or completed exceptionally are ignored.
    *
    * @return a list of results from all normally completed futures; an empty list if no futures are completed
    */
   public static <T> List<T> getAllNow(final @Nullable Collection<? extends @Nullable Future<? extends T>> futures) {
      if (futures == null || futures.isEmpty())
         return Collections.emptyList();
      final List<T> result = new ArrayList<>();
      for (final var future : futures) {
         if (future != null && future.isDone()) {
            try {
               result.add(future.get(0, TimeUnit.SECONDS));
            } catch (final InterruptedException ex) {
               Thread.interrupted();
               LOG.log(Level.DEBUG, ex.getMessage(), ex);
            } catch (final Exception ex) {
               LOG.log(Level.DEBUG, ex.getMessage(), ex);
            }
         }
      }
      return result;
   }

   /**
    * Returns a list of results from all normally completed futures.
    * <p>
    * Futures that are incomplete, canceled, or completed exceptionally are ignored.
    *
    * @return a list of results from all normally completed futures; an empty list if no futures are completed
    */
   @SafeVarargs
   public static <T> List<T> getAllNow(final @NonNullByDefault({}) Future<? extends T> @Nullable... futures) {
      if (futures == null || futures.length == 0)
         return Collections.emptyList();
      final List<T> result = new ArrayList<>();
      for (final var future : futures) {
         if (future != null && future.isDone()) {
            try {
               result.add(future.get(0, TimeUnit.SECONDS));
            } catch (final InterruptedException ex) {
               Thread.interrupted();
               LOG.log(Level.DEBUG, ex.getMessage(), ex);
            } catch (final Exception ex) {
               LOG.log(Level.DEBUG, ex.getMessage(), ex);
            }
         }
      }
      return result;
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
    */
   public static <T> List<T> getAllNowOrThrow(final @Nullable Collection<? extends @Nullable Future<? extends T>> futures)
         throws ExecutionException {
      if (futures == null || futures.isEmpty())
         return Collections.emptyList();
      final List<T> result = new ArrayList<>();
      for (final var future : futures) {
         if (future != null && future.isDone()) {
            try {
               result.add(future.get());
            } catch (final InterruptedException ex) {
               Thread.interrupted();
               LOG.log(Level.DEBUG, ex.getMessage(), ex);
            } catch (final CancellationException | ExecutionException ex) {
               throw ex;
            }
         }
      }
      return result;
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
    */
   @SafeVarargs
   public static <T> List<T> getAllNowOrThrow(final @NonNullByDefault({}) Future<? extends T> @Nullable... futures)
         throws ExecutionException {
      if (futures == null || futures.length == 0)
         return Collections.emptyList();
      final List<T> result = new ArrayList<>();
      for (final var future : futures) {
         if (future != null && future.isDone()) {
            try {
               result.add(future.get());
            } catch (final InterruptedException ex) {
               Thread.interrupted();
               LOG.log(Level.DEBUG, ex.getMessage(), ex);
            } catch (final CancellationException | ExecutionException ex) {
               throw ex;
            }
         }
      }
      return result;
   }

   /**
    * Returns the result of the given {@link Future} if it is already completed wrapped in an {@link Optional},
    * or an empty {@link Optional} if the future is incomplete, cancelled or completed exceptionally.
    *
    * @return an {@link Optional} containing the result of the future if completed normally, or an empty {@link Optional} otherwise
    */
   public static <T> Optional<T> getNowOptional(final Future<T> future) {
      if (future.isDone())
         return getOptional(future, 0, TimeUnit.SECONDS);
      return Optional.empty();
   }

   /**
    * Returns the result of the given {@link Future} if it is already completed, or the value computed by
    * {@code fallbackComputer} if the future is incomplete or completed exceptionally.
    *
    * @return the result of the future if completed, otherwise the value computed by {@code fallbackComputer}
    */
   public static <T> T getNowOrComputeFallback(final Future<T> future,
         final BiFunction<Future<T>, @Nullable Exception, T> fallbackComputer) {
      if (future.isDone())
         return getOrComputeFallback(future, 0, TimeUnit.SECONDS, fallbackComputer);
      return fallbackComputer.apply(future, null);
   }

   /**
    * Returns the result of the given {@link Future} if it is already completed, or the specified
    * {@code fallback} if the future is incomplete, cancelled or completed exceptionally.
    *
    * @return the result of the future if completed normally, otherwise {@code fallback}
    */
   public static <T> T getNowOrFallback(final CompletableFuture<T> future, final T fallback) {
      if (future.isDone()) {
         try {
            return future.getNow(fallback);
         } catch (final Exception ex) {
            LOG.log(Level.DEBUG, ex.getMessage(), ex);
         }
      }
      return fallback;
   }

   /**
    * Returns the result of the given {@link Future} if it is already completed, or the specified
    * {@code fallback} if the future is incomplete or completed exceptionally.
    *
    * @return the result of the future if completed, otherwise {@code fallback}
    */
   public static <T> T getNowOrFallback(final Future<T> future, final T fallback) {
      if (future.isDone())
         return getOrFallback(future, 0, TimeUnit.SECONDS, fallback);
      return fallback;
   }

   /**
    * Attempts to retrieve the result of the given {@link Future} within the specified timeout.
    *
    * @return an {@link Optional} containing the result of the future if completed normally within the timeout,
    *         or an empty {@link Optional} otherwise
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
    * Attempts to retrieve the result of the given {@link Future} within the specified timeout.
    *
    * @return the result of the future if completed normally, otherwise the value computed by {@code fallbackComputer}
    */
   public static <T> T getOrComputeFallback(final Future<T> future, final long timeout, final TimeUnit unit,
         final BiFunction<Future<T>, @Nullable Exception, T> fallbackComputer) {
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
    * Attempts to retrieve the result of the given {@link Future} within the specified timeout.
    *
    * @return the result of the future if completed normally, otherwise {@code fallback}
    */
   public static <T> T getOrFallback(final Future<T> future, final long timeout, final TimeUnit unit, final T fallback) {
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
}
