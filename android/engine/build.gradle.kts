plugins {
    id("org.jetbrains.kotlin.jvm")
}

dependencies {
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

sourceSets {
    named("test") {
        resources.srcDir("../fixtures")
    }
}

tasks.test {
    useJUnitPlatform()
    maxHeapSize = "2g"
    testLogging {
        events("passed", "failed", "skipped")
    }
}

// Offline HARD bank: ./gradlew -p engine generateHardBank -Pout=<path> -Pcount=50
tasks.register<JavaExec>("generateHardBank") {
    group = "build"
    description = "Generate the bundled HARD puzzle bank (unique puzzles)"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("io.github.cuso4deposit.regexcrossword.engine.BankGeneratorKt")
    maxHeapSize = "2g"
    args(
        (project.findProperty("out") as String?) ?: "hard_bank.json",
        (project.findProperty("count") as String?) ?: "50",
        (project.findProperty("base") as String?) ?: "3000000",
    )
}
