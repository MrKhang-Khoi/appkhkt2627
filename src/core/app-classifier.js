/**
 * CVA-SmartGuardian - App Classifier Engine
 * Bộ phân loại ứng dụng tự động dành riêng cho thị trường giáo dục Việt Nam
 */

const KNOWN_PACKAGES = {
  // --- 1. NHÓM ỨNG DỤNG HỌC TẬP (STUDY) ---
  'vn.azota.app': { name: 'Azota (Nộp bài tập)', category: 'study', icon: '📝' },
  'vn.k12online.app': { name: 'K12Online (Học trực tuyến)', category: 'study', icon: '🎓' },
  'vn.olm.app': { name: 'OLM.vn (Học trực tuyến)', category: 'study', icon: '📚' },
  'com.duolingo': { name: 'Duolingo (Học ngoại ngữ)', category: 'study', icon: '🦉' },
  'org.khanacademy.android': { name: 'Khan Academy', category: 'study', icon: '🌲' },
  'com.google.android.apps.classroom': { name: 'Google Classroom', category: 'study', icon: '🏫' },
  'com.microsoft.teams': { name: 'Microsoft Teams', category: 'study', icon: '👥' },
  'com.vietjack.app': { name: 'VietJack (Giải bài tập)', category: 'study', icon: '📖' },
  'vn.loigiaihay': { name: 'Lời Giải Hay', category: 'study', icon: '💡' },
  'com.quizlet.android': { name: 'Quizlet (Thẻ ghi nhớ)', category: 'study', icon: '📇' },
  'org.geogebra.android': { name: 'GeoGebra Toán học', category: 'study', icon: '📐' },

  // --- 2. NHÓM TRÒ CHƠI ĐIỆN TỬ (GAME) ---
  'com.garena.game.kgvn': { name: 'Liên Quân Mobile', category: 'game', icon: '⚔️' },
  'com.dts.freefireth': { name: 'Free Fire', category: 'game', icon: '🔫' },
  'com.roblox.client': { name: 'Roblox', category: 'game', icon: '🧱' },
  'com.miHoYo.GenshinImpact': { name: 'Genshin Impact', category: 'game', icon: '🪄' },
  'com.mojang.minecraftpe': { name: 'Minecraft', category: 'game', icon: '⛏️' },
  'com.zing.zingspeedm': { name: 'ZingSpeed Mobile', category: 'game', icon: '🏎️' },
  'com.vng.pubgmobile': { name: 'PUBG Mobile VN', category: 'game', icon: '🎯' },
  'com.king.candycrushsaga': { name: 'Candy Crush', category: 'game', icon: '🍬' },
  'com.ea.gp.fifamobile': { name: 'FC Mobile / FIFA', category: 'game', icon: '⚽' },
  'com.supercell.clashofclans': { name: 'Clash of Clans', category: 'game', icon: '🏰' },

  // --- 3. NHÓM MẠNG XÃ HỘI & VIDEO (SOCIAL) ---
  'com.zhiliaoapp.musically': { name: 'TikTok', category: 'social', icon: '🎵' },
  'com.facebook.katana': { name: 'Facebook', category: 'social', icon: '📘' },
  'com.facebook.orca': { name: 'Messenger', category: 'social', icon: '💬' },
  'com.instagram.android': { name: 'Instagram', category: 'social', icon: '📷' },
  'com.google.android.youtube': { name: 'YouTube', category: 'social', icon: '📺' },
  'com.zing.zalo': { name: 'Zalo', category: 'social', icon: '💬' },
  'org.telegram.messenger': { name: 'Telegram', category: 'social', icon: '✈️' },
  'com.discord': { name: 'Discord', category: 'social', icon: '🎧' },

  // --- 4. NHÓM TIỆN ÍCH HỆ THỐNG (UTILITY) ---
  'com.android.settings': { name: 'Cài đặt hệ thống', category: 'utility', icon: '⚙️' },
  'com.google.android.dialer': { name: 'Điện thoại / Danh bạ', category: 'utility', icon: '📞' },
  'com.google.android.calculator': { name: 'Máy tính Casio/Hệ thống', category: 'utility', icon: '🧮' },
  'com.google.android.deskclock': { name: 'Đồng hồ báo thức', category: 'utility', icon: '⏰' }
};

/**
 * Phân loại một ứng dụng dựa trên tên gói hoặc tiêu đề
 */
function classifyApp(packageName, appTitle = "") {
  if (!packageName && !appTitle) {
    return { name: 'Ứng dụng chưa rõ', category: 'other', icon: '📱' };
  }

  // 1. Khớp chính xác mã package
  if (packageName && KNOWN_PACKAGES[packageName]) {
    return KNOWN_PACKAGES[packageName];
  }

  // 2. Tìm kiếm theo từ khóa trong tiêu đề
  const lowerTitle = (appTitle || packageName || "").toLowerCase();
  
  // Kiểm tra Game
  if (lowerTitle.includes('game') || lowerTitle.includes('play') || lowerTitle.includes('chơi') || 
      lowerTitle.includes('bắn') || lowerTitle.includes('đua xe') || lowerTitle.includes('nông trại')) {
    return { name: appTitle || 'Trò chơi di động', category: 'game', icon: '🎮' };
  }

  // Kiểm tra Học tập
  if (lowerTitle.includes('học') || lowerTitle.includes('toán') || lowerTitle.includes('văn') || 
      lowerTitle.includes('tiếng anh') || lowerTitle.includes('study') || lowerTitle.includes('edu') || 
      lowerTitle.includes('sách') || lowerTitle.includes('từ điển') || lowerTitle.includes('lớp')) {
    return { name: appTitle || 'Ứng dụng học tập', category: 'study', icon: '📚' };
  }

  // Kiểm tra Mạng xã hội & Giải trí
  if (lowerTitle.includes('chat') || lowerTitle.includes('video') || lowerTitle.includes('social') || 
      lowerTitle.includes('phim') || lowerTitle.includes('music') || lowerTitle.includes('nhạc')) {
    return { name: appTitle || 'Mạng xã hội / Giải trí', category: 'social', icon: '🌐' };
  }

  // Mặc định
  return { name: appTitle || packageName, category: 'utility', icon: '📱' };
}

if (typeof module !== 'undefined' && module.exports) {
  module.exports = {
    KNOWN_PACKAGES,
    classifyApp
  };
}
