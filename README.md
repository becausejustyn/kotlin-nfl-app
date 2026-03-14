# NFL Game Outcome Predictor

A Kotlin ETL pipeline and web dashboard that predicts NFL game outcomes using logistic regression trained on EPA (Expected Points Added) features — based on the methodology from [Open Source Football](https://opensourcefootball.com/posts/2021-01-21-nfl-game-prediction-using-logistic-regression/).

## How It Works

For each game, the model uses 8 features:

| Feature | Description |
|---|---|
| Home Pass Offense EPA | How well the home team's passing offense performs |
| Home Pass Defense EPA | How well the home team limits opposing passing |
| Home Rush Offense EPA | Home team rushing offense effectiveness |
| Home Rush Defense EPA | Home team rushing defense effectiveness |
| Away Pass Offense EPA | Same 4 features for the away team |
| Away Pass Defense EPA | |
| Away Rush Offense EPA | |
| Away Rush Defense EPA | |

Each feature is computed as a **dynamic-window exponentially weighted moving average** of lagged EPA per play — matching the approach from the article. The model is trained using logistic regression and evaluated with 10-fold cross-validation.

## Tech Stack

| Layer | Technology |
|---|---|
| Language | Kotlin 1.9 |
| Build | Gradle (Kotlin DSL) |
| Parquet reading | Apache Parquet + Avro + Hadoop |
| ML | Smile (JVM machine learning library) |
| Database | SQLite via Exposed ORM |
| Web server | Ktor + Netty |
| Frontend | Bootstrap 5 |

## Project Structure

```
nfl-predictor/
├── build.gradle.kts
├── settings.gradle.kts
├── data/                          ← drop your parquet files here
│   ├── play_by_play_2022.parquet
│   ├── play_by_play_2023.parquet
│   └── play_by_play_2024.parquet
├── nfl_stats.db                   ← generated after ETL run
└── src/main/kotlin/
    ├── etl/
    │   ├── MainEtl.kt             ← ETL entry point
    │   ├── Extractor.kt           ← scans data/ and reads parquet files
    │   ├── Transformer.kt         ← engineers EWMA EPA features
    │   └── Loader.kt              ← writes to SQLite
    ├── ml/
    │   └── Model.kt               ← logistic regression + cross-validation
    ├── db/
    │   └── Schema.kt              ← Exposed table definitions
    └── web/
        └── MainServer.kt          ← Ktor web server + HTML pages
```

## Setup

### Prerequisites
- Java 17+ (IntelliJ bundles this — check with `java -version`)
- IntelliJ IDEA Community Edition (free)

### Steps
1. Open the `nfl-predictor/` folder in IntelliJ IDEA — Gradle will sync automatically
2. Get your data from nflfastR (R snippet below) and place files in `data/`

### Getting Data (R)

```r
install.packages("nflfastR")
library(nflfastR)
library(arrow)

# Download and save as parquet for any seasons you want
for (season in 2020:2024) {
  pbp <- load_pbp(season)
  write_parquet(pbp, paste0("play_by_play_", season, ".parquet"))
}
```

Files must be named exactly: `play_by_play_YYYY.parquet`

## Running the Project

### Step 1 — Run the ETL pipeline

```bash
./gradlew runEtl
```

This will:
- Auto-detect all `play_by_play_YYYY.parquet` files in `data/`
- Engineer EWMA EPA features per team per week
- Train a logistic regression model (all seasons except most recent)
- Evaluate with 10-fold cross-validation
- Write predictions and model stats to `nfl_stats.db`

Expected output:
```
=== NFL Game Outcome Predictor — ETL Pipeline ===

Found 3 parquet file(s):
  - play_by_play_2022.parquet
  - play_by_play_2023.parquet
  - play_by_play_2024.parquet
  Loaded 47832 plays from season 2022
  ...
Total plays extracted: 143201

Engineering features...
Built 814 game feature rows across 3 season(s).

Training seasons : [2022, 2023]
Test season      : 2024
Training on 543 games...
10-fold CV accuracy: 63.2%

Loaded 814 game predictions into SQLite.
=== Pipeline complete. Database written to: nfl_stats.db ===
```

### Step 2 — Start the web server

```bash
./gradlew runServer
```

Open your browser at: **http://localhost:8080**

## Dashboard Pages

| URL | What You See |
|---|---|
| `/` | Model CV accuracy, feature importance table |
| `/predictions` | All game predictions with win probabilities, filterable by season and week |
| `/epa` | Team EPA breakdown (pass/rush offense and defense) by season |

## Adding More Seasons

Just drop another `play_by_play_YYYY.parquet` file into `data/` and re-run `./gradlew runEtl`. The pipeline will automatically pick it up. The most recent season is always used as the held-out test set.

## Model Notes

- Expected accuracy: ~62–65% (consistent with the article's findings)
- The model always uses lagged EPA — it never uses the current game's data to predict itself
- The dynamic window EWMA means early-season weeks draw on prior season context
- Adding more seasons improves the model — more training data = more reliable coefficients
