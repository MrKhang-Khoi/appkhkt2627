/**
 * CVA-SmartGuardian - Local NLP Risk Analyzer
 * Bộ lọc phân tích từ khóa an toàn không gian mạng chạy 100% cục bộ trên máy
 */

const RISK_DICTIONARY = {
  // 1. CỜ BẠC & NẠP TIỀN BẤT HỢP PHÁP
  GAMBLING: {
    categoryName: 'Cờ bạc & Đặt cược trực tuyến',
    severity: 'danger',
    keywords: [
      'tài xỉu', 'đánh bài đổi thưởng', 'nổ hũ', 'bắn cá ăn tiền', 'cá độ bóng đá',
      'nạp thẻ game lậu', 'hack xu', 'hack quân huy', 'vay tiền online', 'baccarat'
    ]
  },

  // 2. BẠO LỰC HỌC ĐƯỜNG & BẮT NẠT TRỰC TUYẾN (CYBERBULLYING)
  VIOLENCE: {
    categoryName: 'Nguy cơ bạo lực học đường',
    severity: 'danger',
    keywords: [
      'dọa đánh', 'hẹn đánh nhau', 'tẩy chay', 'phốt lớp', 'lộ clip',
      'đập nó', 'chém nhau', 'mua dao bấm', 'vũ khí tự chế'
    ]
  },

  // 3. TÂM LÝ TIÊU CỰC & NGUY CƠ TỰ HẠI
  MENTAL_HEALTH: {
    categoryName: 'Bất ổn tâm lý & Nguy cơ tự hại',
    severity: 'danger',
    keywords: [
      'chán sống', 'muốn chết', 'tự tử', 'rạch tay', 'không muốn thở nữa',
      'áp lực học tập muốn biến mất', 'trầm cảm nặng'
    ]
  },

  // 4. NỘI DUNG NHẠY CẢM & TRANG WEB KHÔNG PHÙ HỢP LỨA TUỔI
  ADULT: {
    categoryName: 'Nội dung nhạy cảm / Web đen',
    severity: 'danger',
    keywords: [
      'phim sex', 'ảnh nóng', 'gái gọi', 'web đen', 'phim 18+',
      'khiêu dâm', 'chat xxx'
    ]
  },

  // 5. CẢNH BÁO DÙNG GAME QUÁ ĐỘ
  ADDICTION: {
    categoryName: 'Tìm kiếm mua bán tài khoản / Cày thuê',
    severity: 'warning',
    keywords: [
      'mua acc liên quân', 'bán acc free fire', 'cày thuê rank', 'thuê acc roblox',
      'shop bán acc giá rẻ', 'cách qua mặt bố mẹ'
    ]
  }
};

/**
 * Phân tích một chuỗi văn bản (từ khóa tìm kiếm hoặc tiêu đề trang web)
 * @param {string} text - Văn bản cần quét
 * @returns {Array} - Danh sách các rủi ro phát hiện được
 */
function analyzeTextRisk(text) {
  if (!text || typeof text !== 'string') return [];
  const normalizedText = text.toLowerCase();
  const detectedRisks = [];

  for (const [key, rule] of Object.entries(RISK_DICTIONARY)) {
    for (const keyword of rule.keywords) {
      if (normalizedText.includes(keyword)) {
        detectedRisks.push({
          ruleKey: key,
          categoryName: rule.categoryName,
          severity: rule.severity,
          matchedKeyword: keyword,
          timestamp: new Date().toISOString(),
          excerpt: text.length > 80 ? text.substring(0, 80) + '...' : text
        });
        break; // Đã khớp 1 từ trong nhóm này thì chuyển nhóm khác
      }
    }
  }

  return detectedRisks;
}

if (typeof module !== 'undefined' && module.exports) {
  module.exports = {
    RISK_DICTIONARY,
    analyzeTextRisk
  };
}
