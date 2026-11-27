package engine

import (
	"strings"
	"testing"
	"time"

	"github.com/UCKETX/HuyaPure/server/internal/huya"
)

func TestFormatRunNotificationNormal(t *testing.T) {
	parts := []string{
		"虎粮：已领 5 次（今日已领完）",
		"打卡：共 12 枚（新打卡 12，已打卡 0）",
		"福利：粉丝团 +10 个虎粮、每日签到(成功)",
		"送礼：已送 12 个（云汐小馆 +12），囤粮 48 个",
	}
	bag := &huya.BagHuliangSummary{
		NormalCount:      48,
		Expiring24h:      10,
		SuperTotalCount:  10,
		SuperDisplayName: "超粉虎粮",
	}
	ts := time.Date(2026, 11, 27, 8, 30, 0, 0, time.Local)

	title, body := formatRunNotification("星河旅人", "schedule", parts, bag, "", 300, ts)
	if title != "虎伴 · 星河旅人 任务完成" {
		t.Fatalf("unexpected title: %q", title)
	}
	for _, want := range []string{
		"🌾 虎粮：已领 5 次（今日已领完）",
		"🏅 打卡：共 12 枚（新打卡 12，已打卡 0）",
		"🎁 福利：粉丝团 +10 个虎粮、每日签到(成功)",
		"💝 送礼：已送 12 个（云汐小馆 +12），囤粮 48 个",
		"🎒 包裹：免费虎粮 48 个（24h 内过期 10 个） · 超粉虎粮 10 个",
		"⏳ 补领：冷却中，约 5 分钟后自动补领",
		"🕐 每日定时 · 08:30",
	} {
		if !strings.Contains(body, want) {
			t.Errorf("body missing %q\nactual:\n%s", want, body)
		}
	}
}

func TestFormatRunNotificationSuperFanDayAndRescue(t *testing.T) {
	ts := time.Date(2026, 11, 27, 21, 15, 0, 0, time.Local)
	title, body := formatRunNotification(
		"星河旅人",
		"rescue",
		[]string{"送礼：超粉日 4 倍！已送 68 个（云汐小馆 +68）"},
		&huya.BagHuliangSummary{NormalCount: 0},
		"云汐小馆 (4倍)",
		0,
		ts,
	)
	if title != "🎉 今日超粉日 · 星河旅人" {
		t.Fatalf("unexpected superfan title: %q", title)
	}
	if !strings.HasPrefix(body, "🔥 超粉日进行中：云汐小馆 (4倍)\n") {
		t.Fatalf("expected superfan banner on first line, got:\n%s", body)
	}
	if !strings.Contains(body, "🕐 临期抢救 · 21:15") {
		t.Fatalf("expected rescue footer, got:\n%s", body)
	}
}

func TestFormatSuperFanDayOnlyNotification(t *testing.T) {
	title, body := formatSuperFanDayOnlyNotification(
		"星河旅人",
		"云汐小馆 (4倍)",
		&huya.BagHuliangSummary{NormalCount: 48, SuperTotalCount: 10, SuperDisplayName: "超粉虎粮"},
	)
	if title != "🎉 今日超粉日 · 4 倍亲密度（星河旅人）" {
		t.Fatalf("unexpected title: %q", title)
	}
	for _, want := range []string{
		"🔥 进行中：云汐小馆 (4倍)",
		"🎒 包裹：免费虎粮 48 个 · 超粉虎粮 10 个",
		"💡 记得送出囤积的虎粮，吃满 4 倍亲密度！",
	} {
		if !strings.Contains(body, want) {
			t.Errorf("body missing %q\nactual:\n%s", want, body)
		}
	}
}
