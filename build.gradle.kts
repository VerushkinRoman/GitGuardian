plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.shadow)
    application
}

application {
    mainClass.set("com.guardian.cli.GuardianCLIKt")
    applicationDefaultJvmArgs = listOf("-Xmx512m")
}

repositories {
    mavenCentral()
}

dependencies {
    implementation(libs.bundles.ktor)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.slf4j.simple)
}

kotlin {
    jvmToolchain(17)
}

tasks.shadowJar {
    archiveClassifier.set("all")
    mergeServiceFiles()
    manifest.attributes("Main-Class" to "com.guardian.cli.GuardianCLIKt")
    from(rootDir) {
        include("keys.properties")
    }
    doLast {
        val installDir = file("${System.getProperty("user.home")}/.gitGuardian")
        installDir.mkdirs()
        copy {
            from(archiveFile)
            into(installDir)
            rename { "gitGuardian-all.jar" }
        }
        copy {
            from("gitGuardian")
            into(installDir)
        }
        copy {
            from("gitGuardian.bat")
            into(installDir)
        }
        File(installDir, "gitGuardian").setExecutable(true)
        println()
        println("Installed to ${installDir.absolutePath}/")
        println("  gitGuardian-all.jar")
        println("  gitGuardian      (macOS/Linux)")
        println("  gitGuardian.bat  (Windows)")
        println()
        println("macOS/Linux — add to ~/.zshrc:")
        println($$"  export PATH=\"$${installDir.absolutePath}:$PATH\"")
        println()
        println("Windows — add to PATH:")
        println("  ${installDir.absolutePath}")
    }
}
