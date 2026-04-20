package ml

import etl.GameFeatureRow
import smile.classification.LogisticRegression
import smile.validation.CrossValidation
import java.util.Properties

// Names of the 8 features — used for display in the web dashboard
val FEATURE_NAMES = listOf(
    "Home Pass Offense EPA",
    "Home Pass Defense EPA",
    "Home Rush Offense EPA",
    "Home Rush Defense EPA",
    "Away Pass Offense EPA",
    "Away Pass Defense EPA",
    "Away Rush Offense EPA",
    "Away Rush Defense EPA"
)

// The result of training the model
data class ModelResult(
    val model: LogisticRegression,
    val cvAccuracy: Double,          // 10-fold cross-validation accuracy (0.0 - 1.0)
    val featureCoefficients: List<Pair<String, Double>>, // Feature name → coefficient
    val trainingSeason: IntRange,    // Seasons used for training
    val testSeason: Int?             // Held-out season (most recent), or null if only 1 season
)

// Prediction for a single game
data class GamePrediction(
    val season: Int,
    val week: Int,
    val homeTeam: String,
    val awayTeam: String,
    val homeScore: Int,
    val awayScore: Int,
    val homeWinProbability: Double,  // Model's predicted probability that home team wins
    val predictedHomeWin: Boolean,   // true if model predicts home team wins
    val actualHomeWin: Boolean,      // true if home team actually won
    val correct: Boolean             // true if prediction matched result
)

// Trains a logistic regression model on all seasons except the most recent,
// evaluates it with 10-fold cross-validation, then generates predictions
// for every game in the dataset (including the held-out season).
fun trainAndPredict(games: List<GameFeatureRow>): Pair<ModelResult, List<GamePrediction>> {
    val seasons = games.map { it.season }.distinct().sorted()

    if (seasons.isEmpty()) {
        println("ERROR: No game data available to train on.")
        kotlin.system.exitProcess(1)
    }

    // If we have more than one season, hold out the most recent for out-of-sample testing.
    // If we only have one season, train and predict on the same data (still useful as a demo).
    val testSeason   = if (seasons.size > 1) seasons.last() else null
    val trainSeasons = if (testSeason != null) seasons.dropLast(1) else seasons

    println("Training seasons : $trainSeasons")
    println("Test season      : ${testSeason ?: "same as training (only 1 season available)"}")

    val trainGames = games.filter { it.season in trainSeasons }
    val allGames   = games // Predict for every game, including test season

    // Build training arrays
    val X = trainGames.map { it.featureArray() }.toTypedArray()
    val y = trainGames.map { it.homeWin }.toIntArray()

    println("Training on ${trainGames.size} games...")

    // Train logistic regression via Smile
    // lambda=0.1 adds mild L2 regularisation; tol and maxIter for convergence
    val params = Properties().apply {
        setProperty("smile.logistic.lambda", "0.1")
        setProperty("smile.logistic.tolerance", "1e-5")
        setProperty("smile.logistic.iterations", "500")
    }
    val model = LogisticRegression.fit(X, y, params)

    // ── 10-fold cross-validation on training data ────────────────────────────
    val cvResult = CrossValidation.classification(10, X, y) { trainX, trainY ->
        LogisticRegression.fit(trainX, trainY, params)
    }
    val cvAccuracy = cvResult.avg.accuracy
    println("10-fold CV accuracy: ${"%.1f".format(cvAccuracy * 100)}%")

    // ── Feature coefficients (importance) ───────────────────────────────────
    // Smile Binomial stores coefficients as flat array [w_1..w_p, bias]; index 0..7 = features, 8 = intercept
    val binomialModel = model as smile.classification.LogisticRegression.Binomial
    val rawCoef = binomialModel.coefficients()
    // rawCoef is (p+1): features at 0..p-1, bias at p. Zip with feature names (skip bias at end).
    val featureCoefficients = FEATURE_NAMES.mapIndexed { i, name ->
        name to (if (i < rawCoef.size - 1) rawCoef[i] else 0.0)
    }.sortedByDescending { (_, coef) -> kotlin.math.abs(coef) }

    // ── Generate predictions for all games ──────────────────────────────────
    val predictions = allGames.map { game ->
        val features = game.featureArray()
        val posteriori = DoubleArray(2)
        binomialModel.predict(features, posteriori)  // Fills posteriori with [p(0), p(1)]
        val homeWinProb = posteriori[1]  // p(home win)

        GamePrediction(
            season              = game.season,
            week                = game.week,
            homeTeam            = game.homeTeam,
            awayTeam            = game.awayTeam,
            homeScore           = game.homeScore,
            awayScore           = game.awayScore,
            homeWinProbability  = homeWinProb,
            predictedHomeWin    = homeWinProb >= 0.5,
            actualHomeWin       = game.homeWin == 1,
            correct             = (homeWinProb >= 0.5) == (game.homeWin == 1)
        )
    }

    val modelResult = ModelResult(
        model                = model,
        cvAccuracy           = cvAccuracy,
        featureCoefficients  = featureCoefficients,
        trainingSeason       = trainSeasons.first()..trainSeasons.last(),
        testSeason           = testSeason
    )

    return Pair(modelResult, predictions)
}
