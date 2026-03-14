package db

import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.transactions.transaction

// One row per game — stores predictions and results
object GamePredictions : Table("game_predictions") {
    val season             = integer("season")
    val week               = integer("week")
    val homeTeam           = varchar("home_team", 10)
    val awayTeam           = varchar("away_team", 10)
    val homeScore          = integer("home_score")
    val awayScore          = integer("away_score")
    val homeWinProbability = double("home_win_probability")
    val predictedHomeWin   = bool("predicted_home_win")
    val actualHomeWin      = bool("actual_home_win")
    val correct            = bool("correct")
    override val primaryKey = PrimaryKey(season, week, homeTeam, awayTeam)
}

// One row per EPA feature — stores the model's coefficients
object FeatureImportance : Table("feature_importance") {
    val featureName  = varchar("feature_name", 100)
    val coefficient  = double("coefficient")
    override val primaryKey = PrimaryKey(featureName)
}

// Single-row summary of the trained model
object ModelSummary : Table("model_summary") {
    val cvAccuracy      = double("cv_accuracy")
    val trainSeasonMin  = integer("train_season_min")
    val trainSeasonMax  = integer("train_season_max")
    val testSeason      = integer("test_season").nullable()
}

// One row per team per season — stores per-team EPA averages for the web dashboard
object TeamEpaSummary : Table("team_epa_summary") {
    val season        = integer("season")
    val team          = varchar("team", 10)
    val avgPassOffEpa = double("avg_pass_off_epa")
    val avgPassDefEpa = double("avg_pass_def_epa")
    val avgRushOffEpa = double("avg_rush_off_epa")
    val avgRushDefEpa = double("avg_rush_def_epa")
    override val primaryKey = PrimaryKey(season, team)
}

fun initDatabase(dbPath: String): Database {
    val db = Database.connect("jdbc:sqlite:$dbPath", driver = "org.sqlite.JDBC")
    transaction(db) {
        SchemaUtils.create(GamePredictions, FeatureImportance, ModelSummary, TeamEpaSummary)
    }
    return db
}
