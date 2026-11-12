plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.zohar.slotar"
    compileSdk = 37
    defaultConfig {
        applicationId = "com.zohar.slotar"
        minSdk = 26
        targetSdk = 36
        versionCode = 22
        versionName = "9.1"
        buildConfigField("String", "MAPBOX_TOKEN", "\"\"")
        buildConfigField("String", "AR_GEO_KEY", "\"\"")
        manifestPlaceholders["arGeoKey"] = ""
    }
    // two separate apps: the slot machine keeps its old id (installs over the old APK), the scratch cards get their own
    flavorDimensions += "game"
    productFlavors {
        create("slot") { dimension = "game"; applicationId = "com.zohar.slotar"; buildConfigField("String", "GAME", "\"slot\""); resValue("string", "app_name", "ZOHAR Slot AR") }
        create("blackjack") { dimension = "game"; applicationId = "com.zohar.blackjackar"; buildConfigField("String", "GAME", "\"blackjack\""); resValue("string", "app_name", "ZOHAR Blackjack AR") }
        create("holdem") { dimension = "game"; applicationId = "com.zohar.holdemar"; buildConfigField("String", "GAME", "\"holdem\""); resValue("string", "app_name", "ZOHAR Hold\\'em AR") }
        create("baccarat") { dimension = "game"; applicationId = "com.zohar.baccaratar"; buildConfigField("String", "GAME", "\"baccarat\""); resValue("string", "app_name", "ZOHAR Baccarat AR") }
        create("roulette") { dimension = "game"; applicationId = "com.zohar.roulettear"; buildConfigField("String", "GAME", "\"roulette\""); resValue("string", "app_name", "ZOHAR Roulette AR") }
        create("moneywheel") { dimension = "game"; applicationId = "com.zohar.moneywheelar"; buildConfigField("String", "GAME", "\"moneywheel\""); resValue("string", "app_name", "ZOHAR Money Wheel AR") }
        create("scratch") { dimension = "game"; applicationId = "com.zohar.scratchar"; buildConfigField("String", "GAME", "\"scratch\""); resValue("string", "app_name", "ZOHAR Scratch AR") }
        // Higher or Lower: a full game now, a bonus round inside the other games later (same engine and table)
        // Craps (Pass Line + Field) on its own tub table, real casino dice
        create("craps") { dimension = "game"; applicationId = "com.zohar.crapsar"; buildConfigField("String", "GAME", "\"craps\""); resValue("string", "app_name", "ZOHAR Craps AR"); versionCode = 1; versionName = "1.0" }
        // Plinko: a standing neon board, one ball a drop
        create("plinko") { dimension = "game"; applicationId = "com.zohar.plinkoar"; buildConfigField("String", "GAME", "\"plinko\""); resValue("string", "app_name", "ZOHAR Plinko AR"); versionCode = 1; versionName = "1.0" }
        // the three bonus mini-games (Higher or Lower, Dice, Plinko): glowing pieces with no table, one short round each
        create("bonus") { dimension = "game"; applicationId = "com.zohar.bonusar"; buildConfigField("String", "GAME", "\"bonus\""); resValue("string", "app_name", "ZOHAR Bonus AR"); versionCode = 1; versionName = "1.0" }
        // SKY RUSH, a crash game: a launch pad on your table, a jet that flies through the room
        create("crash") { dimension = "game"; applicationId = "com.zohar.crashar"; buildConfigField("String", "GAME", "\"crash\""); resValue("string", "app_name", "ZOHAR Sky Rush AR"); versionCode = 1; versionName = "1.0" }
        create("hilo") { dimension = "game"; applicationId = "com.zohar.hiloar"; buildConfigField("String", "GAME", "\"hilo\""); resValue("string", "app_name", "ZOHAR Hi-Lo AR"); versionCode = 1; versionName = "1.0" }
    }
    // Hold'em reuses the blackjack dealer, cards, chips and layout; it adds its own table
    sourceSets { getByName("holdem") { assets.srcDirs("src/blackjack/assets", "src/holdem/assets") }; getByName("baccarat") { assets.srcDirs("src/blackjack/assets", "src/baccarat/assets") }; getByName("roulette") { assets.srcDirs("src/blackjack/assets", "src/roulette/assets") }; getByName("moneywheel") { assets.srcDirs("src/blackjack/assets", "src/moneywheel/assets") }; getByName("hilo") { assets.srcDirs("src/blackjack/assets", "src/hilo/assets") }; getByName("craps") { assets.srcDirs("src/blackjack/assets", "src/craps/assets") }; getByName("plinko") { assets.srcDirs("src/blackjack/assets", "src/plinko/assets") }; getByName("bonus") { assets.srcDirs("src/blackjack/assets", "src/plinko/assets", "src/bonus/assets") } }
    splits { abi { isEnable = false } }
    // fixed sideload key, so a new APK installs over the old one (not a Play Store key)
    signingConfigs {
        create("zohar") { storeFile = file("zohar-sideload.jks"); storePassword = "zoharplay"; keyAlias = "zohar"; keyPassword = "zoharplay" }
    }
    buildTypes {
        release {
            ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }   // phones; the debug build keeps x86_64 for the emulator smoke test
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("zohar")
        }
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_21; targetCompatibility = JavaVersion.VERSION_21 }
    buildFeatures { compose = true; buildConfig = true; resValues = true }
    androidResources { noCompress += listOf("task", "glb", "wav", "filamat", "ktx") }
    testOptions { unitTests.isReturnDefaultValues = true }
    packaging { resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" } }
}

kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21) } }

dependencies {
    implementation("io.github.sceneview:arsceneview:4.60.0")
    implementation(platform("androidx.compose:compose-bom:2026.09.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("com.google.mediapipe:tasks-vision:0.10.26") { exclude(group = "com.google.auto.value", module = "auto-value") }
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")   // the real org.json for JVM tests (android.jar only has stubs)
}
