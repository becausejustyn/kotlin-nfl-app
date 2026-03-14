package etl

// One row per game — contains the 8 EWMA EPA features and the target label.
// This is what gets passed to the logistic regression model.
data class GameFeatureRow(
    val season: Int,
    val week: Int,
    val homeTeam: String,
    val awayTeam: String,
    val homeWin: Int,           // 1 if home team won, 0 if away team won (target label)
    val homeScore: Int,
    val awayScore: Int,
    // The 8 features — exponentially weighted moving avg EPA, lagged one week
    val homePassOffenseEwma: Double,
    val homePassDefenseEwma: Double,
    val homeRushOffenseEwma: Double,
    val homeRushDefenseEwma: Double,
    val awayPassOffenseEwma: Double,
    val awayPassDefenseEwma: Double,
    val awayRushOffenseEwma: Double,
    val awayRushDefenseEwma: Double
) {
    // Returns the 8 features as a DoubleArray — used as input to the model
    fun featureArray(): DoubleArray = doubleArrayOf(
        homePassOffenseEwma, homePassDefenseEwma,
        homeRushOffenseEwma, homeRushDefenseEwma,
        awayPassOffenseEwma, awayPassDefenseEwma,
        awayRushOffenseEwma, awayRushDefenseEwma
    )
}

// Intermediate: per-team, per-week average EPA for one play category (e.g. passing offense)
private data class TeamWeekEpa(
    val team: String,
    val season: Int,
    val week: Int,
    val avgEpa: Double
)

// Intermediate: TeamWeekEpa enriched with the lagged + EWMA value
private data class TeamWeekEwma(
    val team: String,
    val season: Int,
    val week: Int,
    val ewma: Double
)

// Transforms raw plays into a list of game-level feature rows ready for modelling.
// Mirrors the approach in the article:
//   1. Separate EPA into 4 categories (pass offense, pass defense, rush offense, rush defense)
//   2. Average EPA per team per week for each category
//   3. Lag one week (so we never use the current game's EPA to predict itself)
//   4. Apply dynamic-window EWMA (window = week number, capped at 10 before week 10)
//   5. Join home + away features onto each game row
fun transform(plays: List<RawPlay>): List<GameFeatureRow> {
    println("Engineering features from ${plays.size} raw plays...")

    // ── Step 1: Split plays into 4 EPA categories ────────────────────────────

    val passOffensePlays = plays.filter { it.passAttempt == 1 }
    val rushOffensePlays = plays.filter { it.rushAttempt == 1 }

    val passOffenseEpa = weeklyAvgEpa(passOffensePlays, usePosteam = true)
    val passDefenseEpa = weeklyAvgEpa(passOffensePlays, usePosteam = false)
    val rushOffenseEpa = weeklyAvgEpa(rushOffensePlays, usePosteam = true)
    val rushDefenseEpa = weeklyAvgEpa(rushOffensePlays, usePosteam = false)

    // ── Step 2: Lag + compute EWMA for each category ────────────────────────

    val passOffenseEwma = computeEwma(passOffenseEpa)
    val passDefenseEwma = computeEwma(passDefenseEpa)
    val rushOffenseEwma = computeEwma(rushOffenseEpa)
    val rushDefenseEwma = computeEwma(rushDefenseEpa)

    // ── Step 3: Build lookup maps for fast join (team+season+week → ewma) ───

    fun List<TeamWeekEwma>.toMap() = associateBy { Triple(it.team, it.season, it.week) }

    val passOffMap = passOffenseEwma.toMap()
    val passDefMap = passDefenseEwma.toMap()
    val rushOffMap = rushOffenseEwma.toMap()
    val rushDefMap = rushDefenseEwma.toMap()

    // ── Step 4: Build one row per game ───────────────────────────────────────

    // Extract unique games (one row per game from play-by-play)
    val games = plays
        .map { GameKey(it.season, it.week, it.homeTeam, it.awayTeam, it.homeScore, it.awayScore) }
        .distinct()

    val rows = games.mapNotNull { game ->
        val hKey = { team: String -> Triple(team, game.season, game.week) }
        val aKey = { team: String -> Triple(team, game.season, game.week) }

        // All 8 features must be present — skip the game if any are missing
        val hPO = passOffMap[hKey(game.homeTeam)]?.ewma ?: return@mapNotNull null
        val hPD = passDefMap[hKey(game.homeTeam)]?.ewma ?: return@mapNotNull null
        val hRO = rushOffMap[hKey(game.homeTeam)]?.ewma ?: return@mapNotNull null
        val hRD = rushDefMap[hKey(game.homeTeam)]?.ewma ?: return@mapNotNull null
        val aPO = passOffMap[aKey(game.awayTeam)]?.ewma ?: return@mapNotNull null
        val aPD = passDefMap[aKey(game.awayTeam)]?.ewma ?: return@mapNotNull null
        val aRO = rushOffMap[aKey(game.awayTeam)]?.ewma ?: return@mapNotNull null
        val aRD = rushDefMap[aKey(game.awayTeam)]?.ewma ?: return@mapNotNull null

        GameFeatureRow(
            season              = game.season,
            week                = game.week,
            homeTeam            = game.homeTeam,
            awayTeam            = game.awayTeam,
            homeWin             = if (game.homeScore > game.awayScore) 1 else 0,
            homeScore           = game.homeScore,
            awayScore           = game.awayScore,
            homePassOffenseEwma = hPO,
            homePassDefenseEwma = hPD,
            homeRushOffenseEwma = hRO,
            homeRushDefenseEwma = hRD,
            awayPassOffenseEwma = aPO,
            awayPassDefenseEwma = aPD,
            awayRushOffenseEwma = aRO,
            awayRushDefenseEwma = aRD
        )
    }.sortedWith(compareBy({ it.season }, { it.week }))

    println("Built ${rows.size} game feature rows across ${rows.map { it.season }.distinct().size} season(s).")
    return rows
}

// ── Helpers ────────────────────────────────────────────────────────────────────

private data class GameKey(
    val season: Int, val week: Int,
    val homeTeam: String, val awayTeam: String,
    val homeScore: Int, val awayScore: Int
)

// Averages EPA per team per week for a set of plays.
// usePosteam = true → offensive EPA (posteam); false → defensive EPA (defteam)
private fun weeklyAvgEpa(plays: List<RawPlay>, usePosteam: Boolean): List<TeamWeekEpa> {
    return plays
        .groupBy { play ->
            val team = if (usePosteam) play.posteam else play.defteam
            Triple(team, play.season, play.week)
        }
        .map { (key, groupPlays) ->
            TeamWeekEpa(
                team   = key.first,
                season = key.second,
                week   = key.third,
                avgEpa = groupPlays.map { it.epa }.average()
            )
        }
}

// Lags EPA by one week, then computes dynamic-window EWMA per team.
// Dynamic window: use span=week if week > 10, else span=10 (matches the article).
// This ensures early-season weeks draw on a 10-game window of prior context.
private fun computeEwma(weeklyEpa: List<TeamWeekEpa>): List<TeamWeekEwma> {
    val result = mutableListOf<TeamWeekEwma>()

    // Group by team across all seasons, sorted chronologically
    val byTeam = weeklyEpa.groupBy { it.team }

    for ((team, entries) in byTeam) {
        val sorted = entries.sortedWith(compareBy({ it.season }, { it.week }))

        // Lag: shift EPA values forward by one position (index i uses EPA from index i-1)
        val laggedEpa = listOf(null) + sorted.map { it.avgEpa }

        for (i in sorted.indices) {
            val lagged = laggedEpa[i] ?: continue // Skip the very first row (no prior data)

            // Build the history of lagged EPA values up to and including this point
            val history = (1..i).mapNotNull { j -> laggedEpa[j] }.toDoubleArray()
            if (history.isEmpty()) continue

            val week   = sorted[i].week
            val span   = if (week > 10) week.toDouble() else 10.0
            val ewmaVal = exponentiallyWeightedMean(history, span)

            result.add(TeamWeekEwma(team, sorted[i].season, sorted[i].week, ewmaVal))
        }
    }

    return result
}

// Computes the exponentially weighted moving average of an array with a given span.
// Alpha = 2 / (span + 1), matching pandas ewm(span=N) behaviour.
private fun exponentiallyWeightedMean(data: DoubleArray, span: Double): Double {
    val alpha = 2.0 / (span + 1.0)
    var ewma  = data[0]
    for (i in 1 until data.size) {
        ewma = alpha * data[i] + (1.0 - alpha) * ewma
    }
    return ewma
}
