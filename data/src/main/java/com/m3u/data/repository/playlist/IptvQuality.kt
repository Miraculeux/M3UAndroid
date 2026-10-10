package com.m3u.data.repository.playlist

enum class IptvQuality(val preferenceValue: String, val assetName: String) {
    HD("1080p", "iptv.m3u"),
    UHD_LOW("4k_low", "iptv-4k-low.m3u"),
    UHD_HIGH("4k_high", "iptv-4k.m3u");

    fun next(): IptvQuality = entries[(ordinal + 1) % entries.size]

    companion object {
        fun fromPreference(value: String?, legacyPrefer4k: Boolean): IptvQuality =
            if (value == null) {
                if (legacyPrefer4k) UHD_HIGH else HD
            } else {
                checkNotNull(entries.firstOrNull { it.preferenceValue == value }) {
                    "Unknown IPTV quality preference: $value"
                }
            }
    }
}
