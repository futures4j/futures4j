/*
 * SPDX-FileCopyrightText: © Sebastian Thomschke
 * SPDX-License-Identifier: EPL-2.0
 * SPDX-FileContributor: Sebastian Thomschke: initial API and implementation
 */
package io.github.futures4j.util;

import java.util.function.BiFunction;

/**
 * @author <a href="https://sebthom.de/">Sebastian Thomschke</a>
 */
@FunctionalInterface
public interface ThrowingBiFunction<I1, I2, O, X extends Throwable> extends BiFunction<I1, I2, O> {

   @Override
   default O apply(final I1 arg1, final I2 arg2) {
      try {
         return applyOrThrow(arg1, arg2);
      } catch (final RuntimeException ex) {
         throw ex;
      } catch (final Throwable t) { // CHECKSTYLE:IGNORE IllegalCatch
         throw new RuntimeException(t);
      }
   }

   O applyOrThrow(I1 arg1, I2 arg2) throws X;
}
