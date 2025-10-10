# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).


## [Unreleased]

### Fixed
- Cancellation (and interrupt intent) don't propagate because completeWith(...) and WrappingFuture treat cancellations as exceptional completion


## [1.1.2] 2025-10-08

### Changed

- propagate `CombinedFuture.cancel(true)` interrupt intent to combined futures while keeping CombinedFuture non-interruptible

### Fixed

- mutation on read-only futures (`completeWith`, `orTimeout`) is not prevented
- `ExtendedFuture#cancel(true)` on non-interruptible downstream stage does not interrupt upstream
- some delegation methods are missing on internal `ExtendedFuture#WrappingFuture` class
- `Combiner#toMap` ignores null results from futures
- ClassCastException when re-wrapping interruptible futures via `asCancellableByDependents`, `withInterruptibleStages`, `withDefaultExecutor`
- `mayInterruptIfRunning` not preserved in forwarded cancellation


## [1.1.1] 2025-05-01

### Fixed

- IllegalStateException in ExtendedFuture with long running stages


## [1.1.0] 2024-10-10

### Changed
- change minimum Java requirement from 17 to 11

### Added
- `ExtendedFuture` methods:
  - `thenAcceptBoth(...,ThrowingBiConsumer<...>)`
  - `thenAcceptBothAsync(...,ThrowingBiConsumer<...>)`
  - `thenCombine(...,ThrowingBiFunction<...>)`
  - `thenCombineAsync(...,ThrowingBiFunction<...>)`
  - `thenCompose(ThrowingFunction<...>)`
  - `thenComposeAsync(ThrowingFunction<...>)`
  - `whenComplete(ThrowingBiConsumer<...>)`
  - `whenCompleteAsync(ThrowingBiConsumer<...>)`


## [1.0.0] 2024-10-08

- internal releases
