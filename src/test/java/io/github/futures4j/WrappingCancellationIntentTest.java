/*
 * SPDX-FileCopyrightText: © Sebastian Thomschke
 * SPDX-License-Identifier: EPL-2.0
 */
package io.github.futures4j;

import static org.assertj.core.api.Assertions.*;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import io.github.futures4j.ExtendedFuture.ReadOnlyMode;

/**
 * Verifies that cancellation preserves caller interrupt intent across views and upstream stages,
 * while each stage retains control over interruption of its own task.
 *
 * @author futures4j contributors
 */
class WrappingCancellationIntentTest extends AbstractFutureTest {

   private enum CancellationLink {
      COMPLETE_WITH,
      FORWARD_ONE,
      FORWARD_ARRAY,
      FORWARD_COLLECTION;

      void connect(final ExtendedFuture<String> source, final ExtendedFuture<String> target) {
         switch (this) {
            case COMPLETE_WITH:
               target.completeWith(source);
               break;
            case FORWARD_ONE:
               source.forwardCancellation(target);
               break;
            case FORWARD_ARRAY:
               source.forwardCancellation(new Future<?>[] {target});
               break;
            case FORWARD_COLLECTION:
               source.forwardCancellationTo(List.of(target));
               break;
         }
      }
   }

   private static final class RecordingFuture extends ExtendedFuture<String> {
      private int cancellations;
      private boolean interruptRequested;

      @Override
      public boolean cancel(final boolean mayInterruptIfRunning) {
         cancellations++;
         interruptRequested = mayInterruptIfRunning;
         return super.cancel(mayInterruptIfRunning);
      }

      void assertCancelledWith(final boolean mayInterruptIfRunning) {
         assertThat(this).isCancelled();
         assertThat(cancellations).isOne();
         assertThat(interruptRequested).isEqualTo(mayInterruptIfRunning);
      }
   }

   /** Keeps a task running until interruption or explicit release, with bounded cleanup on assertion failure. */
   private static final class BlockingTask implements AutoCloseable {
      private final ExecutorService executor = Executors.newSingleThreadExecutor();
      private final CountDownLatch started = new CountDownLatch(1);
      private final CountDownLatch release = new CountDownLatch(1);
      private final CountDownLatch finished = new CountDownLatch(1);
      private final AtomicBoolean interrupted = new AtomicBoolean();
      private final ExtendedFuture<String> future = ExtendedFuture.supplyAsync(() -> {
         started.countDown();
         try {
            release.await();
         } catch (final InterruptedException ex) {
            interrupted.set(true);
         } finally {
            finished.countDown();
         }
         return "done";
      }, executor);

      void awaitStarted() throws InterruptedException {
         assertThat(started.await(MAX_WAIT_SECS, TimeUnit.SECONDS)).isTrue();
      }

      void assertInterruption(final boolean expected) throws InterruptedException {
         if (!expected) {
            assertThat(finished.getCount()).as("the task must remain blocked until released").isOne();
            release.countDown();
         }
         assertThat(finished.await(MAX_WAIT_SECS, TimeUnit.SECONDS)).as("the task must stop").isTrue();
         assertThat(interrupted.get()).isEqualTo(expected);
      }

      @Override
      public void close() throws InterruptedException {
         release.countDown();
         executor.shutdown();
         if (!executor.awaitTermination(MAX_WAIT_SECS, TimeUnit.SECONDS)) {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(MAX_WAIT_SECS, TimeUnit.SECONDS)).isTrue();
         }
      }
   }

   @ParameterizedTest
   @CsvSource({"false,false", "false,true", "true,false", "true,true"})
   void testExtendedSourceViewsPreserveIntent(final boolean throughView, final boolean mayInterruptIfRunning) {
      for (final var link : CancellationLink.values()) {
         final var source = ExtendedFuture.builder(String.class).build();
         final var view = source.asCancellableByDependents(true).withDefaultExecutor(Runnable::run);
         final var target = new RecordingFuture();
         link.connect(view, target);

         assertThat((throughView ? view : source).cancel(mayInterruptIfRunning)).isTrue();

         assertThat(view).isCancelled();
         target.assertCancelledWith(mayInterruptIfRunning);
      }
   }

   @ParameterizedTest
   @CsvSource({"false,false", "false,true", "true,false", "true,true"})
   void testLateObserversPreserveIntentAfterUpstreamCancellation(final boolean createViewAfterCancellation,
         final boolean mayInterruptIfRunning) {
      for (final var link : CancellationLink.values()) {
         final var parent = ExtendedFuture.builder(String.class).withCancellableByDependents(true).build();
         final var source = parent.thenApply(Function.identity());
         if (createViewAfterCancellation) {
            source.cancel(mayInterruptIfRunning);
         }
         final var view = source.withDefaultExecutor(Runnable::run);
         if (!createViewAfterCancellation) {
            source.cancel(mayInterruptIfRunning);
         }
         final var target = new RecordingFuture();
         link.connect(view, target);

         assertThat(parent).isCancelled();
         target.assertCancelledWith(mayInterruptIfRunning);
      }
   }

   @ParameterizedTest
   @CsvSource({"false,false", "false,true", "true,false", "true,true"})
   void testPlainSourceOnlyPreservesKnownCallerIntent(final boolean throughView, final boolean mayInterruptIfRunning) {
      for (final var link : CancellationLink.values()) {
         final var source = new CompletableFuture<String>();
         final var view = ExtendedFuture.from(source).withDefaultExecutor(Runnable::run);
         final var target = new RecordingFuture();
         link.connect(view, target);

         (throughView ? view : source).cancel(mayInterruptIfRunning);

         // Direct cancellation of an ordinary CompletableFuture does not expose the caller's interrupt flag.
         target.assertCancelledWith(throughView && mayInterruptIfRunning);
      }
   }

   @ParameterizedTest
   @ValueSource(booleans = {false, true})
   void testCompleteWithPreservesIntentThroughPlainDestination(final boolean mayInterruptIfRunning) {
      final var source = new ExtendedFuture<String>();
      final var destination = ExtendedFuture.from(new CompletableFuture<String>()).withDefaultExecutor(Runnable::run);
      final var forwarded = new RecordingFuture();
      destination.forwardCancellation(forwarded);
      destination.completeWith(source);

      source.cancel(mayInterruptIfRunning);

      assertThat(destination).isCancelled();
      forwarded.assertCancelledWith(mayInterruptIfRunning);
   }

   @ParameterizedTest
   @ValueSource(booleans = {false, true})
   void testCompleteWithFromViewInterruptsRunningTarget(final boolean mayInterruptIfRunning) throws InterruptedException {
      try (var task = new BlockingTask()) {
         task.awaitStarted();
         final var source = ExtendedFuture.builder(String.class).build().asCancellableByDependents(true).withDefaultExecutor(Runnable::run);
         task.future.completeWith(source);

         source.cancel(mayInterruptIfRunning);

         assertThat(task.future).isCancelled();
         task.assertInterruption(mayInterruptIfRunning);
      }
   }

   @ParameterizedTest
   @ValueSource(booleans = {false, true})
   void testNonInterruptibleMiddleStagesPreserveUpstreamIntent(final boolean mayInterruptIfRunning) throws InterruptedException {
      try (var task = new BlockingTask()) {
         task.awaitStarted();
         final var root = task.future.asCancellableByDependents(true);
         final var firstMiddle = root.thenApply(Function.identity()).asNonInterruptible();
         final var secondMiddle = firstMiddle.thenApply(Function.identity()).asNonInterruptible().withDefaultExecutor(Runnable::run);
         final var last = secondMiddle.thenApply(Function.identity());

         last.cancel(mayInterruptIfRunning);

         assertThat(firstMiddle).isCancelled();
         assertThat(secondMiddle).isCancelled();
         assertThat(task.future).isCancelled();
         task.assertInterruption(mayInterruptIfRunning);
      }
   }

   @Test
   void testNonInterruptiblePredecessorProtectsItsOwnRunningTask() throws InterruptedException {
      try (var task = new BlockingTask()) {
         task.awaitStarted();
         final var view = task.future.asCancellableByDependents(true).asNonInterruptible().withDefaultExecutor(Runnable::run);
         final var dependent = view.thenApply(Function.identity());

         dependent.cancel(true);

         assertThat(task.future).isCancelled();
         task.assertInterruption(false);
      }
   }

   @Test
   void testCancellationStopsAtOptedOutPredecessor() throws InterruptedException {
      try (var task = new BlockingTask()) {
         task.awaitStarted();
         final var dependent = task.future.thenApply(Function.identity());

         dependent.cancel(true);

         assertThat(task.future).isNotCancelled();
         task.assertInterruption(false);
      }
   }

   @ParameterizedTest
   @EnumSource(ReadOnlyMode.class)
   void testRejectedReadOnlyCancellationDoesNotOverrideLaterIntent(final ReadOnlyMode mode) {
      final var source = new ExtendedFuture<String>();
      final var readOnly = source.asReadOnly(mode);
      final var view = readOnly.withDefaultExecutor(Runnable::run);
      final var target = new RecordingFuture();
      view.forwardCancellation(target);

      if (mode == ReadOnlyMode.THROW_ON_MUTATION) {
         assertThatThrownBy(() -> view.cancel(true)).isInstanceOf(UnsupportedOperationException.class);
      } else {
         assertThat(view.cancel(true)).isFalse();
      }
      readOnly.thenApply(Function.identity()).cancel(true);
      assertThat(source).isNotCompleted();
      assertThat(target).isNotCompleted();

      source.cancel(false);

      target.assertCancelledWith(false);
   }

   @ParameterizedTest
   @ValueSource(booleans = {false, true})
   void testRepeatedCancellationBeforeViewNotificationDoesNotInterrupt(final boolean nestedView) throws InterruptedException {
      for (final var link : CancellationLink.values()) {
         try (var task = new BlockingTask()) {
            task.awaitStarted();
            final var backing = new CompletableFuture<String>();
            final var initialView = ExtendedFuture.from(backing);
            final var view = nestedView ? initialView.withDefaultExecutor(Runnable::run) : initialView;
            final var viewWasPending = new AtomicBoolean();
            final var repeatedCancelAccepted = new AtomicBoolean();
            link.connect(view, task.future);

            // The later-registered callback runs before the wrapper mirror in this reproduction.
            // Capture that ordering explicitly so the test cannot pass without exercising the notification gap.
            backing.whenComplete((result, error) -> {
               viewWasPending.set(!view.isDone());
               repeatedCancelAccepted.set(view.cancel(true));
            });
            backing.cancel(false);

            assertThat(viewWasPending.get()).as("the wrapper notification must still be pending during the repeated cancel").isTrue();
            assertThat(repeatedCancelAccepted.get()).isTrue();
            assertThat(view).isCancelled();
            assertThat(task.future).isCancelled();
            // A redundant request cannot turn an unobservable plain-future interrupt flag into a known true request.
            task.assertInterruption(false);
         }
      }
   }

   @ParameterizedTest
   @EnumSource(ReadOnlyMode.class)
   void testCompletedReadOnlyViewRetainsCancellationPolicy(final ReadOnlyMode mode) {
      for (final boolean cancelled : List.of(false, true)) {
         final var source = new ExtendedFuture<String>();
         final var view = source.asReadOnly(mode).withDefaultExecutor(Runnable::run);
         if (cancelled) {
            source.cancel(false);
         } else {
            source.complete("done");
         }

         // Completion must not turn a forbidden mutation into an allowed no-op through an outer view.
         if (mode == ReadOnlyMode.THROW_ON_MUTATION) {
            assertThatThrownBy(() -> view.cancel(true)).isInstanceOf(UnsupportedOperationException.class);
         } else {
            assertThat(view.cancel(true)).isEqualTo(cancelled);
         }
      }
   }

   @ParameterizedTest
   @ValueSource(booleans = {false, true})
   void testRepeatedCancellationDoesNotReplaceOriginalIntent(final boolean mayInterruptIfRunning) {
      final var parent = ExtendedFuture.builder(String.class).withCancellableByDependents(true).build();
      final var source = parent.thenApply(Function.identity());
      final var view = source.withDefaultExecutor(Runnable::run);
      view.cancel(mayInterruptIfRunning);
      view.cancel(!mayInterruptIfRunning);
      source.cancel(!mayInterruptIfRunning);
      final var target = new RecordingFuture();

      target.completeWith(view);

      target.assertCancelledWith(mayInterruptIfRunning);
   }
}
