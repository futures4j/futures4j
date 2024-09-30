/*
 * SPDX-FileCopyrightText: © Sebastian Thomschke
 * SPDX-License-Identifier: EPL-2.0
 * SPDX-FileContributor: Sebastian Thomschke: initial API and implementation
 */
package io.github.futures4j;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ForkJoinTask;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Enumerates the possible completion states of a {@link Future}, providing a unified way to determine
 * whether a task is still running, completed successfully, cancelled, or failed due to an exception.
 *
 * <p>
 * This utility supports various implementations of {@link Future}, including {@link CompletableFuture}
 * and {@link ForkJoinTask}, and offers a consistent method to assess their completion status.
 * </p>
 *
 * <p>
 * Use the {@link #of(Future)} method to determine the {@code CompletionState} of a given {@link Future}.
 * </p>
 *
 * @author <a href="https://sebthom.de/">Sebastian Thomschke</a>
 */
public enum CompletionState {

   /**
    * Indicates that the task hasn't started or is still in progress.
    */
   INCOMPLETE,

   /**
    * Indicates that the task completed successfully without throwing any exceptions.
    */
   SUCCESS,

   /**
    * Indicates that the task was cancelled before it completed.
    */
   CANCELLED,

   /**
    * Indicates that the task completed with an exception (excluding cancellation exceptions).
    */
   FAILED;

   private static final Logger LOG = System.getLogger(CompletionState.class.getName());

   /**
    * Determines the {@link CompletionState} of the given {@link Future} based on its current status.
    *
    * <p>
    * The method returns:
    * </p>
    * <ul>
    * <li>{@link #INCOMPLETE} if the future has not completed yet (i.e., is still running or pending).</li>
    * <li>{@link #SUCCESS} if the future completed successfully without exceptions.</li>
    * <li>{@link #CANCELLED} if the future was cancelled before completion.</li>
    * <li>{@link #FAILED} if the future completed exceptionally due to an error (other than cancellation).</li>
    * </ul>
    *
    * @param future the {@link Future} whose completion state is to be determined
    * @return the {@link CompletionState} representing the current state of the future
    */
   public static CompletionState of(final Future<?> future) {
      if (future.isDone()) {
         if (future.isCancelled())
            return CompletionState.CANCELLED;

         if (future instanceof final CompletableFuture<?> cf) {
            if (cf.isCompletedExceptionally())
               return CompletionState.FAILED;
            return CompletionState.SUCCESS;
         }

         if (future instanceof final ForkJoinTask<?> fjt) {
            if (fjt.isCompletedNormally())
               return CompletionState.SUCCESS;
            return CompletionState.FAILED;
         }

         try {
            future.get(0, TimeUnit.SECONDS);
            return CompletionState.SUCCESS;
         } catch (final CancellationException ex) {
            return CompletionState.CANCELLED;
         } catch (final InterruptedException ex) {
            LOG.log(Level.DEBUG, ex.getMessage(), ex);
            Thread.currentThread().interrupt();
            return CompletionState.FAILED;
         } catch (final Exception ex) {
            return CompletionState.FAILED;
         }
      }
      return CompletionState.INCOMPLETE;
   }
}
