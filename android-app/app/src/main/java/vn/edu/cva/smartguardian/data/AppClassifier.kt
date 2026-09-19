package vn.edu.cva.smartguardian.data

import android.content.pm.ApplicationInfo
import android.os.Build

enum class AppCategory(val displayName: String, val weight: Float) {
    STUDY("Học tập", 1.0f),
    GAME("Trò chơi", -1.0f),
    SOCIAL("Mạng xã hội", -0.5f),
    UTILITY("Tiện ích", 0.0f),
    OTHER("Khác", 0.0f)
}

data class AppMetadata(
    val packageName: String,
    val appName: String,
    val category: AppCategory
)

object AppClassifier {
    private val KNOWN_PACKAGES = mapOf(
        // 1. NHÓM HỌC TẬP (STUDY)
        "vn.azota.app" to AppMetadata("vn.azota.app", "Azota (Nộp bài tập)", AppCategory.STUDY),
        "vn.k12online.app" to AppMetadata("vn.k12online.app", "K12Online (Học trực tuyến)", AppCategory.STUDY),
        "vn.olm.app" to AppMetadata("vn.olm.app", "OLM.vn (Học trực tuyến)", AppCategory.STUDY),
        "com.viettel.smas" to AppMetadata("com.viettel.smas", "VT-SMAS (Sổ liên lạc điện tử)", AppCategory.STUDY),
        "com.viettel.vtsmas" to AppMetadata("com.viettel.vtsmas", "VT-SMAS", AppCategory.STUDY),
        "vn.edu.smas" to AppMetadata("vn.edu.smas", "VT-SMAS", AppCategory.STUDY),
        "com.duolingo" to AppMetadata("com.duolingo", "Duolingo (Học tiếng Anh)", AppCategory.STUDY),
        "org.khanacademy.android" to AppMetadata("org.khanacademy.android", "Khan Academy", AppCategory.STUDY),
        "com.vietjack.app" to AppMetadata("com.vietjack.app", "VietJack", AppCategory.STUDY),
        "com.google.android.apps.classroom" to AppMetadata("com.google.android.apps.classroom", "Google Classroom", AppCategory.STUDY),
        "org.geogebra.android" to AppMetadata("org.geogebra.android", "GeoGebra Toán học", AppCategory.STUDY),
        "com.microsoft.teams" to AppMetadata("com.microsoft.teams", "Microsoft Teams (Học online)", AppCategory.STUDY),
        "us.zoom.videomeetings" to AppMetadata("us.zoom.videomeetings", "Zoom Meetings (Học online)", AppCategory.STUDY),
        "com.google.android.apps.meetings" to AppMetadata("com.google.android.apps.meetings", "Google Meet (Học online)", AppCategory.STUDY),
        "com.vuihoc.app" to AppMetadata("com.vuihoc.app", "VuiHoc (Học online)", AppCategory.STUDY),

        // 2. NHÓM GAME (TRÒ CHƠI)
        "com.garena.game.kgvn" to AppMetadata("com.garena.game.kgvn", "Liên Quân Mobile", AppCategory.GAME),
        "com.dts.freefireth" to AppMetadata("com.dts.freefireth", "Free Fire", AppCategory.GAME),
        "com.roblox.client" to AppMetadata("com.roblox.client", "Roblox", AppCategory.GAME),
        "com.miHoYo.GenshinImpact" to AppMetadata("com.miHoYo.GenshinImpact", "Genshin Impact", AppCategory.GAME),
        "com.mojang.minecraftpe" to AppMetadata("com.mojang.minecraftpe", "Minecraft", AppCategory.GAME),
        "com.zing.zingspeedm" to AppMetadata("com.zing.zingspeedm", "ZingSpeed Mobile", AppCategory.GAME),
        "com.vng.pubgmobile" to AppMetadata("com.vng.pubgmobile", "PUBG Mobile VN", AppCategory.GAME),
        "com.garena.game.codm" to AppMetadata("com.garena.game.codm", "Call of Duty Mobile", AppCategory.GAME),
        "com.ea.gp.fifamobile" to AppMetadata("com.ea.gp.fifamobile", "EA SPORTS FC Mobile", AppCategory.GAME),
        "com.supercell.brawlstars" to AppMetadata("com.supercell.brawlstars", "Brawl Stars", AppCategory.GAME),
        "com.supercell.clashofclans" to AppMetadata("com.supercell.clashofclans", "Clash of Clans", AppCategory.GAME),
        "com.vng.playtogether" to AppMetadata("com.vng.playtogether", "Play Together", AppCategory.GAME),
        "com.fingersoft.hillclimb" to AppMetadata("com.fingersoft.hillclimb", "Hill Climb Racing", AppCategory.GAME),
        "com.king.candycrushsaga" to AppMetadata("com.king.candycrushsaga", "Candy Crush Saga", AppCategory.GAME),

        // 3. NHÓM MẠNG XÃ HỘI & GIẢI TRÍ
        "com.google.android.youtube" to AppMetadata("com.google.android.youtube", "YouTube", AppCategory.SOCIAL),
        "com.google.android.youtube.tv" to AppMetadata("com.google.android.youtube.tv", "YouTube TV", AppCategory.SOCIAL),
        "com.google.android.apps.youtube.music" to AppMetadata("com.google.android.apps.youtube.music", "YouTube Music", AppCategory.SOCIAL),
        "com.zhiliaoapp.musically" to AppMetadata("com.zhiliaoapp.musically", "TikTok", AppCategory.SOCIAL),
        "com.ss.android.ugc.trill" to AppMetadata("com.ss.android.ugc.trill", "TikTok", AppCategory.SOCIAL),
        "com.zhiliaoapp.musically.go" to AppMetadata("com.zhiliaoapp.musically.go", "TikTok Lite", AppCategory.SOCIAL),
        "com.facebook.katana" to AppMetadata("com.facebook.katana", "Facebook", AppCategory.SOCIAL),
        "com.facebook.lite" to AppMetadata("com.facebook.lite", "Facebook Lite", AppCategory.SOCIAL),
        "com.facebook.orca" to AppMetadata("com.facebook.orca", "Messenger", AppCategory.SOCIAL),
        "com.facebook.mlite" to AppMetadata("com.facebook.mlite", "Messenger Lite", AppCategory.SOCIAL),
        "com.instagram.android" to AppMetadata("com.instagram.android", "Instagram", AppCategory.SOCIAL),
        "com.zing.zalo" to AppMetadata("com.zing.zalo", "Zalo", AppCategory.SOCIAL),
        "org.telegram.messenger" to AppMetadata("org.telegram.messenger", "Telegram", AppCategory.SOCIAL),
        "com.twitter.android" to AppMetadata("com.twitter.android", "X (Twitter)", AppCategory.SOCIAL),
        "com.threads.android" to AppMetadata("com.threads.android", "Threads", AppCategory.SOCIAL)
    )

    fun classify(
        packageName: String,
        label: String = "",
        appInfo: ApplicationInfo? = null
    ): AppMetadata {
        // 1. Tra bảng định nghĩa sẵn
        KNOWN_PACKAGES[packageName]?.let { return it }

        // 2. Tra hạng mục chuẩn từ hệ điều hành Android (API 26+)
        if (appInfo != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            when (appInfo.category) {
                ApplicationInfo.CATEGORY_GAME -> {
                    return AppMetadata(packageName, label.ifEmpty { "Trò chơi" }, AppCategory.GAME)
                }
                ApplicationInfo.CATEGORY_SOCIAL -> {
                    return AppMetadata(packageName, label.ifEmpty { "Mạng xã hội" }, AppCategory.SOCIAL)
                }
                ApplicationInfo.CATEGORY_VIDEO, ApplicationInfo.CATEGORY_AUDIO -> {
                    return AppMetadata(packageName, label.ifEmpty { "Giải trí" }, AppCategory.SOCIAL)
                }
            }
        }

        // 3. Heuristic phân loại theo từ khóa nhãn ứng dụng
        val lower = (label.ifEmpty { packageName }).lowercase()
        return when {
            lower.contains("game") || lower.contains("chơi") || lower.contains("bắn") ||
                    lower.contains("roblox") || lower.contains("racing") || lower.contains("clash") ||
                    lower.contains("craft") || lower.contains("football") || lower.contains("fifa") ->
                AppMetadata(packageName, label.ifEmpty { "Trò chơi" }, AppCategory.GAME)

            lower.contains("học") || lower.contains("toán") || lower.contains("văn") ||
                    lower.contains("edu") || lower.contains("smas") || lower.contains("azota") ||
                    lower.contains("k12") || lower.contains("study") || lower.contains("tuyên giáo") ->
                AppMetadata(packageName, label.ifEmpty { "Học tập" }, AppCategory.STUDY)

            lower.contains("chat") || lower.contains("video") || lower.contains("social") ||
                    lower.contains("tiktok") || lower.contains("mess") || lower.contains("face") ||
                    lower.contains("insta") ->
                AppMetadata(packageName, label.ifEmpty { "Mạng xã hội" }, AppCategory.SOCIAL)

            else ->
                AppMetadata(packageName, label.ifEmpty { packageName }, AppCategory.UTILITY)
        }
    }
}
