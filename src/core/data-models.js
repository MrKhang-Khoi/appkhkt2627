/**
 * CVA-SmartGuardian - Core Data Models & Schemas
 * Tiêu chuẩn cấu trúc dữ liệu cho Hệ thống Giám sát & Đồng hành Tự chủ Số
 */

const APP_CATEGORIES = {
  STUDY: 'study',     // Ứng dụng học tập: Azota, K12Online, OLM, Duolingo, Khan Academy
  GAME: 'game',       // Trò chơi: Liên Quân, Free Fire, Roblox, Genshin Impact
  SOCIAL: 'social',   // Mạng xã hội & Video: TikTok, Facebook, Instagram, YouTube
  UTILITY: 'utility', // Tiện ích hệ thống: Cài đặt, Điện thoại, Máy tính, Đồng hồ
  OTHER: 'other'      // Chưa phân loại
};

const RISK_LEVELS = {
  SAFE: 'safe',         // An toàn (Mức Xanh)
  WARNING: 'warning',   // Cần lưu ý (Mức Vàng: dùng quá 45p hoặc dùng đêm)
  DANGER: 'danger'      // Báo động khẩn cấp (Mức Đỏ: nội dung độc hại, cờ bạc, bạo lực)
};

/**
 * Cấu trúc bản ghi hoạt động từng phiên (App Session)
 */
class ActivitySession {
  constructor({ id, packageName, appName, category, startTime, durationSeconds, isNightTime = false }) {
    this.id = id || 'act_' + Date.now() + '_' + Math.random().toString(36).substr(2, 5);
    this.packageName = packageName;
    this.appName = appName;
    this.category = category || APP_CATEGORIES.OTHER;
    this.startTime = startTime || new Date().toISOString();
    this.durationSeconds = durationSeconds || 0;
    this.isNightTime = isNightTime; // Sau 22h30 đêm
  }
}

/**
 * Cấu trúc Thỏa ước số Gia đình (Family Digital Contract)
 */
class DigitalContract {
  constructor({ maxGameMinutesDaily = 45, maxGameMinutesWeekend = 90, bedtimeHour = 22, bedtimeMinute = 30, currentStreak = 0, totalPoints = 0 }) {
    this.maxGameMinutesDaily = maxGameMinutesDaily;
    this.maxGameMinutesWeekend = maxGameMinutesWeekend;
    this.bedtimeHour = bedtimeHour;
    this.bedtimeMinute = bedtimeMinute;
    this.currentStreak = currentStreak; // Số ngày liên tiếp giữ cam kết
    this.totalPoints = totalPoints;     // Điểm thưởng tích lũy đổi quà
  }
}

/**
 * Cấu trúc Báo cáo tổng hợp ngày (Daily Report)
 */
class DailyReport {
  constructor({ dateStr, studentName = "Học sinh THCS Chu Văn An", totalScreenTimeMinutes = 0, studyMinutes = 0, gameMinutes = 0, socialMinutes = 0, utilityMinutes = 0, nightTimeMinutes = 0, riskAlerts = [], sessions = [] }) {
    this.dateStr = dateStr || new Date().toISOString().slice(0, 10);
    this.studentName = studentName;
    this.totalScreenTimeMinutes = totalScreenTimeMinutes;
    this.studyMinutes = studyMinutes;
    this.gameMinutes = gameMinutes;
    this.socialMinutes = socialMinutes;
    this.utilityMinutes = utilityMinutes;
    this.nightTimeMinutes = nightTimeMinutes;
    this.riskAlerts = riskAlerts;
    this.sessions = sessions;
  }

  // Tính Chỉ số Cân bằng Hoạt động R_GS = T_game / (T_study + 1)
  getRatio() {
    return parseFloat((this.gameMinutes / (this.studyMinutes + 1)).toFixed(2));
  }

  // Đánh giá xếp loại trạng thái trong ngày
  getEvaluation() {
    const ratio = this.getRatio();
    if (this.nightTimeMinutes > 0) {
      return { level: RISK_LEVELS.DANGER, label: 'Báo động: Dùng máy khuya sau 22h30' };
    }
    if (ratio > 1.0) {
      return { level: RISK_LEVELS.DANGER, label: 'Báo động: Chơi game nhiều hơn học bài' };
    }
    if (ratio > 0.5) {
      return { level: RISK_LEVELS.WARNING, label: 'Cân bằng: Thời gian chơi và học xấp xỉ' };
    }
    return { level: RISK_LEVELS.SAFE, label: 'Rất tốt: Dành nhiều thời gian học tập' };
  }
}

if (typeof module !== 'undefined' && module.exports) {
  module.exports = {
    APP_CATEGORIES,
    RISK_LEVELS,
    ActivitySession,
    DigitalContract,
    DailyReport
  };
}
