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
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "Giffer2"
include(":app")
include(":core:model")
include(":core:ffmpeg")
include(":feature:home")

toolchainManagement {
    jvm {
        javaRepositories {
            repository("foojay") {
                url.set(uri("https://api.foojay.io/disco/v3.0/"))
            }
        }
    }
}
