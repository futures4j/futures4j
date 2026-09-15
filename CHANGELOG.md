# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).


## [Unreleased]

### Fixed

#### `ExtendedFuture`

- Honor interruption policies for `completeAsync` tasks and recovery callbacks, including overlapping executions.
- Preserve cancellation and caller interrupt intent across views and stage chains, including opted-in upstream inputs.
- Reject null handlers and explicit executors immediately in recovery methods.
- Prevent unexpected callback failures and preserve receiver settings with reentrant factories and either-stage operations.
- Preserve input outcomes and avoid unnecessary wrappers in `thenCombine`, `thenAcceptBoth`, and `runAfterBoth`.
- Keep view outcomes consistent after forced completion through a view, and honor its configured executor in `completeAsync`.
- Release unused stage references and reduce per-future memory and callback-tracking overhead.


## [1.1.3] 2025-10-11

### Fixed
- Cancellation (and interrupt intent) don't propagate because completeWith(...) and WrappingFuture treat cancellations as exceptional completion
- exceptionallyAsync/exceptionallyComposeAsync(...) schedule executor work on the success path
- WrappingFuture.completeWith(...) does not preserve original mayInterruptIfRunning intent on the wrapped future
- time units are debug logged locale-dependent in `Futures`


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
