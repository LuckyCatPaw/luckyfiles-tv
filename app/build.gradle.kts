import com.android.build.api.variant.HasUnitTestBuilder
import io.gitlab.arturbosch.detekt.Detekt
import io.gitlab.arturbosch.detekt.DetektCreateBaselineTask

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.detekt)
}

val ktlintTool = configurations.create("ktlintTool") {
    isCanBeConsumed = false
    isCanBeResolved = true
}

dependencies {
    ktlintTool(libs.ktlint.cli)
}

// The whole checkout, not just app/src: settings.gradle.kts and both
// build.gradle.kts files are Kotlin too, and would otherwise be the only ones
// left to drift.
val ktlintSources = fileTree(rootDir) {
    include("**/*.kt", "**/*.kts")
    exclude("**/build/**", "**/.gradle/**", "**/.git/**", "**/.idea/**")
}

val ktlintReport = layout.buildDirectory.file("reports/ktlint/ktlint-checkstyle.xml")

val ktlintCheck = tasks.register<JavaExec>("ktlintCheck") {
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    description = "Checks Kotlin sources against the rules in .editorconfig."

    classpath = ktlintTool
    mainClass.set("com.pinterest.ktlint.Main")
    workingDir = rootDir

    // Without declared inputs and outputs the task runs on every invocation.
    // Hanging off preBuild, that would be a fixed surcharge on every single
    // build, including the ones where no Kotlin was touched at all.
    inputs.files(ktlintSources)
        .withPropertyName("ktlintSources")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.file(rootDir.resolve(".editorconfig"))
        .withPropertyName("editorconfig")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    outputs.file(ktlintReport)
    outputs.cacheIf { true }

    args(
        "--relative",
        "--reporter=plain",
        "--reporter=checkstyle,output=${ktlintReport.get().asFile}",
        "**/*.kt",
        "**/*.kts",
        "!**/build/**"
    )
}

val ktlintFormat = tasks.register<JavaExec>("ktlintFormat") {
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    description = "Rewrites Kotlin sources to match the rules in .editorconfig."

    classpath = ktlintTool
    mainClass.set("com.pinterest.ktlint.Main")
    workingDir = rootDir

    // Deliberately never up to date: the task writes its own inputs.
    outputs.upToDateWhen { false }

    args(
        "--format",
        "--relative",
        "--reporter=plain",
        "**/*.kt",
        "**/*.kts",
        "!**/build/**"
    )
}

// Before every build. preBuild is what every variant hangs off, so this covers
// assembleDebug the same way it covers bundlePlayRelease.
//
// Only ktlint hangs here. detekt takes far longer, so it belongs on check rather than
// in front of every build.
tasks.named("preBuild") {
    dependsOn(ktlintCheck)
}

// And on check as well, so `./gradlew check` needs no compilation to be useful.
//
// Both lint variants by name rather than the `lint` task AGP wires in: that one covers
// the default variant alone, which would silently leave the system flavor unchecked. The
// pair here is what the CI workflow runs, so a green `check` means a green pipeline.
//
// detekt is the plain task because under AGP 9 there is no other: AGP's built-in Kotlin
// stops the detekt plugin from creating its per-variant tasks, so `detekt` is all that
// gets registered. It runs without type resolution, which leaves the rules needing a
// resolved type inert - RedundantSuspendModifier, SuspendFunWithFlowReturnType,
// SuspendFunWithCoroutineScopeReceiver and part of potential-bugs. Worth re-checking on
// the next detekt upgrade with `./gradlew tasks --all`.
tasks.named("check") {
    dependsOn(ktlintCheck)
    dependsOn("detekt")
    dependsOn("lintPlayDebug", "lintSystemDebug")
}

android {
    namespace = "com.luckycatpaw.luckyfilestv"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.luckycatpaw.luckyfilestv"
        minSdk = 30
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"
    }

    lint {
        abortOnError = true
        warningsAsErrors = true
        checkDependencies = false
        checkTestSources = true
        checkReleaseBuilds = true
        explainIssues = true
    }

    flavorDimensions += "distribution"

    productFlavors {
        create("play") {
            dimension = "distribution"
        }

        create("system") {
            dimension = "distribution"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false

            proguardFiles(
                getDefaultProguardFile(
                    "proguard-android-optimize.txt"
                ),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    testOptions {
        // Local unit tests run against a stubbed android.jar whose methods throw
        // by default. Anything that touches android.util.Log on the way through —
        // FileRepository does, on every failed operation — would then fail on the
        // stub rather than on the behaviour under test.
        unitTests.isReturnDefaultValues = true
    }
}

detekt {
    // Without this the file below replaces the default configuration instead of
    // overriding it, and every rule it does not mention behaves unpredictably. It also
    // means new rules from a detekt upgrade arrive switched on rather than staying
    // silent because the config never heard of them.
    buildUponDefaultConfig = true
    allRules = false
    config.setFrom(files("$rootDir/config/detekt/detekt.yml"))

    // The thresholds in that file describe where the code should end up, not where it
    // is. The gap lives here, so anything new fails the build while the existing
    // findings stay a list that can be worked off. Create it with:
    //
    //   ./gradlew detektBaseline
    baseline = file("$rootDir/config/detekt/baseline.xml")
    parallel = true
}

tasks.withType<Detekt>().configureEach {
    // Defaults to 1.8 and then disagrees with everything else in the build.
    jvmTarget = JavaVersion.VERSION_17.toString()

    reports {
        html.required.set(true)
        // Read by GitHub code scanning when the workflow uploads it.
        sarif.required.set(true)
        xml.required.set(false)
        txt.required.set(false)
        md.required.set(false)
    }
}

tasks.withType<DetektCreateBaselineTask>().configureEach {
    jvmTarget = JavaVersion.VERSION_17.toString()
}

// The unit tests live in src/test and know nothing about flavors or build types, so all
// four variants would run the identical suite — and compile it four times to do so. That
// is why the CI workflow names one variant instead of calling the `test` task. Enabling
// only that variant lets `check` do the same without listing tasks by hand. Keep the name
// in step with the workflow.
//
// The cast is how the API is reached: enableUnitTest lives on HasUnitTestBuilder, and
// beforeVariants hands over a builder type that does not expose it.
androidComponents {
    beforeVariants { variantBuilder ->
        (variantBuilder as HasUnitTestBuilder).enableUnitTest = variantBuilder.name == "playDebug"
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.tv.material)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.material.icons.extended)

    // SMB. bouncycastle is what makes SMB3 encryption and signing work; without it smbj
    // falls back and some servers refuse the session. slf4j-android routes the library's
    // logging into logcat instead of leaving it unbound.
    implementation(libs.smbj)
    implementation(libs.bouncycastle.prov)
    implementation(libs.slf4j.api)
    implementation(libs.slf4j.android)

    // kotlin-test resolves to its JUnit 4 flavour because junit is on the same
    // classpath, so @Test comes from JUnit and the assertions from kotlin.test.
    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlinx.coroutines.test)

    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
}
