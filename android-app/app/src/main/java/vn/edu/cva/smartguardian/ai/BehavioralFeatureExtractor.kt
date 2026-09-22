package vn.edu.cva.smartguardian.ai

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import vn.edu.cva.smartguardian.service.UsageTrackerService
import java.util.Calendar

/**
 * Trích xuất 6 biến số hành vi (Behavioral Feature Vector) từ dữ liệu thực tế
 */
object BehavioralFeatureExtractor {

    /**
     * Trích xuất vector đặc trưng từ đối tượng JSON lịch sử ứng dụng
     */
    fun extractFromAppHistory(historyJsonArray: JSONArray?, totalMinutesToday: Long): BehavioralFeatureVector {
        if (historyJsonArray == null || historyJsonArray.length() == 0) {
            return BehavioralFeatureVector(
                nightUnlockCount = 0f,
                maxContinuousMinutes = 0f,
                entertainmentRatio = 0f,
                switchingVelocity = 0f,
                schoolHoursMinutes = 0f,
                velocitySlope7d = 0f
            )
        }

        var maxDuration = 0L
        var entertainmentMinutes = 0L
        var schoolMinutes = 0L
        var nightCount = 0
        val count = historyJsonArray.length()

        val cal = Calendar.getInstance()

        for (i in 0 until count) {
            val appObj = historyJsonArray.optJSONObject(i) ?: continue
            val dur = appObj.optLong("durationMinutes", 0L)
            val category = appObj.optString("category", "UTILITY")
            val lastUsed = appObj.optLong("lastTimeUsed", 0L)

            if (dur > maxDuration) {
                maxDuration = dur
            }

            if (category == "GAME" || category == "SOCIAL") {
                entertainmentMinutes += dur
            }

            if (lastUsed > 0L) {
                cal.timeInMillis = lastUsed
                val hour = cal.get(Calendar.HOUR_OF_DAY)
                val minute = cal.get(Calendar.MINUTE)
                val timeMinutes = hour * 60 + minute

                // Ban đêm: 23h00 (1380m) đến 05h00 (300m)
                if (hour >= 23 || hour < 5) {
                    nightCount++
                }

                // Giờ học: 07h00 - 11h30 (420m - 690m) hoặc 13h30 - 17h00 (810m - 1020m)
                val isSchoolTime = (timeMinutes in 420..690) || (timeMinutes in 810..1020)
                val isWeekday = cal.get(Calendar.DAY_OF_WEEK) in Calendar.MONDAY..Calendar.FRIDAY
                if (isSchoolTime && isWeekday && (category == "GAME" || category == "SOCIAL")) {
                    schoolMinutes += dur
                }
            }
        }

        val totalMinutes = maxOf(1L, totalMinutesToday)
        val entRatio = (entertainmentMinutes.toFloat() / totalMinutes.toFloat()).coerceIn(0f, 1f)
        val activeHours = maxOf(1f, totalMinutes / 60f)
        val switchVelocity = (count.toFloat() / activeHours).coerceIn(0f, 60f)

        return BehavioralFeatureVector(
            nightUnlockCount = nightCount.toFloat(),
            maxContinuousMinutes = maxDuration.toFloat(),
            entertainmentRatio = entRatio,
            switchingVelocity = switchVelocity,
            schoolHoursMinutes = schoolMinutes.toFloat(),
            velocitySlope7d = 0.05f
        )
    }

    /**
     * Đánh giá trực tiếp dữ liệu học sinh từ JSONObject trên Firebase
     */
    fun evaluateChildDevice(childRawObj: JSONObject): DigitalWellbeingAssessment {
        val usageObj = childRawObj.optJSONObject("usage")
        val historyArr = childRawObj.optJSONArray("app_history") ?: usageObj?.optJSONArray("appHistory")
        val totalMinutes = usageObj?.optLong("totalScreenTimeMinutes", 0L) ?: 0L

        val features = extractFromAppHistory(historyArr, totalMinutes)
        return DigitalAddictionEngine.assess(features)
    }
}
