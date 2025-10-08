/*
 * SPDX-FileCopyrightText: © Sebastian Thomschke
 * SPDX-License-Identifier: EPL-2.0
 * SPDX-FileContributor: Sebastian Thomschke: initial API and implementation
 */
package io.github.futures4j;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Tests to verify the cancellation behaviour of Java's {@link java.util.concurrent.FutureTask}.
 *
 * @author <a href="https://sebthom.de/">Sebastian Thomschke</a>
 */
class FutureTaskTest extends AbstractFutureTest {

   final ExecutorService executor = Executors.newSingleThreadExecutor();

   @AfterEach
   void tearDown() {
      executor.shutdownNow();
   }

   @Test
   void testSingleStageCancel() throws InterruptedException {
      testSingleStageCancel(executor::submit, true);
   }
}
