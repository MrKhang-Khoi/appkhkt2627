package vn.edu.cva.smartguardian.data

import java.net.URI
import java.util.Locale

enum class WebCategory(val title: String) {
    SAFE("An toàn"),
    ADULT("Nội dung khiêu dâm, người lớn"),
    GAMBLING("Cờ bạc, cá cược trực tuyến"),
    SCAM("Lừa đảo, giả mạo, mã độc"),
    VIOLENCE("Bạo lực, vũ khí, chất cấm")
}

data class FilterResult(
    val isBlocked: Boolean,
    val category: WebCategory,
    val reason: String,
    val matchedDomainOrKeyword: String = ""
)

object WebFilterList {

    // 1. DANH SÁCH TÊN MIỀN ĐỘC HẠI ĐÃ BIẾT (KNOWN DOMAIN BLACKLIST)
    private val BLOCKED_DOMAINS = mapOf(
        // CỜ BẠC, CÁ ĐỘ (GAMBLING)
        "kubet.com" to WebCategory.GAMBLING,
        "kubet77.com" to WebCategory.GAMBLING,
        "thabet.com" to WebCategory.GAMBLING,
        "sunwin.vin" to WebCategory.GAMBLING,
        "go88.vin" to WebCategory.GAMBLING,
        "rikvip.fun" to WebCategory.GAMBLING,
        "b52.club" to WebCategory.GAMBLING,
        "789club.org" to WebCategory.GAMBLING,
        "w88.com" to WebCategory.GAMBLING,
        "m88.com" to WebCategory.GAMBLING,
        "fun88.com" to WebCategory.GAMBLING,
        "fb88.com" to WebCategory.GAMBLING,
        "188bet.com" to WebCategory.GAMBLING,
        "bet365.com" to WebCategory.GAMBLING,

        // NỘI DUNG KHIÊU DÂM, NGƯỜI LỚN (ADULT)
        "pornhub.com" to WebCategory.ADULT,
        "xvideos.com" to WebCategory.ADULT,
        "xnxx.com" to WebCategory.ADULT,
        "xhamster.com" to WebCategory.ADULT,
        "javhd.com" to WebCategory.ADULT,
        "redtube.com" to WebCategory.ADULT,
        "youporn.com" to WebCategory.ADULT,
        "onlyfans.com" to WebCategory.ADULT,
        "hentaihaven.xxx" to WebCategory.ADULT,
        "vlxx.tv" to WebCategory.ADULT,
        "phimsex.tv" to WebCategory.ADULT,
        "sexvl.net" to WebCategory.ADULT,
        "thienvadia.com" to WebCategory.ADULT,

        // LỪA ĐẢO, PHISHING, NẠP THẺ GIẢ (SCAM)
        "napthegiare.vn" to WebCategory.SCAM,
        "nhanquafreefire.com" to WebCategory.SCAM,
        "nhanqualienquan.vn" to WebCategory.SCAM,
        "hackaccfacebook.com" to WebCategory.SCAM,
        "kiemtienonline247.net" to WebCategory.SCAM,
        "vaytiennhanh-online.com" to WebCategory.SCAM
    )

    // 2. TỪ KHÓA ĐỘC HẠI TRONG URL HOẶC TIÊU ĐỀ (HEURISTIC KEYWORD DETECTION)
    private val ADULT_KEYWORDS = listOf(
        "porn", "sex", "xxx", "xvideos", "xnxx", "phimsex", "hentai",
        "jav", "phim18", "gai-goi", "goidau", "loanthuan", "nudity"
    )

    private val GAMBLING_KEYWORDS = listOf(
        "nha-cai", "nhacai", "ca-cuoc", "cacuoc", "danh-bac", "danhbac",
        "tai-xiu", "taixiu", "nohu", "no-hu", "lo-de", "lode", "xo-so-online",
        "casino", "quay-hu", "bacarat", "baccarat", "keonhacai"
    )

    private val SCAM_KEYWORDS = listOf(
        "nhan-qua-free", "hack-nick", "nap-the-lau", "mod-skin-mien-phi",
        "kiem-tien-tai-nha", "vay-nong-nhanh"
    )

    /**
     * Kiểm tra một URL hoặc tên miền xem có thuộc danh mục bị chặn hay không
     */
    fun checkUrl(rawUrl: String): FilterResult {
        if (rawUrl.isBlank()) {
            return FilterResult(false, WebCategory.SAFE, "")
        }

        val cleanUrl = rawUrl.trim().lowercase(Locale.ROOT)

        // Trích xuất hostname từ URL
        val host = extractHostname(cleanUrl)

        // 1. Kiểm tra chính xác tên miền hoặc tên miền cha (Domain & Subdomain Matching)
        for ((domain, category) in BLOCKED_DOMAINS) {
            if (host == domain || host.endsWith(".$domain")) {
                return FilterResult(
                    isBlocked = true,
                    category = category,
                    reason = "Tên miền nằm trong danh sách đen cảnh báo của nhà trường & phụ huynh.",
                    matchedDomainOrKeyword = domain
                )
            }
        }

        // 2. Phân tích ngữ nghĩa từ khóa trong đường dẫn URL (Keyword Inspection)
        for (kw in ADULT_KEYWORDS) {
            if (cleanUrl.contains(kw)) {
                return FilterResult(
                    isBlocked = true,
                    category = WebCategory.ADULT,
                    reason = "Phát hiện từ khóa liên quan đến nội dung người lớn/khiêu dâm không phù hợp với học sinh.",
                    matchedDomainOrKeyword = kw
                )
            }
        }

        for (kw in GAMBLING_KEYWORDS) {
            if (cleanUrl.contains(kw)) {
                return FilterResult(
                    isBlocked = true,
                    category = WebCategory.GAMBLING,
                    reason = "Phát hiện nội dung cá cược, cờ bạc trực tuyến nguy hại.",
                    matchedDomainOrKeyword = kw
                )
            }
        }

        for (kw in SCAM_KEYWORDS) {
            if (cleanUrl.contains(kw)) {
                return FilterResult(
                    isBlocked = true,
                    category = WebCategory.SCAM,
                    reason = "Phát hiện dấu hiệu lừa đảo, chiếm đoạt tài khoản hoặc nạp thẻ giả mạo.",
                    matchedDomainOrKeyword = kw
                )
            }
        }

        return FilterResult(false, WebCategory.SAFE, "An toàn")
    }

    private fun extractHostname(url: String): String {
        return try {
            val formattedUrl = if (!url.startsWith("http://") && !url.startsWith("https://")) {
                "http://$url"
            } else {
                url
            }
            val uri = URI(formattedUrl)
            uri.host ?: formattedUrl.split("/").firstOrNull() ?: ""
        } catch (e: Exception) {
            url.split("/").firstOrNull()?.split(":")?.firstOrNull() ?: ""
        }
    }
}
