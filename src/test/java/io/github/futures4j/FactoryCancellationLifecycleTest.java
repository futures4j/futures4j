/*
 * SPDX-FileCopyrightText: © Sebastian Thomschke
 * SPDX-License-Identifier: EPL-2.0
 */
package io.github.futures4j;

import static net.sf.jstuff.core.validation.NullAnalysisHelper.sneakyNull;
import static org.assertj.core.api.Assertions.*;

import java.lang.ref.Reference;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import org.eclipse.jdt.annotation.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import io.github.futures4j.util.ThrowingRunnable;

/**
 * Verifies bounded factory-link bookkeeping and cancellation/cleanup ownership independently of native callback execution.
 * Assertions inspect registrations before fixture cleanup; simulated reference enqueueing does not claim GC collectability.
 *
 * @author futures4j contributors
 */
class FactoryCancellationLifecycleTest extends AbstractFutureTest {

   enum IndependentCompletion {
      VALUE,
      EXCEPTION,
      OBTRUDED_VALUE,
      OBTRUDED_EXCEPTION,
      OBTRUDED_CANCELLATION,
      ASYNC_DEFAULT,
      ASYNC_EXPLICIT
   }

   /** Holds async completion until its cleanup observer has been installed. */
   private static final class QueuedExecutor implements Executor {
      private final ArrayDeque<Runnable> tasks = new ArrayDeque<>();

      @Override
      public void execute(final Runnable task) {
         tasks.add(task);
      }

      void runAll() {
         while (!tasks.isEmpty()) {
            tasks.remove().run();
         }
      }
   }

   /** Exposes the cancellation window after ownership is claimed but before CompletableFuture publishes the outcome. */
   private static final class CancellationCheckpointFuture extends ExtendedFuture<String> {
      private @Nullable Runnable checkpoint;

      CancellationCheckpointFuture(final ExtendedFuture<String> source) {
         super(false, false, Runnable::run);
         // This protected link isolates cancellation coordination; ordinary factory registration is tested separately below.
         cancellablePrecedingStages.add(source);
         ExtendedFuture.from(this);
      }

      @Override
      public boolean isInterruptible() {
         Objects.requireNonNull(checkpoint).run();
         return false;
      }
   }

   /** Pauses late registration after insertion, before its terminal recheck can discard the new link. */
   private static final class RegistrationCheckpointFuture extends ExtendedFuture<String> {
      private @Nullable Thread registeringThread;
      private int checks;
      private final CountDownLatch inserted = new CountDownLatch(1);
      private final CountDownLatch release = new CountDownLatch(1);

      RegistrationCheckpointFuture() {
         super(false, false, Runnable::run);
      }

      @Override
      public boolean isDone() {
         if (Thread.currentThread() != registeringThread)
            return super.isDone();
         if (++checks == 2) {
            inserted.countDown();
            try {
               assertThat(release.await(MAX_WAIT_SECS, TimeUnit.SECONDS)).isTrue();
            } catch (final InterruptedException ex) {
               Thread.currentThread().interrupt();
               throw new AssertionError(ex);
            }
         }
         return super.isDone();
      }
   }

   /** Distinct equal sources ensure cleanup uses identity rather than subclass equality. */
   private static final class EqualFuture extends ExtendedFuture<String> {
      EqualFuture() {
         super(true, false, Runnable::run);
      }

      @Override
      public boolean equals(final @Nullable Object other) {
         return other instanceof EqualFuture;
      }

      @Override
      public int hashCode() {
         return 1;
      }
   }

   private static @Nullable Object readField(final Class<?> type, final Object instance, final String name)
         throws ReflectiveOperationException {
      final var field = type.getDeclaredField(name);
      field.setAccessible(true);
      return field.get(instance);
   }

   private static @Nullable Object futureField(final ExtendedFuture<?> future, final String name) throws ReflectiveOperationException {
      return readField(ExtendedFuture.class, future, name);
   }

   private static List<Reference<?>> registrations(final Object registry) throws ReflectiveOperationException {
      final var entries = new ArrayList<Reference<?>>();
      synchronized (registry) {
         var entry = readField(registry.getClass(), registry, "first");
         while (entry != null) {
            assertThat(entries).doesNotContain((Reference<?>) entry);
            entries.add((Reference<?>) entry);
            entry = readField(entry.getClass(), entry, "next");
         }
      }
      return entries;
   }

   @ParameterizedTest
   @EnumSource(IndependentCompletion.class)
   void testIndependentCompletionDeregistersPromptly(final IndependentCompletion completion) throws Exception {
      for (final boolean interruptibleStages : List.of(false, true)) {
         final var executor = new QueuedExecutor();
         final var source = new ExtendedFuture<String>(true, interruptibleStages, executor);
         for (int index = 0; index < 200; index++) {
            // A stage method would add native callbacks to the source. A bare factory call isolates our own subscriptions.
            final var result = source.<String>newIncompleteFuture();
            final var registry = Objects.requireNonNull(futureField(source, "factoryDependents"));
            assertThat(registrations(registry)).hasSize(1);
            assertThat(result.cancellablePrecedingStages).containsExactly(source);
            switch (completion) {
               case VALUE:
                  assertThat(result.complete("done")).isTrue();
                  break;
               case EXCEPTION:
                  assertThat(result.completeExceptionally(new IllegalStateException("done"))).isTrue();
                  break;
               case OBTRUDED_VALUE:
                  result.obtrudeValue(sneakyNull());
                  break;
               case OBTRUDED_EXCEPTION:
                  result.obtrudeException(new IllegalStateException("done"));
                  break;
               case OBTRUDED_CANCELLATION:
                  result.obtrudeException(new CancellationException("outcome, not a cancel request"));
                  break;
               case ASYNC_DEFAULT:
               case ASYNC_EXPLICIT:
                  final Supplier<String> supplier = () -> "done";
                  if (completion == IndependentCompletion.ASYNC_DEFAULT) {
                     result.completeAsync(supplier);
                  } else {
                     result.completeAsync(supplier, executor);
                  }
                  assertThat(result.getNumberOfDependents()).isOne();
                  executor.runAll();
                  break;
               default:
                  throw new AssertionError(completion);
            }
            // Assert before completing the source or starting the next iteration; neither GC nor a later purge may hide retention.
            assertThat(result).isDone();
            assertThat(result.cancellablePrecedingStages).isEmpty();
            assertThat(futureField(result, "factoryPredecessor")).isNull();
            assertThat(registrations(registry)).isEmpty();
            assertThat(source).isNotCompleted();
            assertThat(source.getNumberOfDependents()).isOne();
         }
         source.complete("cleanup");
         assertThat(futureField(source, "factoryDependents")).isNull();
      }
   }

   @Test
   void testAbandonedRegistrationsArePurgedWithoutRemovingLiveEntries() throws Exception {
      final var source = new ExtendedFuture<String>(true, false, Runnable::run);
      final var live = source.<String>newIncompleteFuture();
      final var stale = source.<String>newIncompleteFuture();
      final var newerLive = source.<String>newIncompleteFuture();
      final var registry = Objects.requireNonNull(futureField(source, "factoryDependents"));
      final var staleReference = (Reference<?>) Objects.requireNonNull(futureField(stale, "factoryPredecessor"));
      try {
         staleReference.clear();
         assertThat(staleReference.enqueue()).isTrue();
         final var next = source.<String>newIncompleteFuture();
         // Reclaim from the middle, not only the head; both neighbors must remain registered.
         assertThat(registrations(registry)).containsExactlyInAnyOrder((Reference<?>) Objects.requireNonNull(futureField(live,
            "factoryPredecessor")), (Reference<?>) Objects.requireNonNull(futureField(newerLive, "factoryPredecessor")),
            (Reference<?>) Objects.requireNonNull(futureField(next, "factoryPredecessor")));
         newerLive.complete("done");
         assertThat(futureField(newerLive, "factoryPredecessor")).isNull();
         assertThat(registrations(registry)).containsExactlyInAnyOrder((Reference<?>) Objects.requireNonNull(futureField(live,
            "factoryPredecessor")), (Reference<?>) Objects.requireNonNull(futureField(next, "factoryPredecessor")));
         assertThat(source.getNumberOfDependents()).isOne();
         live.cancel(false);
         assertThat(source).isCancelled();
         assertThat(next.cancellablePrecedingStages).isEmpty();
         assertThat(registrations(registry)).isEmpty();
      } finally {
         // Manual enqueueing tests queue processing only. Keep its referent alive so real GC cannot enqueue it first.
         stale.complete("cleanup");
         source.complete("cleanup");
         Reference.reachabilityFence(stale);
      }
   }

   @ParameterizedTest
   @ValueSource(booleans = {false, true})
   void testOptedOutFactoriesAllocateNoRegistrations(final boolean interruptibleStages) throws Exception {
      final var source = new ExtendedFuture<String>(false, interruptibleStages, Runnable::run);
      for (int index = 0; index < 200; index++) {
         final var result = source.newIncompleteFuture();
         assertThat(result.cancellablePrecedingStages).isEmpty();
         assertThat(futureField(result, "factoryPredecessor")).isNull();
      }
      assertThat(futureField(source, "factoryDependents")).isNull();
      assertThat(source.getNumberOfDependents()).isZero();
   }

   @Test
   void testSourceCleanupKeepsAnEqualUnfinishedInput() {
      final var source = new EqualFuture();
      final var other = new EqualFuture();
      final var result = source.thenCombine(other, (left, right) -> left + right);
      source.complete("source");
      // containsExactly alone would accept the wrong input because these distinct futures compare equal.
      assertThat(result.cancellablePrecedingStages).singleElement().isSameAs(other);
      result.cancel(false);
      assertThat(other).isCancelled();
   }

   @Test
   void testFactoryRegistrationRacingSourceCompletion() throws Exception {
      final var executor = Executors.newFixedThreadPool(2);
      try {
         for (int index = 0; index < 200; index++) {
            final var source = new ExtendedFuture<String>(true, false, Runnable::run);
            final var existing = source.newIncompleteFuture();
            final var registry = Objects.requireNonNull(futureField(source, "factoryDependents"));
            assertThat(registrations(registry)).hasSize(1);
            final var start = new CyclicBarrier(3);
            final var creation = executor.submit(() -> {
               start.await(MAX_WAIT_SECS, TimeUnit.SECONDS);
               return (ExtendedFuture<?>) CompletableFuture.anyOf(source);
            });
            final var completion = executor.submit(() -> {
               start.await(MAX_WAIT_SECS, TimeUnit.SECONDS);
               // NOTE: RedundantReturn mistakes this Callable's required result for a return from the void test.
               return source.complete("source"); // CHECKSTYLE:IGNORE RedundantReturn
            });
            start.await(MAX_WAIT_SECS, TimeUnit.SECONDS);
            final var result = Objects.requireNonNull(creation.get(MAX_WAIT_SECS, TimeUnit.SECONDS));
            assertThat(completion.get(MAX_WAIT_SECS, TimeUnit.SECONDS)).isTrue();
            assertThat(result.join()).isEqualTo("source");
            assertThat(result.cancellablePrecedingStages).isEmpty();
            assertThat(futureField(result, "factoryPredecessor")).isNull();
            assertThat(registrations(registry)).isEmpty();
            // Closing a source's registry removes links, but must not complete unrelated bare factory results.
            assertThat(existing).isNotCompleted();
            assertThat(existing.cancellablePrecedingStages).isEmpty();
            assertThat(futureField(source, "factoryDependents")).isNull();
         }
      } finally {
         executor.shutdownNow();
         assertThat(executor.awaitTermination(MAX_WAIT_SECS, TimeUnit.SECONDS)).isTrue();
      }
   }

   @Test
   void testLosingCancellationFinishesDeferredCleanup() {
      final var source = new ExtendedFuture<String>(true, false, Runnable::run);
      final var result = new CancellationCheckpointFuture(source);
      result.checkpoint = () -> result.complete("winner");
      assertThat(result.cancel(true)).isFalse();
      assertThat(result).isCompletedWithValue("winner");
      assertThat(result.cancellablePrecedingStages).isEmpty();
      assertThat(source).isNotCompleted();
   }

   @Test
   void testReentrantCancellationPreservesForwarding() {
      final var source = new ExtendedFuture<String>(true, false, Runnable::run);
      final var result = new CancellationCheckpointFuture(source);
      final var nestedCancellation = new AtomicBoolean();
      result.checkpoint = () -> nestedCancellation.set(result.cancel(false));
      result.whenComplete((value, error) -> result.obtrudeValue("forced"));
      // The inner call wins and obtrudes its outcome; the outer call then loses without undoing the accepted request.
      assertThat(result.cancel(true)).isFalse();
      assertThat(nestedCancellation).isTrue();
      assertThat(source).isCancelled();
      assertThat(result.cancellablePrecedingStages).isEmpty();
   }

   @Test
   void testLosingConcurrentCancellationCannotReleaseTheWinnersLinks() throws Exception {
      final var source = new ExtendedFuture<String>(true, false, Runnable::run);
      final var result = new CancellationCheckpointFuture(source);
      final var entered = new CountDownLatch(1);
      final var release = new CountDownLatch(1);
      final var calls = new AtomicInteger();
      final var loserFinished = new AtomicBoolean();
      result.checkpoint = () -> {
         if (calls.getAndIncrement() == 0) {
            entered.countDown();
            try {
               assertThat(release.await(MAX_WAIT_SECS, TimeUnit.SECONDS)).isTrue();
            } catch (final InterruptedException ex) {
               Thread.currentThread().interrupt();
               throw new AssertionError(ex);
            }
         }
      };
      final var executor = Executors.newSingleThreadExecutor();
      try {
         final var losing = executor.submit(() -> result.cancel(true));
         assertThat(entered.await(MAX_WAIT_SECS, TimeUnit.SECONDS)).isTrue();
         result.whenComplete((value, error) -> {
            result.obtrudeValue("forced");
            release.countDown();
            // Keep the winning call inside its observer until the losing call's finally block has finished.
            loserFinished.set(!losing.get(MAX_WAIT_SECS, TimeUnit.SECONDS));
         });
         assertThat(result.cancel(true)).isTrue();
         assertThat(loserFinished).isTrue();
         assertThat(source).isCancelled();
         assertThat(result.cancellablePrecedingStages).isEmpty();
      } finally {
         release.countDown();
         executor.shutdownNow();
         assertThat(executor.awaitTermination(MAX_WAIT_SECS, TimeUnit.SECONDS)).isTrue();
      }
   }

   @Test
   void testLateRegistrationCannotDiscardAnAcceptedCancellation() throws Exception {
      final var result = new RegistrationCheckpointFuture();
      final var nested = new ExtendedFuture<String>(true, false, Runnable::run);
      final var register = ExtendedFuture.class.getDeclaredMethod("registerCancellablePrecedingStage", ExtendedFuture.class,
         CompletionStage.class);
      register.setAccessible(true);
      final var executor = Executors.newSingleThreadExecutor();
      final var registered = new AtomicBoolean();
      try {
         // Exercise the shared binary/composition handoff directly so the insertion/recheck race is deterministic.
         final ThrowingRunnable<?> registration = () -> {
            result.registeringThread = Thread.currentThread();
            register.invoke(null, result, nested);
         };
         final var handoff = executor.submit(registration);
         assertThat(result.inserted.await(MAX_WAIT_SECS, TimeUnit.SECONDS)).isTrue();
         result.whenComplete((value, error) -> {
            result.obtrudeValue("forced");
            result.release.countDown();
            handoff.get(MAX_WAIT_SECS, TimeUnit.SECONDS);
            registered.set(true);
         });
         assertThat(result.cancel(false)).isTrue();
         assertThat(registered).isTrue();
         assertThat(nested).isCancelled();
         assertThat(result.cancellablePrecedingStages).isEmpty();
      } finally {
         result.release.countDown();
         executor.shutdownNow();
         assertThat(executor.awaitTermination(MAX_WAIT_SECS, TimeUnit.SECONDS)).isTrue();
      }
   }

   @ParameterizedTest
   @ValueSource(booleans = {false, true})
   void testLateComposedInputUsesTheRequestNotTheObtrudedOutcome(final boolean acceptedCancellation) throws Exception {
      final var executor = Executors.newSingleThreadExecutor();
      final var entered = new CountDownLatch(1);
      final var release = new CountDownLatch(1);
      try {
         final var source = new ExtendedFuture<String>(false, false, executor);
         final var nested = new ExtendedFuture<String>(true, false, executor);
         final var result = source.thenCompose(value -> {
            entered.countDown();
            assertThat(release.await(MAX_WAIT_SECS, TimeUnit.SECONDS)).isTrue();
            return nested;
         });
         executor.execute(() -> source.complete("source"));
         assertThat(entered.await(MAX_WAIT_SECS, TimeUnit.SECONDS)).isTrue();
         if (acceptedCancellation) {
            assertThat(result.cancel(false)).isTrue();
            result.obtrudeValue("forced");
         } else {
            result.obtrudeException(new CancellationException("outcome only"));
         }
         release.countDown();
         // A worker barrier waits for the handoff, not just for the mapper body to return its nested future.
         executor.submit(() -> true).get(MAX_WAIT_SECS, TimeUnit.SECONDS);
         assertThat(nested.isCancelled()).isEqualTo(acceptedCancellation);
         assertThat(result.isCancelled()).isEqualTo(!acceptedCancellation);
         assertThat(result.cancellablePrecedingStages).isEmpty();
      } finally {
         release.countDown();
         executor.shutdownNow();
         assertThat(executor.awaitTermination(MAX_WAIT_SECS, TimeUnit.SECONDS)).isTrue();
      }
   }
}
