# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).


## [Unreleased]

### Fixed

- mutation on read-only futures (`completeWith`, `orTimeout`) is not prevented
- `ExtendedFuture#cancel(true)` on non-interruptible downstream stage does not interrupt upstream


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
