/*
 * SPDX-FileCopyrightText: © Sebastian Thomschke
 * SPDX-License-Identifier: EPL-2.0
 * SPDX-FileContributor: Sebastian Thomschke: initial API and implementation
 */
package io.github.futures4j.util;

import java.util.function.Supplier;

/**
 * @author <a href="https://sebthom.de/">Sebastian Thomschke</a>
 */
@FunctionalInterface
public interface ThrowingSupplier<I, X extends Throwable> extends Supplier<I> {

   @Override
   default I get() {
      try {
         return getOrThrow();
      } catch (final RuntimeException ex) {
         throw ex;
      } catch (final Throwable t) { // CHECKSTYLE:IGNORE IllegalCatch
         throw new RuntimeException(t);
      }
   }

   I getOrThrow() throws X;
}
