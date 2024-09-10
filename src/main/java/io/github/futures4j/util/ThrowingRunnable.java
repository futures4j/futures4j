/*
 * SPDX-FileCopyrightText: © Sebastian Thomschke
 * SPDX-License-Identifier: EPL-2.0
 * SPDX-FileContributor: Sebastian Thomschke: initial API and implementation
 */
package io.github.futures4j.util;

/**
 * @author <a href="https://sebthom.de/">Sebastian Thomschke</a>
 */
@FunctionalInterface
public interface ThrowingRunnable<X extends Throwable> extends Runnable {

   static ThrowingRunnable<RuntimeException> from(final Runnable runnable) {
      return runnable::run;
   }

   @Override
   default void run() {
      try {
         runOrThrow();
      } catch (final RuntimeException ex) {
         throw ex;
      } catch (final Throwable t) { // CHECKSTYLE:IGNORE IllegalCatch
         throw new RuntimeException(t);
      }
   }

   void runOrThrow() throws X;
}
