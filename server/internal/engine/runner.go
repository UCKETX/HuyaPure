package engine

import (
	"context"
	"fmt"
	"log"
	"regexp"
	"strconv"
	"strings"
	"sync"
	"time"

	"github.com/UCKETX/HuyaPure/server/internal/huya"
	"github.com/UCKETX/HuyaPure/server/internal/notify"
	"github.com/UCKETX/HuyaPure/server/internal/store"
)

var cooldownRegex = regexp.MustCompile(`(\d+)\s*秒`)

type RunResult struct {
	Success            bool
	Message            string
	NotifyTitle        string
	NotifyBody         string
	CooldownSeconds    int
	FreeEarliestExpire int64
	SFDayActiveSummary string
}

// Runner executes full multi-phase automation tasks and manual gift sends per account.
type Runner struct {
	API        *huya.API
	MobileAuth *huya.MobileAuthClient
	Store      *store.Store
	Notifier   *notify.Notifier
	OnNotify   func(accountID, title, body string)

	mu      sync.Mutex
	running map[string]bool
}

func NewRunner(api *huya.API, st *store.Store, notifier *notify.Notifier) *Runner {
	return &Runner{
		API:        api,
		MobileAuth: huya.NewMobileAuthClient(),
		Store:      st,
		Notifier:   notifier,
		running:    make(map[string]bool),
	}
}

func (r *Runner) ensureFreshCredentials(ctx context.Context, accountID string, creds huya.Credentials) huya.Credentials {
	if r.MobileAuth == nil || !creds.NeedsMobileTokenRefresh(time.Now()) {
		return creds
	}
	refreshed, err := r.MobileAuth.RefreshMobileToken(ctx, creds)
	if err != nil {
		log.Printf("[Runner:%s] mobile token refresh warning: %v", accountID, err)
		return creds
	}
	_, _ = r.Store.UpdateAccount(accountID, func(a *store.Account) error {
		a.Credentials = refreshed
		return nil
	})
	log.Printf("[Runner:%s] refreshed mobile 5008 biztoken via udb_cred", accountID)
	return refreshed
}

func (r *Runner) IsRunning(accountID string) bool {
	r.mu.Lock()
	defer r.mu.Unlock()
	return r.running[accountID]
}

// RunAccount executes the full daily task pipeline for one account.
func (r *Runner) RunAccount(ctx context.Context, accountID, trigger string) (RunResult, error) {
	acc, ok := r.Store.GetClone(accountID)
	if !ok {
		return RunResult{}, fmt.Errorf("账号不存在: %s", accountID)
	}
	if !acc.Credentials.IsValid() {
		return RunResult{}, fmt.Errorf("账号未登录或凭据无效，请重新扫码登录")
	}

	r.mu.Lock()
	if r.running[accountID] {
		r.mu.Unlock()
		return RunResult{}, fmt.Errorf("该账号任务正在执行中，请稍候")
	}
	r.running[accountID] = true
	r.mu.Unlock()

	_, _ = r.Store.UpdateAccount(accountID, func(a *store.Account) error {
		a.State.Running = true
		return nil
	})

	defer func() {
		r.mu.Lock()
		delete(r.running, accountID)
		r.mu.Unlock()
		_, _ = r.Store.UpdateAccount(accountID, func(a *store.Account) error {
			a.State.Running = false
			return nil
		})
	}()

	now := time.Now()
	today := now.Format("2006-01-02")
	creds := r.ensureFreshCredentials(ctx, accountID, acc.Credentials)
	cfg := acc.Config

	var parts []string
	var adClaimed, cooldownSec int
	var checkinOK, checkinAlready, checkinTotal int
	var sentNormal, sentSuper int

	// ── Phase 1: Bag reward-ad Huliang claiming (optional) ────────────────────
	if cfg.AutoAdHuliang {
		var hintPID int64
		if len(acc.State.Badges) > 0 {
			hintPID = acc.State.Badges[0].PID
		}
		var adSummary string
		adClaimed, cooldownSec, adSummary = r.runBagAdHuliang(ctx, creds, hintPID)
		log.Printf("[Runner:%s] bagAdHuliang claimed=%d cooldown=%ds summary=%q", acc.Label, adClaimed, cooldownSec, adSummary)
		if adSummary != "" {
			parts = append(parts, "虎粮："+adSummary)
		}
	}

	// ── Phase 2: Query fan badges & daily check-in ───────────────────────────
	badges, err := r.API.QueryFanBadges(ctx, creds)
	if err != nil {
		log.Printf("[Runner:%s] QueryFanBadges error: %v", acc.Label, err)
		parts = append(parts, "粉丝牌：查询失败("+err.Error()+")")
		// Keep previous cached badges if query transiently failed.
		badges = acc.State.Badges
	} else {
		checkinTotal = len(badges)
		if cfg.AutoFansCheckin && len(badges) > 0 {
			for _, b := range badges {
				state, _ := r.API.FansSign(ctx, creds, b.PID)
				b.CheckinState = state
				switch state {
				case "success":
					checkinOK++
				case "already":
					checkinAlready++
				}
				sleepCtx(ctx, 250*time.Millisecond)
			}
			doneCnt := checkinOK + checkinAlready
			if checkinOK > 0 {
				parts = append(parts, fmt.Sprintf("打卡：共 %d 枚（新打卡 %d，已打卡 %d）", checkinTotal, checkinOK, checkinAlready))
			} else if doneCnt == checkinTotal {
				parts = append(parts, fmt.Sprintf("打卡：共 %d 枚（全部已打卡）", checkinTotal))
			} else {
				parts = append(parts, fmt.Sprintf("打卡：%d/%d 枚", doneCnt, checkinTotal))
			}
		} else {
			preserveTodayCheckin(acc, badges, today)
			parts = append(parts, fmt.Sprintf("打卡：共 %d 枚粉丝牌", checkinTotal))
		}
	}

	// ── Phase 2.5: Fan-group daily 10 Huliang, SuperFan welfare & Daily Sign-in ─
	if cfg.AutoClaimWelfare {
		var welfareLabels []string
		boxClaimed := false
		attemptedComm := make(map[string]bool)

		// Scan superfan badges first, then normal badges (up to 15)
		scanLimit := min(len(badges), 15)
		for i := 0; i < scanLimit; i++ {
			b := badges[i]
			taskInfo, err := r.API.QueryFansGroupTaskInfo(ctx, creds, b.PID)
			if err != nil {
				continue
			}
			if !boxClaimed && taskInfo.DayState == 1 {
				ok, cnt, _ := r.API.OperateFansBoxReceive(ctx, creds, b.PID)
				if ok {
					boxClaimed = true
					if cnt > 0 {
						welfareLabels = append(welfareLabels, fmt.Sprintf("粉丝团 +%d 个虎粮", cnt))
					} else {
						welfareLabels = append(welfareLabels, "粉丝团每日虎粮")
					}
				}
			}
			for _, cw := range taskInfo.CommonWelfares {
				if cw.Status != 1 {
					continue
				}
				key := fmt.Sprintf("%d:%d", cw.BusinessID, cw.WelfareID)
				if attemptedComm[key] {
					continue
				}
				attemptedComm[key] = true
				ok, _ := r.API.ReceiveCommonWelfare(ctx, creds, cw)
				if ok {
					label := cw.Title
					if label == "" {
						label = "粉丝团福利"
					}
					welfareLabels = append(welfareLabels, label)
				}
			}
			sleepCtx(ctx, 180*time.Millisecond)
		}

		if state, detail := r.API.DailyUserSignIn(ctx, creds); state == "success" {
			welfareLabels = append(welfareLabels, "每日签到("+detail+")")
		}
		if len(welfareLabels) > 0 {
			parts = append(parts, "福利："+strings.Join(welfareLabels, "、"))
		} else {
			parts = append(parts, "福利：今日均已领取")
		}
	}

	// ── Phase 2.8: Enrich SuperFan Day (SuperFansConfig) & Guard Info ────────
	nowTime := time.Now()
	maxEnrich := min(len(badges), 20)
	for i := 0; i < maxEnrich; i++ {
		b := badges[i]
		if cfgInfo, err := r.API.QuerySuperFansConfig(ctx, creds, b.PID, b.RoomID); err == nil && cfgInfo != nil {
			b.SFDayActive = cfgInfo.SFDayActive
			b.SFDay = cfgInfo.SFDay
			b.SFDayScoreTimes = cfgInfo.SFDayScoreTimes
			b.SFDayQuotaTimes = cfgInfo.SFDayQuotaTimes
			b.SFDayLeftSec = cfgInfo.BeLeftTime
			if !b.SFDayActive && b.SFDay > 0 {
				b.NextSFDayEpoch = huya.ComputeNextSFDayEpoch(b.SFDay, nowTime)
			} else {
				b.NextSFDayEpoch = 0
			}
		}
		if gInfo, err := r.API.QueryGuardInfo(ctx, creds, b.PID); err == nil && gInfo != nil {
			if gInfo.Level >= 1 && gInfo.Level <= 3 && (gInfo.EndTime <= 0 || gInfo.EndTime > nowTime.Unix()) {
				b.GuardLevel = gInfo.Level
				b.GuardName = huya.GuardLevelName(gInfo.Level)
				b.GuardType = gInfo.GuardType
				b.GuardAccompanyDay = gInfo.AccompanyDay
				b.GuardEndTime = gInfo.EndTime
			}
		}
		sleepCtx(ctx, 120*time.Millisecond)
	}
	r.API.PopulateBadgeRenderInfo(ctx, creds, badges)
	sfDaySummary := buildSFDaySummary(badges)

	// ── Phase 3: Backpack free Huliang query & smart auto-send ───────────────
	sfPIDs := collectSuperFanPIDs(badges)
	bag, bagErr := r.API.QueryBagHuliang(ctx, creds, sfPIDs...)
	if bagErr != nil {
		log.Printf("[Runner:%s] QueryBagHuliang error: %v", acc.Label, bagErr)
		parts = append(parts, "背包：查询失败("+bagErr.Error()+")")
	} else if cfg.AutoSendHuliang || cfg.SendSuperHuliang {
		decision := BuildSendPlan(cfg, bag, badges)
		sentByAnchor := make(map[string]int)
		var anchorOrder []string

		for _, p := range decision.Plans {
			ok, detail := r.API.SendFreeHuliang(ctx, creds, p.ItemType, p.TargetPID, p.Count)
			log.Printf("[Runner:%s] SendFreeHuliang %s(%d) x%d -> %s(%d): ok=%v (%s)",
				acc.Label, p.ItemName, p.ItemType, p.Count, p.Nick, p.TargetPID, ok, detail)
			sentNick := p.Nick
			if !ok && p.IsSuper && len(sfPIDs) > 0 && sfPIDs[0] != p.TargetPID {
				// Fallback retry on the first SuperFan anchor if target wasn't eligible for 超粉虎粮.
				fbPID := sfPIDs[0]
				fbNick := fmt.Sprintf("UID:%d", fbPID)
				for _, b := range badges {
					if b != nil && b.PID == fbPID && b.PresenterNick != "" {
						fbNick = b.PresenterNick
						break
					}
				}
				ok, detail = r.API.SendFreeHuliang(ctx, creds, p.ItemType, fbPID, p.Count)
				log.Printf("[Runner:%s] SendFreeHuliang fallback %s(%d) x%d -> %s(%d): ok=%v (%s)",
					acc.Label, p.ItemName, p.ItemType, p.Count, fbNick, fbPID, ok, detail)
				if ok {
					sentNick = fbNick
				}
			}
			if ok {
				if p.IsSuper {
					sentSuper += p.Count
				} else {
					sentNormal += p.Count
				}
				if _, exists := sentByAnchor[sentNick]; !exists {
					anchorOrder = append(anchorOrder, sentNick)
				}
				sentByAnchor[sentNick] += p.Count
			}
			sleepCtx(ctx, 250*time.Millisecond)
		}

		totalSent := sentNormal + sentSuper
		if totalSent > 0 {
			var detailParts []string
			for _, nick := range anchorOrder {
				detailParts = append(detailParts, fmt.Sprintf("%s +%d", nick, sentByAnchor[nick]))
			}
			prefix := "已送"
			if decision.HasTodaySFDay {
				prefix = "超粉日 4 倍！已送"
			}
			sendMsg := fmt.Sprintf("送礼：%s %d 个（%s）", prefix, totalSent, strings.Join(detailParts, "、"))
			if decision.HoardedNormal > 0 {
				sendMsg += fmt.Sprintf("，囤粮 %d 个", decision.HoardedNormal)
			}
			parts = append(parts, sendMsg)
			// Refresh bag & badges after sending so displayed numbers reflect latest counts.
			if refreshedBag, err := r.API.QueryBagHuliang(ctx, creds, sfPIDs...); err == nil {
				bag = refreshedBag
			}
		} else if decision.HoardedNormal > 0 {
			parts = append(parts, fmt.Sprintf("送礼：%s", decision.Reason))
		} else if bag.NormalCount == 0 && bag.SuperTotalCount == 0 {
			parts = append(parts, "送礼：包裹暂无可用免费虎粮")
		}
	}

	msg := strings.Join(parts, "；")
	if msg == "" {
		msg = "任务执行完成"
	}

	var earliestExpire int64
	if bag != nil {
		earliestExpire = bag.EarliestExpireSec
	}

	// Refresh own Huya nickname & avatar (best-effort)
	var selfProf *huya.UserProfile
	if p, pErr := r.API.QueryUserProfile(ctx, creds); pErr == nil && p != nil {
		selfProf = p
	}

	// Persist updated account state & history.
	_, _ = r.Store.UpdateAccount(accountID, func(a *store.Account) error {
		if selfProf != nil {
			store.ApplyHuyaProfile(a, selfProf.NickName, selfProf.AvatarURL)
		}
		st := &a.State
		st.LastRunTime = time.Now().UnixMilli()
		st.LastRunDate = today
		st.LastRunSuccess = true
		st.LastRunMessage = msg

		if st.TodayClaimedDay != today {
			st.TodayClaimedDay = today
			st.TodayClaimedCount = 0
		}
		st.TodayClaimedCount += adClaimed

		st.TodayCheckinDay = today
		st.TodayCheckinCount = checkinOK + checkinAlready
		st.TodayCheckinTotal = checkinTotal

		if st.TodaySentDay != today {
			st.TodaySentDay = today
			st.TodaySentCount = 0
		}
		st.TodaySentCount += sentNormal + sentSuper

		if bag != nil {
			st.FreeHuliangCount = bag.NormalCount
			st.FreeExpiring24h = bag.Expiring24h
			st.FreeEarliestExpire = bag.EarliestExpireSec
			st.SuperHuliangCount = bag.SuperTotalCount
			st.SuperHuliangName = bag.SuperDisplayName
		}
		st.SFDayActiveSummary = sfDaySummary
		if badges != nil {
			st.Badges = badges
			st.BadgesUpdateTime = time.Now().UnixMilli()
		}
		rec := RunRecord(store.RunRecord{
			Time:       st.LastRunTime,
			Date:       today,
			Trigger:    trigger,
			Success:    true,
			Message:    msg,
			AdClaimed:  adClaimed,
			CheckinOK:  checkinOK + checkinAlready,
			CheckinAll: checkinTotal,
			SentNormal: sentNormal,
			SentSuper:  sentSuper,
		})
		st.History = append([]store.RunRecord{store.RunRecord(rec)}, st.History...)
		if len(st.History) > 30 {
			st.History = st.History[:30]
		}
		return nil
	})

	// Send notifications if configured.
	var notifyTitle, notifyBody string
	displayName := acc.Label
	if selfProf != nil && strings.TrimSpace(selfProf.NickName) != "" {
		displayName = strings.TrimSpace(selfProf.NickName)
	}
	if cfg.NotifyOnRun {
		notifyTitle, notifyBody = formatRunNotification(displayName, trigger, parts, bag, sfDaySummary, cooldownSec, time.Now())
	} else if cfg.NotifyOnSuperFanDay && sfDaySummary != "" {
		notifyTitle, notifyBody = formatSuperFanDayOnlyNotification(displayName, sfDaySummary, bag)
	}
	if notifyTitle != "" {
		if r.Notifier != nil {
			notifyCfg := r.Store.GetNotify()
			if errs := r.Notifier.Send(ctx, notifyCfg, notifyTitle, notifyBody); len(errs) > 0 {
				log.Printf("[Runner:%s] notify errors: %v", displayName, errs)
			}
		}
		if r.OnNotify != nil {
			r.OnNotify(accountID, notifyTitle, notifyBody)
		}
	}

	return RunResult{
		Success:            true,
		Message:            msg,
		NotifyTitle:        notifyTitle,
		NotifyBody:         notifyBody,
		CooldownSeconds:    cooldownSec,
		FreeEarliestExpire: earliestExpire,
		SFDayActiveSummary: sfDaySummary,
	}, nil
}

// RefreshBadgesAndBag performs a lightweight sync without triggering daily claim/send actions.
func (r *Runner) RefreshBadgesAndBag(ctx context.Context, accountID string) (*store.Account, error) {
	acc, ok := r.Store.GetClone(accountID)
	if !ok {
		return nil, fmt.Errorf("账号不存在")
	}
	if !acc.Credentials.IsValid() {
		return nil, fmt.Errorf("账号未登录")
	}
	creds := r.ensureFreshCredentials(ctx, accountID, acc.Credentials)
	badges, err := r.API.QueryFanBadges(ctx, creds)
	if err != nil {
		return nil, fmt.Errorf("查询粉丝牌失败: %w", err)
	}
	nowTime := time.Now()
	maxEnrich := min(len(badges), 20)
	for i := 0; i < maxEnrich; i++ {
		b := badges[i]
		if cfgInfo, err := r.API.QuerySuperFansConfig(ctx, creds, b.PID, b.RoomID); err == nil && cfgInfo != nil {
			b.SFDayActive = cfgInfo.SFDayActive
			b.SFDay = cfgInfo.SFDay
			b.SFDayScoreTimes = cfgInfo.SFDayScoreTimes
			b.SFDayQuotaTimes = cfgInfo.SFDayQuotaTimes
			b.SFDayLeftSec = cfgInfo.BeLeftTime
			if !b.SFDayActive && b.SFDay > 0 {
				b.NextSFDayEpoch = huya.ComputeNextSFDayEpoch(b.SFDay, nowTime)
			}
		}
		if gInfo, err := r.API.QueryGuardInfo(ctx, creds, b.PID); err == nil && gInfo != nil {
			if gInfo.Level >= 1 && gInfo.Level <= 3 && (gInfo.EndTime <= 0 || gInfo.EndTime > nowTime.Unix()) {
				b.GuardLevel = gInfo.Level
				b.GuardName = huya.GuardLevelName(gInfo.Level)
				b.GuardType = gInfo.GuardType
				b.GuardAccompanyDay = gInfo.AccompanyDay
				b.GuardEndTime = gInfo.EndTime
			}
		}
		sleepCtx(ctx, 80*time.Millisecond)
	}
	r.API.PopulateBadgeRenderInfo(ctx, creds, badges)
	bag, _ := r.API.QueryBagHuliang(ctx, creds, collectSuperFanPIDs(badges)...)
	selfProf, _ := r.API.QueryUserProfile(ctx, creds)
	sfSummary := buildSFDaySummary(badges)

	return r.Store.UpdateAccount(accountID, func(a *store.Account) error {
		if selfProf != nil {
			store.ApplyHuyaProfile(a, selfProf.NickName, selfProf.AvatarURL)
		}
		today := time.Now().Format("2006-01-02")
		preserveTodayCheckin(a, badges, today)
		a.State.Badges = badges
		a.State.BadgesUpdateTime = time.Now().UnixMilli()
		a.State.SFDayActiveSummary = sfSummary
		if bag != nil {
			a.State.FreeHuliangCount = bag.NormalCount
			a.State.FreeExpiring24h = bag.Expiring24h
			a.State.FreeEarliestExpire = bag.EarliestExpireSec
			a.State.SuperHuliangCount = bag.SuperTotalCount
			a.State.SuperHuliangName = bag.SuperDisplayName
		}
		return nil
	})
}

// preserveTodayCheckin restores today's known per-badge CheckinState ("success"/"already")
// onto freshly fetched FanBadge rows so a lightweight sync never regresses checked-in badges
// back to "待打卡".
func preserveTodayCheckin(a *store.Account, fresh []*huya.FanBadge, today string) {
	if a == nil || len(fresh) == 0 {
		return
	}
	sameDay := a.State.TodayCheckinDay == today ||
		a.State.LastRunDate == today ||
		(a.State.BadgesUpdateTime > 0 && time.UnixMilli(a.State.BadgesUpdateTime).Format("2006-01-02") == today)
	if !sameDay {
		return
	}
	prevByPID := make(map[int64]string, len(a.State.Badges))
	for _, old := range a.State.Badges {
		if old != nil && (old.CheckinState == "success" || old.CheckinState == "already") {
			prevByPID[old.PID] = old.CheckinState
		}
	}
	allCheckedToday := a.State.TodayCheckinDay == today &&
		a.State.TodayCheckinTotal > 0 &&
		a.State.TodayCheckinCount >= a.State.TodayCheckinTotal
	for _, b := range fresh {
		if b == nil || b.CheckinState != "" {
			continue
		}
		if st, ok := prevByPID[b.PID]; ok {
			b.CheckinState = st
		} else if allCheckedToday {
			b.CheckinState = "already"
		}
	}
}

// SyncUserProfile refreshes only the account's Huya nickname and avatar.
func (r *Runner) SyncUserProfile(ctx context.Context, accountID string) {
	acc, ok := r.Store.GetClone(accountID)
	if !ok || !acc.Credentials.IsValid() {
		return
	}
	creds := r.ensureFreshCredentials(ctx, accountID, acc.Credentials)
	prof, err := r.API.QueryUserProfile(ctx, creds)
	if err != nil || prof == nil || (prof.NickName == "" && prof.AvatarURL == "") {
		return
	}
	_, _ = r.Store.UpdateAccount(accountID, func(a *store.Account) error {
		store.ApplyHuyaProfile(a, prof.NickName, prof.AvatarURL)
		return nil
	})
}

// BackfillBadgeAssets populates authentic Huya fan badge CDN render metadata onto already-synced badges.
func (r *Runner) BackfillBadgeAssets(ctx context.Context, accountID string) {
	acc, ok := r.Store.GetClone(accountID)
	if !ok || !acc.Credentials.IsValid() || len(acc.State.Badges) == 0 {
		return
	}
	needs := false
	for _, b := range acc.State.Badges {
		if b != nil && (b.Render == nil || (b.GuardLevel > 0 && b.Render.GuardIconURL == "")) {
			needs = true
			break
		}
	}
	if !needs {
		return
	}
	creds := r.ensureFreshCredentials(ctx, accountID, acc.Credentials)
	r.API.PopulateBadgeRenderInfo(ctx, creds, acc.State.Badges)
	_, _ = r.Store.UpdateAccount(accountID, func(a *store.Account) error {
		byPID := make(map[int64]*huya.BadgeRenderInfo, len(acc.State.Badges))
		for _, b := range acc.State.Badges {
			if b != nil && b.Render != nil {
				byPID[b.PID] = b.Render
			}
		}
		for _, b := range a.State.Badges {
			if b != nil {
				if rend, ok := byPID[b.PID]; ok {
					b.Render = rend
				}
			}
		}
		return nil
	})
}

// ManualSendGift sends free normal or superfan Huliang to one specific presenter (strictly free package only).
func (r *Runner) ManualSendGift(ctx context.Context, accountID string, targetPID int64, sendSuper bool, count int) (string, error) {
	acc, ok := r.Store.GetClone(accountID)
	if !ok {
		return "", fmt.Errorf("账号不存在")
	}
	if !acc.Credentials.IsValid() {
		return "", fmt.Errorf("账号未登录")
	}
	if targetPID <= 0 || count <= 0 {
		return "", fmt.Errorf("主播 UID 和数量必须大于 0")
	}
	creds := r.ensureFreshCredentials(ctx, accountID, acc.Credentials)
	hintPIDs := append([]int64{targetPID}, collectSuperFanPIDs(acc.State.Badges)...)
	bag, err := r.API.QueryBagHuliang(ctx, creds, hintPIDs...)
	if err != nil {
		return "", fmt.Errorf("查询背包失败: %w", err)
	}

	var itemType int
	var itemName string
	var avail int
	if sendSuper {
		if len(bag.SuperItems) == 0 || bag.SuperTotalCount <= 0 {
			return "", fmt.Errorf("当前包裹没有可用的超粉虎粮")
		}
		itemType = bag.SuperItems[0].ItemType
		itemName = bag.SuperItems[0].Name
		avail = bag.SuperItems[0].Count
	} else {
		if bag.NormalCount <= 0 {
			return "", fmt.Errorf("当前包裹没有可用的免费虎粮")
		}
		itemType = bag.NormalItemType
		itemName = nonEmpty(bag.NormalName, "虎粮")
		avail = bag.NormalCount
	}
	if count > avail {
		count = avail
	}
	okSend, detail := r.API.SendFreeHuliang(ctx, creds, itemType, targetPID, count)
	if !okSend {
		return "", fmt.Errorf("赠送失败：%s", detail)
	}

	// Refresh bag counts asynchronously/immediately so UI updates accurately.
	if refreshedBag, err := r.API.QueryBagHuliang(ctx, creds, hintPIDs...); err == nil {
		today := time.Now().Format("2006-01-02")
		_, _ = r.Store.UpdateAccount(accountID, func(a *store.Account) error {
			a.State.FreeHuliangCount = refreshedBag.NormalCount
			a.State.FreeExpiring24h = refreshedBag.Expiring24h
			a.State.FreeEarliestExpire = refreshedBag.EarliestExpireSec
			a.State.SuperHuliangCount = refreshedBag.SuperTotalCount
			a.State.SuperHuliangName = refreshedBag.SuperDisplayName
			if a.State.TodaySentDay != today {
				a.State.TodaySentDay = today
				a.State.TodaySentCount = 0
			}
			a.State.TodaySentCount += count
			return nil
		})
	}
	return fmt.Sprintf("已赠送 %d 个免费%s（%s）", count, itemName, detail), nil
}

func (r *Runner) runBagAdHuliang(ctx context.Context, creds huya.Credentials, hintPID int64) (claimed int, cooldownSec int, summary string) {
	if !creds.IsValid() {
		return 0, 0, "登录凭据无效，请重新登录"
	}

	// Activate daily gift-bag Huliang ad placement (getPropsListPlacement -> notifyPropsListPlacement)
	// so Huya's backend transitions iStatus from 0 -> 1 before getCommAdPannel (iAppId=8).
	if pl, err := r.API.EnsureBagAdPlacementActive(ctx, creds, hintPID); err == nil && pl != nil {
		if pl.Status == 2 || pl.Status == 3 {
			return 0, 0, nonEmpty(pl.FinishText, "今日已领满")
		}
	}

	const maxRounds = 30
	refreshRetries := 0
	var lastRewardToast string
	for round := 1; round <= maxRounds; round++ {
		if ctx.Err() != nil {
			break
		}
		reqID := fmt.Sprintf("%d_%d", time.Now().UnixMilli(), creds.UID)
		panel, err := r.API.GetBagAdPanel(ctx, creds, reqID)
		if err != nil {
			if claimed > 0 {
				return claimed, 0, formatClaimedSummary(claimed, lastRewardToast, fmt.Sprintf("后续拉取失败：%v", err))
			}
			if strings.Contains(err.Error(), "ret=905") && !creds.IsMobile() {
				return 0, 0, "登录状态已失效，请重新扫码或改用手机短信登录"
			}
			return 0, 0, fmt.Sprintf("服务端拉取受限（%v）", err)
		}
		if panel.AdTaskID == "" {
			if (panel.WupRet == 914 || strings.Contains(panel.Toast, "已刷新") || strings.Contains(panel.Toast, "重新点击")) && refreshRetries < 2 {
				refreshRetries++
				round--
				_, _ = r.API.EnsureBagAdPlacementActive(ctx, creds, hintPID)
				sleepCtx(ctx, 1000*time.Millisecond)
				continue
			}
			if sec := parseCooldownSec(panel.Toast); sec > 0 {
				if claimed > 0 {
					return claimed, sec, formatClaimedSummary(claimed, lastRewardToast, fmt.Sprintf("冷却 %d 秒后自动补领", sec))
				}
				return 0, sec, fmt.Sprintf("冷却中（%d 秒后自动补领）", sec)
			}
			if claimed > 0 {
				return claimed, 0, formatClaimedSummary(claimed, lastRewardToast, nonEmpty(panel.Toast, "今日已领完"))
			}
			if panel.Toast != "" {
				return 0, 0, panel.Toast
			}
			return 0, 0, "今日已领满或暂无任务"
		}
		refreshRetries = 0

		// Report click event & award task
		r.API.ReportAdClick(ctx, creds, reqID, panel.Ext, panel.SlotAdRaw)
		sleepCtx(ctx, 500*time.Millisecond)
		ok, toast := r.API.AwardBagAdTask(ctx, creds, panel.AdTaskID, reqID, panel.Ext, panel.NeedWatchTime)
		if !ok {
			if sec := parseCooldownSec(toast); sec > 0 {
				if claimed > 0 {
					return claimed, sec, formatClaimedSummary(claimed, lastRewardToast, fmt.Sprintf("冷却 %d 秒后自动补领", sec))
				}
				return 0, sec, fmt.Sprintf("冷却中（%d 秒后自动补领）", sec)
			}
			if claimed > 0 {
				return claimed, 0, formatClaimedSummary(claimed, lastRewardToast, toast)
			}
			if isAdExhaustedToast(toast) {
				return 0, 0, toast
			}
			return 0, 0, fmt.Sprintf("领奖返回：%s", toast)
		}
		claimed++
		if toast != "" {
			lastRewardToast = toast
		}
		sleepCtx(ctx, 1000*time.Millisecond)
	}
	return claimed, 0, formatClaimedSummary(claimed, lastRewardToast, "")
}

func formatClaimedSummary(claimed int, lastToast, tail string) string {
	s := fmt.Sprintf("已领 %d 次", claimed)
	if lastToast != "" {
		s += "（" + lastToast + "）"
	}
	if tail != "" {
		s += "，" + tail
	}
	return s
}

func isAdExhaustedToast(toast string) bool {
	if toast == "" {
		return false
	}
	for _, kw := range []string{"上限", "已领完", "已完成", "明日", "明天", "次数已用完", "无更多", "已结束"} {
		if strings.Contains(toast, kw) {
			return true
		}
	}
	return false
}

func parseCooldownSec(msg string) int {
	if msg == "" {
		return 0
	}
	if m := cooldownRegex.FindStringSubmatch(msg); len(m) == 2 {
		if n, err := strconv.Atoi(m[1]); err == nil && n > 0 && n <= 3600 {
			return n
		}
	}
	return 0
}

func buildSFDaySummary(badges []*huya.FanBadge) string {
	var parts []string
	for _, b := range badges {
		if b != nil && b.SuperFan && b.SFDayActive {
			times := b.SFDayScoreTimes
			if times <= 0 {
				times = 4
			}
			parts = append(parts, fmt.Sprintf("%s (%d倍)", nonEmpty(b.PresenterNick, fmt.Sprintf("UID:%d", b.PID)), times))
		}
	}
	return strings.Join(parts, ", ")
}

func collectSuperFanPIDs(badges []*huya.FanBadge) []int64 {
	var pids []int64
	seen := make(map[int64]bool)
	for _, b := range badges {
		if b != nil && b.PID > 0 && b.SuperFan && !seen[b.PID] {
			seen[b.PID] = true
			pids = append(pids, b.PID)
		}
	}
	return pids
}

func nonEmpty(s, fallback string) string {
	if strings.TrimSpace(s) != "" {
		return s
	}
	return fallback
}

func sleepCtx(ctx context.Context, d time.Duration) {
	t := time.NewTimer(d)
	defer t.Stop()
	select {
	case <-ctx.Done():
	case <-t.C:
	}
}

func formatRunNotification(accountName, trigger string, parts []string, bag *huya.BagHuliangSummary, sfDaySummary string, cooldownSec int, now time.Time) (string, string) {
	name := nonEmpty(accountName, "虎牙账号")
	var title string
	if sfDaySummary != "" {
		title = fmt.Sprintf("🎉 今日超粉日 · %s", name)
	} else {
		switch trigger {
		case "rescue":
			title = fmt.Sprintf("虎伴 · %s 临期抢救完成", name)
		case "retry":
			title = fmt.Sprintf("虎伴 · %s 冷却补领完成", name)
		default:
			title = fmt.Sprintf("虎伴 · %s 任务完成", name)
		}
	}

	var lines []string
	if sfDaySummary != "" {
		lines = append(lines, "🔥 超粉日进行中："+sfDaySummary)
	}
	for _, p := range parts {
		p = strings.TrimSpace(p)
		if p == "" {
			continue
		}
		lines = append(lines, decorateSummaryPart(p))
	}
	if len(lines) == 0 {
		lines = append(lines, "✅ 任务执行完成")
	}
	if bagLine := formatBagSummaryLine(bag); bagLine != "" {
		lines = append(lines, bagLine)
	}
	if cooldownSec > 0 {
		if cooldownSec >= 60 {
			mins := (cooldownSec + 30) / 60
			lines = append(lines, fmt.Sprintf("⏳ 补领：冷却中，约 %d 分钟后自动补领", mins))
		} else {
			lines = append(lines, fmt.Sprintf("⏳ 补领：冷却中，约 %d 秒后自动补领", cooldownSec))
		}
	}
	lines = append(lines, fmt.Sprintf("🕐 %s · %s", triggerLabel(trigger), now.Format("15:04")))
	return title, strings.Join(lines, "\n")
}

func formatSuperFanDayOnlyNotification(accountName, sfDaySummary string, bag *huya.BagHuliangSummary) (string, string) {
	name := nonEmpty(accountName, "虎牙账号")
	title := fmt.Sprintf("🎉 今日超粉日 · 4 倍亲密度（%s）", name)
	lines := []string{
		"🔥 进行中：" + sfDaySummary,
	}
	if bagLine := formatBagSummaryLine(bag); bagLine != "" {
		lines = append(lines, bagLine)
	}
	lines = append(lines, "💡 记得送出囤积的虎粮，吃满 4 倍亲密度！")
	return title, strings.Join(lines, "\n")
}

func decorateSummaryPart(part string) string {
	switch {
	case strings.HasPrefix(part, "虎粮："):
		return "🌾 " + part
	case strings.HasPrefix(part, "打卡："), strings.HasPrefix(part, "粉丝牌："):
		return "🏅 " + part
	case strings.HasPrefix(part, "福利："):
		return "🎁 " + part
	case strings.HasPrefix(part, "送礼："):
		return "💝 " + part
	case strings.HasPrefix(part, "背包："):
		return "🎒 " + part
	default:
		return "• " + part
	}
}

func formatBagSummaryLine(bag *huya.BagHuliangSummary) string {
	if bag == nil {
		return ""
	}
	normal := fmt.Sprintf("免费虎粮 %d 个", bag.NormalCount)
	if bag.Expiring24h > 0 {
		normal += fmt.Sprintf("（24h 内过期 %d 个）", bag.Expiring24h)
	}
	if bag.SuperTotalCount > 0 {
		return fmt.Sprintf("🎒 包裹：%s · %s %d 个", normal, nonEmpty(bag.SuperDisplayName, "超粉虎粮"), bag.SuperTotalCount)
	}
	return "🎒 包裹：" + normal
}

func triggerLabel(trigger string) string {
	switch trigger {
	case "schedule":
		return "每日定时"
	case "manual":
		return "手动执行"
	case "retry":
		return "冷却补领"
	case "rescue":
		return "临期抢救"
	default:
		return "自动任务"
	}
}

type RunRecord = store.RunRecord
