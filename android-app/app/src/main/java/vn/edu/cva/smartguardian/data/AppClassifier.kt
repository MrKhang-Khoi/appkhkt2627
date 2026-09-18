package vn.edu.cva.smartguardian.data

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
        "com.duolingo" to AppMetadata("com.duolingo", "Duolingo (Học tiếng Anh)", AppCategory.STUDY),
        "org.khanacademy.android" to AppMetadata("org.khanacademy.android", "Khan Academy", AppCategory.STUDY),
        "com.vietjack.app" to AppMetadata("com.vietjack.app", "VietJack", AppCategory.STUDY),
        "com.google.android.apps.classroom" to AppMetadata("com.google.android.apps.classroom", "Google Classroom", AppCategory.STUDY),
        "org.geogebra.android" to AppMetadata("org.geogebra.android", "GeoGebra Toán học", AppCategory.STUDY),

        // 2. NHÓM GAME (TRÒ CHƠI)
        "com.garena.game.kgvn" to AppMetadata("com.garena.game.kgvn", "Liên Quân Mobile", AppCategory.GAME),
        "com.dts.freefireth" to AppMetadata("com.dts.freefireth", "Free Fire", AppCategory.GAME),
        "com.roblox.client" to AppMetadata("com.roblox.client", "Roblox", AppCategory.GAME),
        "com.miHoYo.GenshinImpact" to AppMetadata("com.miHoYo.GenshinImpact", "Genshin Impact", AppCategory.GAME),
        "com.mojang.minecraftpe" to AppMetadata("com.mojang.minecraftpe", "Minecraft", AppCategory.GAME),
        "com.zing.zingspeedm" to AppMetadata("com.zing.zingspeedm", "ZingSpeed Mobile", AppCategory.GAME),
        "com.vng.pubgmobile" to AppMetadata("com.vng.pubgmobile", "PUBG Mobile VN", AppCategory.GAME),

        // 3. NHÓM MẠNG XÃ HỘI & GIẢI TRÍ
        "com.zhiliaoapp.musically" to AppMetadata("com.zhiliaoapp.musically", "TikTok", AppCategory.SOCIAL),
        "com.facebook.katana" to AppMetadata("com.facebook.katana", "Facebook", AppCategory.SOCIAL),
        "com.instagram.android" to AppMetadata("com.instagram.android", "Instagram", AppCategory.SOCIAL),
        "com.google.android.youtube" to AppMetadata("com.google.android.youtube", "YouTube", AppCategory.SOCIAL),
        "com.zing.zalo" to AppMetadata("com.zing.zalo", "Zalo", AppCategory.SOCIAL)
    )

    fun classify(packageName: String, label: String = ""): AppMetadata {
        KNOWN_PACKAGES[packageName]?.let { return it }

        val lower = (label.ifEmpty { packageName }).lowercase()
        return when {
            lower.contains("game") || lower.contains("chơi") || lower.contains("bắn") || lower.contains("roblox") ->
                AppMetadata(packageName, label.ifEmpty { "Trò chơi" }, AppCategory.GAME)
            lower.contains("học") || lower.contains("toán") || lower.contains("văn") || lower.contains("edu") ->
                AppMetadata(packageName, label.ifEmpty { "Học tập" }, AppCategory.STUDY)
            lower.contains("chat") || lower.contains("video") || lower.contains("social") ->
                AppMetadata(packageName, label.ifEmpty { "Mạng xã hội" }, AppCategory.SOCIAL)
            else ->
                AppMetadata(packageName, label.ifEmpty { packageName }, AppCategory.UTILITY)
        }
    }
}
