package vn.edu.cva.smartguardian.ai

import org.json.JSONObject
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

/**
 * Thang đo Nguy cơ Nghiện số và Rối loạn Chú ý ở Thanh Thiếu niên
 * Kế thừa nghiên cứu chuẩn quốc tế:
 * - Smartphone Addiction Scale - Short Version (SAS-SV) - Kwon et al., PLOS ONE (2013)
 * - Internet Addiction Test (IAT) - Dr. Kimberly Young (1998)
 */
enum class RiskLevel(val label: String, val description: String) {
    BALANCED("CÂN BẰNG", "Lành mạnh, sinh hoạt điều độ"),   // Lành mạnh, sinh hoạt điều độ (DWI >= 75)
    WARNING("CẢNH BÁO", "Cảnh báo mất tập trung, dùng máy kéo dài"),    // Cảnh báo mất tập trung, dùng máy kéo dài (50 <= DWI < 75)
    HIGH_RISK("NGUY CƠ CAO", "Nguy cơ nghiện số cao, thức đêm cày game")   // Nguy cơ nghiện số cao, thức đêm cày game (DWI < 50)
}

/**
 * Vector đặc trưng hành vi 6 chiều (X in R^6)
 */
data class BehavioralFeatureVector(
    val nightUnlockCount: Float,      // x1: Số lần mở máy ban đêm (23h00 - 05h00) [lần]
    val maxContinuousMinutes: Float,  // x2: Phiên sử dụng dài nhất không nghỉ [phút]
    val entertainmentRatio: Float,    // x3: Tỷ lệ app game và mạng xã hội [0.0 - 1.0]
    val switchingVelocity: Float,     // x4: Tốc độ nhảy app trong 1 giờ [lần/giờ]
    val schoolHoursMinutes: Float,    // x5: Thời lượng dùng trong khung giờ học [phút]
    val velocitySlope7d: Float        // x6: Độ dốc biến thiên thời gian so với tuần trước [-1.0 đến +1.0]
) {
    fun toJsonObject(): JSONObject {
        return JSONObject().apply {
            put("nightUnlockCount", nightUnlockCount.toDouble())
            put("maxContinuousMinutes", maxContinuousMinutes.toDouble())
            put("entertainmentRatio", entertainmentRatio.toDouble())
            put("switchingVelocity", switchingVelocity.toDouble())
            put("schoolHoursMinutes", schoolHoursMinutes.toDouble())
            put("velocitySlope7d", velocitySlope7d.toDouble())
        }
    }
}

/**
 * Kết quả đánh giá Chỉ số Cân Bằng Số (Digital Wellbeing Index)
 */
data class DigitalWellbeingAssessment(
    val score: Int,                       // Chỉ số Cân Bằng Số DWI (0 - 100)
    val riskLevel: RiskLevel,             // Phân cấp nguy cơ
    val dominantRiskFactor: String,       // Yếu tố hành vi rủi ro nổi trội
    val recommendation: String,           // Khuyến nghị can thiệp sư phạm
    val probabilities: FloatArray,        // Vector xác suất Softmax [P_Safe, P_Warning, P_HighRisk]
    val evaluatedAt: Long = System.currentTimeMillis()
) {
    val dwiScore: Int get() = score
    val dominantFactor: String get() = dominantRiskFactor
    val pedagogicalAdvice: String get() = recommendation

    fun toJsonObject(): JSONObject {
        return JSONObject().apply {
            put("score", score)
            put("riskLevel", riskLevel.name)
            put("dominantRiskFactor", dominantRiskFactor)
            put("recommendation", recommendation)
            put("probSafe", probabilities.getOrElse(0) { 0f }.toDouble())
            put("probWarning", probabilities.getOrElse(1) { 0f }.toDouble())
            put("probHighRisk", probabilities.getOrElse(2) { 0f }.toDouble())
            put("evaluatedAt", evaluatedAt)
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is DigitalWellbeingAssessment) return false
        return score == other.score &&
                riskLevel == other.riskLevel &&
                dominantRiskFactor == other.dominantRiskFactor &&
                recommendation == other.recommendation &&
                probabilities.contentEquals(other.probabilities)
    }

    override fun hashCode(): Int {
        var result = score
        result = 31 * result + riskLevel.hashCode()
        result = 31 * result + dominantRiskFactor.hashCode()
        result = 31 * result + recommendation.hashCode()
        result = 31 * result + probabilities.contentHashCode()
        return result
    }
}

/**
 * Động cơ Học máy On-Device (Edge AI) phân tích nguy cơ nghiện số
 * Kiến trúc mạng: Multi-Layer Perceptron (MLP: 6 -> 16 -> 8 -> 3)
 * Tối ưu hóa cho thiết bị di động: Tốc độ suy luận < 2ms, không tốn pin, không gửi dữ liệu ra ngoài.
 */
object DigitalAddictionEngine {

    // Giới hạn chuẩn hóa Min-Max Scaling (từ tập huấn luyện KHKT)
    private const val X1_MAX = 10.0f
    private const val X2_MAX = 180.0f
    private const val X3_MAX = 1.0f
    private const val X4_MAX = 60.0f
    private const val X5_MAX = 120.0f
    private const val X6_MIN = -1.0f
    private const val X6_MAX = 1.0f

    // Trọng số Layer 1 (16 nơ-ron x 6 đầu vào)
    private val W1: Array<FloatArray> = arrayOf(
        floatArrayOf(-0.04344f, -0.05213f, -0.03356f, 0.21166f, -0.03847f, -0.45147f),
        floatArrayOf(-1.47814f, -1.47683f, -1.63725f, -1.00438f, -1.02775f, -0.21786f),
        floatArrayOf(-0.72018f, -2.09977f, -0.99021f, -0.84670f, -1.23315f, -0.10878f),
        floatArrayOf(-0.63973f, -1.36568f, -0.35728f, -0.81993f, -0.96060f, -0.15199f),
        floatArrayOf(0.23046f, -0.11483f, 0.05510f, 0.02069f, 0.19920f, -0.37856f),
        floatArrayOf(-0.34161f, -0.75336f, -1.20474f, -0.44097f, -0.50291f, 0.17467f),
        floatArrayOf(0.19617f, -0.37024f, 0.24546f, -0.30750f, -0.02806f, -0.09442f),
        floatArrayOf(1.38023f, 2.22785f, 1.36193f, 1.23917f, 1.46008f, 1.33634f),
        floatArrayOf(-0.91984f, -1.84660f, -0.75025f, -0.34605f, -1.10492f, 0.20441f),
        floatArrayOf(-0.57552f, -1.31505f, -0.16807f, -0.12421f, -0.45320f, -0.11461f),
        floatArrayOf(0.43006f, -0.02835f, -0.42904f, -0.16043f, 0.28731f, -0.43529f),
        floatArrayOf(0.54129f, 1.04737f, 0.38263f, 0.29566f, 0.95817f, 0.51886f),
        floatArrayOf(0.18693f, -0.18374f, -0.16939f, -0.25073f, 0.28712f, -0.17091f),
        floatArrayOf(-0.02118f, 0.22592f, -0.21813f, -0.08854f, -0.55517f, -0.32638f),
        floatArrayOf(-0.05332f, 0.17952f, 0.39413f, 0.02644f, 0.10228f, 0.09529f),
        floatArrayOf(1.83462f, 2.53841f, 1.39964f, 0.98213f, 1.77366f, 1.44763f)
    )

    private val B1 = floatArrayOf(
        0.0f, 1.76320f, 3.45845f, 2.26695f, -0.06258f,
        0.83004f, -0.01680f, -2.30323f, 2.52308f, 0.71154f,
        0.0f, -1.98729f, 0.0f, 0.0f, -0.25263f, -2.63018f
    )

    // Trọng số Layer 2 (8 nơ-ron x 16 đầu vào)
    private val W2: Array<FloatArray> = arrayOf(
        floatArrayOf(0.35419f, 0.48918f, 1.10709f, 0.42895f, 0.45577f, 0.07477f, -0.14477f, -0.86949f, 0.65720f, 0.92343f, 0.23567f, -0.20103f, -0.68586f, 0.20524f, -0.02877f, -0.51731f),
        floatArrayOf(-0.18111f, 0.20934f, 0.58069f, -0.24318f, -0.12356f, 0.48715f, -0.12879f, -0.74460f, 0.09654f, -0.25508f, 0.06349f, -0.49983f, 0.25553f, 0.00092f, 0.58812f, -0.63590f),
        floatArrayOf(0.39424f, -1.31993f, -0.32657f, -0.03284f, 0.49873f, -0.87749f, 0.28556f, 0.29617f, 0.44881f, -0.06714f, 0.01503f, 0.02864f, -0.36032f, 0.05641f, 0.04785f, 0.70518f),
        floatArrayOf(-0.17639f, -0.00771f, -0.57454f, -0.13895f, 0.07345f, 0.20551f, 0.41551f, 0.54028f, -0.40033f, 0.29761f, 0.14924f, 1.07156f, -0.20216f, 0.32718f, 0.21673f, 0.75656f),
        floatArrayOf(0.36779f, 0.08605f, 0.70783f, 1.08260f, 0.06835f, -0.18944f, 0.03490f, -0.09325f, 0.70644f, 0.37742f, 0.34518f, -0.67913f, -0.49842f, 0.08644f, -0.07020f, -0.60101f),
        floatArrayOf(0.25146f, 0.17141f, -1.21886f, -0.40153f, -0.05660f, -0.43046f, 0.13061f, 0.50891f, -0.97974f, -0.13948f, 0.14090f, 0.35982f, 0.11557f, 0.29302f, -0.15646f, 0.69367f),
        floatArrayOf(0.00080f, 1.41681f, 0.17188f, 0.69153f, -0.08889f, 0.29277f, 0.55090f, -2.24410f, 1.14702f, 0.26062f, 0.10118f, -0.77963f, -0.19938f, 0.01804f, -0.36797f, -2.32598f),
        floatArrayOf(-0.60767f, 0.65131f, 0.11618f, 0.60614f, -0.29073f, 0.14100f, 0.91366f, -0.50448f, -0.31599f, 0.01176f, 0.13640f, 0.17633f, 0.37250f, -0.07419f, -0.47388f, -0.40743f)
    )

    private val B2 = floatArrayOf(
        2.41086f, -0.32917f, 1.16145f, -0.82227f,
        2.17977f, -1.25785f, -0.69737f, -0.10486f
    )

    // Trọng số Layer 3 (3 nơ-ron output x 8 đầu vào)
    private val W3: Array<FloatArray> = arrayOf(
        floatArrayOf(1.00466f, 0.46003f, -2.65538f, -1.90091f, 0.67512f, 0.90552f, 1.78498f, 0.29244f),
        floatArrayOf(0.57430f, -0.62060f, 0.49200f, 0.34165f, 1.21621f, -0.53367f, -1.83741f, -0.48171f),
        floatArrayOf(-2.07229f, 0.27393f, 0.36299f, 1.61676f, -0.85593f, 0.98000f, -0.16885f, 0.41645f)
    )

    private val B3 = floatArrayOf(
        -0.76094f, 2.26991f, -1.50897f
    )

    /**
     * Chuẩn hóa Min-Max Scaling vector đầu vào theo biên độ huấn luyện
     */
    fun normalizeFeatures(vector: BehavioralFeatureVector): FloatArray {
        val n1 = (vector.nightUnlockCount / X1_MAX).coerceIn(0.0f, 1.0f)
        val n2 = (vector.maxContinuousMinutes / X2_MAX).coerceIn(0.0f, 1.0f)
        val n3 = (vector.entertainmentRatio / X3_MAX).coerceIn(0.0f, 1.0f)
        val n4 = (vector.switchingVelocity / X4_MAX).coerceIn(0.0f, 1.0f)
        val n5 = (vector.schoolHoursMinutes / X5_MAX).coerceIn(0.0f, 1.0f)
        val n6 = ((vector.velocitySlope7d - X6_MIN) / (X6_MAX - X6_MIN)).coerceIn(0.0f, 1.0f)
        return floatArrayOf(n1, n2, n3, n4, n5, n6)
    }

    /**
     * Lan truyền xuôi (Forward Propagation) qua 3 tầng nơ-ron:
     * Layer 1: ReLU(W1 * x + B1)
     * Layer 2: ReLU(W2 * a1 + B2)
     * Layer 3: Softmax(W3 * a2 + B3)
     */
    fun forwardPass(normalized: FloatArray): FloatArray {
        // Tầng 1: 16 nơ-ron ReLU
        val a1 = FloatArray(16)
        for (i in 0 until 16) {
            var sum = B1[i]
            for (j in 0 until 6) {
                sum += W1[i][j] * normalized[j]
            }
            a1[i] = max(0.0f, sum)
        }

        // Tầng 2: 8 nơ-ron ReLU
        val a2 = FloatArray(8)
        for (i in 0 until 8) {
            var sum = B2[i]
            for (j in 0 until 16) {
                sum += W2[i][j] * a1[j]
            }
            a2[i] = max(0.0f, sum)
        }

        // Tầng 3: 3 nơ-ron Softmax
        val z3 = FloatArray(3)
        var maxZ = -Float.MAX_VALUE
        for (i in 0 until 3) {
            var sum = B3[i]
            for (j in 0 until 8) {
                sum += W3[i][j] * a2[j]
            }
            z3[i] = sum
            if (sum > maxZ) maxZ = sum
        }

        val expZ = FloatArray(3)
        var sumExp = 0.0f
        for (i in 0 until 3) {
            val v = exp(z3[i] - maxZ)
            expZ[i] = v
            sumExp += v
        }

        val probs = FloatArray(3)
        for (i in 0 until 3) {
            probs[i] = if (sumExp > 0f) expZ[i] / sumExp else (1.0f / 3.0f)
        }

        return probs
    }

    /**
     * Đánh giá toàn diện vector hành vi và xuất bản báo cáo sức khỏe số (DWI)
     */
    fun assess(vector: BehavioralFeatureVector): DigitalWellbeingAssessment {
        val normalized = normalizeFeatures(vector)
        val probs = forwardPass(normalized)

        val pSafe = probs[0]
        val pWarning = probs[1]
        val pHighRisk = probs[2]

        // Chỉ số Cân Bằng Số DWI (0 - 100 điểm) theo kỳ vọng toán học
        val rawScore = (100.0f * pSafe) + (50.0f * pWarning) + (10.0f * pHighRisk)
        val dwiScore = rawScore.toInt().coerceIn(0, 100)

        val riskLevel = when {
            dwiScore >= 75 -> RiskLevel.BALANCED
            dwiScore >= 50 -> RiskLevel.WARNING
            else -> RiskLevel.HIGH_RISK
        }

        val dominantRisk = identifyDominantRisk(vector)
        val recommendation = buildPedagogicalRecommendation(riskLevel, dominantRisk)

        return DigitalWellbeingAssessment(
            score = dwiScore,
            riskLevel = riskLevel,
            dominantRiskFactor = dominantRisk,
            recommendation = recommendation,
            probabilities = probs
        )
    }

    private fun identifyDominantRisk(vector: BehavioralFeatureVector): String {
        return when {
            vector.nightUnlockCount >= 3f -> "Thức khuya dùng điện thoại sau 23h (${vector.nightUnlockCount.toInt()} lần)"
            vector.maxContinuousMinutes >= 60f -> "Phiên sử dụng liên tục kéo dài (${vector.maxContinuousMinutes.toInt()} phút)"
            vector.schoolHoursMinutes >= 30f -> "Sử dụng điện thoại trong khung giờ học (${vector.schoolHoursMinutes.toInt()} phút)"
            vector.entertainmentRatio >= 0.70f -> "Ứng dụng giải trí chiếm tỷ lệ cao (${(vector.entertainmentRatio * 100).toInt()}%)"
            vector.switchingVelocity >= 25f -> "Xao nhãng, chuyển đổi app liên tục (${vector.switchingVelocity.toInt()} lần/giờ)"
            vector.velocitySlope7d >= 0.40f -> "Thời gian dùng máy tăng đột biến so với tuần trước"
            else -> "Thói quen sinh hoạt và học tập điều độ"
        }
    }

    private fun buildPedagogicalRecommendation(level: RiskLevel, riskFactor: String): String {
        return when (level) {
            RiskLevel.BALANCED -> "Rất tuyệt vời! Bạn đang duy trì lối sống số lành mạnh và cân bằng. Hãy tiếp tục phát huy nhé!"
            RiskLevel.WARNING -> "Chú ý: $riskFactor. Bạn nên nghỉ ngơi mắt theo quy tắc 20-20-20 và bật Chế độ Tập trung Pomodoro khi học bài."
            RiskLevel.HIGH_RISK -> "Cảnh báo an toàn: $riskFactor. Hệ thống khuyến nghị kích hoạt phiên học tập trung để bảo vệ giấc ngủ và sức khỏe mắt."
        }
    }
}
