pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
// NO foojay-resolver-convention here, deliberately. That plugin auto-downloads a JDK from api.foojay.io
// when the requested toolchain isn't installed locally. This project already requires JDK 21 (see
// gradle/gradle-daemon-jvm.properties and .agents/rules/build-and-test.md), and for a build that has to be
// byte-reproducible on someone else's machine, "silently fetch a JDK over the network" is the wrong
// failure mode twice over: it makes the compiler a moving, unpinned input, and it needs outbound network
// a locked-down builder may not grant. Without it Gradle fails loudly with "no matching toolchain", which
// the builder fixes by installing JDK 21.
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "Knit"
include(":app")
