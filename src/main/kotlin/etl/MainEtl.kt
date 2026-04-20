package etl

import db.initDatabase
import ml.trainAndPredict

fun main() {
    println("=== NFL Game Outcome Predictor — ETL Pipeline ===")
    println()

    val dataDir = "data"
    val dbPath  = "nfl_stats.db"

    // Step 1: Extract — scan data/ and read all play_by_play_YYYY.parquet files
    val plays = extractAllSeasons(dataDir)
    println("Total plays extracted: ${plays.size}")
    println()

    // Step 2: Transform — engineer EWMA EPA features, build one row per game
    val gameRows = transform(plays)
    println()

    // Step 3: Train — fit logistic regression, run cross-validation, generate predictions
    val (modelResult, predictions) = trainAndPredict(gameRows)
    println()

    // Step 4: Load — write everything to SQLite
    val db = initDatabase(dbPath)
    load(db, plays, predictions, modelResult)

    println()
    println("=== Pipeline complete. Database written to: $dbPath ===")
    println("Start the web dashboard with: ./gradlew runServer")
}
