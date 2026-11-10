import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    // 只用 lightweight API（org.bouncycastle.crypto.*），不注册 JCE Provider
    implementation(libs.bouncycastle)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)

    testImplementation(libs.junit)
    testImplementation(libs.gson)
    testImplementation(libs.kotlinx.coroutines.test)
}

// golden vectors 由 tools/vectors/gen.mjs 生成，产物 vectors.json 放在 tools/vectors/ 下。
// 这里把它挂进测试资源根目录，测试用 /vectors.json 读取。
sourceSets {
    test {
        resources.srcDir(rootProject.layout.projectDirectory.dir("tools/vectors"))
    }
}

tasks.withType<Test>().configureEach {
    useJUnit()
    testLogging {
        events("passed", "skipped", "failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
