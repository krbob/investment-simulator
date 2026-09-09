plugins {
    application
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

allprojects {
    group = "net.bobinski.investmentsimulator"
    version = "0.2.0-SNAPSHOT"

    pluginManager.withPlugin("org.jetbrains.kotlin.jvm") {
        extensions.configure<org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension> {
            jvmToolchain(21)
        }
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
    }
}

application {
    mainClass = "net.bobinski.investmentsimulator.ApplicationKt"
}

dependencies {
    implementation(project(":engine"))
    implementation(project(":portfolio-adapter"))
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.server.status.pages)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.logback.classic)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.swagger.parser)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.named<JavaExec>("run") {
    standardInput = System.`in`
}

tasks.test {
    inputs.file(layout.projectDirectory.file("openapi/investment-simulator-v1.json"))
    inputs.file(layout.projectDirectory.file("portfolio-adapter/src/test/resources/portfolio-bundle.json"))
}
