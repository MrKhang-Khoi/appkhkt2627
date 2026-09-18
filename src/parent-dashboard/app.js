/**
 * CVA-SmartGuardian - Parent Dashboard Application Logic
 */

document.addEventListener('DOMContentLoaded', () => {
  // Dữ liệu mẫu ban đầu mô phỏng 1 ngày học sinh dùng máy
  let currentReport = new DailyReport({
    dateStr: new Date().toISOString().slice(0, 10),
    studentName: "Nguyễn Hoàng Nam (8A2)",
    totalScreenTimeMinutes: 205, // 3h 25p
    studyMinutes: 95,            // 1h 35p (Azota, K12Online)
    gameMinutes: 42,             // 42p (Liên Quân Mobile)
    socialMinutes: 48,           // 48p (TikTok, YouTube)
    utilityMinutes: 20,          // 20p
    nightTimeMinutes: 0,
    riskAlerts: [
      {
        ruleKey: 'ADDICTION',
        categoryName: 'Tìm kiếm nạp thẻ / cày thuê',
        severity: 'warning',
        matchedKeyword: 'nạp thẻ game lậu',
        timestamp: '14:20 Hôm nay',
        excerpt: 'Tìm kiếm: shop nạp thẻ game lậu uy tín'
      }
    ],
    sessions: [
      {
        appName: 'Azota (Nộp bài tập)',
        category: 'study',
        timeStr: '19:40 - 20:35',
        durationMinutes: 55,
        icon: '📝'
      },
      {
        appName: 'Liên Quân Mobile',
        category: 'game',
        timeStr: '17:15 - 17:57',
        durationMinutes: 42,
        icon: '⚔️'
      },
      {
        appName: 'K12Online (Học trực tuyến)',
        category: 'study',
        timeStr: '14:00 - 14:40',
        durationMinutes: 40,
        icon: '🎓'
      },
      {
        appName: 'TikTok (Xem video ngắn)',
        category: 'social',
        timeStr: '11:45 - 12:15',
        durationMinutes: 30,
        icon: '🎵'
      },
      {
        appName: 'YouTube (Kênh Khoa học vui)',
        category: 'social',
        timeStr: '07:10 - 07:28',
        durationMinutes: 18,
        icon: '📺'
      }
    ]
  });

  let contract = new DigitalContract({
    maxGameMinutesDaily: 45,
    maxGameMinutesWeekend: 90,
    bedtimeHour: 22,
    bedtimeMinute: 30,
    currentStreak: 5,
    totalPoints: 250
  });

  // Tham chiếu các phần tử DOM
  const valTotalScreenTime = document.getElementById('valTotalScreenTime');
  const valGameTime = document.getElementById('valGameTime');
  const valStudyTime = document.getElementById('valStudyTime');
  const valRatio = document.getElementById('valRatio');
  const subRatioStatus = document.getElementById('subRatioStatus');
  const barGameProgress = document.getElementById('barGameProgress');
  const subGameStatus = document.getElementById('subGameStatus');
  const badgeGameLimit = document.getElementById('badgeGameLimit');

  const distSliceStudy = document.getElementById('distSliceStudy');
  const distSliceGame = document.getElementById('distSliceGame');
  const distSliceSocial = document.getElementById('distSliceSocial');
  const distSliceUtility = document.getElementById('distSliceUtility');

  const legendStudyMinutes = document.getElementById('legendStudyMinutes');
  const legendGameMinutes = document.getElementById('legendGameMinutes');
  const legendSocialMinutes = document.getElementById('legendSocialMinutes');
  const legendUtilityMinutes = document.getElementById('legendUtilityMinutes');

  const timelineList = document.getElementById('timelineList');
  const timelineCount = document.getElementById('timelineCount');
  const alertFeed = document.getElementById('alertFeed');

  const sliderGameLimit = document.getElementById('sliderGameLimit');
  const labelSliderGame = document.getElementById('labelSliderGame');
  const btnSaveContract = document.getElementById('btnSaveContract');
  const btnRewardStudent = document.getElementById('btnRewardStudent');
  const valStreakDays = document.getElementById('valStreakDays');
  const btnSimulateActivity = document.getElementById('btnSimulateActivity');

  // Hàm chuyển đổi phút sang định dạng chuỗi: "2h 15p"
  function formatMinutes(totalMins) {
    const h = Math.floor(totalMins / 60);
    const m = totalMins % 60;
    if (h === 0) return `${m}p`;
    if (m === 0) return `${h}h`;
    return `${h}h ${m}p`;
  }

  // Hàm cập nhật toàn bộ giao diện
  function renderDashboard() {
    // 1. Cập nhật các thẻ Metric trên cùng
    valTotalScreenTime.textContent = formatMinutes(currentReport.totalScreenTimeMinutes);
    valGameTime.textContent = formatMinutes(currentReport.gameMinutes);
    valStudyTime.textContent = formatMinutes(currentReport.studyMinutes);

    // Tính chỉ số Cân bằng R_GS = T_game / (T_study + 1)
    const ratio = currentReport.getRatio();
    valRatio.textContent = ratio.toFixed(2);
    const evaluation = currentReport.getEvaluation();

    if (evaluation.level === 'danger') {
      subRatioStatus.textContent = '⚠️ ' + evaluation.label;
      subRatioStatus.style.color = 'var(--game-color)';
    } else if (evaluation.level === 'warning') {
      subRatioStatus.textContent = '⏱️ ' + evaluation.label;
      subRatioStatus.style.color = 'var(--social-color)';
    } else {
      subRatioStatus.textContent = '✓ ' + evaluation.label;
      subRatioStatus.style.color = 'var(--study-color)';
    }

    // 2. Cập nhật thanh tiến trình hạn mức game
    badgeGameLimit.textContent = `Hạn mức: ${contract.maxGameMinutesDaily}p`;
    const gamePercent = Math.min(100, Math.round((currentReport.gameMinutes / contract.maxGameMinutesDaily) * 100));
    barGameProgress.style.width = `${gamePercent}%`;
    if (gamePercent >= 100) {
      barGameProgress.style.background = '#dc2626';
      subGameStatus.textContent = `⚠️ ĐÃ VƯỢT HẠN MỨC (${gamePercent}%)! Cần can thiệp.`;
      subGameStatus.style.color = '#dc2626';
      subGameStatus.style.fontWeight = '700';
    } else {
      barGameProgress.style.background = 'var(--game-color)';
      subGameStatus.textContent = `Đã dùng ${gamePercent}% hạn mức trong ngày`;
      subGameStatus.style.color = 'var(--text-muted)';
      subGameStatus.style.fontWeight = 'normal';
    }

    // 3. Cập nhật thanh phân bổ thời gian
    const totalM = Math.max(1, currentReport.totalScreenTimeMinutes);
    const pStudy = Math.round((currentReport.studyMinutes / totalM) * 100);
    const pGame = Math.round((currentReport.gameMinutes / totalM) * 100);
    const pSocial = Math.round((currentReport.socialMinutes / totalM) * 100);
    const pUtility = Math.max(0, 100 - (pStudy + pGame + pSocial));

    distSliceStudy.style.width = `${pStudy}%`;
    distSliceGame.style.width = `${pGame}%`;
    distSliceSocial.style.width = `${pSocial}%`;
    distSliceUtility.style.width = `${pUtility}%`;

    legendStudyMinutes.textContent = `${currentReport.studyMinutes}p (${pStudy}%)`;
    legendGameMinutes.textContent = `${currentReport.gameMinutes}p (${pGame}%)`;
    legendSocialMinutes.textContent = `${currentReport.socialMinutes}p (${pSocial}%)`;
    legendUtilityMinutes.textContent = `${currentReport.utilityMinutes}p (${pUtility}%)`;

    // 4. Render Dòng thời gian sử dụng (Timeline)
    timelineCount.textContent = `${currentReport.sessions.length} phiên`;
    timelineList.innerHTML = '';
    currentReport.sessions.forEach(item => {
      const el = document.createElement('div');
      el.className = 'timeline-item';
      el.innerHTML = `
        <div class="item-icon">${item.icon || '📱'}</div>
        <div class="item-info">
          <div class="item-title">${item.appName}</div>
          <div class="item-meta">${item.timeStr || 'Hôm nay'}</div>
        </div>
        <div class="item-duration" style="color: ${getCategoryColor(item.category)}">
          ${item.durationMinutes} phút
        </div>
      `;
      timelineList.appendChild(el);
    });

    // 5. Render Bảng Cảnh báo Rủi ro (Risk Alerts)
    alertFeed.innerHTML = '';
    if (currentReport.riskAlerts.length === 0) {
      alertFeed.innerHTML = `
        <div class="alert-item safe">
          <span style="font-size: 1.2rem;">🛡️</span>
          <div>
            <strong>Không có nguy cơ nào được phát hiện</strong><br>
            <span style="font-size: 0.78rem;">Học sinh không tìm kiếm nội dung bạo lực, cờ bạc hay web đen trong ngày.</span>
          </div>
        </div>
      `;
    } else {
      currentReport.riskAlerts.forEach(alert => {
        const aEl = document.createElement('div');
        aEl.className = `alert-item ${alert.severity || 'warning'}`;
        aEl.innerHTML = `
          <span style="font-size: 1.2rem;">${alert.severity === 'danger' ? '🚨' : '⚠️'}</span>
          <div style="flex: 1;">
            <div style="font-weight: 700; font-size: 0.86rem;">${alert.categoryName}</div>
            <div style="font-size: 0.78rem; margin-top: 2px;">${alert.excerpt}</div>
            <div style="font-size: 0.7rem; opacity: 0.8; margin-top: 4px;">Thời gian: ${alert.timestamp}</div>
          </div>
        `;
        alertFeed.appendChild(aEl);
      });
    }

    // 6. Cập nhật Thỏa ước số
    valStreakDays.textContent = `${contract.currentStreak} Ngày 🔥`;
  }

  function getCategoryColor(cat) {
    switch (cat) {
      case 'study': return 'var(--study-color)';
      case 'game': return 'var(--game-color)';
      case 'social': return 'var(--social-color)';
      default: return 'var(--utility-color)';
    }
  }

  // Lắng nghe thay đổi thanh trượt hạn mức game
  if (sliderGameLimit) {
    sliderGameLimit.addEventListener('input', (e) => {
      labelSliderGame.textContent = `${e.target.value} phút`;
    });
  }

  // Nút Lưu Thỏa ước số
  if (btnSaveContract) {
    btnSaveContract.addEventListener('click', () => {
      const newLimit = parseInt(sliderGameLimit.value, 10);
      contract.maxGameMinutesDaily = newLimit;
      renderDashboard();
      alert(`🤝 ĐÃ CẬP NHẬT THỎA ƯỚC GIA ĐÌNH THÀNH CÔNG!\n\nHạn mức chơi game mới của con: ${newLimit} phút/ngày.\nThông báo đã được gửi đồng bộ xuống thiết bị của con.`);
    });
  }

  // Nút Thưởng Điểm Rèn Luyện
  if (btnRewardStudent) {
    btnRewardStudent.addEventListener('click', () => {
      contract.currentStreak++;
      contract.totalPoints += 50;
      renderDashboard();
      alert(`🎉 ĐÃ THƯỞNG 50 ĐIỂM TỰ CHỦ CHO CON!\n\nChuỗi ngày giữ cam kết hiện tại: ${contract.currentStreak} ngày.\nTổng điểm tích lũy: ${contract.totalPoints} điểm (Đủ đổi 1 buổi đi xem phim cuối tuần cùng gia đình).`);
    });
  }

  // Nút Giả Lập Hoạt Động (Dành cho Giám khảo KHKT thử nghiệm)
  if (btnSimulateActivity) {
    btnSimulateActivity.addEventListener('click', () => {
      // Giả lập con chơi game thêm 25 phút
      currentReport.gameMinutes += 25;
      currentReport.totalScreenTimeMinutes += 25;
      currentReport.sessions.unshift({
        appName: 'Roblox (Chơi game)',
        category: 'game',
        timeStr: 'Vừa xong',
        durationMinutes: 25,
        icon: '🧱'
      });

      // Kèm một cảnh báo tìm kiếm nạp tiền
      currentReport.riskAlerts.unshift({
        ruleKey: 'GAMBLING',
        categoryName: 'Cảnh báo nạp tiền game / Rủi ro cờ bạc',
        severity: 'danger',
        matchedKeyword: 'hack quân huy',
        timestamp: 'Vừa phát hiện',
        excerpt: 'Tìm kiếm trên Google: cách hack quân huy liên quân miễn phí'
      });

      renderDashboard();
    });
  }

  // Khởi tạo Web Blocker Engine
  const webBlocker = new WebBlockerEngine(['shopaccgiare.vn', 'hackgame.xyz']);
  webBlocker.blockedHistory = [
    {
      id: 'blk_1',
      category: 'adult',
      categoryName: 'Nội dung khiêu dâm / Tình dục / Người lớn',
      reason: 'Trang web có nội dung khiêu dâm người lớn không phù hợp với học sinh',
      domain: 'phimsexvn.net',
      url: 'https://phimsexvn.net/clip-hot',
      severity: 'danger',
      timestamp: '15:10 Hôm nay'
    },
    {
      id: 'blk_2',
      category: 'scam',
      categoryName: 'Website lừa đảo / Nạp thẻ game giả mạo',
      reason: 'Phát hiện đường dẫn lừa đảo nạp quân huy lậu',
      domain: 'napthegamelau.com',
      url: 'https://napthegamelau.com/hack-quan-huy',
      severity: 'danger',
      timestamp: '11:25 Hôm nay'
    }
  ];

  // DOM elements của Web Blocker
  const toggleBlockAdult = document.getElementById('toggleBlockAdult');
  const toggleBlockScam = document.getElementById('toggleBlockScam');
  const toggleBlockGambling = document.getElementById('toggleBlockGambling');
  const toggleBlockViolence = document.getElementById('toggleBlockViolence');

  const inputCustomDomain = document.getElementById('inputCustomDomain');
  const btnAddCustomDomain = document.getElementById('btnAddCustomDomain');
  const customDomainList = document.getElementById('customDomainList');

  const inputTestUrl = document.getElementById('inputTestUrl');
  const btnTestUrl = document.getElementById('btnTestUrl');
  const testerResultBox = document.getElementById('testerResultBox');

  const blockedAttemptsFeed = document.getElementById('blockedAttemptsFeed');
  const blockedCountBadge = document.getElementById('blockedCountBadge');

  function renderWebBlockerUI() {
    // 1. Render danh sách domain riêng của phụ huynh
    if (customDomainList) {
      customDomainList.innerHTML = '';
      if (webBlocker.customBlocklist.length === 0) {
        customDomainList.innerHTML = '<span style="font-size: 0.74rem; color: var(--text-muted); font-style: italic;">Chưa có domain riêng nào.</span>';
      } else {
        webBlocker.customBlocklist.forEach(domain => {
          const chip = document.createElement('span');
          chip.className = 'domain-chip';
          chip.innerHTML = `
            <span>🌐 ${domain}</span>
            <span class="domain-chip-remove" data-domain="${domain}" title="Xóa bỏ chặn">×</span>
          `;
          customDomainList.appendChild(chip);
        });
      }
    }

    // 2. Render lịch sử các lần chặn
    if (blockedAttemptsFeed) {
      blockedAttemptsFeed.innerHTML = '';
      blockedCountBadge.textContent = `${webBlocker.blockedHistory.length} lần`;

      if (webBlocker.blockedHistory.length === 0) {
        blockedAttemptsFeed.innerHTML = `
          <div style="font-size: 0.78rem; color: var(--text-muted); padding: 8px; text-align: center;">
            Chưa có lần chặn nào trong ngày.
          </div>
        `;
      } else {
        webBlocker.blockedHistory.forEach(b => {
          const item = document.createElement('div');
          item.style.cssText = 'padding: 8px 12px; background: #fff; border: 1px solid #fecaca; border-radius: 6px; font-size: 0.78rem; border-left: 3px solid #ef4444;';
          item.innerHTML = `
            <div style="display: flex; justify-content: space-between; align-items: center; margin-bottom: 2px;">
              <strong style="color: #b91c1c;">🚫 ĐÃ CHẶN: ${b.domain}</strong>
              <span style="font-size: 0.68rem; color: var(--text-muted);">${b.timestamp || 'Vừa xong'}</span>
            </div>
            <div style="color: var(--text-main);">${b.reason}</div>
            <div style="font-size: 0.7rem; color: #475569; margin-top: 2px; text-decoration: line-through; overflow: hidden; text-overflow: ellipsis; white-space: nowrap;">
              ${b.url}
            </div>
          `;
          blockedAttemptsFeed.appendChild(item);
        });
      }
    }
  }

  // Lắng nghe các nút gạt cấu hình chặn
  if (toggleBlockAdult) toggleBlockAdult.addEventListener('change', e => webBlocker.updateSettings({ blockAdult: e.target.checked }));
  if (toggleBlockScam) toggleBlockScam.addEventListener('change', e => webBlocker.updateSettings({ blockScam: e.target.checked }));
  if (toggleBlockGambling) toggleBlockGambling.addEventListener('change', e => webBlocker.updateSettings({ blockGambling: e.target.checked }));
  if (toggleBlockViolence) toggleBlockViolence.addEventListener('change', e => webBlocker.updateSettings({ blockViolence: e.target.checked }));

  // Thêm domain riêng
  if (btnAddCustomDomain && inputCustomDomain) {
    btnAddCustomDomain.addEventListener('click', () => {
      const val = inputCustomDomain.value.trim();
      if (!val) return;
      if (webBlocker.addCustomDomain(val)) {
        inputCustomDomain.value = '';
        renderWebBlockerUI();
        alert(`🛡️ Đã thêm '${val}' vào danh sách cấm truy cập của gia đình!`);
      } else {
        alert('Tên miền không hợp lệ hoặc đã có trong danh sách cấm.');
      }
    });
  }

  // Xóa domain riêng khi bấm dấu ×
  if (customDomainList) {
    customDomainList.addEventListener('click', e => {
      if (e.target.classList.contains('domain-chip-remove')) {
        const domain = e.target.getAttribute('data-domain');
        if (domain && confirm(`Bỏ chặn tên miền '${domain}'?`)) {
          webBlocker.removeCustomDomain(domain);
          renderWebBlockerUI();
        }
      }
    });
  }

  // Hộp thử nghiệm Tường lửa (Tester Sandbox)
  if (btnTestUrl && inputTestUrl && testerResultBox) {
    btnTestUrl.addEventListener('click', () => {
      const url = inputTestUrl.value.trim();
      if (!url) return;
      const check = webBlocker.checkUrl(url);

      if (check.isBlocked) {
        testerResultBox.className = 'tester-result blocked';
        testerResultBox.innerHTML = `
          <strong>🚨 PHÁT HIỆN VI PHẠM - SẼ BỊ CHẶN NGAY LẬP TỨC:</strong><br>
          • <strong>Phân loại:</strong> ${check.categoryName || check.category}<br>
          • <strong>Lý do:</strong> ${check.reason}<br>
          • <strong>Hành động:</strong> Hệ thống chuyển hướng máy con sang màn hình cảnh báo <code>blocked.html</code>.
        `;
        renderWebBlockerUI();
      } else {
        testerResultBox.className = 'tester-result safe';
        testerResultBox.innerHTML = `
          <strong>✓ TRANG AN TOÀN / HỢP LỆ:</strong><br>
          Không phát hiện dấu hiệu lừa đảo, cờ bạc hay nội dung người lớn trên liên kết này.
        `;
      }
    });
  }

  // Khởi chạy hiển thị lần đầu
  renderDashboard();
  renderWebBlockerUI();
});

