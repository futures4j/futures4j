/*
 * SPDX-FileCopyrightText: © Sebastian Thomschke
 * SPDX-License-Identifier: EPL-2.0
 * SPDX-FileContributor: Sebastian Thomschke: initial API and implementation
 */
package io.github.futures4j.util;

import java.util.function.Consumer;

/**
 * @author <a href="https://sebthom.de/">Sebastian Thomschke</a>
 */
@FunctionalInterface
public interface ThrowingConsumer<I, X extends Throwable> extends Consumer<I> {

   static <T> ThrowingConsumer<T, RuntimeException> from(final Consumer<T> consumer) {
      return consumer::accept;
   }

   @Override
   default void accept(final I elem) {
      try {
         acceptOrThrow(elem);
      } catch (final RuntimeException ex) {
         throw ex;
      } catch (final Throwable t) { // CHECKSTYLE:IGNORE IllegalCatch
         throw new RuntimeException(t);
      }
   }

   void acceptOrThrow(I elem) throws X;
}
