/*
 * SPDX-FileCopyrightText: © Sebastian Thomschke
 * SPDX-License-Identifier: EPL-2.0
 * SPDX-FileContributor: Codex CLI Agent: failing test for cancel intent propagation
 */
package io.github.futures4j;

import static org.assertj.core.api.Assertions.*;

import java.util.concurrent.CancellationException;

import org.junit.jupiter.api.Test;

/**
 * Failing test exposing that WrappingFuture.completeWith(...) does not preserve the original
 * mayInterrupt intent on the wrapped future when the wrapper is non-interruptible.
 *
 * @author <a href="https://sebthom.de/">Sebastian Thomschke</a>
 */
class WrappingCompleteWithIntentTest extends AbstractFutureTest {

   @Test
   @SuppressWarnings("null")
   void completeWith_cancellation_should_preserve_interrupt_intent_on_wrapped() {
      // Backing/wrapped future (interruptible stages). The concrete instance of returned stages is irrelevant;
      // we check the cancel intent recorded on the wrapped when cancellation is propagated via completeWith.
      final ExtendedFuture<String> backing = ExtendedFuture.builder(String.class).withInterruptible(true).build();

      // Create a non-interruptible wrapper view around the backing future
      final ExtendedFuture<String> wrapper = backing.asNonInterruptible();

      // Source future that will be cancelled with interrupt intent
      final ExtendedFuture<String> source = ExtendedFuture.builder(String.class).withInterruptible(true).build();

      // Propagate completion/cancellation from source → wrapper (and thus to backing)
      wrapper.completeWith(source);

      // Cancel the source with interrupt intent
      source.cancel(true);

      // Both wrapper and backing are expected to be cancelled
      assertThat(wrapper).isCancelled();
      assertThat(backing).isCancelled();
      assertThatThrownBy(wrapper::join).isInstanceOf(CancellationException.class);

      // EXPECTED (fails today): the original cancel intent from source (true) should be preserved on the wrapped
      assertThat(backing.getCancelInterruptIntentOrDefault(false)).as("cancel intent should be preserved on wrapped").isTrue();
   }
}
