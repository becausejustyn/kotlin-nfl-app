package etl

import db.FeatureImportance
import db.GamePredictions
import db.ModelSummary
import db.TeamEpaSummary
import ml.GamePrediction
import ml.ModelResult
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.deleteAll
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.transactions.transaction

fun load(
    db: Database,
    plays: List<RawPlay>,
    predictions: List<GamePrediction>,
    modelResult: ModelResult
) {
    transaction(db) {
        // Clear all tables so re-running ETL always gives a fresh result
        GamePredictions.deleteAll()
        FeatureImportance.deleteAll()
        ModelSummary.deleteAll()
        TeamEpaSummary.deleteAll()

        // ── Game predictions ─────────────────────────────────────────────────
        for (pred in predictions) {
            GamePredictions.insert {
                it[season]             = pred.season
                it[week]               = pred.week
                it[homeTeam]           = pred.homeTeam
                it[awayTeam]           = pred.awayTeam
                it[homeScore]          = pred.homeScore
                it[awayScore]          = pred.awayScore
                it[homeWinProbability] = pred.homeWinProbability.round(3)
                it[predictedHomeWin]   = pred.predictedHomeWin
                it[actualHomeWin]      = pred.actualHomeWin
                it[correct]            = pred.correct
            }
        }

        // ── Feature importance ───────────────────────────────────────────────
        for ((name, coef) in modelResult.featureCoefficients) {
            FeatureImportance.insert {
                it[featureName]  = name
                it[coefficient]  = coef.round(4)
            }
        }

        // ── Model summary ────────────────────────────────────────────────────
        ModelSummary.insert {
            it[cvAccuracy]     = modelResult.cvAccuracy.round(4)
            it[trainSeasonMin] = modelResult.trainingSeason.first
            it[trainSeasonMax] = modelResult.trainingSeason.last
            it[testSeason]     = modelResult.testSeason
        }

        // ── Team EPA summary ─────────────────────────────────────────────────
        // Compute per-team per-season average EPA across pass/rush offense and defense
        val passPlays = plays.filter { it.passAttempt == 1 }
        val rushPlays = plays.filter { it.rushAttempt == 1 }

        val seasons = plays.map { it.season }.distinct()
        val teams   = plays.map { it.posteam }.distinct()

        for (season in seasons) {
            for (team in teams) {
                val passOff = passPlays.filter { it.season == season && it.posteam == team }.map { it.epa }
                val passDef = passPlays.filter { it.season == season && it.defteam == team }.map { it.epa }
                val rushOff = rushPlays.filter { it.season == season && it.posteam == team }.map { it.epa }
                val rushDef = rushPlays.filter { it.season == season && it.defteam == team }.map { it.epa }

                if (passOff.isEmpty() && passDef.isEmpty()) continue

                TeamEpaSummary.insert {
                    it[TeamEpaSummary.season] = season
                    it[TeamEpaSummary.team]   = team
                    it[avgPassOffEpa]          = passOff.averageOrZero().round(3)
                    it[avgPassDefEpa]          = passDef.averageOrZero().round(3)
                    it[avgRushOffEpa]          = rushOff.averageOrZero().round(3)
                    it[avgRushDefEpa]          = rushDef.averageOrZero().round(3)
                }
            }
        }
    }

    println("Loaded ${predictions.size} game predictions into SQLite.")
    println("Loaded ${modelResult.featureCoefficients.size} feature importance rows.")
    println("ETL complete.")
}

private fun Double.round(decimals: Int): Double {
    val factor = Math.pow(10.0, decimals.toDouble())
    return Math.round(this * factor) / factor
}

private fun List<Double>.averageOrZero() = if (isEmpty()) 0.0 else average()
