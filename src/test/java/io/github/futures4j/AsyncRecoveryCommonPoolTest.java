/*
 * SPDX-FileCopyrightText: © Sebastian Thomschke
 * SPDX-License-Identifier: EPL-2.0
 */
package io.github.futures4j;

import static org.assertj.core.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ForkJoinWorkerThread;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Verifies async recovery's native executor policy under low common-pool parallelism without changing the test runner's pool.
 *
 * @author futures4j contributors
 */
class AsyncRecoveryCommonPoolTest {

   @ParameterizedTest
   @ValueSource(ints = {0, 1})
   void testCommonPoolFallback(final int parallelism, @TempDir final Path temporaryDirectory) throws Exception {
      final var javaHome = Objects.requireNonNull(System.getProperty("java.home"));
      final var classpath = Objects.requireNonNull(System.getProperty("java.class.path"));
      final var javaExecutable = Path.of(javaHome, "bin", System.getProperty("os.name", "").startsWith("Windows") ? "java.exe" : "java");
      final var output = temporaryDirectory.resolve("common-pool.log");
      // Pool configuration is read once by the JVM. A property change inside an ordinary unit test would not exercise the fallback.
      final var process = new ProcessBuilder(javaExecutable.toString(), "-Dfile.encoding=UTF-8",
         "-Djava.util.concurrent.ForkJoinPool.common.parallelism=" + parallelism, "-cp", classpath, AsyncRecoveryCommonPoolTest.class
            .getName()).redirectErrorStream(true).redirectOutput(output.toFile()).start();
      try {
         assertThat(process.waitFor(30, TimeUnit.SECONDS)).as("common-pool probe must terminate").isTrue();
         assertThat(process.exitValue()).withFailMessage("Common-pool probe failed:%n%s", Files.readString(output)).isZero();
      } finally {
         if (process.isAlive()) {
            process.destroyForcibly();
            assertThat(process.waitFor(5, TimeUnit.SECONDS)).as("probe cleanup must terminate the child JVM").isTrue();
         }
      }
   }

   /** Child-JVM entry point; native mapping establishes the expected executor policy for the current JDK. */
   public static void main(final String[] args) throws Exception {
      final var executor = ForkJoinPool.commonPool();
      // Check startup configuration before CompletableFuture can raise the common pool's parallelism.
      assertThat(ForkJoinPool.getCommonPoolParallelism()).isOne();
      // JDK 25 uses common-pool workers at low parallelism; older JDKs use per-task fallback threads.
      // Observe a native stage so recovery must match this runtime's policy without version-specific assertions.
      final var controlWorker = new AtomicReference<Thread>();
      final var control = CompletableFuture.completedFuture("control").thenApplyAsync(value -> {
         controlWorker.set(Thread.currentThread());
         return value;
      }, executor);
      assertThat(control.get(3, TimeUnit.SECONDS)).isEqualTo("control");
      final var nativeWorker = Objects.requireNonNull(controlWorker.get());
      final var expectedPool = nativeWorker instanceof ForkJoinWorkerThread ? ((ForkJoinWorkerThread) nativeWorker).getPool() : null;

      for (final var entry : TaskExecutionTrackingTest.asyncRecoveryEntryPoints()) {
         for (final boolean interruptible : List.of(false, true)) {
            for (final boolean alreadyFailed : List.of(false, true)) {
               final var source = new ExtendedFuture<String>(false, interruptible, executor);
               final var failure = new IllegalStateException("source");
               if (alreadyFailed) {
                  source.completeExceptionally(failure);
               }
               final var worker = new AtomicReference<Thread>();
               final var result = entry.recover(source, error -> {
                  worker.set(Thread.currentThread());
                  return "recovered";
               }, executor);
               source.completeExceptionally(failure);
               assertThat(result.get(3, TimeUnit.SECONDS)).isEqualTo("recovered");
               final var recoveryWorker = Objects.requireNonNull(worker.get());
               final var recoveryPool = recoveryWorker instanceof ForkJoinWorkerThread ? ((ForkJoinWorkerThread) recoveryWorker).getPool()
                     : null;
               // Match pool identity (or neither using a pool), not thread identity: either policy may use different workers.
               assertThat(recoveryPool).as("recovery must match the native executor policy").isSameAs(expectedPool);
            }
         }
      }
   }
}
