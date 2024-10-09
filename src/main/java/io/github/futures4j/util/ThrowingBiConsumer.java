/*
 * SPDX-FileCopyrightText: © Sebastian Thomschke
 * SPDX-License-Identifier: EPL-2.0
 * SPDX-FileContributor: Sebastian Thomschke: initial API and implementation
 */
package io.github.futures4j.util;

import java.util.function.BiConsumer;

/**
 * @author <a href="https://sebthom.de/">Sebastian Thomschke</a>
 */
@FunctionalInterface
public interface ThrowingBiConsumer<A, B, X extends Throwable> extends BiConsumer<A, B> {

   @Override
   default void accept(final A a, final B b) {
      try {
         acceptOrThrow(a, b);
      } catch (final RuntimeException ex) {
         throw ex;
      } catch (final Throwable t) { // CHECKSTYLE:IGNORE IllegalCatch
         throw new RuntimeException(t);
      }
   }

   void acceptOrThrow(A a, B b) throws X;
}
