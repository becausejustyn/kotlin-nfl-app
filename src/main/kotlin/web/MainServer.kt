package web

import db.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.html.*
import io.ktor.server.netty.*
import io.ktor.server.routing.*
import kotlinx.html.*
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction

fun main() {
    val dbPath = "nfl_stats.db"

    if (!java.io.File(dbPath).exists()) {
        println("ERROR: Database not found at $dbPath")
        println("Run the ETL pipeline first: ./gradlew runEtl")
        kotlin.system.exitProcess(1)
    }

    val db = initDatabase(dbPath)
    println("=== NFL Predictor Web Server ===")
    println("Open your browser at: http://localhost:8080")

    embeddedServer(Netty, port = 8080) {
        routing {

            // ── Home page: model summary ─────────────────────────────────────
            get("/") {
                val summary     = fetchModelSummary(db)
                val importance  = fetchFeatureImportance(db)
                val predictions = fetchPredictions(db)

                // Overall accuracy
                val totalGames   = predictions.size
                val correctGames = predictions.count { it.correct }
                val overallAcc   = if (totalGames > 0) correctGames.toDouble() / totalGames * 100 else 0.0

                // Test season accuracy (held-out season)
                val testPreds    = predictions.filter { summary?.testSeason != null && it.season == summary.testSeason }
                val testAcc      = if (testPreds.isNotEmpty()) testPreds.count { it.correct }.toDouble() / testPreds.size * 100 else null

                call.respondHtml {
                    nflPage("Model Summary") {
                        h2("mb-4") { +"🏈 NFL Game Outcome Predictor" }

                        // Stat cards row
                        div("row g-3 mb-4") {
                            statCard("CV Accuracy", "${"%.1f".format((summary?.cvAccuracy ?: 0.0) * 100)}%", "bg-primary")
                            statCard("Overall Accuracy", "${"%.1f".format(overallAcc)}%", "bg-success")
                            if (testAcc != null) {
                                statCard("Test Season Accuracy", "${"%.1f".format(testAcc)}%", "bg-warning")
                            }
                            statCard("Total Games", totalGames.toString(), "bg-secondary")
                        }

                        if (summary != null) {
                            p("text-muted") {
                                +"Trained on seasons ${summary.trainSeasonMin}–${summary.trainSeasonMax}. "
                                if (summary.testSeason != null) {
                                    +"Held-out test season: ${summary.testSeason}."
                                }
                            }
                        }

                        // Feature importance table
                        h4("mt-4 mb-3") { +"Feature Importance (Logistic Regression Coefficients)" }
                        p("text-muted small") {
                            +"Positive coefficients favour a home win. Negative coefficients favour an away win. "
                            +"Larger absolute values = stronger influence on predictions."
                        }
                        div("table-responsive") {
                            table("table table-striped table-bordered table-hover align-middle") {
                                thead("table-dark") {
                                    tr {
                                        th { +"Feature" }
                                        th { +"Coefficient" }
                                        th { +"Direction" }
                                    }
                                }
                                tbody {
                                    for (feat in importance) {
                                        tr {
                                            td { +feat.first }
                                            td {
                                                val cls = if (feat.second >= 0) "text-success fw-bold" else "text-danger fw-bold"
                                                span(cls) { +feat.second.toString() }
                                            }
                                            td {
                                                if (feat.second >= 0) {
                                                    span("badge bg-success") { +"Favours Home Win" }
                                                } else {
                                                    span("badge bg-danger") { +"Favours Away Win" }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // ── Predictions page ─────────────────────────────────────────────
            get("/predictions") {
                val predictions = fetchPredictions(db)
                val seasons     = predictions.map { it.season }.distinct().sorted()
                val selectedSeason = call.request.queryParameters["season"]?.toIntOrNull()
                    ?: seasons.lastOrNull()
                val weeks       = predictions.filter { it.season == selectedSeason }.map { it.week }.distinct().sorted()
                val selectedWeek = call.request.queryParameters["week"]?.toIntOrNull()

                val filtered = predictions
                    .filter { it.season == selectedSeason }
                    .let { if (selectedWeek != null) it.filter { g -> g.week == selectedWeek } else it }
                    .sortedWith(compareBy({ it.week }, { it.homeTeam }))

                val filteredCorrect = filtered.count { it.correct }
                val filteredAcc = if (filtered.isNotEmpty()) filteredCorrect.toDouble() / filtered.size * 100 else 0.0

                call.respondHtml {
                    nflPage("Game Predictions") {
                        h2("mb-4") { +"Game Predictions" }

                        // Season + week filter controls
                        div("d-flex flex-wrap gap-2 mb-3 align-items-center") {
                            span("fw-semibold me-1") { +"Season:" }
                            for (s in seasons) {
                                val active = if (s == selectedSeason) "btn-primary" else "btn-outline-primary"
                                a("/predictions?season=$s", classes = "btn btn-sm $active") { +s.toString() }
                            }
                        }
                        if (weeks.isNotEmpty()) {
                            div("d-flex flex-wrap gap-2 mb-3 align-items-center") {
                                span("fw-semibold me-1") { +"Week:" }
                                val allActive = if (selectedWeek == null) "btn-secondary" else "btn-outline-secondary"
                                a("/predictions?season=$selectedSeason", classes = "btn btn-sm $allActive") { +"All" }
                                for (w in weeks) {
                                    val active = if (w == selectedWeek) "btn-secondary" else "btn-outline-secondary"
                                    a("/predictions?season=$selectedSeason&week=$w", classes = "btn btn-sm $active") { +w.toString() }
                                }
                            }
                        }

                        // Accuracy summary for current filter
                        div("alert alert-info") {
                            +"Showing ${filtered.size} games — accuracy: ${"%.1f".format(filteredAcc)}% ($filteredCorrect / ${filtered.size} correct)"
                        }

                        div("table-responsive") {
                            table("table table-striped table-bordered table-hover align-middle") {
                                thead("table-dark") {
                                    tr {
                                        th { +"Wk" }
                                        th { +"Home" }
                                        th { +"Away" }
                                        th { +"Score" }
                                        th { +"Home Win Prob" }
                                        th { +"Predicted Winner" }
                                        th { +"Actual Winner" }
                                        th { +"Result" }
                                    }
                                }
                                tbody {
                                    for (game in filtered) {
                                        val homeProb  = "${"%.1f".format(game.homeWinProbability * 100)}%"
                                        val predWinner = if (game.predictedHomeWin) game.homeTeam else game.awayTeam
                                        val actualWinner = if (game.actualHomeWin) game.homeTeam else game.awayTeam

                                        tr {
                                            td { +game.week.toString() }
                                            td { strong { +game.homeTeam } }
                                            td { +game.awayTeam }
                                            td { +"${game.homeScore}–${game.awayScore}" }
                                            td {
                                                // Colour-code: green if high home prob, red if low
                                                val prob = game.homeWinProbability
                                                val cls = when {
                                                    prob >= 0.65 -> "text-success fw-bold"
                                                    prob <= 0.35 -> "text-danger fw-bold"
                                                    else         -> "text-secondary"
                                                }
                                                span(cls) { +homeProb }
                                            }
                                            td { strong { +predWinner } }
                                            td { +actualWinner }
                                            td {
                                                if (game.correct) {
                                                    span("badge bg-success") { +"✓ Correct" }
                                                } else {
                                                    span("badge bg-danger") { +"✗ Wrong" }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // ── Team EPA page ────────────────────────────────────────────────
            get("/epa") {
                val seasons = fetchEpaSeasons(db)
                val selectedSeason = call.request.queryParameters["season"]?.toIntOrNull()
                    ?: seasons.lastOrNull()
                val epaRows = fetchTeamEpa(db, selectedSeason)

                call.respondHtml {
                    nflPage("Team EPA") {
                        h2("mb-4") { +"Team EPA Summary" }
                        p("text-muted") {
                            +"Average Expected Points Added per play by team. "
                            +"Positive EPA = above average; negative = below average."
                        }

                        // Season filter
                        div("d-flex flex-wrap gap-2 mb-3 align-items-center") {
                            span("fw-semibold me-1") { +"Season:" }
                            for (s in seasons) {
                                val active = if (s == selectedSeason) "btn-primary" else "btn-outline-primary"
                                a("/epa?season=$s", classes = "btn btn-sm $active") { +s.toString() }
                            }
                        }

                        div("table-responsive") {
                            table("table table-striped table-bordered table-hover align-middle") {
                                thead("table-dark") {
                                    tr {
                                        th { +"Team" }
                                        th { +"Pass Off EPA" }
                                        th { +"Pass Def EPA" }
                                        th { +"Rush Off EPA" }
                                        th { +"Rush Def EPA" }
                                    }
                                }
                                tbody {
                                    for (row in epaRows) {
                                        tr {
                                            td { strong { +row.team } }
                                            td { epaCell(this, row.avgPassOffEpa) }
                                            td { epaCell(this, row.avgPassDefEpa) }
                                            td { epaCell(this, row.avgRushOffEpa) }
                                            td { epaCell(this, row.avgRushDefEpa) }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }.start(wait = true)
}

// ── Data fetching ──────────────────────────────────────────────────────────────

data class PredRow(
    val season: Int, val week: Int, val homeTeam: String, val awayTeam: String,
    val homeScore: Int, val awayScore: Int, val homeWinProbability: Double,
    val predictedHomeWin: Boolean, val actualHomeWin: Boolean, val correct: Boolean
)

data class SummaryRow(
    val cvAccuracy: Double, val trainSeasonMin: Int, val trainSeasonMax: Int, val testSeason: Int?
)

data class EpaRow(
    val team: String, val avgPassOffEpa: Double, val avgPassDefEpa: Double,
    val avgRushOffEpa: Double, val avgRushDefEpa: Double
)

fun fetchPredictions(db: org.jetbrains.exposed.sql.Database): List<PredRow> = transaction(db) {
    GamePredictions.selectAll().map {
        PredRow(
            it[GamePredictions.season], it[GamePredictions.week],
            it[GamePredictions.homeTeam], it[GamePredictions.awayTeam],
            it[GamePredictions.homeScore], it[GamePredictions.awayScore],
            it[GamePredictions.homeWinProbability], it[GamePredictions.predictedHomeWin],
            it[GamePredictions.actualHomeWin], it[GamePredictions.correct]
        )
    }
}

fun fetchModelSummary(db: org.jetbrains.exposed.sql.Database): SummaryRow? = transaction(db) {
    ModelSummary.selectAll().firstOrNull()?.let {
        SummaryRow(
            it[ModelSummary.cvAccuracy], it[ModelSummary.trainSeasonMin],
            it[ModelSummary.trainSeasonMax], it[ModelSummary.testSeason]
        )
    }
}

fun fetchFeatureImportance(db: org.jetbrains.exposed.sql.Database): List<Pair<String, Double>> = transaction(db) {
    FeatureImportance.selectAll()
        .orderBy(FeatureImportance.coefficient, SortOrder.DESC)
        .map { it[FeatureImportance.featureName] to it[FeatureImportance.coefficient] }
}

fun fetchEpaSeasons(db: org.jetbrains.exposed.sql.Database): List<Int> = transaction(db) {
    TeamEpaSummary.slice(TeamEpaSummary.season).selectAll().withDistinct()
        .map { it[TeamEpaSummary.season] }.sorted()
}

fun fetchTeamEpa(db: org.jetbrains.exposed.sql.Database, season: Int?): List<EpaRow> = transaction(db) {
    val query = if (season != null)
        TeamEpaSummary.select { TeamEpaSummary.season eq season }
    else
        TeamEpaSummary.selectAll()
    query.orderBy(TeamEpaSummary.team).map {
        EpaRow(
            it[TeamEpaSummary.team], it[TeamEpaSummary.avgPassOffEpa],
            it[TeamEpaSummary.avgPassDefEpa], it[TeamEpaSummary.avgRushOffEpa],
            it[TeamEpaSummary.avgRushDefEpa]
        )
    }
}

// ── Shared HTML layout & components ───────────────────────────────────────────

fun HTML.nflPage(title: String, content: DIV.() -> Unit) {
    head {
        title { +"NFL Predictor | $title" }
        link(rel = "stylesheet", href = "https://cdn.jsdelivr.net/npm/bootstrap@5.3.3/dist/css/bootstrap.min.css")
        style {
            unsafe {
                raw("""
                    .stat-card { border-radius: 12px; color: white; padding: 1.2rem 1.5rem; }
                    .stat-card .label { font-size: 0.85rem; opacity: 0.85; }
                    .stat-card .value { font-size: 2rem; font-weight: 700; }
                """.trimIndent())
            }
        }
    }
    body {
        nav("navbar navbar-expand-lg navbar-dark bg-dark mb-4") {
            div("container") {
                a("/", classes = "navbar-brand fw-bold") { +"🏈 NFL Predictor" }
                div("navbar-nav") {
                    a("/", classes = "nav-link text-white") { +"Model Summary" }
                    a("/predictions", classes = "nav-link text-white") { +"Predictions" }
                    a("/epa", classes = "nav-link text-white") { +"Team EPA" }
                }
            }
        }
        div("container pb-5") {
            content()
        }
    }
}

// Renders a coloured summary stat card
fun DIV.statCard(label: String, value: String, bgClass: String) {
    div("col-6 col-md-3") {
        div("stat-card $bgClass") {
            div("label") { +label }
            div("value") { +value }
        }
    }
}

// Renders a colour-coded EPA table cell (green = positive, red = negative)
fun epaCell(td: TD, value: Double) {
    val cls = when {
        value > 0.05  -> "text-success fw-bold"
        value < -0.05 -> "text-danger fw-bold"
        else          -> "text-secondary"
    }
    td.span(cls) { +"${"%.3f".format(value)}" }
}
