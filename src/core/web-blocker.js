/**
 * CVA-SmartGuardian - Safe Web Shield & Domain Blocker Engine
 * Hệ thống tường lửa phân tích và chặn website lừa đảo, khiêu dâm, cờ bạc cục bộ
 */

// DANH MỤC CÁC TÊN MIỀN & TỪ KHÓA BỊ CHẶN CHUẨN KHOA HỌC
const WEB_FILTER_CATEGORIES = {
  // 1. WEBSITE KHIÊU DÂM, TÌNH DỤC, ĐỒI TRỤY (ADULT & PORNOGRAPHY)
  ADULT: {
    id: 'adult',
    name: 'Nội dung khiêu dâm / Tình dục / Người lớn',
    severity: 'danger',
    description: 'Bảo vệ học sinh THCS khỏi nội dung không phù hợp với lứa tuổi theo Luật Trẻ em 2016',
    domains: [
      'pornhub.com', 'xvideos.com', 'xnxx.com', 'phimsex.com', 'vlxx.sex',
      'javhd.com', 'sex.com', 'hentaihaven.xxx', 'redtube.com', 'youporn.com',
      'phimsexvn.net', 'sexngon.com', 'phimsex.net', 'hentai.tv', 'onlyfans.com'
    ],
    urlKeywords: [
      'phimsex', 'phim-sex', 'clip-nong', 'anh-nong', 'khiêu-dâm', 'khieudam',
      'hentai', 'javhd', 'gai-goi', 'gaigoi', 'sex-sub', 'chat-xxx', 'video-18+'
    ]
  },

  // 2. WEBSITE LỪA ĐẢO, PHISHING, NẠP THẺ ẢO, HACK GAME (SCAM & PHISHING)
  SCAM_PHISHING: {
    id: 'scam',
    name: 'Website lừa đảo / Nạp thẻ game giả mạo / Phishing',
    severity: 'danger',
    description: 'Chống chiếm đoạt tài khoản, lừa tiền học sinh qua chiêu trò tặng quà, nạp quân huy lậu',
    domains: [
      'napthegiare.vn', 'napthegamelau.com', 'hackquanhuy.online', 'nhanquafreefire.com',
      'tangkimcuong.net', 'shopaccgiare.vn', 'nhanquaroblox.xyz', 'nhanqualienquan.com',
      'vongquaykimcuong.vn', 'trungthuongapple.com', 'quatangfacebook.net'
    ],
    urlKeywords: [
      'napthegamelau', 'nap-the-lau', 'hack-quan-huy', 'hack-kim-cuong', 'nhan-qua-freefire',
      'vong-quay-0d', 'vongquaykimcuong', 'shop-acc-0k', 'trung-thuong-iphone', 'dang-nhap-nhan-qua'
    ]
  },

  // 3. WEBSITE CỜ BẠC, CÁ ĐỘ BÓNG ĐÁ, TÀI XỈU TRỰC TUYẾN (GAMBLING)
  GAMBLING: {
    id: 'gambling',
    name: 'Cờ bạc trực tuyến / Tài xỉu / Cá độ',
    severity: 'danger',
    description: 'Ngăn chặn hiểm họa nợ nần, cờ bạc phi pháp xâm nhập học đường',
    domains: [
      'sunwin.fun', 'go88.info', 'ku777.me', 'kubet.com', 'kubet77.com', 'thabet.gg',
      'b52.club', 'rikvip.club', 'w88.com', 'fun88.com', 'm88.com',
      'taixiuonline.com', 'nohu88.club', 'ban-ca-doi-thuong.com'
    ],
    urlKeywords: [
      'tai-xiu-online', 'taixiu', 'danh-bai-doi-thuong', 'nohu', 'no-hu', 'kubet',
      'ca-do-bong-da', 'baccarat-online', 'game-bai-doi-thuong', 'quay-hu'
    ]
  },

  // 4. WEBSITE BẠO LỰC, VŨ KHÍ TỰ CHẾ, NỘI DUNG TỰ HẠI (VIOLENCE & HARM)
  VIOLENCE: {
    id: 'violence',
    name: 'Bạo lực / Mua bán vũ khí tự chế / Kích động tự hại',
    severity: 'danger',
    description: 'Ngăn ngừa bạo lực học đường và các nguy cơ đe dọa an toàn tính mạng',
    domains: [
      'muadaobam.com', 'vukhitot.vn', 'hoichansong.club', 'hoitutu.xyz'
    ],
    urlKeywords: [
      'mua-dao-bam', 'sung-tu-che', 'mua-kiem-nhat', 'cach-tu-tu', 'hoi-chan-song', 'rach-tay-tu-hai'
    ]
  }
};

class WebBlockerEngine {
  constructor(customBlocklist = []) {
    this.customBlocklist = customBlocklist; // Danh sách domain phụ huynh tự bổ sung thêm
    this.settings = {
      blockAdult: true,
      blockScam: true,
      blockGambling: true,
      blockViolence: true,
      blockCustom: true
    };
    this.blockedHistory = []; // Lưu nhật ký các lần chặn
  }

  // Cập nhật cấu hình bảo vệ của phụ huynh
  updateSettings(newSettings) {
    this.settings = { ...this.settings, ...newSettings };
  }

  // Thêm một domain vào danh sách chặn riêng của phụ huynh
  addCustomDomain(domain) {
    const clean = domain.trim().toLowerCase().replace(/^https?:\/\//, '').replace(/\/.*$/, '');
    if (clean && !this.customBlocklist.includes(clean)) {
      this.customBlocklist.push(clean);
      return true;
    }
    return false;
  }

  // Xóa domain khỏi danh sách chặn riêng
  removeCustomDomain(domain) {
    this.customBlocklist = this.customBlocklist.filter(d => d !== domain);
  }

  /**
   * Kiểm tra một URL có bị chặn hay không
   * @param {string} rawUrl - Đường dẫn hoặc domain cần kiểm tra
   * @returns {Object} - { isBlocked: boolean, category: string, reason: string, domain: string }
   */
  checkUrl(rawUrl) {
    if (!rawUrl || typeof rawUrl !== 'string') {
      return { isBlocked: false };
    }

    const lowerUrl = rawUrl.toLowerCase().trim();
    let hostname = lowerUrl;
    try {
      if (lowerUrl.startsWith('http://') || lowerUrl.startsWith('https://')) {
        const parsed = new URL(lowerUrl);
        hostname = parsed.hostname;
      } else {
        hostname = lowerUrl.split('/')[0];
      }
    } catch (e) {
      hostname = lowerUrl.split('/')[0];
    }

    // 1. Kiểm tra Danh sách Chặn Riêng của Phụ huynh
    if (this.settings.blockCustom && this.customBlocklist.length > 0) {
      for (const customDomain of this.customBlocklist) {
        if (hostname === customDomain || hostname.endsWith('.' + customDomain) || lowerUrl.includes(customDomain)) {
          const result = {
            isBlocked: true,
            category: 'custom',
            categoryName: 'Danh mục chặn riêng của Phụ huynh',
            reason: `Phụ huynh đã chủ động đưa '${customDomain}' vào danh sách cấm truy cập`,
            domain: hostname,
            url: rawUrl,
            timestamp: new Date().toISOString()
          };
          this.logBlockedAttempt(result);
          return result;
        }
      }
    }

    // 2. Kiểm tra Danh mục Tình dục / Người lớn
    if (this.settings.blockAdult) {
      const match = this._matchCategory(hostname, lowerUrl, WEB_FILTER_CATEGORIES.ADULT);
      if (match) {
        this.logBlockedAttempt(match);
        return match;
      }
    }

    // 3. Kiểm tra Danh mục Lừa đảo / Nạp thẻ ảo
    if (this.settings.blockScam) {
      const match = this._matchCategory(hostname, lowerUrl, WEB_FILTER_CATEGORIES.SCAM_PHISHING);
      if (match) {
        this.logBlockedAttempt(match);
        return match;
      }
    }

    // 4. Kiểm tra Danh mục Cờ bạc / Cá độ
    if (this.settings.blockGambling) {
      const match = this._matchCategory(hostname, lowerUrl, WEB_FILTER_CATEGORIES.GAMBLING);
      if (match) {
        this.logBlockedAttempt(match);
        return match;
      }
    }

    // 5. Kiểm tra Danh mục Bạo lực / Tự hại
    if (this.settings.blockViolence) {
      const match = this._matchCategory(hostname, lowerUrl, WEB_FILTER_CATEGORIES.VIOLENCE);
      if (match) {
        this.logBlockedAttempt(match);
        return match;
      }
    }

    return { isBlocked: false, domain: hostname, url: rawUrl };
  }

  _matchCategory(hostname, fullUrl, cat) {
    // So khớp domain chính xác hoặc subdomain
    for (const d of cat.domains) {
      if (hostname === d || hostname.endsWith('.' + d)) {
        return {
          isBlocked: true,
          category: cat.id,
          categoryName: cat.name,
          reason: `Trang web này thuộc danh mục cấm: ${cat.name}. ${cat.description}`,
          domain: hostname,
          url: fullUrl,
          severity: cat.severity,
          timestamp: new Date().toISOString()
        };
      }
    }

    // So khớp từ khóa đặc trưng trong đường dẫn URL
    for (const kw of cat.urlKeywords) {
      if (fullUrl.includes(kw)) {
        return {
          isBlocked: true,
          category: cat.id,
          categoryName: cat.name,
          reason: `Phát hiện cụm từ độc hại '${kw}' trong địa chỉ truy cập. ${cat.description}`,
          domain: hostname,
          url: fullUrl,
          severity: cat.severity,
          timestamp: new Date().toISOString()
        };
      }
    }

    return null;
  }

  logBlockedAttempt(item) {
    this.blockedHistory.unshift({
      ...item,
      id: 'blk_' + Date.now() + '_' + Math.random().toString(36).substr(2, 4)
    });
    // Giới hạn 50 bản ghi gần nhất
    if (this.blockedHistory.length > 50) {
      this.blockedHistory.pop();
    }
  }
}

if (typeof module !== 'undefined' && module.exports) {
  module.exports = {
    WEB_FILTER_CATEGORIES,
    WebBlockerEngine
  };
}
