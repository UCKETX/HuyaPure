package engine

import (
	"fmt"
	"sort"

	"github.com/UCKETX/HuyaPure/server/internal/huya"
	"github.com/UCKETX/HuyaPure/server/internal/store"
)

const (
	// ExpiringRescueHorizonSec treats batches expiring within 36 hours as urgent.
	ExpiringRescueHorizonSec = 36 * 3600
	// RescueBufferBeforeNextSfSec requires batches to last at least 6h past next SuperFan Day to hoard.
	RescueBufferBeforeNextSfSec = 6 * 3600
)

type SendPlan struct {
	ItemType  int
	ItemName  string
	TargetPID int64
	Nick      string
	Count     int
	IsSuper   bool
}

type PlanDecision struct {
	Plans          []SendPlan
	HoardedNormal  int
	HasTodaySFDay  bool
	NextSFDayEpoch int64
	Reason         string
}

// BuildSendPlan computes the exact gift-sending plan matching 虎伴 Android's smart SuperFan Day
// hoarding, <=36h expiry rescue (with cap bypass), and 4 multi-target distribution strategies.
func BuildSendPlan(
	cfg store.AccountConfig,
	bag *huya.BagHuliangSummary,
	badges []*huya.FanBadge,
) PlanDecision {
	decision := PlanDecision{}
	if bag == nil || (!cfg.AutoSendHuliang && !cfg.SendSuperHuliang) {
		return decision
	}

	badgeByPID := make(map[int64]*huya.FanBadge, len(badges))
	for _, b := range badges {
		if b != nil && b.PID > 0 {
			badgeByPID[b.PID] = b
		}
	}

	// Resolve target anchors & weights (empty SendTargets = all non-extinguished badges, fallback all badges).
	type targetEntry struct {
		pid    int64
		nick   string
		badge  *huya.FanBadge
		weight int
	}
	var resolved []targetEntry
	if len(cfg.SendTargets) > 0 {
		seen := make(map[int64]bool)
		for _, t := range cfg.SendTargets {
			if t.PID <= 0 || seen[t.PID] {
				continue
			}
			seen[t.PID] = true
			w := t.Weight
			if w <= 0 {
				w = 1
			}
			b := badgeByPID[t.PID]
			nick := t.Name
			if b != nil && b.PresenterNick != "" {
				nick = b.PresenterNick
			}
			if nick == "" {
				nick = fmt.Sprintf("UID:%d", t.PID)
			}
			resolved = append(resolved, targetEntry{pid: t.PID, nick: nick, badge: b, weight: w})
		}
	} else {
		for _, b := range badges {
			if b != nil && b.PID > 0 && b.Extinguished == 0 {
				resolved = append(resolved, targetEntry{pid: b.PID, nick: b.PresenterNick, badge: b, weight: 1})
			}
		}
		if len(resolved) == 0 {
			for _, b := range badges {
				if b != nil && b.PID > 0 {
					resolved = append(resolved, targetEntry{pid: b.PID, nick: b.PresenterNick, badge: b, weight: 1})
				}
			}
		}
	}
	if len(resolved) == 0 {
		decision.Reason = "无可用赠送目标"
		return decision
	}

	// Identify today's SuperFan Day targets & earliest upcoming SuperFan Day among targets.
	var sfDayTargets []targetEntry
	var earliestNextSF int64
	for _, t := range resolved {
		if t.badge != nil && t.badge.SuperFan {
			if t.badge.SFDayActive {
				sfDayTargets = append(sfDayTargets, t)
			} else if t.badge.NextSFDayEpoch > 0 {
				if earliestNextSF == 0 || t.badge.NextSFDayEpoch < earliestNextSF {
					earliestNextSF = t.badge.NextSFDayEpoch
				}
			}
		}
	}
	decision.HasTodaySFDay = len(sfDayTargets) > 0
	decision.NextSFDayEpoch = earliestNextSF

	nowSec := bag.ServerTimeSec
	if nowSec <= 0 {
		nowSec = 0
	}

	// Helper to distribute `amount` across a list of targetEntry according to cfg.SendDistributeMode.
	distribute := func(itemType int, itemName string, isSuper bool, amount int, list []targetEntry) []SendPlan {
		if amount <= 0 || len(list) == 0 {
			return nil
		}
		if len(list) == 1 {
			return []SendPlan{{
				ItemType:  itemType,
				ItemName:  itemName,
				TargetPID: list[0].pid,
				Nick:      list[0].nick,
				Count:     amount,
				IsSuper:   isSuper,
			}}
		}

		alloc := make([]int, len(list))
		switch cfg.SendDistributeMode {
		case store.DistributeFillQuota:
			rem := amount
			for i, t := range list {
				if rem <= 0 {
					break
				}
				capCount := estimateRemainingHuliangCapacity(t.badge, isSuper)
				if capCount <= 0 {
					continue
				}
				give := min(rem, capCount)
				alloc[i] = give
				rem -= give
			}
			// Overflow evenly if all known quotas are full (or unknown).
			if rem > 0 {
				for i := range list {
					extra := rem / (len(list) - i)
					alloc[i] += extra
					rem -= extra
				}
			}

		case store.DistributeNearestUpgrade:
			order := make([]int, len(list))
			for i := range order {
				order[i] = i
			}
			sort.SliceStable(order, func(a, b int) bool {
				ga := upgradeGap(list[order[a]].badge)
				gb := upgradeGap(list[order[b]].badge)
				return ga < gb
			})
			rem := amount
			for _, idx := range order {
				if rem <= 0 {
					break
				}
				capCount := estimateRemainingHuliangCapacity(list[idx].badge, isSuper)
				if capCount <= 0 {
					continue
				}
				give := min(rem, capCount)
				alloc[idx] = give
				rem -= give
			}
			if rem > 0 {
				alloc[order[0]] += rem
			}

		case store.DistributeCustomRatio:
			totalW := 0
			for _, t := range list {
				totalW += max(1, t.weight)
			}
			type remFrac struct {
				idx  int
				frac float64
			}
			rems := make([]remFrac, len(list))
			assigned := 0
			for i, t := range list {
				exact := float64(amount) * float64(max(1, t.weight)) / float64(totalW)
				floorVal := int(exact)
				alloc[i] = floorVal
				assigned += floorVal
				rems[i] = remFrac{idx: i, frac: exact - float64(floorVal)}
			}
			leftover := amount - assigned
			sort.SliceStable(rems, func(i, j int) bool {
				return rems[i].frac > rems[j].frac
			})
			for i := 0; i < leftover && i < len(rems); i++ {
				alloc[rems[i].idx]++
			}

		default: // DistributeEven
			base := amount / len(list)
			rem := amount % len(list)
			for i := range list {
				alloc[i] = base
				if i < rem {
					alloc[i]++
				}
			}
		}

		var plans []SendPlan
		for i, c := range alloc {
			if c > 0 {
				plans = append(plans, SendPlan{
					ItemType:  itemType,
					ItemName:  itemName,
					TargetPID: list[i].pid,
					Nick:      list[i].nick,
					Count:     c,
					IsSuper:   isSuper,
				})
			}
		}
		return plans
	}

	// 1) SuperFan Huliang (1-day validity): always send today, prioritizing SFDay targets.
	// Huya restricts 超粉虎粮 (itemType=20699) to anchors where the user is a SuperFan, so if
	// selected SendTargets contain no SuperFan anchor, fall back to SuperFan badges from the account.
	if cfg.SendSuperHuliang && len(bag.SuperItems) > 0 {
		superTargets := resolved
		if cfg.SmartSuperFanDay && len(sfDayTargets) > 0 {
			superTargets = sfDayTargets
		} else {
			var sfOnly []targetEntry
			for _, t := range resolved {
				if t.badge != nil && t.badge.SuperFan {
					sfOnly = append(sfOnly, t)
				}
			}
			if len(sfOnly) == 0 {
				for _, b := range badges {
					if b != nil && b.PID > 0 && b.SuperFan && b.Extinguished == 0 {
						sfOnly = append(sfOnly, targetEntry{pid: b.PID, nick: b.PresenterNick, badge: b, weight: 1})
					}
				}
			}
			if len(sfOnly) > 0 {
				superTargets = sfOnly
			}
		}
		for _, item := range bag.SuperItems {
			if item.Count > 0 {
				decision.Plans = append(decision.Plans, distribute(item.ItemType, item.Name, true, item.Count, superTargets)...)
			}
		}
	}

	// 2) Normal free Huliang: Smart SuperFan Day hoarding + <=36h expiry rescue (with cap bypass).
	if cfg.AutoSendHuliang && bag.NormalCount > 0 {
		available := bag.NormalCount
		if cfg.SmartSuperFanDay && len(sfDayTargets) > 0 {
			// Today is SuperFan Day! Send all available (respecting optional maxCount unless expiring).
			toSend := available
			if cfg.SendMaxCount > 0 && toSend > cfg.SendMaxCount {
				expiringUrgent := bag.CountNormalExpiringBy(nowSec + ExpiringRescueHorizonSec)
				toSend = max(cfg.SendMaxCount, expiringUrgent)
				if toSend > available {
					toSend = available
				}
			}
			decision.Reason = "超粉日 4 倍爆发赠送"
			decision.Plans = append(decision.Plans, distribute(bag.NormalItemType, bag.NormalName, false, toSend, sfDayTargets)...)
		} else if cfg.SmartSuperFanDay && earliestNextSF > nowSec {
			// Non-SFDay with upcoming SFDay: send ONLY batches that will expire before next SFDay (or within 36h).
			deadline := max(nowSec+ExpiringRescueHorizonSec, earliestNextSF+RescueBufferBeforeNextSfSec)
			expiringCount := bag.CountNormalExpiringBy(deadline)
			urgentCount := bag.CountNormalExpiringBy(nowSec + ExpiringRescueHorizonSec)
			toSend := expiringCount
			if cfg.SendMaxCount > 0 && toSend > cfg.SendMaxCount {
				// Bypass cap for batches expiring within 36h so zero Huliang ever expires.
				toSend = max(cfg.SendMaxCount, urgentCount)
				if toSend > expiringCount {
					toSend = expiringCount
				}
			}
			decision.HoardedNormal = max(0, available-toSend)
			if toSend > 0 {
				decision.Reason = fmt.Sprintf("临期抢救送出 %d 个，囤粮 %d 个等超粉日", toSend, decision.HoardedNormal)
				decision.Plans = append(decision.Plans, distribute(bag.NormalItemType, bag.NormalName, false, toSend, resolved)...)
			} else {
				decision.Reason = fmt.Sprintf("智能囤粮 %d 个，静候超粉日 4 倍亲密度", decision.HoardedNormal)
			}
		} else {
			// Standard send mode (no upcoming SFDay or smart mode disabled).
			toSend := available
			if cfg.SendMaxCount > 0 && toSend > cfg.SendMaxCount {
				urgentCount := bag.CountNormalExpiringBy(nowSec + ExpiringRescueHorizonSec)
				toSend = max(cfg.SendMaxCount, urgentCount)
				if toSend > available {
					toSend = available
				}
			}
			decision.Reason = "日常自动赠送"
			decision.Plans = append(decision.Plans, distribute(bag.NormalItemType, bag.NormalName, false, toSend, resolved)...)
		}
	}

	return decision
}

func estimateRemainingHuliangCapacity(b *huya.FanBadge, isSuper bool) int {
	if b == nil || b.Quota <= 0 {
		return 1 << 20 // unknown quota -> don't artificially block
	}
	remScore := b.Quota - b.QuotaUsed
	if remScore <= 0 {
		return 0
	}
	perUnit := 1
	if isSuper {
		perUnit = 10
	}
	if b.SuperFan && b.SFDayActive {
		times := b.SFDayScoreTimes
		if times <= 0 {
			times = 4
		}
		perUnit *= times
	}
	return (remScore + perUnit - 1) / perUnit
}

func upgradeGap(b *huya.FanBadge) int {
	if b == nil || b.NextScore <= 0 {
		return 1 << 30
	}
	gap := b.NextScore - b.Score
	if gap < 0 {
		return 0
	}
	return gap
}
