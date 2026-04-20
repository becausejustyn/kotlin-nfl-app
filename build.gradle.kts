plugins {
    kotlin("jvm") version "1.9.23"
}

group = "com.nflpredictor"
version = "1.0.0"

repositories {
    mavenCentral()
}

val ktorVersion = "2.3.10"
val exposedVersion = "0.50.1"
val smileVersion = "3.0.2"

dependencies {
    implementation(kotlin("stdlib"))

    // Parquet + Hadoop (for reading .parquet files)
    implementation("org.apache.parquet:parquet-avro:1.13.1")
    implementation("org.apache.hadoop:hadoop-common:3.3.6") {
        exclude(group = "org.slf4j", module = "slf4j-reload4j")
        exclude(group = "ch.qos.reload4j", module = "reload4j")
        exclude(group = "log4j", module = "log4j")
    }
    implementation("org.apache.hadoop:hadoop-mapreduce-client-core:3.3.6") {
        exclude(group = "org.slf4j", module = "slf4j-reload4j")
        exclude(group = "ch.qos.reload4j", module = "reload4j")
        exclude(group = "log4j", module = "log4j")
    }
    implementation("org.apache.avro:avro:1.11.3")

    // Smile — JVM machine learning library (logistic regression, cross-validation)
    implementation("com.github.haifengl:smile-core:$smileVersion")
    implementation("com.github.haifengl:smile-kotlin:$smileVersion")

    // Exposed ORM + SQLite
    implementation("org.jetbrains.exposed:exposed-core:$exposedVersion")
    implementation("org.jetbrains.exposed:exposed-dao:$exposedVersion")
    implementation("org.jetbrains.exposed:exposed-jdbc:$exposedVersion")
    implementation("org.xerial:sqlite-jdbc:3.45.3.0")

    // Ktor web server
    implementation("io.ktor:ktor-server-core:$ktorVersion")
    implementation("io.ktor:ktor-server-netty:$ktorVersion")
    implementation("io.ktor:ktor-server-html-builder:$ktorVersion")

    // Logging
    implementation("ch.qos.logback:logback-classic:1.4.14")
    implementation("org.slf4j:slf4j-api:2.0.13")
}

// ETL + model training entry point
tasks.register<JavaExec>("runEtl") {
    group = "application"
    description = "Run ETL pipeline: read parquet files, engineer features, train model, write SQLite"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("etl.MainEtlKt")
    // Smile needs more stack space for some operations
    jvmArgs = listOf("-Xss4m", "-Xmx2g")
}

// Web server entry point
tasks.register<JavaExec>("runServer") {
    group = "application"
    description = "Start Ktor web dashboard at http://localhost:8080"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("web.MainServerKt")
}

kotlin {
    jvmToolchain(17)
}
