package io.github.selfupdater

/** Release track selected from a channel-aware update manifest. */
public enum class UpdateChannel(internal val manifestKey: String) {
    /** Production releases. Legacy manifests without `channels` are treated as stable. */
    STABLE("stable"),

    /** Prerelease builds intended for opt-in testing. */
    BETA("beta"),
}
