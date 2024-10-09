# futures4j - Enhancements for Java's Futures

[![Build Status](https://img.shields.io/github/actions/workflow/status/futures4j/futures4j/build.yml?logo=github)](https://github.com/futures4j/futures4j/actions/workflows/build.yml)
[![Javadoc](https://img.shields.io/badge/javadoc-online-green)](https://futures4j.github.io/futures4j/javadoc/)
[![Contributor Covenant](https://img.shields.io/badge/contributor%20covenant-v2.1%20adopted-ff69b4.svg)](CODE_OF_CONDUCT.md)
[![Code Climate maintainability](https://img.shields.io/codeclimate/maintainability/futures4j/futures4j)](https://codeclimate.com/github/futures4j/futures4j/maintainability)
[![Test Coverage](https://img.shields.io/codeclimate/coverage/futures4j/futures4j)](https://codeclimate.com/github/futures4j/futures4j/test_coverage)
[![License](https://img.shields.io/github/license/futures4j/futures4j.svg?color=blue)](LICENSE.txt)
[![Maven Central](https://img.shields.io/maven-central/v/io.github.futures4j/futures4j)](https://central.sonatype.com/artifact/io.github.futures4j/futures4j)


## Table of Contents

1. [What is it?](#what-is-it)
2. [Java Compatibility](#compatibility)
3. [Usage](#usage)
   - [Binaries](#binaries)
   - [The **ExtendedFuture** class](#ExtendedFuture)
   - [The **Futures** utility class](#Futures)
   - [The **CompletionState** enum](#CompletionState)
   - [Building from sources](#building)
4. [License](#license)


## <a name="what-is-it"></a>What is it?

**futures4j** is a lightweight, dependency-free utility library designed to enhance and extend the functionality of Java's `java.util.concurrent` futures.
It simplifies working with futures, providing convenient utilities to improve readability and efficiency when handling asynchronous operations.


## <a name="compatibility"></a>Java Compatibility

- futures4j 1.1.x requires Java 11 or newer.
- futures4j 1.0.x requires Java 17 or newer.


## <a name="usage"></a>Usage

### <a name="binaries"></a>Binaries

Latest **release** binaries are available on Maven Central, see https://central.sonatype.com/artifact/io.github.futures4j/futures4j

Add futures4j as a dependency in your `pom.xml`:

```xml
<project>

  <!-- ... -->

  <dependencies>
    <dependency>
      <groupId>io.github.futures4j</groupId>
      <artifactId>futures4j</artifactId>
      <version>[LATEST_VERSION]</version>
    </dependency>
  </dependencies>
</project>
```

Replace `[LATEST_VERSION]` with the latest release version.

The latest **snapshot** binaries are available via the [mvn-snapshots-repo](https://github.com/futures4j/futures4j/tree/mvn-snapshots-repo) Git branch.
You need to add this repository configuration to your Maven `settings.xml`:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<settings xmlns="http://maven.apache.org/SETTINGS/1.2.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
   xsi:schemaLocation="http://maven.apache.org/SETTINGS/1.2.0 https://maven.apache.org/xsd/settings-1.2.0.xsd">
  <profiles>
    <profile>
      <id>futures4j-snapshot</id>
      <repositories>
        <repository>
          <id>futures4j-snapshot</id>
          <name>futures4j Snapshot Repository</name>
          <url>https://raw.githubusercontent.com/futures4j/futures4j/mvn-snapshots-repo</url>
          <releases><enabled>false</enabled></releases>
          <snapshots><enabled>true</enabled></snapshots>
        </repository>
      </repositories>
    </profile>
  </profiles>
  <activeProfiles>
    <activeProfile>futures4j-snapshot</activeProfile>
  </activeProfiles>
</settings>
```


### <a name="ExtendedFuture"></a>The `ExtendedFuture` class

The [io.github.futures4j.ExtendedFuture](src/main/java/io/github/futures4j/ExtendedFuture.java "Source code")
[ 📘 ](https://futures4j.github.io/futures4j/javadoc/io/github/futures4j/ExtendedFuture.html "JavaDoc")
serves as a drop-in replacement and enhanced version of Java's
[java.util.concurrent.CompletableFuture](https://docs.oracle.com/en/java/javase/11/docs/api/java.base/java/util/concurrent/CompletableFuture.html) class.

It offers several improvements:

1. **Supports Thread Interruption on Cancellation**

   Unlike `CompletableFuture`, `ExtendedFuture` supports task thread interruption when you cancel the future with `cancel(true)`.

   ```java
   var myFuture = ExtendedFuture.supplyAsync(...);

   myFuture.cancel(true); // Will attempt to interrupt the async task

   var myFutureNonInterruptible = myFuture.asNonInterruptible();
   myFutureNonInterruptible.cancel(true); // Behaves like CompletableFuture.cancel(true), which ignores the boolean value
   ```

   This functionality addresses issues discussed in:

   - [StackOverflow: How to interrupt underlying execution of CompletableFuture](https://stackoverflow.com/questions/29013831/how-to-interrupt-underlying-execution-of-completablefuture)
   - [nurkiewicz.com: CompletableFuture can't be interrupted](https://nurkiewicz.com/2015/03/completablefuture-cant-be-interrupted.html)
   - [tremblay.pro: Cancel CompletableFuture](https://blog.tremblay.pro/2017/08/supply-async.html)

2. **Allows Dependent Stages to Cancel Preceding Stages**

   With `ExtendedFuture`, you can enable cancellation of preceding stages by dependent stages using `asCancellableByDependents(true)`.

   ```java
   var myFuture = ExtendedFuture.supplyAsync(...);

   var myCancellableChain = myFuture
       .thenApply(...)   // Step 1
       .asCancellableByDependents(true) // allows subsequent stages cancel the previous stage
       .thenRun(...)     // Step 2
       .thenApply(...)   // Step 3
       .thenAccept(...); // Step 4

   if (shouldCancel) {
     // Cancels steps 1 to 4
     myCancellableChain.cancel(true);
   }
   ```

   This even works for nested chains:
   ```java
   var myFuture = ExtendedFuture.supplyAsync(...);

   var myCancellableChain = myFuture
       .thenApply(...)   // Step 1
       .asCancellableByDependents(true) // allows subsequent stages cancel the previous stage
       .thenRun(...)     // Step 2
       .thenApply(...)   // Step 3
       .thenCompose(x -> {
         var innerFuture = ExtendedFuture
             .runAsync(...)
             .asCancellableByDependents(true);
         return innerFuture;
       })
       .thenAccept(...); // Step 4

   if (shouldCancel) {
     // Cancels steps 1 to 4 and the innerFuture
     myCancellableChain.cancel(true);
   }
   ```

   This addresses issues discussed in:

   - [StackOverflow: Canceling a CompletableFuture chain](https://stackoverflow.com/questions/25417881/canceling-a-completablefuture-chain)
   - [StackOverflow: Cancellation of CompletableFuture controlled by ExecutorService](https://stackoverflow.com/questions/36727820/cancellation-of-completablefuture-controlled-by-executorservice)
   - [StackOverflow: Is there a better way for cancelling a chain of futures in Java?](https://stackoverflow.com/questions/62106428/is-there-a-better-way-for-cancelling-a-chain-of-futures-in-java)

3. **Allows Running Tasks That Throw Checked Exceptions**

   With `ExtendedFuture`, you can write code that throws checked exceptions without wrapping them in `UncheckedIOException` or similar.

   I.e. instead of
   ```java
   CompletableFuture.runAsync(() -> {
     try (var writer = new FileWriter("output.txt")) {
       writer.write("Hello, World!");
     } catch (IOException ex) {
       throw new UncheckedIOException(ex);
     }
   });
   ```
   You can now do:
   ```java
   ExtendedFuture.runAsync(() -> {
     try (var writer = new FileWriter("output.txt")) {
       writer.write("Hello, World!");
     }
   });
   ```

4. **Provides Read-Only Views of Futures**

   You can create read-only views of futures via `asReadOnly(ReadOnlyMode)`:

   ```java
   ExtendedFuture<?> myFuture = ExtendedFuture.supplyAsync(...);

   var readOnly1 = myFuture.asReadOnly(ReadOnlyMode.THROW_ON_MUTATION); // returns a delegating future that throws an UnsupportedException when a modification attempt is made
   var readOnly2 = myFuture.asReadOnly(ReadOnlyMode.IGNORE_MUTATION); // returns a delegating future that silently ignore modification attempts
   ```

5. **Allows Defining a Default Executor**

   You can specify a default executor for a future and its subsequent stages using
   [ExtendedFuture.createWithDefaultExecutor(Executor)](https://futures4j.github.io/futures4j/javadoc/io/github/futures4j/ExtendedFuture.html#createWithDefaultExecutor(java.util.concurrent.Executor)) or
   [ExtendedFuture.withDefaultExecutor(Executor)](https://futures4j.github.io/futures4j/javadoc/io/github/futures4j/ExtendedFuture.html#withDefaultExecutor(java.util.concurrent.Executor)):

   ```java
   // Use myDefaultExecutor for all subsequent stages by default
   var myFuture = ExtendedFuture.createWithDefaultExecutor(myDefaultExecutor);

   // Use myDefaultExecutor2 for all subsequent stages by default
   var myFuture2 = myFuture.withDefaultExecutor(myDefaultExecutor2);

   // Use myDefaultExecutor3 for execution and subsequent stages by default
   var myOtherFuture = ExtendedFuture.runAsyncWithDefaultExecutor(runnable, myDefaultExecutor3);
   ```

6. **Additional Convenience Methods**

   ```java
   ExtendedFuture<String> myFuture = ...;

   // Returns true if the future completed normally
   boolean success = myFuture.isSuccess();

   // Returns the completion state (CANCELLED, SUCCESS, FAILED, INCOMPLETE)
   CompletionState state = myFuture.getCompletionState();

   // Returns the completed value or the fallbackValue; never throws an exception
   T result = myFuture.getNowOrFallback(fallbackValue);

   // Returns the completed value or the fallbackValue after waiting; never throws an exception
   T result = myFuture.getOrFallback(10, TimeUnit.SECONDS, fallbackValue);

   // Complete a future with the same value/exception of another future
   CompletableFuture<String> otherFuture = ...;
   myFuture.completeWith(otherFuture);
   ```

7. **Backports of Future Methods from Newer Java Versions to Java 11**

   `ExtendedFuture` makes Future methods available in Java 11 that were only introduced in later Java versions, providing equivalent functionality.

   Backported from Java 12:
   - [CompletionStage#exceptionallyAsync(...)](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/util/concurrent/CompletionStage.html#exceptionallyAsync(java.util.function.Function))
   - [CompletionStage#exceptionallyCompose(...)](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/util/concurrent/CompletionStage.html#exceptionallyCompose(java.util.function.Function))
   - [CompletionStage#exceptionallyComposeAsync(...)](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/util/concurrent/CompletionStage.html#exceptionallyComposeAsync(java.util.function.Function))

   Backported from Java 19:
   - [CompletableFuture#exceptionNow()](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/util/concurrent/Future.html#exceptionNow())
   - [CompletableFuture#resultNow()](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/util/concurrent/Future.html#resultNow())


### <a name="Futures"></a>The `Futures` utility class

The [io.github.futures4j.Futures](src/main/java/io/github/futures4j/Futures.java "Source code")
[ 📘 ](https://futures4j.github.io/futures4j/javadoc/io/github/futures4j/Futures.html "JavaDoc")
class provides a set of utility methods for managing and combining Java `Future` and `CompletableFuture` instances.
It simplifies handling asynchronous computations by offering functionality to cancel, combine, and retrieve results from multiple futures.

1. **Cancelling Futures**

   - Cancel multiple futures:

     ```java
     Futures.combine(future1, future2, future3).cancelAll(true);
     ```

   - Propagate cancellations from one `CompletableFuture` to others:

     ```java
     var cancellableFuture = new CompletableFuture<>();

     var dependentFuture1 = new CompletableFuture<>();
     var dependentFuture2 = new CompletableFuture<>();

     // Propagate cancellation from cancellableFuture to dependent futures
     Futures.forwardCancellation(cancellableFuture, dependentFuture1, dependentFuture2);

     // Cancelling the primary future also cancels the dependent futures
     cancellableFuture.cancel(true);
     ```


2. **Combining Futures**

   - Combine futures into lists or sets:

     ```java
     CompletableFuture<String> future1 = CompletableFuture.completedFuture("Result 1");
     CompletableFuture<String> future2 = CompletableFuture.completedFuture("Result 2");
     CompletableFuture<String> future3 = CompletableFuture.completedFuture("Result 2");

     Futures.combine(List.of(future1, future2, future3))
       .toList()
       .thenAccept(results -> System.out.println(results)); // Outputs: [Result 1, Result 2, Result 2]

     Futures.combine(List.of(future1, future2, future3))
       .toSet()
       .thenAccept(results -> System.out.println(results)); // Outputs: [Result 1, Result 2]
     ```

   - Combine and flatten futures:

     ```java
     CompletableFuture<List<String>> future1 = CompletableFuture.completedFuture(List.of("A", "B"));
     CompletableFuture<List<String>> future2 = CompletableFuture.completedFuture(List.of("C", "D"));

     Futures.combineFlattened(List.of(future1, future2))
       .toList()
       .thenAccept(flattenedResults -> System.out.println(flattenedResults)); // Outputs: [A, B, C, D]
     ```


3. **Retrieving Results**

   - Get future result with timeout and fallback:

     ```java
     Future<String> future = ...;

     // Returns "Fallback Result" if timeout occurs, future is cancelled or completes exceptionally
     String result = Futures.getOrFallback(future, 1, TimeUnit.SECONDS, "Fallback Result");
     ```


### <a name="CompletionState"></a>The `CompletionState` enum

The [io.github.futures4j.CompletionState](src/main/java/io/github/futures4j/CompletionState.java "Source code")
[📘](https://futures4j.github.io/futures4j/javadoc/io/github/futures4j/CompletionState.html "JavaDoc")
enum allows switching on a future's completion state:

```java
Future<String> future = ...;

switch (CompletionState.of(future)) {
  case INCOMPLETE -> /* ... */;
  case SUCCESS    -> /* ... */;
  case CANCELLED  -> /* ... */;
  case FAILED     -> /* ... */;
}
```

This is similar to the [Future.State](https://docs.oracle.com/en/java/javase/19/docs/api/java.base/java/util/concurrent/Future.State.html)
enum introduced in Java 19, which allows switching like so:

```java
Future<String> future = ...;

switch (future.state()) {
  case RUNNING   -> /* ... */;
  case SUCCESS   -> /* ... */;
  case CANCELLED -> /* ... */;
  case FAILED    -> /* ... */;
}
```


### <a id="building"></a>Building from Sources

To ensure reproducible builds, this [Maven](https://books.sonatype.com/mvnref-book/reference/index.html) project inherits from the
[vegardit-maven-parent](https://github.com/vegardit/vegardit-maven-parent) project, which declares fixed versions and sensible
default settings for all official Maven plugins.

The project also uses the [maven-toolchains-plugin](http://maven.apache.org/plugins/maven-toolchains-plugin/), which decouples the
JDK used to execute Maven and its plugins from the target JDK used for compilation and unit testing.
This ensures full binary compatibility of the compiled artifacts with the runtime library of the required target JDK.

To build the project, follow these steps:

1. **Install Java 11 *AND* Java 17 JDKs**

   Download and install the JDKs from e.g.:
   - Java 11: https://adoptium.net/releases.html?variant=openjdk11 or https://www.azul.com/downloads/?version=java-11-lts&package=jdk#zulu
   - Java 17: https://adoptium.net/releases.html?variant=openjdk17 or https://www.azul.com/downloads/?version=java-17-lts&package=jdk#zulu

2. **Configure Maven Toolchains**

   In your user home directory, create the file `.m2/toolchains.xml` with the following content:

   ```xml
   <?xml version="1.0" encoding="UTF8"?>
   <toolchains xmlns="http://maven.apache.org/TOOLCHAINS/1.1.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
       xsi:schemaLocation="http://maven.apache.org/TOOLCHAINS/1.1.0 https://maven.apache.org/xsd/toolchains-1.1.0.xsd">
     <toolchain>
       <type>jdk</type>
       <provides>
         <version>11</version>
         <vendor>openjdk</vendor>
       </provides>
       <configuration>
         <jdkHome>[PATH_TO_YOUR_JDK_11]</jdkHome>
       </configuration>
     </toolchain>
     <toolchain>
       <type>jdk</type>
       <provides>
         <version>17</version>
         <vendor>openjdk</vendor>
       </provides>
       <configuration>
         <jdkHome>[PATH_TO_YOUR_JDK_17]</jdkHome>
       </configuration>
     </toolchain>
   </toolchains>
   ```

   Replace `[PATH_TO_YOUR_JDK_11]` and `[PATH_TO_YOUR_JDK_17]` with the path to your respective JDK installations.

3. **Clone the Repository**

   ```bash
   git clone https://github.com/futures4j/futures4j.git
   ```

4. **Build the Project**

   Run `mvnw clean verify` in the project root directory.
   This will execute compilation, unit testing, integration testing, and packaging of all artifacts.


## <a name="license"></a>License

All files are released under the [Eclipse Public License 2.0](LICENSE.txt).

Individual files contain the following tag instead of the full license text:
```
SPDX-License-Identifier: EPL-2.0
```

This enables machine processing of license information based on the SPDX License Identifiers that are available here: https://spdx.org/licenses/.
