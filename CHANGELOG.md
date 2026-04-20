# Changelog

All notable changes to this project will be documented in this file.

## [Unreleased]

### Fixed

- **Model.kt** – Smile ML library compatibility
  - Replaced named arguments for `LogisticRegression.fit()` (Kotlin does not support named arguments for Java functions) with a `Properties` object
  - Fixed cross-validation accuracy: `CrossValidation.classification()` returns `ClassificationValidations`; now uses `cvResult.avg.accuracy` instead of `Accuracy.of(y, cvPred.prediction)`
  - Fixed feature coefficients: cast model to `LogisticRegression.Binomial` since `coefficients()` is defined only on the Binomial subclass
  - Fixed prediction probabilities: use `predict(features, posteriori)` to obtain posterior probabilities instead of treating `predict()` result as a probability array
  - Removed unused `Accuracy` import
