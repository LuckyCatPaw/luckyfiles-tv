plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
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
tasks.named("preBuild") {
    dependsOn(ktlintCheck)
}

// And on check as well, so `./gradlew check` needs no compilation to be useful.
tasks.named("check") {
    dependsOn(ktlintCheck)
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

    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
}
