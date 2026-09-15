/*
 * SPDX-FileCopyrightText: © Sebastian Thomschke
 * SPDX-License-Identifier: EPL-2.0
 */
package io.github.futures4j;

import static net.sf.jstuff.core.validation.NullAnalysisHelper.sneakyNull;
import static org.assertj.core.api.Assertions.*;

import java.io.IOException;
import java.lang.ref.Reference;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.eclipse.jdt.annotation.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import io.github.futures4j.ExtendedFuture.ExecutionBinding;
import io.github.futures4j.util.ThrowingFunction;
import io.github.futures4j.util.ThrowingSupplier;

/**
 * Verifies interruption and execution lifetime for completion suppliers and stage callbacks,
 * including overlapping computations, safe reuse of their executor threads, cleanup when callbacks are skipped,
 * and shared synchronous/asynchronous recovery ownership and stack-safe propagation.
 *
 * @author futures4j contributors
 */
class TaskExecutionTrackingTest extends AbstractFutureTest {

   enum RecoveryExecution {
      SYNC,
      DEFAULT,
      EXPLICIT
   }

   /** Keeps all twelve recovery overloads explicit; bare lambdas would select only the throwing overloads. */
   enum RecoveryEntryPoint {
      VALUE_SYNC(false, RecoveryExecution.SYNC, false),
      VALUE_SYNC_THROWING(false, RecoveryExecution.SYNC, true),
      VALUE_DEFAULT(false, RecoveryExecution.DEFAULT, false),
      VALUE_EXPLICIT(false, RecoveryExecution.EXPLICIT, false),
      VALUE_DEFAULT_THROWING(false, RecoveryExecution.DEFAULT, true),
      VALUE_EXPLICIT_THROWING(false, RecoveryExecution.EXPLICIT, true),
      COMPOSE_SYNC(true, RecoveryExecution.SYNC, false),
      COMPOSE_SYNC_THROWING(true, RecoveryExecution.SYNC, true),
      COMPOSE_DEFAULT(true, RecoveryExecution.DEFAULT, false),
      COMPOSE_EXPLICIT(true, RecoveryExecution.EXPLICIT, false),
      COMPOSE_DEFAULT_THROWING(true, RecoveryExecution.DEFAULT, true),
      COMPOSE_EXPLICIT_THROWING(true, RecoveryExecution.EXPLICIT, true);

      final boolean compose;
      final RecoveryExecution execution;
      final boolean throwingFunction;

      RecoveryEntryPoint(final boolean compose, final RecoveryExecution execution, final boolean throwingFunction) {
         this.compose = compose;
         this.execution = execution;
         this.throwingFunction = throwingFunction;
      }

      ExtendedFuture<String> recover(final ExtendedFuture<String> source, final ThrowingFunction<Throwable, String, ?> handler,
            final Executor executor) {
         if (compose)
            return compose(source, error -> CompletableFuture.completedFuture(handler.applyOrThrow(error)), executor);
         final Function<Throwable, String> function = handler;
         if (execution == RecoveryExecution.SYNC)
            return throwingFunction ? source.exceptionally(handler) : source.exceptionally(function);
         if (throwingFunction)
            return execution == RecoveryExecution.DEFAULT ? source.exceptionallyAsync(handler)
                  : source.exceptionallyAsync(handler, executor);
         return execution == RecoveryExecution.DEFAULT ? source.exceptionallyAsync(function)
               : source.exceptionallyAsync(function, executor);
      }

      ExtendedFuture<String> compose(final ExtendedFuture<String> source,
            final ThrowingFunction<Throwable, CompletionStage<String>, ?> handler, final Executor executor) {
         final Function<Throwable, CompletionStage<String>> function = handler;
         if (execution == RecoveryExecution.SYNC)
            return throwingFunction ? source.exceptionallyCompose(handler) : source.exceptionallyCompose(function);
         if (throwingFunction)
            return execution == RecoveryExecution.DEFAULT ? source.exceptionallyComposeAsync(handler)
                  : source.exceptionallyComposeAsync(handler, executor);
         return execution == RecoveryExecution.DEFAULT ? source.exceptionallyComposeAsync(function)
               : source.exceptionallyComposeAsync(function, executor);
      }
   }

   static List<RecoveryEntryPoint> asyncRecoveryEntryPoints() {
      // Submission and queued-work contracts do not apply to synchronous recovery; shared ownership tests use every entry.
      return Stream.of(RecoveryEntryPoint.values()).filter(entry -> entry.execution != RecoveryExecution.SYNC).collect(Collectors.toList());
   }

   /** Observes registrations at allocation, before an inline callback could consume and hide them. */
   private static final class RecoverySource extends ExtendedFuture<String> {
      // These factories run on the test thread; keeping referents alive also prevents GC from hiding registrations.
      final List<ExtendedFuture<?>> created = new ArrayList<>();
      final List<ExecutionBinding> bindings = new ArrayList<>();
      int registrations;

      RecoverySource(final boolean cancellable, final boolean interruptible, final Executor executor) {
         super(cancellable, interruptible, executor);
      }

      @Override
      @SuppressWarnings("resource") // The test observes the caller's scope; closing it here would change the operation under test.
      public <V> ExtendedFuture<V> newIncompleteFuture() {
         final var binding = ExecutionBinding.ACTIVE.get();
         final ExtendedFuture<V> result = super.newIncompleteFuture();
         created.add(result);
         // Observe before inline execution can discard the owner, and distinguish suspended scopes from tracked callbacks.
         if (binding != null && binding.owner == result) {
            bindings.add(binding);
            registrations++;
         }
         return result;
      }
   }

   enum EntryPoint {
      COMPLETE_ASYNC,
      EXCEPTIONALLY,
      THEN_APPLY;

      ExtendedFuture<String> start(final Supplier<String> task, final Executor executor) {
         if (this == COMPLETE_ASYNC)
            return ExtendedFuture.builder(String.class).build().completeAsync(task, executor);
         final var source = new ExtendedFuture<String>();
         if (this == EXCEPTIONALLY) {
            final var result = source.exceptionally(error -> task.get());
            executor.execute(() -> source.completeExceptionally(new IllegalStateException("source")));
            return result;
         }
         final var result = source.thenApply(value -> task.get());
         executor.execute(() -> source.complete("source"));
         return result;
      }
   }

   /** Holds user code open independently of future completion, with unconditional release during cleanup. */
   private static final class BlockingSupplier implements Supplier<String>, AutoCloseable {
      final CountDownLatch started = new CountDownLatch(1);
      final CountDownLatch release = new CountDownLatch(1);
      final CountDownLatch finished = new CountDownLatch(1);
      final AtomicBoolean interrupted = new AtomicBoolean();

      @Override
      public String get() {
         started.countDown();
         try {
            assertThat(release.await(15, TimeUnit.SECONDS)).as("test must release the supplier").isTrue();
         } catch (final InterruptedException ex) {
            interrupted.set(true);
         } finally {
            finished.countDown();
         }
         return "result";
      }

      void awaitStarted() throws InterruptedException {
         assertThat(started.await(MAX_WAIT_SECS, TimeUnit.SECONDS)).isTrue();
      }

      void assertInterruption(final boolean expected) throws InterruptedException {
         if (!expected) {
            assertThat(finished.getCount()).as("the supplier must remain blocked until released").isOne();
            release.countDown();
         }
         assertThat(finished.await(MAX_WAIT_SECS, TimeUnit.SECONDS)).as("the supplier must stop").isTrue();
         assertThat(interrupted.get()).isEqualTo(expected);
      }

      @Override
      public void close() {
         release.countDown();
      }
   }

   /** Owns bounded worker cleanup and counts actual interrupt requests before a pool can clear their status. */
   private static final class TaskExecutor implements Executor, AutoCloseable {
      final AtomicInteger executions = new AtomicInteger();
      final AtomicInteger interruptions = new AtomicInteger();
      final ExecutorService service;

      TaskExecutor(final int threads) {
         service = Executors.newFixedThreadPool(threads, command -> new Thread(command) {
            @Override
            public void interrupt() {
               interruptions.incrementAndGet();
               super.interrupt();
            }
         });
      }

      @Override
      public void execute(final Runnable command) {
         executions.incrementAndGet();
         service.execute(command);
      }

      @Override
      public void close() throws InterruptedException {
         service.shutdown();
         if (!service.awaitTermination(MAX_WAIT_SECS, TimeUnit.SECONDS)) {
            service.shutdownNow();
            assertThat(service.awaitTermination(MAX_WAIT_SECS, TimeUnit.SECONDS)).isTrue();
         }
      }
   }

   @ParameterizedTest
   @CsvSource({"false,false,false", "false,false,true", "false,true,false", "false,true,true", "true,false,false", "true,false,true",
      "true,true,false", "true,true,true"})
   void testCompleteAsyncInterruption(final boolean defaultExecutor, final boolean throwingSupplier, final boolean mayInterrupt)
         throws Exception {
      try (var executor = new TaskExecutor(1);
           var task = new BlockingSupplier()) {
         final var future = ExtendedFuture.builder(String.class).withDefaultExecutor(executor).build();
         final ExtendedFuture<String> returned;
         if (throwingSupplier) {
            final ThrowingSupplier<String, ?> supplier = task::get;
            returned = defaultExecutor ? future.completeAsync(supplier) : future.completeAsync(supplier, executor);
         } else {
            returned = defaultExecutor ? future.completeAsync(task) : future.completeAsync(task, executor);
         }
         assertThat(returned).isSameAs(future);
         task.awaitStarted();
         assertThat(executor.executions).hasValue(1);

         assertThat(future.cancel(mayInterrupt)).isTrue();

         task.assertInterruption(mayInterrupt);
         assertThat(future).isCancelled();
      }
   }

   @ParameterizedTest
   @ValueSource(booleans = {false, true})
   void testCompleteAsyncRespectsNonInterruptiblePolicy(final boolean throughView) throws Exception {
      try (var executor = new TaskExecutor(1);
           var task = new BlockingSupplier()) {
         final var backing = ExtendedFuture.builder(String.class).withInterruptible(throughView).build();
         final var future = throughView ? backing.asNonInterruptible() : backing;
         future.completeAsync(task, executor);
         task.awaitStarted();

         assertThat(future.cancel(true)).isTrue();

         task.assertInterruption(false);
         assertThat(executor.interruptions).hasValue(0);
         assertThat(backing).isCancelled();
      }
   }

   @ParameterizedTest
   @CsvSource({"false,false,false", "false,false,true", "false,true,false", "false,true,true", "true,false,false", "true,false,true",
      "true,true,false", "true,true,true"})
   void testExceptionallyInterruption(final boolean interruptibleStages, final boolean throwingFunction, final boolean mayInterrupt)
         throws Exception {
      try (var executor = new TaskExecutor(1);
           var task = new BlockingSupplier()) {
         final var source = new ExtendedFuture<String>().withInterruptibleStages(interruptibleStages);
         final Function<Throwable, String> handler = error -> task.get();
         final var future = throwingFunction ? source.exceptionally((ThrowingFunction<Throwable, String, ?>) handler::apply)
               : source.exceptionally(handler);
         // A pending source exposes the recovery stage before its synchronous handler starts on the completing thread.
         final var completion = executor.service.submit(() -> source.completeExceptionally(new IllegalStateException("source")));
         task.awaitStarted();

         assertThat(future.cancel(mayInterrupt)).isTrue();

         task.assertInterruption(interruptibleStages && mayInterrupt);
         assertThat(completion.get(MAX_WAIT_SECS, TimeUnit.SECONDS)).isTrue();
         assertThat(future).isCancelled();
      }
   }

   @ParameterizedTest
   @EnumSource(RecoveryEntryPoint.class)
   void testRecoveryInterruption(final RecoveryEntryPoint entry) throws Exception {
      for (final boolean interruptible : List.of(false, true)) {
         for (final boolean mayInterrupt : List.of(false, true)) {
            try (var executor = new TaskExecutor(1);
                 var task = new BlockingSupplier()) {
               final var source = new ExtendedFuture<String>(false, interruptible, executor);
               if (entry.execution != RecoveryExecution.SYNC) {
                  source.completeExceptionally(new IllegalStateException("source"));
               }
               final var result = entry.recover(source, error -> task.get(), executor);
               if (entry.execution == RecoveryExecution.SYNC) {
                  // Expose the result before its mapper blocks on the completing thread.
                  executor.execute(() -> source.completeExceptionally(new IllegalStateException("source")));
               }
               task.awaitStarted();

               assertThat(result.cancel(mayInterrupt)).isTrue();

               task.assertInterruption(interruptible && mayInterrupt);
               assertThat(result).isCancelled();
               assertThat(result.isInterruptible()).isEqualTo(interruptible);
               assertThat(result.isCancellableByDependents()).isFalse();
               assertThat(result.cancellablePrecedingStages).isEmpty();
            }
         }
      }
   }

   @ParameterizedTest
   @EnumSource(RecoveryEntryPoint.class)
   void testRecoveryNonInterruptibleResultView(final RecoveryEntryPoint entry) throws Exception {
      try (var executor = new TaskExecutor(1);
           var task = new BlockingSupplier()) {
         final var source = new ExtendedFuture<String>(false, true, executor);
         if (entry.execution != RecoveryExecution.SYNC) {
            source.completeExceptionally(new IllegalStateException("source"));
         }
         final var result = entry.recover(source, error -> task.get(), executor);
         final var view = result.asNonInterruptible();
         if (entry.execution == RecoveryExecution.SYNC) {
            executor.execute(() -> source.completeExceptionally(new IllegalStateException("source")));
         }
         task.awaitStarted();

         assertThat(view.cancel(true)).isTrue();

         // The private pipeline must not bypass the view's policy for this operation's own callback.
         task.assertInterruption(false);
         assertThat(executor.interruptions).hasValue(0);
         assertThat(result).isCancelled();
      }
   }

   @ParameterizedTest
   @EnumSource(value = RecoveryEntryPoint.class, names = {"COMPOSE_SYNC", "COMPOSE_SYNC_THROWING", "COMPOSE_DEFAULT", "COMPOSE_EXPLICIT",
      "COMPOSE_DEFAULT_THROWING", "COMPOSE_EXPLICIT_THROWING"})
   void testRecoveryNonInterruptibleViewPreservesNestedIntent(final RecoveryEntryPoint entry) {
      final var source = new ExtendedFuture<String>(false, true, Runnable::run);
      final var nested = new ExtendedFuture<String>(true, true, Runnable::run);
      final var owner = new AtomicReference<ExtendedFuture<String>>();
      final var result = entry.compose(source, error -> {
         assertThat(owner.get().asNonInterruptible().cancel(true)).isTrue();
         return nested;
      }, Runnable::run);
      owner.set(result);
      try {
         source.completeExceptionally(new IllegalStateException("source"));
         // Mask only the mapper's interruption; a late, opted-in nested stage still receives the original request.
         assertThat(Thread.currentThread().isInterrupted()).isFalse();
         assertThat(result).isCancelled();
         assertThat(nested).isCancelled();
         assertThat(nested.getCancelInterruptIntentOrDefault(false)).isTrue();
      } finally {
         Thread.interrupted();
         nested.complete("cleanup");
      }
   }

   @ParameterizedTest
   @MethodSource("asyncRecoveryEntryPoints")
   void testRecoveryQueuedExplicitCompletion(final RecoveryEntryPoint entry) {
      for (final boolean interruptible : List.of(false, true)) {
         final var queued = new ArrayList<Runnable>();
         final Executor executor = queued::add;
         final var source = new ExtendedFuture<String>(false, interruptible, executor);
         final var invoked = new AtomicBoolean();
         final var result = entry.recover(source, error -> {
            invoked.set(true);
            return "unused";
         }, executor);
         source.completeExceptionally(new IllegalStateException("source"));
         assertThat(queued).hasSize(1);
         assertThat(result.complete("winner")).isTrue();

         queued.forEach(Runnable::run);

         // Normal completion does not cancel private work, so the callback must separately check its public owner.
         assertThat(invoked).isFalse();
         assertThat(result.join()).isEqualTo("winner");
         assertThat(result.cancellablePrecedingStages).isEmpty();
      }
   }

   @ParameterizedTest
   @EnumSource(RecoveryEntryPoint.class)
   void testRecoveryCancellationBeforeFailure(final RecoveryEntryPoint entry) {
      for (final boolean cancellable : List.of(false, true)) {
         for (final boolean interruptible : List.of(false, true)) {
            for (final boolean mayInterrupt : List.of(false, true)) {
               final var queued = new ArrayList<Runnable>();
               final Executor executor = queued::add;
               final var source = new ExtendedFuture<String>(cancellable, interruptible, executor);
               final var result = entry.recover(source, error -> {
                  throw new AssertionError("cancelled recovery must not start later");
               }, executor);

               assertThat(result.cancel(mayInterrupt)).isTrue();
               assertThat(source.isCancelled()).isEqualTo(cancellable);
               if (cancellable) {
                  assertThat(source.getCancelInterruptIntentOrDefault(!mayInterrupt)).isEqualTo(mayInterrupt);
               }
               source.completeExceptionally(new IllegalStateException("source"));

               assertThat(queued).isEmpty();
               assertThat(result).isCancelled();
               assertThat(result.isCancellableByDependents()).isEqualTo(cancellable);
               assertThat(result.cancellablePrecedingStages).isEmpty();
            }
         }
      }
   }

   @ParameterizedTest
   @MethodSource("asyncRecoveryEntryPoints")
   void testRecoveryQueuedCancellation(final RecoveryEntryPoint entry) {
      for (final boolean interruptible : List.of(false, true)) {
         for (final boolean mayInterrupt : List.of(false, true)) {
            final var queued = new ArrayList<Runnable>();
            final Executor executor = queued::add;
            final var source = new ExtendedFuture<String>(false, interruptible, executor);
            final var invoked = new AtomicBoolean();
            final var result = entry.recover(source, error -> {
               invoked.set(true);
               return "unused";
            }, executor);
            source.completeExceptionally(new IllegalStateException("source"));
            assertThat(queued).hasSize(1);

            result.cancel(mayInterrupt);
            queued.forEach(Runnable::run);

            assertThat(invoked).isFalse();
            assertThat(result).isCancelled();
         }
      }
   }

   @ParameterizedTest
   @EnumSource(RecoveryEntryPoint.class)
   void testRecoveryAlreadyFailedInlineCancellation(final RecoveryEntryPoint entry) {
      for (final boolean interruptible : List.of(false, true)) {
         for (final boolean mayInterrupt : List.of(false, true)) {
            final var source = new RecoverySource(false, interruptible, Runnable::run);
            source.completeExceptionally(new IllegalStateException("source"));
            try {
               final var result = entry.recover(source, error -> {
                  // The factory exposes the pre-created owner before the inline recovery method returns to its caller.
                  assertThat(source.created.get(0).cancel(mayInterrupt)).isTrue();
                  return "ignored";
               }, Runnable::run);
               // Cancelling a private observation would give a false positive; this must be the actual public result.
               assertThat(result).isSameAs(source.created.get(0)).isCancelled();
               assertThat(Thread.currentThread().isInterrupted()).isEqualTo(interruptible && mayInterrupt);
            } finally {
               Thread.interrupted();
            }
         }
      }
   }

   @ParameterizedTest
   @MethodSource("asyncRecoveryEntryPoints")
   void testRecoveryCancellationDuringSubmission(final RecoveryEntryPoint entry) {
      for (final boolean interruptible : List.of(false, true)) {
         final var owner = new AtomicReference<ExtendedFuture<String>>();
         final var invoked = new AtomicBoolean();
         final var submissions = new AtomicInteger();
         final Executor executor = task -> {
            submissions.incrementAndGet();
            // Cancellation here must already own the private task, even though execute has not returned yet.
            assertThat(owner.get().cancel(true)).isTrue();
            task.run();
         };
         final var source = new ExtendedFuture<String>(false, interruptible, executor);
         final var result = entry.recover(source, error -> {
            invoked.set(true);
            return "unused";
         }, executor);
         owner.set(result);
         source.completeExceptionally(new IllegalStateException("source"));

         assertThat(submissions).hasValue(1);
         assertThat(invoked).isFalse();
         assertThat(result).isCancelled();
      }
   }

   @ParameterizedTest
   @EnumSource(value = RecoveryEntryPoint.class, names = {"COMPOSE_SYNC", "COMPOSE_SYNC_THROWING", "COMPOSE_DEFAULT", "COMPOSE_EXPLICIT",
      "COMPOSE_DEFAULT_THROWING", "COMPOSE_EXPLICIT_THROWING"})
   void testRecoveryNestedCancellation(final RecoveryEntryPoint entry) {
      for (final boolean interruptible : List.of(false, true)) {
         for (final boolean mayInterrupt : List.of(false, true)) {
            for (final boolean cancellable : List.of(false, true)) {
               for (final boolean cancelInsideMapper : List.of(false, true)) {
                  final var source = new ExtendedFuture<String>(false, interruptible, Runnable::run);
                  final var nested = new ExtendedFuture<String>(cancellable, true, Runnable::run);
                  final var owner = new AtomicReference<ExtendedFuture<String>>();
                  final var invoked = new AtomicBoolean();
                  final var result = entry.compose(source, error -> {
                     invoked.set(true);
                     if (cancelInsideMapper) {
                        // The nested stage becomes known only after its recovery owner has already been cancelled.
                        assertThat(owner.get().cancel(mayInterrupt)).isTrue();
                     }
                     return nested;
                  }, Runnable::run);
                  owner.set(result);
                  try {
                     source.completeExceptionally(new IllegalStateException("source"));
                     if (!cancelInsideMapper) {
                        assertThat(result.cancel(mayInterrupt)).isTrue();
                     }
                     assertThat(invoked).isTrue();
                     assertThat(result).isCancelled();
                     assertThat(nested.isCancelled()).isEqualTo(cancellable);
                     if (cancellable) {
                        assertThat(nested.getCancelInterruptIntentOrDefault(!mayInterrupt)).isEqualTo(mayInterrupt);
                     }
                     assertThat(result.cancellablePrecedingStages).isEmpty();
                     assertThat(Thread.currentThread().isInterrupted()).isEqualTo(cancelInsideMapper && interruptible && mayInterrupt);
                  } finally {
                     // The direct executor uses this thread; a deliberate self-interrupt must not escape into the next test.
                     Thread.interrupted();
                     nested.complete("cleanup");
                  }
               }
            }
         }
      }
   }

   @ParameterizedTest
   @EnumSource(RecoveryEntryPoint.class)
   void testRecoverySuccessfulHandler(final RecoveryEntryPoint entry) {
      for (final boolean interruptible : List.of(false, true)) {
         for (final boolean cancelledSource : List.of(false, true)) {
            for (final boolean nullResult : List.of(false, true)) {
               final var source = new ExtendedFuture<String>(true, interruptible, Runnable::run);
               final String value = nullResult ? sneakyNull() : "recovered";
               final var failure = new IllegalStateException("source");
               final var calls = new AtomicInteger();
               final var result = entry.recover(source, error -> {
                  calls.incrementAndGet();
                  if (cancelledSource) {
                     assertThat(error).isInstanceOf(CancellationException.class);
                  } else {
                     assertThat(error).isSameAs(failure);
                  }
                  return value;
               }, Runnable::run);
               if (cancelledSource) {
                  source.cancel(false);
               } else {
                  source.completeExceptionally(failure);
               }

               assertThat(result.join()).isEqualTo(value);
               assertThat(calls).hasValue(1);
               assertThat(result.isCancellableByDependents()).isTrue();
               assertThat(result.isInterruptible()).isEqualTo(interruptible);
               assertThat(result.cancellablePrecedingStages).isEmpty();
            }
         }
      }
   }

   @ParameterizedTest
   @EnumSource(RecoveryEntryPoint.class)
   void testRecoveryReadOnlySourceBoundary(final RecoveryEntryPoint entry) {
      for (final var mode : ExtendedFuture.ReadOnlyMode.values()) {
         final var backing = new ExtendedFuture<String>(true, true, Runnable::run);
         final var source = backing.asReadOnly(mode);
         final var invoked = new AtomicBoolean();
         final var result = entry.recover(source, error -> {
            invoked.set(true);
            return "unused";
         }, Runnable::run);
         assertThat(result.cancel(true)).isTrue();
         assertThat(backing.isDone()).isFalse();
         assertThat(source.isDone()).isFalse();
         backing.completeExceptionally(new IllegalStateException("source"));
         assertThat(invoked).isFalse();
      }
   }

   @ParameterizedTest
   @EnumSource(value = RecoveryEntryPoint.class, names = {"COMPOSE_SYNC", "COMPOSE_SYNC_THROWING", "COMPOSE_DEFAULT", "COMPOSE_EXPLICIT",
      "COMPOSE_DEFAULT_THROWING", "COMPOSE_EXPLICIT_THROWING"})
   void testRecoveryNestedWithoutOptIn(final RecoveryEntryPoint entry) {
      final List<Function<CompletableFuture<String>, CompletionStage<String>>> views = List.of(backing -> backing,
         CompletableFuture::minimalCompletionStage, backing -> ExtendedFuture.from(backing).asReadOnly(
            ExtendedFuture.ReadOnlyMode.IGNORE_MUTATION), backing -> ExtendedFuture.from(backing).asReadOnly(
               ExtendedFuture.ReadOnlyMode.THROW_ON_MUTATION));
      for (final var view : views) {
         final var source = new ExtendedFuture<String>(false, true, Runnable::run);
         final var backing = new CompletableFuture<String>();
         final var nested = view.apply(backing);
         final var result = entry.compose(source, error -> nested, Runnable::run);
         try {
            source.completeExceptionally(new IllegalStateException("source"));
            assertThat(result.cancel(true)).isTrue();
            // Conversion to CompletableFuture must not grant cancellation permission to an ordinary or read-only stage.
            assertThat(backing.isDone()).isFalse();
         } finally {
            backing.complete("cleanup");
         }
      }
      final var source = new ExtendedFuture<String>(false, true, Runnable::run);
      final var result = entry.compose(source, error -> sneakyNull(), Runnable::run);
      source.completeExceptionally(new IllegalStateException("source"));
      assertThat(result).isCompletedExceptionally();
      assertThatThrownBy(result::join).isInstanceOf(CompletionException.class).hasCauseInstanceOf(NullPointerException.class);
   }

   @ParameterizedTest
   @EnumSource(RecoveryEntryPoint.class)
   void testRecoveryFailureShapeAndExecutor(final RecoveryEntryPoint entry) {
      for (final Exception failure : List.of(new IllegalStateException("handler"), new CancellationException("handler"),
         new CompletionException(new CancellationException("wrapped")), new IOException("checked"))) {
         final var submissions = new AtomicInteger();
         final Executor executor = task -> {
            submissions.incrementAndGet();
            task.run();
         };
         final Executor unexpectedExecutor = task -> {
            throw new AssertionError("explicit executor must take precedence");
         };
         final Executor defaultExecutor = entry.execution == RecoveryExecution.DEFAULT ? executor : unexpectedExecutor;
         final var source = new ExtendedFuture<String>(true, true, defaultExecutor);
         final var sourceFailure = new IllegalArgumentException("source");
         final var result = entry.recover(source, error -> {
            assertThat(error).isSameAs(sourceFailure);
            throw failure;
         }, executor);
         source.completeExceptionally(sourceFailure);

         assertThatThrownBy(result::join).isInstanceOf(CompletionException.class).hasRootCause(failure instanceof CompletionException
               ? failure.getCause()
               : failure);
         assertThat(result.isCancelled()).isFalse();
         assertThat(result.defaultExecutor()).isSameAs(defaultExecutor);
         assertThat(result.isCancellableByDependents()).isTrue();
         assertThat(result.cancellablePrecedingStages).isEmpty();
         assertThat(submissions).hasValue(entry.execution == RecoveryExecution.SYNC ? 0 : 1);
      }
   }

   @ParameterizedTest
   @MethodSource("asyncRecoveryEntryPoints")
   void testRecoveryRejectedExecutor(final RecoveryEntryPoint entry) {
      for (final boolean alreadyFailed : List.of(false, true)) {
         final var rejection = new RejectedExecutionException("executor");
         final Executor executor = task -> {
            throw rejection;
         };
         final var source = new ExtendedFuture<String>(false, true, executor);
         final var failure = new IllegalStateException("source");
         if (alreadyFailed) {
            source.completeExceptionally(failure);
         }
         final var result = entry.recover(source, error -> "unused", executor);
         source.completeExceptionally(failure);

         // A failure captured only by the private observer would leave the public result pending forever.
         assertThat(result).isCompletedExceptionally();
         assertThatThrownBy(result::join).isInstanceOf(CompletionException.class).hasCause(rejection);
         assertThat(result.isCancelled()).isFalse();
      }
   }

   @ParameterizedTest
   @EnumSource(value = RecoveryEntryPoint.class, names = {"COMPOSE_SYNC", "COMPOSE_SYNC_THROWING", "COMPOSE_DEFAULT", "COMPOSE_EXPLICIT",
      "COMPOSE_DEFAULT_THROWING", "COMPOSE_EXPLICIT_THROWING"})
   void testRecoveryNestedOutcomeAndExplicitCompletion(final RecoveryEntryPoint entry) {
      for (final boolean interruptible : List.of(false, true)) {
         for (final boolean explicitCompletion : List.of(false, true)) {
            final var source = new ExtendedFuture<String>(false, interruptible, Runnable::run);
            final var nested = new ExtendedFuture<String>(true, true, Runnable::run);
            final var result = entry.compose(source, error -> nested, Runnable::run);
            source.completeExceptionally(new IllegalStateException("source"));
            assertThat(result.isDone()).isFalse();
            if (explicitCompletion) {
               assertThat(result.complete("winner")).isTrue();
               assertThat(result.cancel(true)).isFalse();
               assertThat(nested.isDone()).isFalse();
               nested.complete("later");
               assertThat(result.join()).isEqualTo("winner");
            } else {
               nested.cancel(true);
               // Cancellation of a nested stage is an exceptional dependent result, not a direct cancellation of that dependent.
               assertThat(result.isCancelled()).isFalse();
               assertThatThrownBy(result::join).isInstanceOf(CompletionException.class).hasCauseInstanceOf(CancellationException.class);
            }
            assertThat(result.cancellablePrecedingStages).isEmpty();
         }
      }
   }

   @ParameterizedTest
   @EnumSource(RecoveryEntryPoint.class)
   void testRecoverySuccessAvoidsExecutionAndScheduling(final RecoveryEntryPoint entry) {
      final Executor unexpectedExecutor = task -> {
         throw new AssertionError("successful recovery must not submit work");
      };
      for (final boolean interruptible : List.of(true, false)) {
         for (final boolean alreadyCompleted : List.of(false, true)) {
            for (final boolean nullResult : List.of(false, true)) {
               final var source = new RecoverySource(true, interruptible, unexpectedExecutor);
               final String value = nullResult ? sneakyNull() : "success";
               if (alreadyCompleted) {
                  source.complete(value);
               }
               final var result = entry.recover(source, error -> {
                  throw new AssertionError("successful sources must bypass recovery");
               }, unexpectedExecutor);
               source.complete(value);

               assertThat(result.join()).isEqualTo(value);
               assertThat(result).isNotSameAs(source);
               assertThat(result.isInterruptible()).isEqualTo(interruptible);
               assertThat(result.isInterruptibleStages()).isEqualTo(interruptible);
               assertThat(result.isCancellableByDependents()).isTrue();
               assertThat(result.defaultExecutor()).isSameAs(unexpectedExecutor);
               assertThat(source.created).isNotEmpty();
               // Synchronous value recovery creates one association, then releases it on success; async/composed recovery creates none.
               assertThat(source.registrations).isEqualTo(interruptible && !entry.compose && entry.execution == RecoveryExecution.SYNC ? 1
                     : 0);
               assertThat(result.cancellablePrecedingStages).isEmpty();
            }
         }
      }
   }

   @Test
   void testRecoveryRegistrationFixtureDetectsPublicHandle() {
      final var source = new RecoverySource(false, true, Runnable::run);
      source.complete("value");
      assertThat(source.handle((value, error) -> value).join()).isEqualTo("value");
      // Positive control: checking only after handle returns would miss the inline registration entirely.
      assertThat(source.registrations).isOne();
   }

   @ParameterizedTest
   @EnumSource(RecoveryEntryPoint.class)
   void testRecoveryLongChains(final RecoveryEntryPoint entry) throws Exception {
      for (final boolean interruptible : List.of(false, true)) {
         for (final boolean exceptional : List.of(false, true)) {
            for (final boolean interleaveNativeStages : List.of(false, true)) {
               final var source = new ExtendedFuture<String>(false, interruptible, Runnable::run);
               final var failure = new IllegalStateException("recovery");
               var tail = source;
               for (int index = 0; index < 10_000; index++) {
                  tail = entry.recover(tail, error -> {
                     throw failure;
                  }, Runnable::run);
                  if (interleaveNativeStages) {
                     // A completion bridge must remain stack-safe across ordinary stages too, not only adjacent recovery calls.
                     tail = tail.thenApply(Function.identity());
                  }
               }
               if (exceptional) {
                  source.completeExceptionally(failure);
               } else {
                  source.complete("value");
               }

               // Everything runs inline here. Check completion before reading, so a stranded tail cannot hang the test suite.
               assertThat(tail).isDone();
               final var result = tail;
               if (exceptional) {
                  assertThatThrownBy(() -> result.get(MAX_WAIT_SECS, TimeUnit.SECONDS)).hasRootCause(failure);
               } else {
                  assertThat(result.get(MAX_WAIT_SECS, TimeUnit.SECONDS)).isEqualTo("value");
               }
            }
         }
      }
   }

   @ParameterizedTest
   @EnumSource(RecoveryEntryPoint.class)
   void testRecoveryReentrantCompletion(final RecoveryEntryPoint entry) throws Exception {
      final var source = new ExtendedFuture<String>();
      final var otherSource = new ExtendedFuture<String>();
      final var other = entry.recover(otherSource, error -> "unused", Runnable::run);
      final var result = entry.recover(source, error -> "unused", Runnable::run).thenApply(value -> {
         // Deferring all nested completions to a thread-local queue would deadlock these synchronous reads.
         otherSource.complete(value);
         assertThat(other.get(MAX_WAIT_SECS, TimeUnit.SECONDS)).isEqualTo(value);
         final var alreadyCompleted = entry.recover(source, error -> "unused", Runnable::run);
         assertThat(alreadyCompleted.get(MAX_WAIT_SECS, TimeUnit.SECONDS)).isEqualTo(value);
         return value;
      });
      source.complete("value");
      assertThat(result.get(MAX_WAIT_SECS, TimeUnit.SECONDS)).isEqualTo("value");
   }

   @ParameterizedTest
   @EnumSource(RecoveryEntryPoint.class)
   void testRecoveryNullArguments(final RecoveryEntryPoint entry) {
      for (final var state : List.of(CompletionState.INCOMPLETE, CompletionState.SUCCESS, CompletionState.FAILED,
         CompletionState.CANCELLED)) {
         final var source = new ExtendedFuture<String>(false, true, Runnable::run);
         if (state == CompletionState.SUCCESS) {
            source.complete("success");
         } else if (state == CompletionState.FAILED) {
            source.completeExceptionally(new IllegalStateException("source"));
         } else if (state == CompletionState.CANCELLED) {
            source.cancel(false);
         }
         // Pass the null handler directly: the value-to-stage adapter used by recover would otherwise hide it.
         assertThatNullPointerException().isThrownBy(() -> {
            if (entry.compose) {
               entry.compose(source, sneakyNull(), Runnable::run);
            } else {
               entry.recover(source, sneakyNull(), Runnable::run);
            }
         });
         if (entry.execution == RecoveryExecution.EXPLICIT) {
            assertThatNullPointerException().isThrownBy(() -> entry.recover(source, error -> "unused", sneakyNull()));
         }
      }
   }

   @ParameterizedTest
   @CsvSource({"COMPLETE_ASYNC,false", "COMPLETE_ASYNC,true", "EXCEPTIONALLY,false", "EXCEPTIONALLY,true", "THEN_APPLY,false",
      "THEN_APPLY,true"})
   void testConcurrentCompletionAttemptsPreserveInterruptPolicy(final EntryPoint entryPoint, final boolean mayInterrupt) throws Exception {
      try (var executor = new TaskExecutor(3);
           var first = new BlockingSupplier();
           var second = new BlockingSupplier();
           var third = new BlockingSupplier()) {
         final var future = entryPoint.start(first, executor);
         first.awaitStarted();
         future.completeAsync(second, executor);
         second.awaitStarted();
         future.completeAsync(third, executor);
         third.awaitStarted();

         assertThat(future.cancel(mayInterrupt)).isTrue();

         first.assertInterruption(mayInterrupt);
         second.assertInterruption(mayInterrupt);
         third.assertInterruption(mayInterrupt);
      }
   }

   @ParameterizedTest
   @EnumSource(EntryPoint.class)
   void testSingleExecutionDoesNotAllocateCountedRegistry(final EntryPoint entryPoint) throws Exception {
      try (var executor = new TaskExecutor(1);
           var task = new BlockingSupplier()) {
         final var future = entryPoint.start(task, executor);
         task.awaitStarted();
         final var threadsField = future.getClass().getDeclaredField("executingThreads");
         threadsField.setAccessible(true);
         // One registration needs only its thread; the counted map is reserved for overlapping or nested executions.
         assertThat(threadsField.get(future)).isInstanceOf(Thread.class);
      }
   }

   @ParameterizedTest
   @EnumSource(EntryPoint.class)
   void testCancellationBeforeExecutionSkipsUserCode(final EntryPoint entryPoint) {
      final var queued = new ArrayList<Runnable>();
      final var invoked = new AtomicBoolean();
      final var future = entryPoint.start(() -> {
         invoked.set(true);
         return "unused";
      }, queued::add);

      future.cancel(true);
      queued.forEach(Runnable::run);

      assertThat(invoked).isFalse();
      assertThat(future).isCancelled();
   }

   @ParameterizedTest
   @CsvSource({"COMPLETE_ASYNC,false", "COMPLETE_ASYNC,true", "EXCEPTIONALLY,false", "EXCEPTIONALLY,true", "THEN_APPLY,false",
      "THEN_APPLY,true"})
   void testCompletionWhileRegistrationIsBlockedSkipsUserCode(final EntryPoint entryPoint, final boolean cancel) throws Exception {
      final var queued = new ArrayList<Runnable>();
      final var invoked = new AtomicBoolean();
      final var future = entryPoint.start(() -> {
         invoked.set(true);
         return "unused";
      }, queued::add);
      // Queued cancellation never enters our wrapper. Hold its private lock to force the gap after the JDK's completion check.
      final var lockField = future.getClass().getDeclaredField("executingThreadLock");
      lockField.setAccessible(true);
      final var lock = Objects.requireNonNull(lockField.get(future));
      final var worker = new Thread(queued.get(0));
      try {
         synchronized (lock) {
            worker.start();
            assertThat(await(() -> worker.getState() == Thread.State.BLOCKED)).as("registration must be waiting for the lock").isTrue();
            if (cancel) {
               assertThat(future.cancel(true)).isTrue();
            } else {
               assertThat(future.complete("winner")).isTrue();
            }
         }
      } finally {
         worker.join(TimeUnit.SECONDS.toMillis(MAX_WAIT_SECS));
         assertThat(worker.isAlive()).isFalse();
      }

      assertThat(invoked).isFalse();
      if (cancel) {
         assertThat(future).isCancelled();
      } else {
         assertThat(future.join()).isEqualTo("winner");
      }
   }

   @ParameterizedTest
   @CsvSource({"false,false", "false,true", "true,false", "true,true"})
   void testWhenCompleteCompletionDuringRegistrationPreservesSourceFailure(final boolean async, final boolean cancel) throws Exception {
      final var source = new ExtendedFuture<String>();
      final var failure = new IllegalStateException("source");
      final var invoked = new AtomicBoolean();
      final var queued = new ArrayList<Runnable>();
      final var future = async ? source.whenCompleteAsync((value, error) -> invoked.set(true), queued::add)
            : source.whenComplete((value, error) -> invoked.set(true));
      final Thread worker;
      if (async) {
         source.completeExceptionally(failure);
         worker = new Thread(queued.get(0));
      } else {
         worker = new Thread(() -> source.completeExceptionally(failure));
      }
      // Enter the wrapper before completing its owner; a merely queued callback would be skipped by the JDK itself.
      final var lockField = future.getClass().getDeclaredField("executingThreadLock");
      lockField.setAccessible(true);
      final var lock = Objects.requireNonNull(lockField.get(future));
      try {
         synchronized (lock) {
            worker.start();
            assertThat(await(() -> worker.getState() == Thread.State.BLOCKED)).as("registration must be waiting for the lock").isTrue();
            if (cancel) {
               assertThat(future.cancel(true)).isTrue();
            } else {
               assertThat(future.complete("winner")).isTrue();
            }
         }
      } finally {
         worker.join(TimeUnit.SECONDS.toMillis(MAX_WAIT_SECS));
         assertThat(worker.isAlive()).isFalse();
      }

      assertThat(invoked).isFalse();
      assertThatThrownBy(source::join).hasCause(failure);
      // whenComplete can suppress callback failures on the source even after the returned stage has completed.
      assertThat(failure.getSuppressed()).isEmpty();
      if (cancel) {
         assertThat(future).isCancelled();
      } else {
         assertThat(future.join()).isEqualTo("winner");
      }
   }

   @ParameterizedTest
   @ValueSource(booleans = {false, true})
   void testWhenCompletePreservesUserCallbackFailure(final boolean failedSource) {
      final var source = new ExtendedFuture<String>();
      final var callbackFailure = new CancellationException("callback");
      final var future = source.whenComplete((value, error) -> {
         // Only internal registration aborts may be skipped; the same exception type from user code must remain observable.
         throw callbackFailure;
      });

      if (failedSource) {
         final var sourceFailure = new IllegalStateException("source");
         source.completeExceptionally(sourceFailure);
         assertThatThrownBy(future::join).hasCause(sourceFailure);
         assertThat(sourceFailure.getSuppressed()).containsExactly(callbackFailure);
      } else {
         source.complete("source");
         assertThatThrownBy(future::join).hasCause(callbackFailure);
      }
   }

   @ParameterizedTest
   @EnumSource(EntryPoint.class)
   void testLateCancellationDoesNotInterruptReusedWorker(final EntryPoint entryPoint) throws Exception {
      try (var executor = new TaskExecutor(1);
           var canceller = new TaskExecutor(1);
           var first = new BlockingSupplier();
           var following = new BlockingSupplier()) {
         final var future = entryPoint.start(first, executor);
         first.awaitStarted();
         final var notificationStarted = new CountDownLatch(1);
         final var releaseNotification = new CountDownLatch(1);
         // Cancellation publishes completion before issuing interrupts. Pause it there so the original execution can unregister.
         final var observer = future.whenComplete((result, error) -> {
            notificationStarted.countDown();
            assertThat(releaseNotification.await(15, TimeUnit.SECONDS)).isTrue();
         });
         try {
            final var cancellation = canceller.service.submit(() -> future.cancel(true));
            assertThat(notificationStarted.await(MAX_WAIT_SECS, TimeUnit.SECONDS)).isTrue();
            first.release.countDown();
            executor.execute(following::get);
            following.awaitStarted();
            releaseNotification.countDown();

            assertThat(cancellation.get(MAX_WAIT_SECS, TimeUnit.SECONDS)).isTrue();
            assertThat(observer).isCompletedExceptionally();
            following.assertInterruption(false);
            // A pool may clear interrupt status between tasks, so also check the actual interrupt requests.
            assertThat(executor.interruptions).hasValue(0);
         } finally {
            releaseNotification.countDown();
         }
      }
   }

   @ParameterizedTest
   @EnumSource(EntryPoint.class)
   void testSuccessAndFailureDoNotRetainExecution(final EntryPoint entryPoint) throws Exception {
      for (final boolean fail : List.of(false, true)) {
         try (var executor = new TaskExecutor(1);
              var following = new BlockingSupplier()) {
            final var failure = new IllegalStateException("task");
            final var future = entryPoint.start(() -> {
               if (fail)
                  throw failure;
               return "result";
            }, executor);
            executor.execute(following::get);
            following.awaitStarted();
            // Cancellation of a completed future alone would hide leaked registrations behind its early return.
            final var threadsField = future.getClass().getDeclaredField("executingThreads");
            threadsField.setAccessible(true);
            assertThat(threadsField.get(future)).isNull();
            if (fail) {
               assertThatThrownBy(future::join).hasCause(failure);
            } else {
               assertThat(future.join()).isEqualTo("result");
            }

            assertThat(future.cancel(true)).isFalse();
            following.assertInterruption(false);
            assertThat(executor.interruptions).hasValue(0);
         }
      }
   }

   @ParameterizedTest
   @ValueSource(booleans = {false, true})
   void testExceptionallyPreservesSynchronousRecoveryAndSuccess(final boolean interruptibleStages) {
      final var currentThread = Thread.currentThread();
      final var failure = new IllegalStateException("source");
      final var failed = ExtendedFuture.<String>failedFuture(failure).withInterruptibleStages(interruptibleStages);
      final Function<Throwable, String> handler = error -> {
         assertThat(Thread.currentThread()).isSameAs(currentThread);
         assertThat(error).isSameAs(failure);
         return "recovered";
      };
      assertThat(failed.exceptionally(handler).join()).isEqualTo("recovered");
      final var success = ExtendedFuture.completedFuture("success").withInterruptibleStages(interruptibleStages);
      assertThat(success.exceptionally(error -> {
         throw new AssertionError("successful sources must bypass recovery");
      }).join()).isEqualTo("success");
   }

   @ParameterizedTest
   @CsvSource({"false,false,false", "false,false,true", "false,true,false", "false,true,true", "true,false,false", "true,false,true",
      "true,true,false", "true,true,true"})
   void testSuccessfulRecoveryReleasesBindingOwner(final boolean alreadyCompleted, final boolean throwingFunction,
         final boolean nullResult) {
      final Executor unexpectedExecutor = task -> {
         throw new AssertionError("successful recovery must remain synchronous");
      };
      for (final boolean interruptibleStages : List.of(false, true)) {
         final var source = new RecoverySource(true, interruptibleStages, unexpectedExecutor);
         final String value = nullResult ? sneakyNull() : "success";
         if (alreadyCompleted) {
            source.complete(value);
         }
         final Function<Throwable, String> handler = error -> {
            throw new AssertionError("successful sources must bypass recovery");
         };
         final var result = throwingFunction ? source.exceptionally((ThrowingFunction<Throwable, String, ?>) handler::apply)
               : source.exceptionally(handler);
         try {
            if (!alreadyCompleted && interruptibleStages) {
               assertThat(source.bindings).anyMatch(binding -> binding.owner == result);
            }
            source.complete(value);
            assertThat(result.join()).isEqualTo(value);
            assertThat(result).isNotSameAs(source);
            assertThat(result.isInterruptible()).isEqualTo(interruptibleStages);
            assertThat(result.isInterruptibleStages()).isEqualTo(interruptibleStages);
            assertThat(result.isCancellableByDependents()).isTrue();
            assertThat(result.defaultExecutor()).isSameAs(unexpectedExecutor);
            // Keep both result and bindings reachable: immediate owner release must not be hidden by GC or another operation.
            assertThat(source.bindings).noneMatch(binding -> binding.owner == result);
         } finally {
            Reference.reachabilityFence(source);
            Reference.reachabilityFence(result);
         }
      }
   }

   @ParameterizedTest
   @ValueSource(booleans = {false, true})
   @SuppressWarnings("resource") // Weak observations do not own or close the production construction scopes.
   void testSkippedCallbacksReleaseBindingsWithoutARegistrationSweep(final boolean alreadyCompleted) throws InterruptedException {
      final var liveSource = new RecoverySource(false, true, Runnable::run);
      final var liveStage = liveSource.exceptionally(error -> "recovered");
      final var observed = new AtomicReference<WeakReference<@Nullable ExecutionBinding>>();
      final var source = new ExtendedFuture<String>() {
         @Override
         public <V> ExtendedFuture<V> newIncompleteFuture() {
            final var binding = ExecutionBinding.ACTIVE.get();
            final var result = super.<V>newIncompleteFuture();
            if (binding != null && binding.owner == result) {
               observed.set(new WeakReference<>(binding));
            }
            return result;
         }
      };
      final var failure = new IllegalStateException("source");
      if (alreadyCompleted) {
         source.completeExceptionally(failure);
      }
      final var staleStage = source.thenApply(value -> {
         throw new AssertionError("failed sources must bypass thenApply");
      });
      source.completeExceptionally(failure);
      assertThatThrownBy(staleStage::join).hasCause(failure);
      final var reference = Objects.requireNonNull(observed.get());
      final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(MAX_WAIT_SECS);
      // No new registration or synthetic reference clearing: collection must follow the native callback graph's release.
      while (reference.get() != null && System.nanoTime() < deadline) {
         System.gc();
         Thread.sleep(20);
      }
      assertThat(reference.get()).isNull();
      // Keep the finished graph reachable and a separate pending binding live; the recorder must not hide either lifetime.
      assertThat(liveSource.bindings).anyMatch(binding -> binding.owner == liveStage);
      liveSource.completeExceptionally(failure);
      assertThat(liveStage.join()).isEqualTo("recovered");
      assertThat(liveSource.bindings).allMatch(binding -> binding.owner == null);
      Reference.reachabilityFence(source);
      Reference.reachabilityFence(staleStage);
   }

   @Test
   void testCompleteAsyncPreservesExplicitExecutorAndCheckedFailure() {
      final Executor unexpectedExecutor = task -> {
         throw new AssertionError("explicit executor must take precedence");
      };
      final var future = ExtendedFuture.builder(String.class).withDefaultExecutor(unexpectedExecutor).build();
      final var failure = new IOException("supplier");
      final ThrowingSupplier<String, IOException> supplier = () -> {
         throw failure;
      };

      assertThat(future.completeAsync(supplier, Runnable::run)).isSameAs(future);
      assertThatThrownBy(future::join).hasRootCause(failure);
   }

   @Test
   void testNestedCompleteAsyncPreservesOuterRegistrationAndFirstCompletion() throws Exception {
      try (var executor = new TaskExecutor(1)) {
         final var future = ExtendedFuture.builder(String.class).build();
         final var threadsField = future.getClass().getDeclaredField("executingThreads");
         threadsField.setAccessible(true);
         final var remainingRegistrations = new AtomicInteger(-1);
         future.completeAsync(() -> {
            // An inline executor can re-enter execution tracking on the same future and thread.
            future.completeAsync(() -> "inner", Runnable::run);
            final var threads = (Map<?, ?>) Objects.requireNonNull(threadsField.get(future));
            remainingRegistrations.set((Integer) Objects.requireNonNull(threads.get(Thread.currentThread())));
            return "outer";
         }, executor);

         executor.service.submit(() -> {
            // Wait for outer cleanup too: the inner supplier has already published the future's result.
         }).get(MAX_WAIT_SECS, TimeUnit.SECONDS);
         assertThat(remainingRegistrations).hasValue(1);
         assertThat(threadsField.get(future)).isNull();
         assertThat(future.join()).isEqualTo("inner");
      }
   }

   @Test
   void testNullArgumentsAreRejectedBeforeWrapping() {
      // Pass actual nulls through the existing test utility so the API, not a helper or the compiler, must reject them.
      final Supplier<String> nullSupplier = sneakyNull();
      final ThrowingSupplier<String, ?> nullThrowingSupplier = sneakyNull();
      final Function<Throwable, String> nullFunction = sneakyNull();
      final ThrowingFunction<Throwable, String, ?> nullThrowingFunction = sneakyNull();
      final Executor nullExecutor = sneakyNull();
      for (final var state : List.of(CompletionState.INCOMPLETE, CompletionState.SUCCESS, CompletionState.FAILED,
         CompletionState.CANCELLED)) {
         final var future = ExtendedFuture.builder(String.class).build();
         switch (state) {
            case SUCCESS:
               future.complete("done");
               break;
            case FAILED:
               future.completeExceptionally(new IllegalStateException("source"));
               break;
            case CANCELLED:
               future.cancel(false);
               break;
            default:
               break;
         }
         assertThatNullPointerException().isThrownBy(() -> future.completeAsync(nullSupplier));
         assertThatNullPointerException().isThrownBy(() -> future.completeAsync(nullThrowingSupplier));
         assertThatNullPointerException().isThrownBy(() -> future.completeAsync(nullSupplier, Runnable::run));
         assertThatNullPointerException().isThrownBy(() -> future.completeAsync(nullThrowingSupplier, Runnable::run));
         assertThatNullPointerException().isThrownBy(() -> future.completeAsync(() -> "unused", nullExecutor));
         assertThatNullPointerException().isThrownBy(() -> future.exceptionally(nullFunction));
         assertThatNullPointerException().isThrownBy(() -> future.exceptionally(nullThrowingFunction));
      }
   }
}
