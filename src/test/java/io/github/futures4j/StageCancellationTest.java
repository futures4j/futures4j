/*
 * SPDX-FileCopyrightText: © Sebastian Thomschke
 * SPDX-License-Identifier: EPL-2.0
 */
package io.github.futures4j;

import static net.sf.jstuff.core.validation.NullAnalysisHelper.sneakyNull;
import static org.assertj.core.api.Assertions.*;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import io.github.futures4j.ExtendedFuture.ReadOnlyMode;
import io.github.futures4j.util.ThrowingBiConsumer;
import io.github.futures4j.util.ThrowingBiFunction;
import io.github.futures4j.util.ThrowingConsumer;
import io.github.futures4j.util.ThrowingFunction;
import io.github.futures4j.util.ThrowingRunnable;
import io.github.futures4j.util.ThrowingSupplier;

/**
 * Verifies cancellation ownership and link lifetime for unary, binary and composed stages independently of callback interruption,
 * including external factory calls, construction-time completion, shared cleanup observers, late links, and forced completion.
 * Mixed-input cases also check that internal adaptation does not change ordinary CompletableFuture outcomes.
 *
 * @author futures4j contributors
 */
class StageCancellationTest extends AbstractFutureTest {

   enum EntryPoint {
      SYNC,
      ASYNC_DEFAULT,
      ASYNC_EXPLICIT;

      ExtendedFuture<String> compose(final ExtendedFuture<String> source, final Function<String, CompletionStage<String>> mapper,
            final boolean throwing, final Executor executor) {
         if (throwing) {
            final ThrowingFunction<String, CompletionStage<String>, ?> fn = mapper::apply;
            return this == SYNC ? source.thenCompose(fn)
                  : this == ASYNC_DEFAULT ? source.thenComposeAsync(fn) : source.thenComposeAsync(fn, executor);
         }
         return this == SYNC ? source.thenCompose(mapper)
               : this == ASYNC_DEFAULT ? source.thenComposeAsync(mapper) : source.thenComposeAsync(mapper, executor);
      }
   }

   enum BinaryOperation {
      COMBINE,
      ACCEPT_BOTH,
      RUN_AFTER_BOTH,
      APPLY_EITHER,
      ACCEPT_EITHER,
      RUN_AFTER_EITHER;

      boolean isEither() {
         return this == APPLY_EITHER || this == ACCEPT_EITHER || this == RUN_AFTER_EITHER;
      }

      ExtendedFuture<?> create(final ExtendedFuture<String> left, final CompletionStage<String> right, final EntryPoint entry,
            final boolean throwing, final Executor executor, final AtomicInteger calls) {
         return create(left, right, entry, throwing, executor, calls, (value, other) -> {
            // Lifecycle-only cases count executions; mixed-input cases additionally inspect callback values.
         });
      }

      ExtendedFuture<?> create(final ExtendedFuture<String> left, final CompletionStage<String> right, final EntryPoint entry,
            final boolean throwing, final Executor executor, final AtomicInteger calls, final BiConsumer<String, String> observeBoth) {
         final ThrowingRunnable<?> run = calls::incrementAndGet;
         final ThrowingConsumer<String, ?> accept = value -> run.run();
         final ThrowingBiConsumer<String, String, ?> acceptBoth = (value, other) -> {
            observeBoth.accept(value, other);
            run.run();
         };
         final ThrowingFunction<String, String, ?> apply = value -> {
            run.run();
            return value;
         };
         final ThrowingBiFunction<String, String, String, ?> combine = (value, other) -> {
            observeBoth.accept(value, other);
            run.run();
            return value + other;
         };
         // Explicit standard types ensure this matrix exercises both overload families, not just the more specific throwing overloads.
         switch (this) {
            case COMBINE:
               if (throwing)
                  return entry == EntryPoint.SYNC ? left.thenCombine(right, combine)
                        : entry == EntryPoint.ASYNC_DEFAULT ? left.thenCombineAsync(right, combine)
                              : left.thenCombineAsync(right, combine, executor);
               final BiFunction<String, String, String> combineFn = combine;
               return entry == EntryPoint.SYNC ? left.thenCombine(right, combineFn)
                     : entry == EntryPoint.ASYNC_DEFAULT ? left.thenCombineAsync(right, combineFn)
                           : left.thenCombineAsync(right, combineFn, executor);
            case ACCEPT_BOTH:
               if (throwing)
                  return entry == EntryPoint.SYNC ? left.thenAcceptBoth(right, acceptBoth)
                        : entry == EntryPoint.ASYNC_DEFAULT ? left.thenAcceptBothAsync(right, acceptBoth)
                              : left.thenAcceptBothAsync(right, acceptBoth, executor);
               final BiConsumer<String, String> bothAction = acceptBoth;
               return entry == EntryPoint.SYNC ? left.thenAcceptBoth(right, bothAction)
                     : entry == EntryPoint.ASYNC_DEFAULT ? left.thenAcceptBothAsync(right, bothAction)
                           : left.thenAcceptBothAsync(right, bothAction, executor);
            case RUN_AFTER_BOTH:
               if (throwing)
                  return entry == EntryPoint.SYNC ? left.runAfterBoth(right, run)
                        : entry == EntryPoint.ASYNC_DEFAULT ? left.runAfterBothAsync(right, run)
                              : left.runAfterBothAsync(right, run, executor);
               final Runnable bothRun = run;
               return entry == EntryPoint.SYNC ? left.runAfterBoth(right, bothRun)
                     : entry == EntryPoint.ASYNC_DEFAULT ? left.runAfterBothAsync(right, bothRun)
                           : left.runAfterBothAsync(right, bothRun, executor);
            case APPLY_EITHER:
               if (throwing)
                  return entry == EntryPoint.SYNC ? left.applyToEither(right, apply)
                        : entry == EntryPoint.ASYNC_DEFAULT ? left.applyToEitherAsync(right, apply)
                              : left.applyToEitherAsync(right, apply, executor);
               final Function<String, String> applyFn = apply;
               return entry == EntryPoint.SYNC ? left.applyToEither(right, applyFn)
                     : entry == EntryPoint.ASYNC_DEFAULT ? left.applyToEitherAsync(right, applyFn)
                           : left.applyToEitherAsync(right, applyFn, executor);
            case ACCEPT_EITHER:
               if (throwing)
                  return entry == EntryPoint.SYNC ? left.acceptEither(right, accept)
                        : entry == EntryPoint.ASYNC_DEFAULT ? left.acceptEitherAsync(right, accept)
                              : left.acceptEitherAsync(right, accept, executor);
               final Consumer<String> action = accept;
               return entry == EntryPoint.SYNC ? left.acceptEither(right, action)
                     : entry == EntryPoint.ASYNC_DEFAULT ? left.acceptEitherAsync(right, action)
                           : left.acceptEitherAsync(right, action, executor);
            case RUN_AFTER_EITHER:
               if (throwing)
                  return entry == EntryPoint.SYNC ? left.runAfterEither(right, run)
                        : entry == EntryPoint.ASYNC_DEFAULT ? left.runAfterEitherAsync(right, run)
                              : left.runAfterEitherAsync(right, run, executor);
               final Runnable eitherRun = run;
               return entry == EntryPoint.SYNC ? left.runAfterEither(right, eitherRun)
                     : entry == EntryPoint.ASYNC_DEFAULT ? left.runAfterEitherAsync(right, eitherRun)
                           : left.runAfterEitherAsync(right, eitherRun, executor);
            default:
               throw new AssertionError(this);
         }
      }
   }

   /** Captures the flag arriving at an input before that input applies its own interruption policy. */
   private static final class RecordingFuture extends ExtendedFuture<String> {
      private int cancellations;
      private boolean interruptRequested;

      RecordingFuture(final boolean cancellable, final boolean interruptibleStages, final Executor executor) {
         super(cancellable, interruptibleStages, executor);
      }

      @Override
      public boolean cancel(final boolean mayInterruptIfRunning) {
         cancellations++;
         interruptRequested = mayInterruptIfRunning;
         return super.cancel(mayInterruptIfRunning);
      }

      void assertCancellation(final boolean expected, final boolean mayInterrupt) {
         assertThat(isCancelled()).isEqualTo(expected);
         assertThat(cancellations).isEqualTo(expected ? 1 : 0);
         if (expected) {
            assertThat(interruptRequested).isEqualTo(mayInterrupt);
         }
      }
   }

   /** Forces native stage construction to observe source completion after the dependent's source link has been created. */
   private static final class CompletingFactoryFuture extends ExtendedFuture<String> {
      private final CompletionState outcome;
      private final Throwable failure = new IllegalStateException("source");
      private ExtendedFuture<?> created = this;

      CompletingFactoryFuture(final boolean interruptibleStages, final CompletionState outcome) {
         super(true, interruptibleStages, Runnable::run);
         this.outcome = outcome;
      }

      @Override
      public <V> ExtendedFuture<V> newIncompleteFuture() {
         final var result = super.<V>newIncompleteFuture();
         // An already-completed source would skip link creation and let a broken cleanup implementation pass this test.
         assertThat(result.cancellablePrecedingStages).containsExactly(this);
         created = result;
         // This is the same window as concurrent completion, but does not depend on scheduler timing.
         if (outcome == CompletionState.SUCCESS) {
            complete("source");
         } else if (outcome == CompletionState.FAILED) {
            completeExceptionally(failure);
         } else {
            cancel(false);
         }
         return result;
      }
   }

   /** Exposes the actual dependent during inline execution, before the public factory call returns it. */
   private static final class ResultPublishingFuture extends ExtendedFuture<String> {
      private final AtomicReference<ExtendedFuture<?>> latest = new AtomicReference<>();

      ResultPublishingFuture(final boolean interruptibleStages) {
         super(true, interruptibleStages, Runnable::run);
         complete("source");
      }

      @Override
      public <V> ExtendedFuture<V> newIncompleteFuture() {
         final var result = super.<V>newIncompleteFuture();
         latest.set(result);
         return result;
      }
   }

   /** Lets tests choose the exact callback execution point without relying on worker timing. */
   private static final class QueuedExecutor implements Executor {
      private final ArrayDeque<Runnable> tasks = new ArrayDeque<>();

      @Override
      public void execute(final Runnable command) {
         tasks.add(command);
      }

      void runAll() {
         while (!tasks.isEmpty()) {
            tasks.remove().run();
         }
      }
   }

   static Stream<Arguments> binaryEntryPoints() {
      return Stream.of(BinaryOperation.values()).flatMap(operation -> Stream.of(EntryPoint.values()).flatMap(entry -> Stream.of(false, true)
         .map(throwing -> Objects.requireNonNull(Arguments.of(operation, entry, throwing)))));
   }

   static Stream<Arguments> bothEntryPoints() {
      return binaryEntryPoints().filter(arguments -> !((BinaryOperation) arguments.get()[0]).isEither());
   }

   static Stream<Arguments> compositionEntryPoints() {
      return Stream.of(EntryPoint.values()).flatMap(entry -> Stream.of(false, true).map(throwing -> Objects.requireNonNull(Arguments.of(
         entry, throwing))));
   }

   @ParameterizedTest
   @MethodSource("binaryEntryPoints")
   void testBinaryCancellationPermissions(final BinaryOperation operation, final EntryPoint entry, final boolean throwing) {
      for (final boolean interruptibleStages : List.of(false, true)) {
         for (final boolean mayInterrupt : List.of(false, true)) {
            for (final boolean leftOptIn : List.of(false, true)) {
               for (final boolean rightOptIn : List.of(false, true)) {
                  final var executor = new QueuedExecutor();
                  final var calls = new AtomicInteger();
                  final var left = new RecordingFuture(leftOptIn, interruptibleStages, executor);
                  final var right = new RecordingFuture(rightOptIn, true, executor);
                  final var result = operation.create(left, right, entry, throwing, executor, calls);
                  assertThat(result.isInterruptible()).isEqualTo(interruptibleStages);
                  assertThat(result.isCancellableByDependents()).isEqualTo(leftOptIn);
                  assertThat(result.defaultExecutor()).isSameAs(executor);

                  assertThat(result.cancel(mayInterrupt)).isTrue();
                  left.assertCancellation(leftOptIn, mayInterrupt);
                  right.assertCancellation(rightOptIn, mayInterrupt);
                  executor.runAll();
                  assertThat(calls).hasValue(0);
                  assertThat(result.cancellablePrecedingStages).isEmpty();
               }
            }
         }
      }
   }

   @ParameterizedTest
   @MethodSource("binaryEntryPoints")
   void testBinaryCompletionAndExecutors(final BinaryOperation operation, final EntryPoint entry, final boolean throwing) {
      for (final boolean interruptibleStages : List.of(false, true)) {
         final var defaultExecutor = new QueuedExecutor();
         final var explicitExecutor = new QueuedExecutor();
         final var calls = new AtomicInteger();
         final var left = new RecordingFuture(false, interruptibleStages, defaultExecutor);
         final var right = new RecordingFuture(true, true, defaultExecutor);
         final var result = operation.create(left, right, entry, throwing, explicitExecutor, calls);
         left.complete("left");
         if (!operation.isEither()) {
            right.complete("right");
         }
         assertThat(calls).hasValue(entry == EntryPoint.SYNC ? 1 : 0);
         (entry == EntryPoint.ASYNC_EXPLICIT ? defaultExecutor : explicitExecutor).runAll();
         assertThat(calls).hasValue(entry == EntryPoint.SYNC ? 1 : 0);
         (entry == EntryPoint.ASYNC_EXPLICIT ? explicitExecutor : defaultExecutor).runAll();
         assertThat(calls).hasValue(1);
         assertThat(result.isSuccess()).isTrue();
         // Natural completion bypasses the public complete methods; a retained result must release its old cancellation inputs.
         assertThat(result.cancellablePrecedingStages).isEmpty();
         assertThat(result.cancel(true)).isFalse();
         right.assertCancellation(false, false);
         if (operation.isEither()) {
            // Ordinary completion of an either stage must not cancel its losing input.
            assertThat(right).isNotCompleted();
         }
      }
   }

   @ParameterizedTest
   @MethodSource("binaryEntryPoints")
   void testBinaryExceptionalCompletionReleasesCancellationLinks(final BinaryOperation operation, final EntryPoint entry,
         final boolean throwing) {
      for (final boolean interruptibleStages : List.of(false, true)) {
         final var executor = new QueuedExecutor();
         final var calls = new AtomicInteger();
         final var left = new RecordingFuture(false, interruptibleStages, executor);
         final var right = new RecordingFuture(true, true, executor);
         final var result = operation.create(left, right, entry, throwing, executor, calls);
         assertThat(result.cancellablePrecedingStages).containsExactly(right);
         final var failure = new IllegalStateException("source");
         left.completeExceptionally(failure);
         if (!operation.isEither()) {
            right.complete("right");
         }
         executor.runAll();
         assertThatThrownBy(result::join).hasCause(failure);
         assertThat(calls).hasValue(0);
         assertThat(result.cancellablePrecedingStages).isEmpty();
         right.assertCancellation(false, false);
         if (operation.isEither()) {
            assertThat(right).isNotCompleted();
         }
      }
   }

   @ParameterizedTest
   @MethodSource("bothEntryPoints")
   void testBothPreservePlainInputCancellation(final BinaryOperation operation, final EntryPoint entry, final boolean throwing) {
      for (final boolean interruptibleStages : List.of(false, true)) {
         for (final boolean completedOther : List.of(false, true)) {
            for (final boolean wrappedCancellation : List.of(false, true)) {
               final var executor = new QueuedExecutor();
               final var calls = new AtomicInteger();
               final var left = new RecordingFuture(true, interruptibleStages, executor);
               // ExtendedFuture inputs bypass the adapter under test, even if the variable is typed as CompletableFuture.
               final var right = new CompletableFuture<String>();
               final var cancellation = new CancellationException("original input cancellation");
               final Throwable failure = wrappedCancellation ? new CompletionException("original completion failure", cancellation)
                     : cancellation;
               if (completedOther) {
                  right.completeExceptionally(failure);
               }
               final var result = operation.create(left, right, entry, throwing, executor, calls);
               left.complete("left");
               right.completeExceptionally(failure);
               executor.runAll();

               final var nativeResult = CompletableFuture.completedFuture("left").thenCombine(right, (value, other) -> value + other);
               final var expected = Objects.requireNonNull(catchThrowable(nativeResult::join));
               final var actual = Objects.requireNonNull(catchThrowable(result::join));
               assertThat(actual).isInstanceOf(CompletionException.class);
               assertThat(actual.getCause()).isSameAs(expected.getCause());
               if (wrappedCancellation) {
                  assertThat(actual).isSameAs(expected);
               }
               assertThat(result.isCancelled()).isFalse();
               assertThat(calls).hasValue(0);
               assertThat(result.cancellablePrecedingStages).isEmpty();
            }
         }
      }
   }

   @ParameterizedTest
   @MethodSource("bothEntryPoints")
   void testBothObservePlainInputObtrusion(final BinaryOperation operation, final EntryPoint entry, final boolean throwing) {
      for (final boolean interruptibleStages : List.of(false, true)) {
         for (final boolean completedOther : List.of(false, true)) {
            for (final var outcome : List.of(CompletionState.SUCCESS, CompletionState.FAILED, CompletionState.CANCELLED)) {
               final var executor = new QueuedExecutor();
               final var calls = new AtomicInteger();
               final var observedOther = new AtomicReference<String>();
               final var left = new RecordingFuture(true, interruptibleStages, executor);
               final var right = new CompletableFuture<String>();
               if (completedOther) {
                  right.complete("old");
               }
               final var result = operation.create(left, right, entry, throwing, executor, calls, (value, other) -> observedOther.set(
                  other));
               final var nativeLeft = new CompletableFuture<String>();
               final var nativeResult = nativeLeft.thenCombine(right, (value, other) -> value + other);
               right.complete("old");
               // Neither callback can have started: both receivers are still pending. This is not a concurrent-obtrusion guarantee.
               if (outcome == CompletionState.SUCCESS) {
                  right.obtrudeValue("new");
               } else {
                  right.obtrudeException(outcome == CompletionState.FAILED ? new IllegalStateException("new failure")
                        : new CancellationException("new cancellation"));
               }
               left.complete("left");
               nativeLeft.complete("left");
               executor.runAll();

               if (outcome == CompletionState.SUCCESS) {
                  assertThat(result.isSuccess()).isTrue();
                  assertThat(calls).hasValue(1);
                  if (operation == BinaryOperation.COMBINE) {
                     assertThat(result.join()).isEqualTo(nativeResult.join());
                  }
                  if (operation != BinaryOperation.RUN_AFTER_BOTH) {
                     // Completion alone would miss thenAcceptBoth receiving the adapter's stale input value.
                     assertThat(observedOther).hasValue("new");
                  }
               } else {
                  assertThat(result).isCompletedExceptionally();
                  final var expected = Objects.requireNonNull(catchThrowable(nativeResult::join));
                  final var actual = Objects.requireNonNull(catchThrowable(result::join));
                  assertThat(actual).isInstanceOf(CompletionException.class);
                  assertThat(actual.getCause()).isSameAs(expected.getCause());
                  assertThat(calls).hasValue(0);
               }
               assertThat(result.cancellablePrecedingStages).isEmpty();
            }
         }
      }
   }

   @ParameterizedTest
   @MethodSource("bothEntryPoints")
   void testBothUseReceiverFactoryWithPlainInput(final BinaryOperation operation, final EntryPoint entry, final boolean throwing) {
      for (final boolean interruptibleStages : List.of(false, true)) {
         for (final boolean completedInputs : List.of(false, true)) {
            final var defaultExecutor = new QueuedExecutor();
            final var explicitExecutor = new QueuedExecutor();
            final var created = new ArrayList<ExtendedFuture<?>>();
            final var left = new ExtendedFuture<String>(true, interruptibleStages, defaultExecutor) {
               @Override
               public <V> ExtendedFuture<V> newIncompleteFuture() {
                  final var result = super.<V>newIncompleteFuture();
                  created.add(result);
                  return result;
               }
            };
            final var right = new CompletableFuture<String>() {
               @Override
               public <V> CompletableFuture<V> newIncompleteFuture() {
                  throw new AssertionError("The other input must not supply an internal adapter or dependent factory");
               }

               @Override
               public Executor defaultExecutor() {
                  throw new AssertionError("The other input must not supply the executor");
               }
            };
            if (completedInputs) {
               left.complete("left");
               right.complete("right");
            }
            final var calls = new AtomicInteger();
            final var result = operation.create(left, right, entry, throwing, explicitExecutor, calls);
            assertThat(created).containsExactly(result);
            assertThat(result.isInterruptible()).isEqualTo(interruptibleStages);
            assertThat(result.isCancellableByDependents()).isTrue();
            assertThat(result.defaultExecutor()).isSameAs(defaultExecutor);
            left.complete("left");
            right.complete("right");
            (entry == EntryPoint.ASYNC_EXPLICIT ? defaultExecutor : explicitExecutor).runAll();
            assertThat(calls).hasValue(entry == EntryPoint.SYNC ? 1 : 0);
            (entry == EntryPoint.ASYNC_EXPLICIT ? explicitExecutor : defaultExecutor).runAll();
            // Native stages capture callback failures, so factory identity alone does not prove that ownership binding worked.
            assertThat(result.isSuccess()).isTrue();
            assertThat(calls).hasValue(1);
            assertThat(result.cancellablePrecedingStages).isEmpty();
         }
      }
   }

   @ParameterizedTest
   @MethodSource("bothEntryPoints")
   void testBothCancellationDoesNotOptInPlainInput(final BinaryOperation operation, final EntryPoint entry, final boolean throwing) {
      for (final boolean interruptibleStages : List.of(false, true)) {
         for (final boolean mayInterrupt : List.of(false, true)) {
            final var executor = new QueuedExecutor();
            final var left = new RecordingFuture(true, interruptibleStages, executor);
            final var right = new CompletableFuture<String>();
            final var calls = new AtomicInteger();
            final var result = operation.create(left, right, entry, throwing, executor, calls);
            assertThat(result.cancellablePrecedingStages).containsExactly(left);
            assertThat(result.cancel(mayInterrupt)).isTrue();
            left.assertCancellation(true, mayInterrupt);
            assertThat(right).isNotCompleted();
            right.complete("cleanup");
            executor.runAll();
            assertThat(calls).hasValue(0);
            assertThat(result.cancellablePrecedingStages).isEmpty();
         }
      }
   }

   @ParameterizedTest
   @EnumSource(BinaryOperation.class)
   void testBinaryInputBoundaries(final BinaryOperation operation) {
      for (final boolean interruptibleStages : List.of(false, true)) {
         final var left = new RecordingFuture(true, interruptibleStages, Runnable::run);
         final var sameInput = operation.create(left, left, EntryPoint.SYNC, false, Runnable::run, new AtomicInteger());
         assertThat(sameInput.cancellablePrecedingStages).containsExactly(left);
         sameInput.cancel(true);
         left.assertCancellation(true, true);

         final var backing = new RecordingFuture(true, true, Runnable::run);
         for (final CompletionStage<String> right : List.of(new CompletableFuture<String>(), backing.asReadOnly(
            ReadOnlyMode.THROW_ON_MUTATION), backing.asReadOnly(ReadOnlyMode.IGNORE_MUTATION))) {
            final var source = new RecordingFuture(true, interruptibleStages, Runnable::run);
            final var result = operation.create(source, right, EntryPoint.SYNC, false, Runnable::run, new AtomicInteger());
            result.cancel(true);
            assertThat(right.toCompletableFuture()).isNotCompleted();
            backing.assertCancellation(false, false);
         }

         final var completed = new RecordingFuture(true, true, Runnable::run);
         completed.complete("ready");
         final var source = new RecordingFuture(false, interruptibleStages, Runnable::run);
         final var result = operation.create(source, completed, EntryPoint.SYNC, false, Runnable::run, new AtomicInteger());
         assertThat(result.cancellablePrecedingStages).isEmpty();
         result.cancel(true);
         completed.assertCancellation(false, false);

         if (operation.isEither()) {
            final var failedSource = new RecordingFuture(false, interruptibleStages, Runnable::run);
            final var pending = new RecordingFuture(true, true, Runnable::run);
            failedSource.completeExceptionally(new IllegalStateException("source"));
            final var failed = operation.create(failedSource, pending, EntryPoint.SYNC, false, Runnable::run, new AtomicInteger());
            assertThat(failed).isCompletedExceptionally();
            assertThat(failed.cancellablePrecedingStages).isEmpty();
            pending.assertCancellation(false, false);
         }
      }
   }

   @ParameterizedTest
   @MethodSource("compositionEntryPoints")
   void testCompositionCancellationHandoff(final EntryPoint entry, final boolean throwing) {
      for (final boolean interruptibleStages : List.of(false, true)) {
         for (final boolean alreadyCompleted : List.of(false, true)) {
            for (final boolean inline : List.of(false, true)) {
               for (final boolean mayInterrupt : List.of(false, true)) {
                  final var queue = new QueuedExecutor();
                  final Executor executor = inline ? Runnable::run : queue;
                  final var source = new RecordingFuture(false, interruptibleStages, executor);
                  final var nested = new RecordingFuture(true, true, executor);
                  if (alreadyCompleted) {
                     source.complete("source");
                  }
                  final var result = entry.compose(source, value -> nested, throwing, executor);
                  source.complete("source");
                  queue.runAll();
                  assertThat(result.cancellablePrecedingStages).containsExactly(nested);
                  assertThat(result.isInterruptible()).isEqualTo(interruptibleStages);
                  assertThat(result.defaultExecutor()).isSameAs(executor);
                  result.cancel(mayInterrupt);
                  nested.assertCancellation(true, mayInterrupt);
                  assertThat(result.cancellablePrecedingStages).isEmpty();
               }
            }
         }
      }
   }

   @ParameterizedTest
   @MethodSource("compositionEntryPoints")
   void testCompositionCompletionReleasesCancellationLinks(final EntryPoint entry, final boolean throwing) {
      for (final boolean interruptibleStages : List.of(false, true)) {
         for (final boolean alreadyCompleted : List.of(false, true)) {
            for (final boolean exceptional : List.of(false, true)) {
               final var executor = new QueuedExecutor();
               final var source = new RecordingFuture(false, interruptibleStages, executor);
               final var nested = new RecordingFuture(true, true, executor);
               if (alreadyCompleted) {
                  source.complete("source");
               }
               final var result = entry.compose(source, value -> nested, throwing, executor);
               source.complete("source");
               executor.runAll();
               assertThat(result.cancellablePrecedingStages).containsExactly(nested);
               if (exceptional) {
                  final var failure = new IllegalStateException("nested");
                  nested.completeExceptionally(failure);
                  assertThatThrownBy(result::join).hasCause(failure);
               } else {
                  nested.complete("nested");
                  assertThat(result).isCompletedWithValue("nested");
               }
               assertThat(result.cancellablePrecedingStages).isEmpty();
               nested.assertCancellation(false, false);
            }
         }
      }
   }

   @ParameterizedTest
   @MethodSource("compositionEntryPoints")
   void testCompositionInputBoundaries(final EntryPoint entry, final boolean throwing) {
      for (final boolean interruptibleStages : List.of(false, true)) {
         final var source = new RecordingFuture(false, interruptibleStages, Runnable::run);
         source.complete("source");
         final var optedOut = new RecordingFuture(false, true, Runnable::run);
         final var backing = new RecordingFuture(true, true, Runnable::run);
         for (final CompletionStage<String> nested : List.of(optedOut, new CompletableFuture<String>(), backing.asReadOnly(
            ReadOnlyMode.THROW_ON_MUTATION), backing.asReadOnly(ReadOnlyMode.IGNORE_MUTATION))) {
            final var result = entry.compose(source, value -> nested, throwing, Runnable::run);
            assertThat(result.cancellablePrecedingStages).isEmpty();
            result.cancel(true);
            assertThat(nested.toCompletableFuture()).isNotCompleted();
            backing.assertCancellation(false, false);
         }
         final var completed = new RecordingFuture(true, true, Runnable::run);
         completed.complete("nested");
         final var result = entry.compose(source, value -> completed, throwing, Runnable::run);
         assertThat(result).isCompletedWithValue("nested");
         assertThat(result.cancellablePrecedingStages).isEmpty();
         final var failure = new IllegalStateException("mapper");
         final var failed = entry.compose(source, value -> {
            throw failure;
         }, throwing, Runnable::run);
         assertThatThrownBy(failed::join).cause().isSameAs(failure);
         final var nullStage = entry.compose(source, value -> sneakyNull(), throwing, Runnable::run);
         assertThatThrownBy(nullStage::join).hasCauseInstanceOf(NullPointerException.class);
      }
   }

   @ParameterizedTest
   @MethodSource("compositionEntryPoints")
   void testCompositionCancelledBeforeMapper(final EntryPoint entry, final boolean throwing) {
      for (final boolean interruptibleStages : List.of(false, true)) {
         final var queue = new QueuedExecutor();
         final var source = new RecordingFuture(false, interruptibleStages, queue);
         final var calls = new AtomicInteger();
         final var result = entry.compose(source, value -> {
            calls.incrementAndGet();
            return new ExtendedFuture<>();
         }, throwing, queue);
         result.cancel(true);
         source.complete("source");
         queue.runAll();
         assertThat(calls).hasValue(0);
      }
   }

   @ParameterizedTest
   @EnumSource(EntryPoint.class)
   void testCompositionLateNestedStage(final EntryPoint entry) throws Exception {
      for (final boolean interruptibleStages : List.of(false, true)) {
         for (final boolean mayInterrupt : List.of(false, true)) {
            for (final boolean optIn : List.of(false, true)) {
               final var executor = Executors.newSingleThreadExecutor();
               final var started = new CountDownLatch(1);
               final var release = new CountDownLatch(1);
               final var interrupted = new AtomicBoolean();
               try {
                  final var source = new RecordingFuture(false, interruptibleStages, executor);
                  final var nested = new RecordingFuture(optIn, true, executor);
                  final var result = entry.compose(source, value -> {
                     started.countDown();
                     boolean released = false;
                     while (!released) {
                        try {
                           released = release.await(15, TimeUnit.SECONDS);
                           assertThat(released).isTrue();
                        } catch (final InterruptedException ex) {
                           // Return the nested stage only after cancellation has finished, even when the mapper was interrupted.
                           interrupted.set(true);
                        }
                     }
                     return nested;
                  }, false, executor);
                  executor.execute(() -> source.complete("source"));
                  assertThat(started.await(MAX_WAIT_SECS, TimeUnit.SECONDS)).isTrue();
                  result.cancel(mayInterrupt);
                  release.countDown();
                  // A barrier on the same worker waits for registration, not merely for the mapper body to return.
                  executor.submit(() -> true).get(MAX_WAIT_SECS, TimeUnit.SECONDS);
                  nested.assertCancellation(optIn, mayInterrupt);
                  assertThat(interrupted.get()).isEqualTo(interruptibleStages && mayInterrupt);
                  assertThat(result.cancellablePrecedingStages).isEmpty();
               } finally {
                  release.countDown();
                  executor.shutdownNow();
                  assertThat(executor.awaitTermination(MAX_WAIT_SECS, TimeUnit.SECONDS)).isTrue();
               }
            }
         }
      }
   }

   @ParameterizedTest
   @CsvSource({"false,false,false", "false,false,true", "false,true,false", "false,true,true", "true,false,false", "true,false,true",
      "true,true,false", "true,true,true"})
   void testRunningInputInterruption(final boolean compose, final boolean interruptibleStages, final boolean mayInterrupt)
         throws Exception {
      final var executor = Executors.newSingleThreadExecutor();
      final var started = new CountDownLatch(1);
      final var release = new CountDownLatch(1);
      final var finished = new CountDownLatch(1);
      final var interrupted = new AtomicBoolean();
      try {
         final var input = ExtendedFuture.builder(String.class).withCancellableByDependents(true).build();
         input.completeAsync(() -> {
            started.countDown();
            try {
               assertThat(release.await(15, TimeUnit.SECONDS)).isTrue();
            } catch (final InterruptedException ex) {
               interrupted.set(true);
            } finally {
               finished.countDown();
            }
            return "input";
         }, executor);
         assertThat(started.await(MAX_WAIT_SECS, TimeUnit.SECONDS)).isTrue();
         final var source = new RecordingFuture(false, interruptibleStages, Runnable::run);
         source.complete("source");
         final var result = compose ? source.thenCompose(value -> input) : source.thenCombine(input, (left, right) -> left + right);
         result.cancel(mayInterrupt);
         assertThat(input).isCancelled();
         if (!mayInterrupt) {
            assertThat(finished.getCount()).isOne();
            release.countDown();
         }
         assertThat(finished.await(MAX_WAIT_SECS, TimeUnit.SECONDS)).isTrue();
         assertThat(interrupted.get()).isEqualTo(mayInterrupt);
      } finally {
         release.countDown();
         executor.shutdownNow();
         assertThat(executor.awaitTermination(MAX_WAIT_SECS, TimeUnit.SECONDS)).isTrue();
      }
   }

   @ParameterizedTest
   @CsvSource({"SYNC,false", "SYNC,true", "ASYNC_DEFAULT,false", "ASYNC_DEFAULT,true", "ASYNC_EXPLICIT,false", "ASYNC_EXPLICIT,true"})
   void testInlineCompositionTerminalOwner(final EntryPoint entry, final boolean interruptibleStages) {
      for (final boolean mayInterrupt : List.of(false, true)) {
         final var source = new ResultPublishingFuture(interruptibleStages);
         final var nested = new RecordingFuture(true, true, Runnable::run);
         try {
            final var result = entry.compose(source, value -> {
               // Cancel the actual dependent before the inline mapper returns its still-pending nested stage.
               assertThat(Objects.requireNonNull(source.latest.get()).cancel(mayInterrupt)).isTrue();
               return nested;
            }, false, Runnable::run);
            assertThat(result).isSameAs(source.latest.get()).isCancelled();
            nested.assertCancellation(true, mayInterrupt);
            assertThat(result.cancellablePrecedingStages).isEmpty();
            assertThat(Thread.currentThread().isInterrupted()).isEqualTo(interruptibleStages && mayInterrupt);
         } finally {
            // The direct executor runs on the test thread; do not leak this test's cancel(true) interrupt to later tests.
            Thread.interrupted();
         }
      }
      for (final boolean exceptional : List.of(false, true)) {
         final var source = new ResultPublishingFuture(interruptibleStages);
         final var nested = new RecordingFuture(true, true, Runnable::run);
         final var result = entry.compose(source, value -> {
            final var actualResult = Objects.requireNonNull(source.latest.get());
            if (exceptional) {
               actualResult.completeExceptionally(new IllegalStateException("explicit completion"));
            } else {
               actualResult.complete(sneakyNull());
            }
            return nested;
         }, false, Runnable::run);
         assertThat(result.isDone()).isTrue();
         assertThat(result.isFailed()).isEqualTo(exceptional);
         nested.assertCancellation(false, false);
         assertThat(result.cancellablePrecedingStages).isEmpty();
      }
      final var source = new ResultPublishingFuture(interruptibleStages);
      final var selfComposed = entry.compose(source, value -> {
         @SuppressWarnings("unchecked") // This factory call creates a String dependent; no other dependent is created by this mapper.
         final var actualResult = (ExtendedFuture<String>) Objects.requireNonNull(source.latest.get());
         return actualResult;
      }, false, Runnable::run);
      assertThat(selfComposed.cancellablePrecedingStages).isEmpty();
      selfComposed.cancel(false);
      assertThat(selfComposed).isCancelled();
   }

   @ParameterizedTest
   @EnumSource(EntryPoint.class)
   void testCompositionRejectsNullMapperImmediately(final EntryPoint entry) {
      for (final boolean interruptibleStages : List.of(false, true)) {
         final var source = new RecordingFuture(false, interruptibleStages, Runnable::run);
         final Function<String, CompletionStage<String>> fn = sneakyNull();
         final ThrowingFunction<String, CompletionStage<String>, ?> throwingFn = sneakyNull();
         switch (entry) {
            case SYNC:
               assertThatNullPointerException().isThrownBy(() -> source.thenCompose(fn));
               assertThatNullPointerException().isThrownBy(() -> source.thenCompose(throwingFn));
               break;
            case ASYNC_DEFAULT:
               assertThatNullPointerException().isThrownBy(() -> source.thenComposeAsync(fn));
               assertThatNullPointerException().isThrownBy(() -> source.thenComposeAsync(throwingFn));
               break;
            case ASYNC_EXPLICIT:
               assertThatNullPointerException().isThrownBy(() -> source.thenComposeAsync(fn, Runnable::run));
               assertThatNullPointerException().isThrownBy(() -> source.thenComposeAsync(throwingFn, Runnable::run));
               assertThatNullPointerException().isThrownBy(() -> source.thenComposeAsync(value -> new ExtendedFuture<>(), sneakyNull()));
               break;
         }
      }
   }

   @ParameterizedTest
   @ValueSource(booleans = {false, true})
   void testLosingCompletionPreservesCancellationLinks(final boolean exceptional) {
      final var source = new RecordingFuture(true, false, Runnable::run);
      final var result = source.thenApply(Function.identity());
      final var observer = result.whenComplete((value, error) -> {
         // CompletableFuture invokes this inline after publishing cancellation, before ExtendedFuture.cancel drains its links.
         final boolean won = exceptional ? result.completeExceptionally(new IllegalStateException("loser")) : result.complete("loser");
         assertThat(won).isFalse();
      });
      result.cancel(true);
      source.assertCancellation(true, true);
      assertThatThrownBy(observer::join).hasCauseInstanceOf(java.util.concurrent.CancellationException.class);
   }

   @ParameterizedTest
   @ValueSource(booleans = {false, true})
   void testCancellationObserverObtrusionPreservesForwarding(final boolean interruptibleStages) {
      for (final boolean exceptional : List.of(false, true)) {
         for (final boolean mayInterrupt : List.of(false, true)) {
            final var source = new RecordingFuture(true, interruptibleStages, Runnable::run);
            final var result = source.thenApply(Function.identity());
            final var failure = new IllegalStateException("forced");
            final BiConsumer<String, Throwable> observer = (value, error) -> {
               // This runs inside super.cancel, before upstream forwarding. Replacing the outcome must not undo that request.
               if (exceptional) {
                  result.obtrudeException(failure);
               } else {
                  result.obtrudeValue("forced");
               }
            };
            result.whenComplete(observer);
            assertThat(result.cancel(mayInterrupt)).isTrue();
            source.assertCancellation(true, mayInterrupt);
            assertThat(result.cancellablePrecedingStages).isEmpty();
            if (exceptional) {
               assertThatThrownBy(result::join).hasCause(failure);
            } else {
               assertThat(result).isCompletedWithValue("forced");
            }
         }
      }
   }

   @ParameterizedTest
   @EnumSource(value = CompletionState.class, names = {"SUCCESS", "FAILED", "CANCELLED"})
   void testDirectFactoryForcedCompletionReleasesLinks(final CompletionState outcome) {
      for (final boolean interruptibleStages : List.of(false, true)) {
         final var source = new RecordingFuture(true, interruptibleStages, Runnable::run);
         final var result = source.<String>newIncompleteFuture();
         assertThat(result.cancellablePrecedingStages).containsExactly(source);
         if (outcome == CompletionState.SUCCESS) {
            result.obtrudeValue("forced");
            assertThat(result).isCompletedWithValue("forced");
         } else {
            final var failure = outcome == CompletionState.CANCELLED ? new CancellationException("forced")
                  : new IllegalStateException("forced");
            result.obtrudeException(failure);
            // Check lifetime before any further observation can hide a missing direct-factory cleanup hook.
            assertThat(result.cancellablePrecedingStages).isEmpty();
            if (outcome == CompletionState.CANCELLED) {
               assertThat(result).isCancelled();
               assertThatThrownBy(result::join).isInstanceOf(CancellationException.class);
               // JDK 25 can wrap cancellation on join; handle exposes the stored exception without that version-dependent wrapper.
               assertThat(result.handle((value, error) -> error).join()).isSameAs(failure);
            } else {
               assertThatThrownBy(result::join).hasCause(failure);
            }
         }
         // Do not complete the source or adapt the result first: either would hide missing direct-factory cleanup.
         assertThat(result.cancellablePrecedingStages).isEmpty();
         // An obtruded CancellationException is an outcome, not an upstream cancellation request.
         source.assertCancellation(false, false);
      }
   }

   @Test
   void testRejectedDirectFactoryObtrusionPreservesLinks() {
      final var source = new RecordingFuture(true, false, Runnable::run);
      final var result = source.<String>newIncompleteFuture();
      assertThatNullPointerException().isThrownBy(() -> result.obtrudeException(sneakyNull()));
      assertThat(result).isNotCompleted();
      assertThat(result.cancellablePrecedingStages).containsExactly(source);
      result.cancel(false);
      source.assertCancellation(true, false);
   }

   @Test
   void testRejectedExceptionalCompletionPreservesCancellationLinks() {
      final var source = new RecordingFuture(true, false, Runnable::run);
      final var result = source.thenApply(Function.identity());
      assertThatNullPointerException().isThrownBy(() -> result.completeExceptionally(sneakyNull()));
      assertThat(result).isNotCompleted();
      result.cancel(false);
      source.assertCancellation(true, false);
   }

   @ParameterizedTest
   @ValueSource(booleans = {false, true})
   void testSuccessfulCompletionClearsCancellationLinks(final boolean exceptional) {
      final var source = new RecordingFuture(true, false, Runnable::run);
      final var result = source.thenApply(Function.identity());
      assertThat(exceptional ? result.completeExceptionally(new IllegalStateException("result")) : result.complete("result")).isTrue();
      assertThat(result.cancellablePrecedingStages).isEmpty();
      source.assertCancellation(false, false);
   }

   @ParameterizedTest
   @EnumSource(value = CompletionState.class, names = {"SUCCESS", "FAILED", "CANCELLED"})
   void testNativeCompletionClearsSourceLinks(final CompletionState outcome) {
      for (final boolean interruptibleStages : List.of(false, true)) {
         final var source = new RecordingFuture(true, interruptibleStages, Runnable::run);
         final Function<String, String> mapper = value -> "dependent";
         final var calls = new AtomicInteger();
         final List<ExtendedFuture<?>> results = List.of(source.thenApply(mapper), source.thenApplyAsync(mapper), source.thenApplyAsync(
            mapper, Runnable::run), source.thenAccept(value -> calls.incrementAndGet()), source.thenRun(calls::incrementAndGet), source
               .handle((value, error) -> "handled"), source.whenComplete((value, error) -> calls.incrementAndGet()), source.exceptionally(
                  error -> "recovered"), source.thenCompose(value -> CompletableFuture.completedFuture("composed")), source.thenCombine(
                     CompletableFuture.completedFuture("other"), (value, other) -> "combined"), source.copy());
         for (final var result : results) {
            assertThat(result.cancellablePrecedingStages).containsExactly(source);
         }

         // Complete the source, not its dependents: JDK stage propagation bypasses the public complete overrides.
         switch (outcome) {
            case SUCCESS:
               source.complete("source");
               break;
            case FAILED:
               source.completeExceptionally(new IllegalStateException("source"));
               break;
            case CANCELLED:
               source.cancel(false);
               break;
            default:
               throw new AssertionError(outcome);
         }
         for (final var result : results) {
            assertThat(result).isDone();
            // Inspect the strong links directly; GC timing is not part of the contract.
            assertThat(result.cancellablePrecedingStages).isEmpty();
         }
         assertThat(calls).hasValue(outcome == CompletionState.SUCCESS ? 3 : 1);
      }
   }

   @ParameterizedTest
   @CsvSource({"false,false", "false,true", "true,false", "true,true"})
   void testNativeCompletionReleasesLongChain(final boolean plainAnyOf, final boolean interruptibleStages) {
      final var source = new ExtendedFuture<String>(true, interruptibleStages, Runnable::run);
      final var results = new ArrayList<ExtendedFuture<?>>();
      ExtendedFuture<?> tail = source;
      for (int index = 0; index < 10_000; index++) {
         tail = plainAnyOf ? (ExtendedFuture<?>) CompletableFuture.anyOf(tail) : tail.thenApply(value -> "dependent");
         results.add(tail);
      }
      source.complete("source");
      // Cleanup must remain part of native propagation, without recursively completing another ExtendedFuture.
      assertThat(tail.join()).isEqualTo(plainAnyOf ? "source" : "dependent");
      for (final var result : results) {
         assertThat(result.cancellablePrecedingStages).isEmpty();
      }
   }

   @ParameterizedTest
   @CsvSource({"false,false", "false,true", "true,false", "true,true"})
   void testNativeCompletionDuringFactory(final boolean copy, final boolean interruptibleStages) {
      final BiFunction<String, String, String> combine = (left, right) -> "combined";
      for (final var outcome : List.of(CompletionState.SUCCESS, CompletionState.FAILED, CompletionState.CANCELLED)) {
         final var source = new CompletingFactoryFuture(interruptibleStages, outcome);
         final var result = copy ? source.copy() : source.thenCombine(CompletableFuture.completedFuture("other"), combine);
         assertThat(result).isSameAs(source.created).isDone();
         if (outcome == CompletionState.SUCCESS) {
            assertThat(result).isCompletedWithValue(copy ? "source" : "combined");
         } else if (outcome == CompletionState.FAILED) {
            assertThatThrownBy(result::join).hasCause(source.failure);
         } else {
            assertThat(result.isCancelled()).isFalse();
            assertThatThrownBy(result::join).hasCauseInstanceOf(java.util.concurrent.CancellationException.class);
         }
         assertThat(result.cancellablePrecedingStages).isEmpty();
         // Clearing only the links is insufficient: a factory-installed observer can remain stranded on the completed result.
         assertThat(result.getNumberOfDependents()).isZero();
      }
   }

   @ParameterizedTest
   @EnumSource(BinaryOperation.class)
   void testBinaryInputsShareCleanupObserver(final BinaryOperation operation) {
      for (final boolean interruptibleStages : List.of(false, true)) {
         for (final var entry : EntryPoint.values()) {
            for (final boolean throwing : List.of(false, true)) {
               final var left = new RecordingFuture(true, interruptibleStages, Runnable::run);
               final var right = new RecordingFuture(true, true, Runnable::run);
               final var result = operation.create(left, right, entry, throwing, Runnable::run, new AtomicInteger());
               assertThat(result.cancellablePrecedingStages).containsExactly(left, right);
               // No user observers or concurrent callbacks exist here, so this count isolates internal cleanup subscriptions.
               assertThat(result.getNumberOfDependents()).isOne();
               left.complete("left");
               right.complete("right");
               assertThat(result).isDone();
               assertThat(result.cancellablePrecedingStages).isEmpty();
               assertThat(result.getNumberOfDependents()).isZero();
            }
         }
      }
   }

   @ParameterizedTest
   @EnumSource(EntryPoint.class)
   void testComposedInputsShareCleanupObserver(final EntryPoint entry) {
      for (final boolean interruptibleStages : List.of(false, true)) {
         for (final boolean throwing : List.of(false, true)) {
            final var source = new RecordingFuture(true, interruptibleStages, Runnable::run);
            final var nested = new RecordingFuture(true, true, Runnable::run);
            final var result = entry.compose(source, value -> nested, throwing, Runnable::run);
            source.complete("source");
            // Source completion now releases its factory edge immediately; only the still-pending nested input needs cancellation.
            assertThat(result.cancellablePrecedingStages).containsExactly(nested);
            assertThat(result.getNumberOfDependents()).isOne();
            nested.complete("nested");
            assertThat(result).isCompletedWithValue("nested");
            assertThat(result.cancellablePrecedingStages).isEmpty();
            assertThat(result.getNumberOfDependents()).isZero();
         }
      }
   }

   @ParameterizedTest
   @CsvSource({"false,false", "false,true", "true,false", "true,true"})
   void testAnyOfCompletionDuringFactory(final boolean plainAnyOf, final boolean interruptibleStages) {
      for (final var outcome : List.of(CompletionState.SUCCESS, CompletionState.FAILED, CompletionState.CANCELLED)) {
         final var source = new CompletingFactoryFuture(interruptibleStages, outcome);
         // Calling only ExtendedFuture.anyOf would let its from adapter hide a broken external-factory path.
         final var result = plainAnyOf ? CompletableFuture.anyOf(source) : ExtendedFuture.anyOf(source);
         assertThat(result).isDone();
         assertThat(source.created).isDone();
         if (outcome == CompletionState.SUCCESS) {
            assertThat(result.join()).isEqualTo("source");
            assertThat(source.created.join()).isEqualTo("source");
         } else if (outcome == CompletionState.FAILED) {
            assertThatThrownBy(result::join).hasCause(source.failure);
            assertThatThrownBy(source.created::join).hasCause(source.failure);
         } else {
            assertThat(source.created.isCancelled()).isFalse();
            assertThatThrownBy(source.created::join).hasCauseInstanceOf(CancellationException.class);
            assertThat(result.isCancelled()).isEqualTo(!plainAnyOf);
         }
         // Inspect the factory-created backing too: an empty wrapper queue would hide retention in the native aggregate.
         assertThat(source.created.cancellablePrecedingStages).isEmpty();
         assertThat(source.created.getNumberOfDependents()).isZero();
      }
   }

   @ParameterizedTest
   @EnumSource(value = CompletionState.class, names = {"SUCCESS", "FAILED", "CANCELLED"})
   void testPlainAnyOfCompletionReleasesSourceLinks(final CompletionState outcome) {
      for (final boolean interruptibleStages : List.of(false, true)) {
         final var source = new RecordingFuture(true, interruptibleStages, Runnable::run);
         final var result = (ExtendedFuture<?>) CompletableFuture.anyOf(source);
         assertThat(result.cancellablePrecedingStages).containsExactly(source);
         if (outcome == CompletionState.SUCCESS) {
            source.complete("source");
            assertThat(result.join()).isEqualTo("source");
         } else if (outcome == CompletionState.FAILED) {
            final var failure = new IllegalStateException("source");
            source.completeExceptionally(failure);
            assertThatThrownBy(result::join).hasCause(failure);
         } else {
            source.cancel(false);
            assertThatThrownBy(result::join).hasCauseInstanceOf(CancellationException.class);
         }
         assertThat(result.cancellablePrecedingStages).isEmpty();
         assertThat(result.getNumberOfDependents()).isZero();
      }
   }

   @ParameterizedTest
   @ValueSource(booleans = {false, true})
   void testDirectFactoryAsyncCompletionSharesCleanupObserver(final boolean interruptibleStages) {
      for (final boolean defaultExecutor : List.of(false, true)) {
         for (final boolean throwing : List.of(false, true)) {
            for (final boolean exceptional : List.of(false, true)) {
               final var executor = new QueuedExecutor();
               final var source = new RecordingFuture(true, interruptibleStages, executor);
               // No stage-return adapter is involved, so completeAsync must establish cleanup itself.
               final var result = source.<String>newIncompleteFuture();
               final var calls = new AtomicInteger();
               final var failure = new IllegalStateException("supplier");
               final ThrowingSupplier<String, ?> task = () -> {
                  calls.incrementAndGet();
                  if (exceptional)
                     throw failure;
                  return "async";
               };
               for (int attempt = 0; attempt < 2; attempt++) {
                  if (throwing) {
                     assertThat(defaultExecutor ? result.completeAsync(task) : result.completeAsync(task, executor)).isSameAs(result);
                  } else {
                     final Supplier<String> supplier = task::get;
                     assertThat(defaultExecutor ? result.completeAsync(supplier) : result.completeAsync(supplier, executor)).isSameAs(
                        result);
                  }
               }
               assertThat(result.cancellablePrecedingStages).containsExactly(source);
               assertThat(result.getNumberOfDependents()).isOne();
               executor.runAll();
               assertThat(calls).hasValue(1);
               if (exceptional) {
                  assertThatThrownBy(result::join).hasCause(failure);
               } else {
                  assertThat(result).isCompletedWithValue("async");
               }
               assertThat(result.cancellablePrecedingStages).isEmpty();
               assertThat(result.getNumberOfDependents()).isZero();
               source.assertCancellation(false, false);
            }
         }
      }
   }
}
