plugins {
    java
}

group = "com.serverdashboard.teams"
version = "1.0.0"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:1.21.4-R0.1-SNAPSHOT")
    compileOnly(files("../ServerDashboard/build/libs/ServerDashboard-1.9.0.jar"))
}

tasks {
    compileJava {
        options.encoding = "UTF-8"
        options.release.set(21)
    }
    jar {
        archiveBaseName.set("ServerDashboard-Teams")
        archiveVersion.set(project.version.toString())
        archiveClassifier.set("")
        duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    }
}
