/*
 * SPDX-FileCopyrightText: © Sebastian Thomschke
 * SPDX-License-Identifier: EPL-2.0
 * SPDX-FileContributor: Sebastian Thomschke: initial API and implementation
 */
package io.github.futures4j;

import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ForkJoinTask;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Represents the possible completion states of a {@link Future}, offering a streamlined
 * method to check whether a task is still pending, has completed successfully, or has
 * terminated due to cancellation or other exceptional conditions.
 * <p>
 * This utility is compatible with different implementations of {@link Future}, such as {@link CompletableFuture}
 * and {@link ForkJoinTask}, and provides a consistent way to analyze their states.
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
    * Indicates that the task has completed successfully without any errors.
    */
   COMPLETED,

   /**
    * Indicates that the task was cancelled before completion.
    */
   CANCELLED,

   /**
    * Indicates that the task failed due to an exception, not related to cancellation.
    */
   FAILED;

   /**
    * Determines the {@link CompletionState} of the provided {@link Future} based on its current status.
    * <ul>
    * <li>If the future is cancelled, it returns {@link #CANCELLED}.</li>
    * <li>If the future completed exceptionally (non-cancellation), it returns {@link #FAILED}.</li>
    * <li>If the future completed successfully, it returns {@link #COMPLETED}.</li>
    * <li>If the future is still in progress or the task has not started, it returns {@link #INCOMPLETE}.</li>
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
            return CompletionState.COMPLETED;
         }

         if (future instanceof final ForkJoinTask<?> fjt) {
            if (fjt.isCompletedNormally())
               return CompletionState.COMPLETED;
            return CompletionState.FAILED;
         }

         try {
            future.get(0, TimeUnit.SECONDS);
            return CompletionState.COMPLETED;
         } catch (final CancellationException ex) {
            return CompletionState.CANCELLED;
         } catch (final Exception ex) {
            return CompletionState.FAILED;
         }
      }
      return CompletionState.INCOMPLETE;
   }
}
