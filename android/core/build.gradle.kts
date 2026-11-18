plugins {
    id("org.jetbrains.kotlin.jvm")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }

dependencies {
    // Bundled SQLite: Android's built-in SQLite lacks FTS5, and bundling makes search
    // behave identically on every device and in JVM unit tests.
    api("androidx.sqlite:sqlite-bundled:2.5.2")
    testImplementation("junit:junit:4.13.2")
}

val repoRoot = rootProject.layout.projectDirectory.dir("..")

// The parity test compares this module's search with the Python reference implementation.
val exportExpected by tasks.registering(Exec::class) {
    group = "verification"
    description = "Builds the knowledge DB and records the Python search results."
    workingDir = repoRoot.asFile
    inputs.dir(repoRoot.dir("knowledge"))
    inputs.dir(repoRoot.dir("tools"))
    inputs.dir(repoRoot.dir("retrieval"))
    inputs.dir(repoRoot.dir("evaluation"))
    outputs.files(repoRoot.file("build/knowledge.db"), repoRoot.file("build/expected_search.tsv"))
    commandLine("sh", "-c", "python3 -m tools.build_kb --quiet && python3 -m evaluation.export_expected")
}

tasks.test {
    dependsOn(exportExpected)
    systemProperty("kb.path", repoRoot.file("build/knowledge.db").asFile.absolutePath)
    systemProperty("expected.path", repoRoot.file("build/expected_search.tsv").asFile.absolutePath)
    testLogging { events("failed"); exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL }
}
