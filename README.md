# futures4j - enhancements for Java's Futures

[![Build Status](https://github.com/futures4j/futures4j/actions/workflows/build.yml/badge.svg)](https://github.com/futures4j/futures4j/actions/workflows/build.yml)
[![Javadoc](https://img.shields.io/badge/JavaDoc-Online-green)](https://futures4j.github.io/futures4j/javadoc/)
[![License](https://img.shields.io/github/license/futures4j/futures4j.svg?color=blue)](LICENSE.txt)
[![Contributor Covenant](https://img.shields.io/badge/Contributor%20Covenant-v2.1%20adopted-ff69b4.svg)](CODE_OF_CONDUCT.md)
[![Maven Central](https://img.shields.io/maven-central/v/io.github.futures4j/futures4j)](https://central.sonatype.com/artifact/io.github.futures4j/futures4j)


1. [What is it?](#what-is-it)
1. [Java Compatibility](#compatibility)
1. [Usage](#usage)
   1. [Binaries](#binaries)
   1. [The `ExtendedFuture` class](#ExtendedFuture)
   1. [The `Futures` utility class](#Futures)
   1. [The `CompletionState` enum](#CompletionState)
   1. [Building from Sources](#building)
1. [License](#license)


## <a name="what-is-it"></a>What is it?

**futures4j** is a lightweight, dependency-free utility library designed to enhance and extend the functionality of Java's `java.util.concurrent` futures.
It simplifies working with futures, providing convenient utilities to improve readability and efficiency when handling asynchronous operations.


## <a name="compatibility"></a>Java Compatibility

futures4j 1.x requires Java 17 or newer.


## <a name="usage"></a>Usage

### <a name="binaries"></a>Binaries

Latest **Release** binaries are available on Maven central, see https://central.sonatype.com/artifact/io.github.futures4j/futures4j

You can add futures4j as a dependency to your `pom.xml` like so:

```xml
<project>

  <!-- ... -->

  <dependencies>
    <dependency>
      <groupId>io.github.futures4j</groupId>
      <artifactId>futures4j</artifactId>
      <version>[VERSION_GOES_HERE]</version>
    </dependency>
  </dependencies>
</project>
```

The latest **Snapshot** binaries are available via the [mvn-snapshots-repo](https://github.com/futures4j/futures4j/tree/mvn-snapshots-repo) git branch.
You need to add this repository configuration to your Maven `settings.xml`:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<settings xmlns="http://maven.apache.org/SETTINGS/1.2.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
   xsi:schemaLocation="http://maven.apache.org/SETTINGS/1.2.0 https://maven.apache.org/xsd/settings-1.2.0.xsd">
  <profiles>
    <profile>
      <repositories>
        <repository>
          <id>futures4j-snapshot</id>
          <name>futures4j-snapshot</name>
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
[java.util.concurrent.CompletableFuture](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/util/concurrent/CompletableFuture.html) class:

1. It supports task **thread interruption** via [ExtendedFuture.cancel(true)](https://futures4j.github.io/futures4j/javadoc/io/github/futures4j/ExtendedFuture.html#cancel(boolean)),
   which is not possible using [CompletableFuture.cancel(boolean)](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/util/concurrent/CompletableFuture.html#cancel(boolean)).
   This is for example discussed at:
   - https://stackoverflow.com/questions/29013831/how-to-interrupt-underlying-execution-of-completablefuture
   - https://nurkiewicz.com/2015/03/completablefuture-cant-be-interrupted.html
   - https://blog.tremblay.pro/2017/08/supply-async.html

   With `ExtendedFuture` you can interrupt task execution:
   ```java
   var myFuture = ExtendedFuture.supplyAsync(...);

   myFuture.cancel(true); // will try to interrupt the async runnable

   var myFutureNonInterruptible = myFuture.asNonInterruptible();
   myFutureNonInterruptible.cancel(true); // behaves like CompletableFuture.cancel(true) which ignores the boolean value
   ```

1. It allows dependent stages **cancel preceding stages**. Another limitation of CompletableFuture discussed at:
   - https://stackoverflow.com/questions/25417881/canceling-a-completablefuture-chain
   - https://stackoverflow.com/questions/36727820/cancellation-of-completablefuture-controlled-by-executorservice
   - https://stackoverflow.com/questions/62106428/is-there-a-better-way-for-cancelling-a-chain-of-futures-in-java

   With `ExtendedFuture` you can cancel a chain in reverse by creating a stage cancellable by subsequent stages using
   [ExtendedFuture.asCancellableByDependents(true)](https://futures4j.github.io/futures4j/javadoc/io/github/futures4j/ExtendedFuture.html#asCancellableByDependents(boolean)):
   ```java
   var myFuture = ExtendedFuture.supplyAsync(...)

   var myCancellableChain = myFuture
         .thenApply(...)   // step 1
         .asCancellableByDependents(true) // allows subsequent stages cancel the previous stage
         .thenRun(...)     // step 2
         .thenApply(...)   // step 3
         .thenAccept(...); // step 4

   if (...) {
      // cancel the futures of step 1 to step 4
      myCancellableChain.cancel(true);
   }
   ```

   This even works for nested chains:
   ```java
   var myFuture = ExtendedFuture.supplyAsync(...)

   var myCancellableChain = myFuture
         .thenApply(...)   // step 1
         .asCancellableByDependents(true) // allows subsequent stages cancel the previous stage
         .thenRun(...)     // step 2
         .thenApply(...)   // step 3
         .thenCompose(x -> {
            var innerFuture = ExtendedFuture
               .runAsync(...)
               .asCancellableByDependents(true);
            return innerFuture;
         })
         .thenAccept(...); // step 4

   if (...) {
      // cancel the futures of step 1 to step 4 and the innerFuture
      myCancellableChain.cancel(true);
   }
   ```

1. It allows **running tasks that throw checked exceptions**. So instead of
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

1. It allows creating **read-only views** of futures via [ExtendedFuture.asReadOnly(ReadOnlyMode)](https://futures4j.github.io/futures4j/javadoc/io/github/futures4j/ExtendedFuture.html#asReadOnly(io.github.futures4j.ExtendedFuture.ReadOnlyMode)):.
   ```java
   ExtendendFuture myFuture = ExtendedFuture.supplyAsync(...)

   var readOnly1 = myFuture.asReadOnly(ReadOnlyMode.THROW_ON_MUTATION); // returns a delegating future that throws an UnsupportedException when a modification attempt is made
   var readOnly2 = myFuture.asReadOnly(ReadOnlyMode.IGNORE_MUTATION); // returns a delegating future that silently ignore modification attempts
   ```

1. It allows defining a **default executor** for this future and all subsequent stages. For example via
   [ExtendedFuture.createWithDefaultExecutor(Executor)](https://futures4j.github.io/futures4j/javadoc/io/github/futures4j/ExtendedFuture.html#createWithDefaultExecutor(java.util.concurrent.Executor)) or
   [ExtendedFuture.withDefaultExecutor(Executor)](https://futures4j.github.io/futures4j/javadoc/io/github/futures4j/ExtendedFuture.html#withDefaultExecutor(java.util.concurrent.Executor))

   ```java
   // use myDefaultExecutor for all subsequent stages of myFuture by default
   var myFuture = ExtendendFuture.createWithDefaultExecutor(myDefaultExecutor);

   // use myDefaultExecutor2 for all subsequent stages of myFuture2 by default
   var myFuture2 = myFuture.withDefaultExecutor(myDefaultExecutor2);

   // use myDefaultExecutor3 to execute and for all subsequent stages of myFuture by default
   var myOtherFuture = ExtendendFuture.runAsyncWithDefaultExecutor(runnable, myDefaultExecutor3);
   ```

1. It offers additional convenience method such as:
   ```java
   ExtendendFuture myFuture = ...

   // returns true if the future completed normally, i.e. with a value
   myFuture.isSuccess();

   // returns an enum describing the current completion state (CANCELLED, SUCCESS, FAILED, INCOMPLETE)
   myFuture.getCompletionState();

   // returns the completed value or the fallbackValue and **never** throws an exception
   myFuture.getNowOrFallback(fallbackValue);

   // returns the completed value or the fallbackValue and **never** throws an exception
   myFuture.getOrFallback(10, TimeUnit.SECONDS, fallbackValue);
   ```


### <a name="Futures"></a>The `Futures` utility class

The [io.github.futures4j.Futures](src/main/java/io/github/futures4j/Futures.java "Source code")
[ 📘 ](https://futures4j.github.io/futures4j/javadoc/io/github/futures4j/Futures.html "JavaDoc")
class provides a set of utility methods for managing and combining Java `Future` and `CompletableFuture` instances.
It simplifies handling asynchronous computations by offering functionality to cancel, combine, and retrieve results from multiple futures.

1. Cancelling Futures

    - Cancel multiple futures

        ```java
        Futures.combine(future1, future2, future3).cancelAll(true);
        ```

    - Propagates cancellations from one `CompletableFuture` to others
        ```java
        var cancellableFuture = new CompletableFuture<>();

        var dependentFuture1 = new CompletableFuture<>();
        var dependentFuture2 = new CompletableFuture<>();

        // Propagate cancellation from cancellableFuture to dependent futures
        Futures.forwardCancellation(cancellableFuture, dependentFuture1, dependentFuture2);

        // Cancelling the primary future also cancels the dependent futures
        cancellableFuture.cancel(true);
      ```


2. Combining Futures

    - Combine futures into lists/sets
        ```java
        CompletableFuture<String> future1 = CompletableFuture.completedFuture("Result 1");
        CompletableFuture<String> future2 = CompletableFuture.completedFuture("Result 2");
        CompletableFuture<String> future2 = CompletableFuture.completedFuture("Result 2");

        Futures.combine(List.of(future1, future2))
          .toList()
          .thenAccept(results -> System.out.println(results)); // Outputs: [Result 1, Result 2, Result 2]

        Futures.combine(List.of(future1, future2))
          .toSet()
          .thenAccept(results -> System.out.println(results)); // Outputs: [Result 1, Result 2]
        ```

    - Combine and flatten futures
        ```java
        CompletableFuture<List<String>> future1 = CompletableFuture.completedFuture(List.of("A", "B"));
        CompletableFuture<List<String>> future2 = CompletableFuture.completedFuture(List.of("C", "D"));

        Futures.combineFlattened(List.of(future1, future2))
           .toList()
           .thenAccept(flattenedResults -> System.out.println(flattenedResults)); // Outputs: [A, B, C, D]
        ```


3. Retrieving Results
    - Get future result with timeout and fallback

       ```java
       Future<String> future = ...;

       // "Fallback Result" if timeout occurs, future is cancelled or completes exceptionally
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
enum introduced in Java 19 which allows switching like so:

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

To ensure reproducible builds this [Maven](https://books.sonatype.com/mvnref-book/reference/index.html) project inherits from the
[vegardit-maven-parent](https://github.com/vegardit/vegardit-maven-parent) project which declares fixed versions
and sensible default settings for all official Maven plug-ins.

The project also uses the [maven-toolchains-plugin](http://maven.apache.org/plugins/maven-toolchains-plugin/) which decouples the
JDK that is used to execute Maven and it's plug-ins from the target JDK that is used for compilation and/or unit testing. This
ensures full binary compatibility of the compiled artifacts with the runtime library of the required target JDK.

To build the project follow these steps:

1. Download and install a Java 17 JDK, e.g. from:
   - Java 17: https://adoptium.net/releases.html?variant=openjdk17 or https://www.azul.com/downloads/?version=java-17-lts&package=jdk#download-openjdk

1. In your user home directory create the file `.m2/toolchains.xml` with the following content:

   ```xml
   <?xml version="1.0" encoding="UTF8"?>
   <toolchains xmlns="http://maven.apache.org/TOOLCHAINS/1.1.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
       xsi:schemaLocation="http://maven.apache.org/TOOLCHAINS/1.1.0 https://maven.apache.org/xsd/toolchains-1.1.0.xsd">
      <toolchain>
         <type>jdk</type>
         <provides>
            <version>17</version>
            <vendor>default</vendor>
         </provides>
         <configuration>
            <jdkHome>[PATH_TO_YOUR_JDK_17]</jdkHome>
         </configuration>
      </toolchain>
   </toolchains>
   ```

   Set the `[PATH_TO_YOUR_JDK_17]` parameters accordingly to where you installed the JDKs.

1. Checkout the code, e.g. using:

    - `git clone https://github.com/futures4j/futures4j`

1. Run `mvnw clean verify` in the project root directory.
   This will execute compilation, unit-testing, integration-testing and packaging of all artifacts.



## <a name="license"></a>License

All files are released under the [Eclipse Public License 2.0](LICENSE.txt).

Individual files contain the following tag instead of the full license text:
```
SPDX-License-Identifier: EPL-2.0
```

This enables machine processing of license information based on the SPDX License Identifiers that are available here: https://spdx.org/licenses/.
