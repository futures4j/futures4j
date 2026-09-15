/*
 * SPDX-FileCopyrightText: © Sebastian Thomschke
 * SPDX-License-Identifier: EPL-2.0
 */
package io.github.futures4j;

import static net.sf.jstuff.core.validation.NullAnalysisHelper.sneakyNull;
import static org.assertj.core.api.Assertions.*;

import java.lang.ref.Reference;
import java.lang.ref.WeakReference;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;

import org.eclipse.jdt.annotation.Nullable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Verifies callback ownership and policy inheritance when stage construction reenters a factory or selects either input.
 *
 * @author futures4j contributors
 */
class StageConstructionTest {

   enum Reentry {
      RECOVERY,
      COPY,
      TRACKED,
      REJECTED_TRACKED
   }

   /** Exercises every native either entry point with typed callbacks, rather than accidentally testing only throwing overloads. */
   enum EitherEntry {
      APPLY,
      APPLY_DEFAULT,
      APPLY_EXPLICIT,
      ACCEPT,
      ACCEPT_DEFAULT,
      ACCEPT_EXPLICIT,
      RUN,
      RUN_DEFAULT,
      RUN_EXPLICIT;

      ExtendedFuture<?> start(final ExtendedFuture<Integer> source, final CompletableFuture<Integer> other,
            final Consumer<Integer> callback, final Executor executor) {
         final Function<Integer, Integer> mapper = value -> {
            callback.accept(value);
            return value + 1;
         };
         final Runnable action = () -> callback.accept(10);
         switch (this) {
            case APPLY:
               return source.applyToEither(other, mapper);
            case APPLY_DEFAULT:
               return source.applyToEitherAsync(other, mapper);
            case APPLY_EXPLICIT:
               return source.applyToEitherAsync(other, mapper, executor);
            case ACCEPT:
               return source.acceptEither(other, callback);
            case ACCEPT_DEFAULT:
               return source.acceptEitherAsync(other, callback);
            case ACCEPT_EXPLICIT:
               return source.acceptEitherAsync(other, callback, executor);
            case RUN:
               return source.runAfterEither(other, action);
            case RUN_DEFAULT:
               return source.runAfterEitherAsync(other, action);
            default:
               return source.runAfterEitherAsync(other, action, executor);
         }
      }
   }

   @ParameterizedTest
   @EnumSource(Reentry.class)
   void testReentrantFactoryPreservesOuterOwner(final Reentry reentry) {
      for (final boolean sameReceiver : List.of(false, true)) {
         final var other = ExtendedFuture.completedFuture(10);
         final var source = new ExtendedFuture<Integer>() {
            boolean insideFactory;

            @Override
            public <V> ExtendedFuture<V> newIncompleteFuture() {
               if (!insideFactory) {
                  insideFactory = true;
                  try {
                     final var nestedSource = sameReceiver ? this : other;
                     switch (reentry) {
                        case RECOVERY:
                           // This path allocates a known recovery owner without opening a tracked mapper operation.
                           assertThat(nestedSource.exceptionallyCompose(error -> CompletableFuture.completedFuture(0)).join()).isEqualTo(
                              10);
                           break;
                        case COPY:
                           assertThat(nestedSource.copy().join()).isEqualTo(10);
                           break;
                        case TRACKED:
                           assertThat(nestedSource.thenApply((Function<Integer, Integer>) value -> value + 1).join()).isEqualTo(11);
                           break;
                        default:
                           assertThatNullPointerException().isThrownBy(() -> nestedSource.thenApplyAsync(Function.identity(),
                              sneakyNull()));
                     }
                  } finally {
                     insideFactory = false;
                  }
               }
               return super.newIncompleteFuture();
            }
         };
         source.complete(10);
         assertThat(source.thenApply((Function<Integer, Integer>) value -> value + 1).join()).isEqualTo(11);
      }
   }

   @ParameterizedTest
   @ValueSource(booleans = {false, true})
   void testFactoryFailureRestoresEnclosingOperation(final boolean allocateBeforeFailure) {
      final var failure = new IllegalStateException("factory");
      final var other = new ExtendedFuture<Integer>() {
         @Override
         public <V> ExtendedFuture<V> newIncompleteFuture() {
            // Exercise failure both before binding and after the nested factory has consumed its operation's binding.
            if (allocateBeforeFailure) {
               super.newIncompleteFuture();
            }
            throw failure;
         }
      };
      other.complete(10);
      final var source = new ExtendedFuture<Integer>() {
         @Override
         public <V> ExtendedFuture<V> newIncompleteFuture() {
            assertThatThrownBy(() -> other.thenApply(Function.identity())).isSameAs(failure);
            return super.newIncompleteFuture();
         }
      };
      source.complete(10);
      assertThat(source.thenApply((Function<Integer, Integer>) value -> value + 1).join()).isEqualTo(11);
   }

   @ParameterizedTest
   @EnumSource(EitherEntry.class)
   void testCompletedNonInterruptibleOtherExecutesCallback(final EitherEntry entry) {
      final var source = new ExtendedFuture<Integer>(false, true, Runnable::run);
      final var other = ExtendedFuture.<Integer>completedFuture(10).withInterruptibleStages(false);
      final var executions = new AtomicInteger();
      // Keep callback execution separate from policy assertions so a policy failure cannot hide a missing callback owner.
      entry.start(source, other, value -> executions.incrementAndGet(), Runnable::run).join();
      assertThat(executions).hasValue(1);
   }

   @ParameterizedTest
   @EnumSource(EitherEntry.class)
   void testEitherUsesReceiverPolicies(final EitherEntry entry) {
      for (final boolean interruptible : List.of(false, true)) {
         for (final boolean otherInterruptible : List.of(false, true)) {
            for (final boolean completedOther : List.of(false, true)) {
               final Executor executor = Runnable::run;
               final var source = new ExtendedFuture<Integer>(true, interruptible, executor);
               // An ExtendedFuture is essential: conversion of a plain CompletableFuture hides the competing factory policy.
               final var other = new ExtendedFuture<Integer>(false, otherInterruptible, command -> {
                  throw new AssertionError("The other input must not select the result's executor");
               });
               if (completedOther) {
                  other.complete(10);
               }
               final var executions = new AtomicInteger();
               final var result = entry.start(source, other, value -> {
                  assertThat(value).isEqualTo(10);
                  executions.incrementAndGet();
               }, executor);
               other.complete(10);
               result.join();
               assertThat(executions).hasValue(1);
               assertThat(result.isInterruptible()).isEqualTo(interruptible);
               assertThat(result.isInterruptibleStages()).isEqualTo(interruptible);
               assertThat(result.isCancellableByDependents()).isTrue();
               assertThat(result.defaultExecutor()).isSameAs(executor);
            }
         }
      }
   }

   @ParameterizedTest
   @EnumSource(EitherEntry.class)
   void testEitherRejectsConversionFailureImmediately(final EitherEntry entry) {
      final var failure = new IllegalStateException("conversion");
      final var other = new CompletableFuture<Integer>() {
         @Override
         public CompletableFuture<Integer> toCompletableFuture() {
            throw failure;
         }
      };
      for (final boolean completed : List.of(false, true)) {
         final var source = new ExtendedFuture<Integer>(false, true, Runnable::run);
         if (completed) {
            source.complete(10);
         }
         // A conversion failure is an invocation error, not the other input's exceptional completion.
         assertThatThrownBy(() -> entry.start(source, other, value -> fail("callback must not run"), Runnable::run)).isSameAs(failure);
      }
   }

   @ParameterizedTest
   @EnumSource(EitherEntry.class)
   void testEitherPublishesTheActualInterruptionOwner(final EitherEntry entry) {
      for (final boolean interruptible : List.of(false, true)) {
         final var published = new AtomicReference<ExtendedFuture<?>>();
         final var verifiedCallbacks = new AtomicInteger();
         final var source = new ExtendedFuture<Integer>(false, interruptible, Runnable::run) {
            @Override
            public <V> ExtendedFuture<V> newIncompleteFuture() {
               final var result = super.<V>newIncompleteFuture();
               published.set(result);
               return result;
            }
         };
         final var other = ExtendedFuture.<Integer>completedFuture(10).withInterruptibleStages(!interruptible);
         try {
            final var result = entry.start(source, other, value -> {
               final var owner = Objects.requireNonNull(published.get());
               assertThat(owner.cancel(true)).isTrue();
               // Check during user code: binding after the stage method returns cannot support inline interruption.
               assertThat(Thread.currentThread().isInterrupted()).isEqualTo(interruptible);
               // Native stages capture callback failures; observe success only after every callback assertion passed.
               verifiedCallbacks.incrementAndGet();
            }, Runnable::run);
            assertThat(result).isSameAs(published.get());
            assertThat(verifiedCallbacks).hasValue(1);
         } finally {
            // The direct executor deliberately interrupts this test thread; do not pass that state to the next test.
            Thread.interrupted();
         }
      }
   }

   @ParameterizedTest
   @ValueSource(booleans = {false, true})
   void testEitherPropagationIsStackSafe(final boolean interruptible) {
      final var source = new ExtendedFuture<Integer>(false, interruptible, Runnable::run);
      var tail = source;
      for (int stage = 0; stage < 10_000; stage++) {
         // The right input remains pending during construction, so completion traverses every native operand relay.
         tail = new ExtendedFuture<Integer>(false, interruptible, Runnable::run).applyToEither(tail, Function.identity());
      }
      source.complete(10);
      assertThat(tail.join()).isEqualTo(10);
   }

   private static WeakReference<@Nullable ExtendedFuture<Integer>> completeReceiver(final CompletableFuture<Integer> other,
         final AtomicReference<ExtendedFuture<Integer>> result, final boolean interruptible) {
      final var source = new ExtendedFuture<Integer>(false, interruptible, Runnable::run);
      result.set(source.applyToEither(other, Function.identity()));
      source.complete(10);
      return new WeakReference<>(source);
   }

   @ParameterizedTest
   @ValueSource(booleans = {false, true})
   void testPendingOtherDoesNotRetainCompletedReceiver(final boolean interruptible) throws InterruptedException {
      final var other = new CompletableFuture<Integer>();
      final var result = new AtomicReference<ExtendedFuture<Integer>>();
      final var source = completeReceiver(other, result, interruptible);
      assertThat(Objects.requireNonNull(result.get()).join()).isEqualTo(10);
      final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
      // Leave the other input pending and retain the result: neither input completion nor result GC may hide an adapter link.
      while (source.get() != null && System.nanoTime() < deadline) {
         System.gc();
         Thread.sleep(20);
      }
      assertThat(source.get()).isNull();
      Reference.reachabilityFence(other);
      Reference.reachabilityFence(result);
   }
}
