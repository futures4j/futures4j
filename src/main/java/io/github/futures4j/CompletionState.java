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
 * Describes the completion state of a {@link Future}
 *
 * @author <a href="https://sebthom.de/">Sebastian Thomschke</a>
 */
public enum CompletionState {
   INCOMPLETE,

   /**
    * Completed normally/successfully.
    */
   COMPLETED,

   CANCELLED,

   FAILED;

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
