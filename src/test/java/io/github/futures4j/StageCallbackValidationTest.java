/*
 * SPDX-FileCopyrightText: © Sebastian Thomschke
 * SPDX-License-Identifier: EPL-2.0
 */
package io.github.futures4j;

import static net.sf.jstuff.core.validation.NullAnalysisHelper.sneakyNull;
import static org.assertj.core.api.Assertions.*;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.eclipse.jdt.annotation.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import io.github.futures4j.util.ThrowingBiConsumer;
import io.github.futures4j.util.ThrowingBiFunction;
import io.github.futures4j.util.ThrowingConsumer;
import io.github.futures4j.util.ThrowingFunction;
import io.github.futures4j.util.ThrowingRunnable;

/**
 * Verifies eager callback validation in adapted stage families, including exact standard and throwing overload coverage.
 *
 * @author futures4j contributors
 */
class StageCallbackValidationTest {

   enum Operation {
      HANDLE("handle", false, BiFunction.class),
      RUN_AFTER_BOTH("runAfterBoth", true, Runnable.class, ThrowingRunnable.class),
      THEN_ACCEPT("thenAccept", false, Consumer.class, ThrowingConsumer.class),
      THEN_ACCEPT_BOTH("thenAcceptBoth", true, BiConsumer.class, ThrowingBiConsumer.class),
      THEN_APPLY("thenApply", false, Function.class, ThrowingFunction.class),
      THEN_COMBINE("thenCombine", true, BiFunction.class, ThrowingBiFunction.class),
      THEN_RUN("thenRun", false, Runnable.class, ThrowingRunnable.class),
      WHEN_COMPLETE("whenComplete", false, BiConsumer.class, ThrowingBiConsumer.class);

      final String methodName;
      final boolean binary;
      final List<Class<?>> callbackTypes;

      Operation(final String methodName, final boolean binary, final Class<?>... callbackTypes) {
         this.methodName = methodName;
         this.binary = binary;
         this.callbackTypes = List.of(callbackTypes);
      }
   }

   private static List<Method> callbackMethods() throws NoSuchMethodException {
      final var methods = new ArrayList<Method>();
      for (final var operation : Operation.values()) {
         for (final var callbackType : operation.callbackTypes) {
            final var parameters = new ArrayList<Class<?>>();
            if (operation.binary) {
               parameters.add(CompletionStage.class);
            }
            parameters.add(callbackType);
            // Exact signatures avoid Java's preference for the more-specific throwing overload when given a bare lambda or null.
            methods.add(ExtendedFuture.class.getDeclaredMethod(operation.methodName, parameters.toArray(Class<?>[]::new)));
            methods.add(ExtendedFuture.class.getDeclaredMethod(operation.methodName + "Async", parameters.toArray(Class<?>[]::new)));
            parameters.add(Executor.class);
            methods.add(ExtendedFuture.class.getDeclaredMethod(operation.methodName + "Async", parameters.toArray(Class<?>[]::new)));
         }
      }
      return methods;
   }

   static Stream<Arguments> nullCallbackCases() throws NoSuchMethodException {
      return callbackMethods().stream().flatMap(method -> Arrays.stream(CompletionState.values()).flatMap(state -> Stream.of(false, true)
         .map(interruptible -> Objects.requireNonNull(Arguments.of(method, state, interruptible)))));
   }

   static Stream<Arguments> validCallbackCases() throws NoSuchMethodException {
      return callbackMethods().stream().flatMap(method -> Stream.of(false, true).map(interruptible -> Objects.requireNonNull(Arguments.of(
         method, interruptible))));
   }

   private static ExtendedFuture<String> newSource(final boolean interruptible, final Executor executor, final AtomicInteger creations) {
      return new ExtendedFuture<>(false, interruptible, executor) {
         @Override
         public <V> ExtendedFuture<V> newIncompleteFuture() {
            creations.incrementAndGet();
            return super.newIncompleteFuture();
         }
      };
   }

   private static @Nullable Object invoke(final Method method, final ExtendedFuture<String> source, final @Nullable Object callback,
         final Executor executor) throws ReflectiveOperationException {
      final List<@Nullable Object> arguments = new ArrayList<>();
      final var parameters = method.getParameterTypes();
      if (parameters[0] == CompletionStage.class) {
         arguments.add(CompletableFuture.completedFuture("other"));
      }
      // Pass the actual null through; a non-null adapter would test a failing callback instead of argument validation.
      arguments.add(callback);
      if (parameters[parameters.length - 1] == Executor.class) {
         arguments.add(executor);
      }
      return method.invoke(source, arguments.toArray());
   }

   private static Object validCallback(final Class<?> callbackType, final AtomicInteger calls) {
      // Throwing interfaces extend the standard ones. Reflection still selects the exact overload from the manifest above.
      if (Runnable.class.isAssignableFrom(callbackType))
         return (ThrowingRunnable<?>) calls::incrementAndGet;
      if (Consumer.class.isAssignableFrom(callbackType))
         return (ThrowingConsumer<@Nullable Object, ?>) value -> calls.incrementAndGet();
      if (BiConsumer.class.isAssignableFrom(callbackType))
         return (ThrowingBiConsumer<@Nullable Object, @Nullable Object, ?>) (value, other) -> calls.incrementAndGet();
      if (Function.class.isAssignableFrom(callbackType))
         return (ThrowingFunction<@Nullable Object, @Nullable Object, ?>) value -> {
            calls.incrementAndGet();
            return null;
         };
      if (BiFunction.class.isAssignableFrom(callbackType))
         return (ThrowingBiFunction<@Nullable Object, @Nullable Object, @Nullable Object, ?>) (value, other) -> {
            calls.incrementAndGet();
            return null;
         };
      throw new IllegalArgumentException("Unknown callback type: " + callbackType);
   }

   @Test
   void testManifestCoversEveryOverloadInSelectedFamilies() throws NoSuchMethodException {
      final var names = Arrays.stream(Operation.values()).flatMap(operation -> Stream.of(operation.methodName, operation.methodName
            + "Async")).collect(Collectors.toSet());
      // Exclude compiler-generated covariant bridges, but require new public overloads in these families to join the test matrix.
      final var declared = Arrays.stream(ExtendedFuture.class.getDeclaredMethods()).filter(method -> Modifier.isPublic(method
         .getModifiers()) && !method.isBridge() && !method.isSynthetic() && names.contains(method.getName())).collect(Collectors.toList());
      assertThat(callbackMethods()).isNotEmpty().doesNotHaveDuplicates().containsExactlyInAnyOrderElementsOf(declared);
   }

   @ParameterizedTest(name = "{0}, state={1}, interruptible={2}")
   @MethodSource("nullCallbackCases")
   void testNullCallbackRejectedBeforeStageCreation(final Method method, final CompletionState state, final boolean interruptible) {
      final var submissions = new AtomicInteger();
      final Executor executor = command -> {
         submissions.incrementAndGet();
         command.run();
      };
      final var creations = new AtomicInteger();
      final var source = newSource(interruptible, executor, creations);
      switch (state) {
         case SUCCESS:
            source.complete("value");
            break;
         case FAILED:
            source.completeExceptionally(new IllegalStateException("source"));
            break;
         case CANCELLED:
            source.cancel(false);
            break;
         default:
            break;
      }
      // Require an NPE from the invoked method, not an unrelated reflection/setup failure.
      assertThatExceptionOfType(InvocationTargetException.class).isThrownBy(() -> invoke(method, source, null, executor))
         .withCauseExactlyInstanceOf(NullPointerException.class);
      assertThat(creations).as("invalid calls must not invoke the dependent factory").hasValue(0);
      assertThat(submissions).as("invalid calls must not submit executor work").hasValue(0);
   }

   @ParameterizedTest(name = "{0}, interruptible={1}")
   @MethodSource("validCallbackCases")
   void testValidCallbacksStillAllowNullResults(final Method method, final boolean interruptible) throws ReflectiveOperationException {
      final var submissions = new AtomicInteger();
      final Executor executor = command -> {
         submissions.incrementAndGet();
         command.run();
      };
      final var creations = new AtomicInteger();
      final var source = newSource(interruptible, executor, creations);
      final var parameters = method.getParameterTypes();
      final var callbackType = parameters[parameters[0] == CompletionStage.class ? 1 : 0];
      final var calls = new AtomicInteger();
      final var result = (ExtendedFuture<?>) Objects.requireNonNull(invoke(method, source, validCallback(callbackType, calls), executor));
      source.complete(sneakyNull());
      // Positive controls verify the invocation/counters and preserve nullable values despite rejecting null callback objects.
      assertThat(result).isCompletedWithValue(sneakyNull());
      assertThat(calls).hasValue(1);
      assertThat(creations).hasValue(1);
      assertThat(submissions).hasValue(method.getName().endsWith("Async") ? 1 : 0);
      assertThat(result.isInterruptible()).isEqualTo(interruptible);
   }
}
