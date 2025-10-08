/*
 * SPDX-FileCopyrightText: © Sebastian Thomschke
 * SPDX-License-Identifier: EPL-2.0
 * SPDX-FileContributor: Sebastian Thomschke: initial API and implementation
 */
package io.github.futures4j;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

/**
 * Verifies that {@link ExtendedFuture.WrappingFuture} properly delegates mutating APIs
 * to the wrapped future. These tests would fail if delegation was missing.
 *
 * @author <a href="https://sebthom.de/">Sebastian Thomschke</a>
 */
@SuppressWarnings("javadoc")
class WrappingFutureDelegationTest extends AbstractFutureTest {

   @Test
   void cancel_delegates_to_wrapped() throws Exception {
      final var base = new ExtendedFuture<String>();
      final var wrapper = newWrapper(base);

      final boolean res = wrapper.cancel(true);
      assertThat(res).isTrue();

      // Wrapped and wrapper must both become CANCELLED
      awaitFutureState(base, CompletionState.CANCELLED);
      assertThat(base).isCancelled();
      awaitFutureState(wrapper, CompletionState.CANCELLED);
      assertThat(wrapper).isCancelled();
   }

   @Test
   void complete_delegates_to_wrapped() throws Exception {
      final var base = new ExtendedFuture<String>();
      final var wrapper = newWrapper(base);

      final boolean res = wrapper.complete("complete");
      assertThat(res).isTrue();

      awaitFutureState(base, CompletionState.SUCCESS);
      assertThat(base).isCompletedWithValue("complete");
      awaitFutureState(wrapper, CompletionState.SUCCESS);
      assertThat(wrapper).isCompletedWithValue("complete");
   }

   @Test
   void completeAsync_supplier_delegates_to_wrapped() throws Exception {
      final var base = new ExtendedFuture<String>();
      final var wrapper = newWrapper(base);

      final var ret = wrapper.completeAsync(() -> "completeAsync");
      assertThat(ret).isSameAs(wrapper);

      awaitFutureState(base, CompletionState.SUCCESS);
      assertThat(base).isCompletedWithValue("completeAsync");
      awaitFutureState(wrapper, CompletionState.SUCCESS);
      assertThat(wrapper).isCompletedWithValue("completeAsync");
   }

   @Test
   void completeAsync_supplier_executor_delegates_to_wrapped() throws Exception {
      final var base = new ExtendedFuture<String>();
      final var wrapper = newWrapper(base);

      final var ret = wrapper.completeAsync(() -> "completeAsync2", Runnable::run);
      assertThat(ret).isSameAs(wrapper);

      awaitFutureState(base, CompletionState.SUCCESS);
      assertThat(base).isCompletedWithValue("completeAsync2");
      awaitFutureState(wrapper, CompletionState.SUCCESS);
      assertThat(wrapper).isCompletedWithValue("completeAsync2");
   }

   @Test
   void completeAsync_throwingSupplier_delegates_to_wrapped() throws Exception {
      final var base = new ExtendedFuture<String>();
      final var wrapper = newWrapper(base);

      final var ret = wrapper.completeAsync((io.github.futures4j.util.ThrowingSupplier<String, ?>) () -> "completeAsync3");
      assertThat(ret).isSameAs(wrapper);

      awaitFutureState(base, CompletionState.SUCCESS);
      assertThat(base).isCompletedWithValue("completeAsync3");
      awaitFutureState(wrapper, CompletionState.SUCCESS);
      assertThat(wrapper).isCompletedWithValue("completeAsync3");
   }

   @Test
   void completeAsync_throwingSupplier_executor_delegates_to_wrapped() throws Exception {
      final var base = new ExtendedFuture<String>();
      final var wrapper = newWrapper(base);

      final var ret = wrapper.completeAsync((io.github.futures4j.util.ThrowingSupplier<String, ?>) () -> "completeAsync4", Runnable::run);
      assertThat(ret).isSameAs(wrapper);

      awaitFutureState(base, CompletionState.SUCCESS);
      assertThat(base).isCompletedWithValue("completeAsync4");
      awaitFutureState(wrapper, CompletionState.SUCCESS);
      assertThat(wrapper).isCompletedWithValue("completeAsync4");
   }

   @Test
   void completeExceptionally_delegates_to_wrapped() throws Exception {
      final var base = new ExtendedFuture<String>();
      final var wrapper = newWrapper(base);

      final boolean res = wrapper.completeExceptionally(new RuntimeException("completeExceptionally"));
      assertThat(res).isTrue();

      awaitFutureState(base, CompletionState.FAILED);
      assertThat(base).isCompletedExceptionally();
      awaitFutureState(wrapper, CompletionState.FAILED);
      assertThat(wrapper).isCompletedExceptionally();
   }

   @Test
   void completeOnTimeout_delegates_to_wrapped() throws Exception {
      final var base = new ExtendedFuture<String>();
      final var wrapper = newWrapper(base);

      final var order = new java.util.concurrent.CopyOnWriteArrayList<String>();
      base.whenComplete((r, ex) -> order.add("base:" + (ex == null)));
      wrapper.whenComplete((r, ex) -> order.add("wrapper:" + (ex == null)));

      // If delegation is missing, wrapper completes first, then base (via overridden complete)
      // If delegation exists, base completes first, then wrapper (via whenComplete)
      wrapper.completeOnTimeout("value", 0, TimeUnit.MILLISECONDS);

      await(() -> order.size() == 2);
      assertThat(order).containsExactly("base:true", "wrapper:true");
   }

   @Test
   void completeWith_delegates_to_wrapped() throws Exception {
      final var base = new ExtendedFuture<String>();
      final var wrapper = newWrapper(base);

      final var src = new java.util.concurrent.CompletableFuture<String>();
      final var ret = wrapper.completeWith(src);
      assertThat(ret).isSameAs(wrapper);

      src.complete("completeWith");

      awaitFutureState(base, CompletionState.SUCCESS);
      assertThat(base).isCompletedWithValue("completeWith");
      awaitFutureState(wrapper, CompletionState.SUCCESS);
      assertThat(wrapper).isCompletedWithValue("completeWith");
   }

   private ExtendedFuture<String> newWrapper(final ExtendedFuture<String> base) {
      // Force creation of a WrappingFuture by changing the default executor
      final var wrapper = base.withDefaultExecutor(Runnable::run);
      assertThat(wrapper).isNotSameAs(base);
      return wrapper;
   }

   @Test
   void obtrudeException_delegates_to_wrapped() throws Exception {
      final var base = new ExtendedFuture<String>();
      final var wrapper = newWrapper(base);

      wrapper.obtrudeException(new RuntimeException("obtrudeException"));

      awaitFutureState(base, CompletionState.FAILED);
      assertThat(base).isCompletedExceptionally();
      assertThat(wrapper).isCompletedExceptionally();
   }

   @Test
   void obtrudeValue_delegates_to_wrapped() throws Exception {
      final var base = new ExtendedFuture<String>();
      final var wrapper = newWrapper(base);

      wrapper.obtrudeValue("obtrudeValue");

      awaitFutureState(base, CompletionState.SUCCESS);
      assertThat(base).isCompletedWithValue("obtrudeValue");
      assertThat(wrapper).isCompletedWithValue("obtrudeValue");
   }

   @Test
   void orTimeout_delegates_to_wrapped() throws Exception {
      final var base = new ExtendedFuture<String>();
      final var wrapper = newWrapper(base);

      final var order = new java.util.concurrent.CopyOnWriteArrayList<String>();
      base.whenComplete((r, ex) -> order.add("base:" + (ex == null)));
      wrapper.whenComplete((r, ex) -> order.add("wrapper:" + (ex == null)));

      wrapper.orTimeout(0, TimeUnit.MILLISECONDS);

      await(() -> order.size() == 2);
      assertThat(order).containsExactly("base:false", "wrapper:false");
   }
}
