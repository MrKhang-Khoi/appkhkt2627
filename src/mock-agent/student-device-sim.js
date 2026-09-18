/**
 * CVA-SmartGuardian - Student Device Simulator (Android Agent Mock)
 * Công cụ giả lập hoạt động thiết bị học sinh phục vụ đo đạc và kiểm thử KHKT
 */

const { classifyApp } = require('../core/app-classifier');
const { analyzeTextRisk } = require('../core/local-nlp-analyzer');
const { WebBlockerEngine } = require('../core/web-blocker');
const { DailyReport, ActivitySession } = require('../core/data-models');

class StudentDeviceSimulator {
  constructor(studentName = "Nguyễn Hoàng Nam", studentClass = "8A2") {
    this.studentName = `${studentName} (${studentClass})`;
    this.currentSessions = [];
    this.riskLogs = [];
    this.webBlocker = new WebBlockerEngine();
    this.blockedAttempts = [];
  }

  // Giả lập học sinh bấm vào 1 liên kết trên trình duyệt
  simulateBrowseUrl(url) {
    const result = this.webBlocker.checkUrl(url);
    if (result.isBlocked) {
      this.blockedAttempts.unshift(result);
    }
    return result;
  }

  // Giả lập học sinh mở một ứng dụng và sử dụng trong N phút
  simulateAppUsage(packageName, durationMinutes, timeStr = "Vừa xong", isNight = false) {
    const appMeta = classifyApp(packageName);
    const session = new ActivitySession({
      packageName,
      appName: appMeta.name,
      category: appMeta.category,
      durationSeconds: durationMinutes * 60,
      isNightTime: isNight
    });
    session.timeStr = timeStr;
    session.icon = appMeta.icon;
    session.durationMinutes = durationMinutes;

    this.currentSessions.unshift(session);
    return session;
  }

  // Giả lập học sinh gõ một từ khóa tìm kiếm trên Google/YouTube
  simulateSearchQuery(queryText) {
    const risks = analyzeTextRisk(queryText);
    if (risks.length > 0) {
      risks.forEach(r => this.riskLogs.unshift(r));
    }
    return risks;
  }

  // Tổng hợp báo cáo ngày hoàn chỉnh
  generateDailyReport(dateStr = new Date().toISOString().slice(0, 10)) {
    let studyM = 0;
    let gameM = 0;
    let socialM = 0;
    let utilityM = 0;
    let nightM = 0;

    this.currentSessions.forEach(s => {
      const mins = s.durationMinutes || Math.round(s.durationSeconds / 60);
      if (s.isNightTime) nightM += mins;

      switch (s.category) {
        case 'study': studyM += mins; break;
        case 'game': gameM += mins; break;
        case 'social': socialM += mins; break;
        default: utilityM += mins; break;
      }
    });

    const totalM = studyM + gameM + socialM + utilityM;

    return new DailyReport({
      dateStr,
      studentName: this.studentName,
      totalScreenTimeMinutes: totalM,
      studyMinutes: studyM,
      gameMinutes: gameM,
      socialMinutes: socialM,
      utilityMinutes: utilityM,
      nightTimeMinutes: nightM,
      riskAlerts: this.riskLogs,
      sessions: this.currentSessions
    });
  }
}

// Chạy thử nghiệm độc lập
if (require.main === module) {
  console.log("=== KHỞI CHẠY TRÌNH GIẢ LẬP THIẾT BỊ HỌC SINH CVA-SMARTGUARDIAN ===");
  const sim = new StudentDeviceSimulator();

  // 1. Giả lập học bài trên K12Online 45 phút
  sim.simulateAppUsage('vn.k12online.app', 45, '08:00 - 08:45');

  // 2. Giả lập làm bài tập trên Azota 40 phút
  sim.simulateAppUsage('vn.azota.app', 40, '14:15 - 14:55');

  // 3. Giả lập chơi Liên Quân 35 phút
  sim.simulateAppUsage('com.garena.game.kgvn', 35, '17:00 - 17:35');

  // 4. Giả lập tìm kiếm một từ khóa đáng ngờ
  const alert = sim.simulateSearchQuery('shop nạp thẻ game lậu giá rẻ');
  console.log('Rủi ro phát hiện:', alert);

  // 5. Xuất báo cáo
  const report = sim.generateDailyReport();
  console.log("\n--- BÁO CÁO TỔNG HỢP NGÀY ---");
  console.log(`Học sinh: ${report.studentName}`);
  console.log(`Tổng thời gian màn hình: ${report.totalScreenTimeMinutes} phút`);
  console.log(`Học tập: ${report.studyMinutes}p | Game: ${report.gameMinutes}p | Mạng xã hội: ${report.socialMinutes}p`);
  console.log(`Chỉ số cân bằng R_GS: ${report.getRatio()}`);
  console.log(`Đánh giá: ${report.getEvaluation().label}`);
  console.log(`Số lượng cảnh báo rủi ro: ${report.riskAlerts.length}`);
}

module.exports = {
  StudentDeviceSimulator
};
