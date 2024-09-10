/*
 * SPDX-FileCopyrightText: © Sebastian Thomschke
 * SPDX-License-Identifier: EPL-2.0
 * SPDX-FileContributor: Sebastian Thomschke: initial API and implementation
 */
package io.github.futures4j;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;

/**
 * Describes the completion state of a {@link Future}
 *
 * @author <a href="https://sebthom.de/">Sebastian Thomschke</a>
 */
public enum CompletionState {
   INCOMPLETE,

   /**
    * Completed normally.
    */
   COMPLETED,

   /**
    * Only applicable to {@link CompletableFuture}
    */
   COMPLETED_EXCEPTIONALLY,

   CANCELLED;

   public static CompletionState of(final Future<?> future) {
      if (future.isCancelled())
         return CompletionState.CANCELLED;

      if (future instanceof final CompletableFuture<?> cf && cf.isCompletedExceptionally())
         return CompletionState.COMPLETED_EXCEPTIONALLY;

      if (future.isDone())
         return CompletionState.COMPLETED;

      return CompletionState.INCOMPLETE;
   }
}
