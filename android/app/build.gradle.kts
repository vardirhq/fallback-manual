import com.android.build.api.variant.ApplicationVariant

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "app.fallbackmanual"
    compileSdk = 36

    defaultConfig {
        applicationId = "app.fallbackmanual"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64") }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        debug {
            applicationIdSuffix = ".debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { compose = true }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
    testOptions { unitTests.isIncludeAndroidResources = true }
}

kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }

dependencies {
    implementation(project(":core"))
    implementation(platform("androidx.compose:compose-bom:2025.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core:1.7.8")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.core:core-ktx:1.16.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.16.1")
    testImplementation("io.github.takahirom.roborazzi:roborazzi:1.76.0")
    testImplementation("io.github.takahirom.roborazzi:roborazzi-compose:1.76.0")
    testImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}

// Robolectric runs on the desktop JVM, so the app's Android SQLite library can't load there.
// Give it the desktop (linux x64) build of the same bundled SQLite instead.
val sqliteNativeJvm by configurations.creating
dependencies { sqliteNativeJvm("androidx.sqlite:sqlite-bundled-jvm:2.5.2") { isTransitive = false } }
val extractSqliteNative by tasks.registering(Sync::class) {
    from({ sqliteNativeJvm.map { zipTree(it) } }) { include("natives/linux_x64/*.so"); eachFile { path = name } }
    includeEmptyDirs = false
    into(layout.buildDirectory.dir("sqlite-native"))
}
tasks.withType<Test>().configureEach {
    dependsOn(extractSqliteNative)
    systemProperty("java.library.path", layout.buildDirectory.dir("sqlite-native").get().asFile.absolutePath)
    systemProperty("roborazzi.test.record", "true")
    systemProperty("screenshots.dir", rootProject.layout.projectDirectory.dir("../docs/screenshots").asFile.absolutePath)
}

/**
 * Compiles knowledge/ into knowledge.db with tools/build_kb.py and adds it to the APK's assets.
 * Release builds pass --release, which fails while any article is unreviewed. To build a
 * release-type APK with draft content anyway (for testing only), pass -PallowDrafts=true;
 * the app then shows its development-build warning.
 */
abstract class BuildKnowledgeDb : DefaultTask() {
    @get:InputDirectory @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val knowledgeDir: DirectoryProperty

    @get:InputDirectory @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val toolsDir: DirectoryProperty

    @get:Input
    abstract val release: Property<Boolean>

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @get:Inject
    abstract val execOps: ExecOperations

    @TaskAction
    fun build() {
        val out = outputDir.get().file("knowledge.db").asFile
        val args = mutableListOf("python3", "-m", "tools.build_kb", "--quiet", "--out", out.absolutePath)
        if (release.get()) args += "--release"
        execOps.exec {
            workingDir = knowledgeDir.get().asFile.parentFile
            commandLine(args)
        }
    }
}

val repoRoot = rootProject.layout.projectDirectory.dir("..")
val allowDrafts = providers.gradleProperty("allowDrafts").map { it.toBoolean() }.orElse(false)

androidComponents {
    onVariants { variant: ApplicationVariant ->
        val name = variant.name.replaceFirstChar { it.uppercase() }
        val task = tasks.register<BuildKnowledgeDb>("buildKnowledgeDb$name") {
            knowledgeDir.set(repoRoot.dir("knowledge"))
            toolsDir.set(repoRoot.dir("tools"))
            release.set(allowDrafts.map { !it && variant.buildType == "release" })
        }
        variant.sources.assets?.addGeneratedSourceDirectory(task, BuildKnowledgeDb::outputDir)
    }
}
