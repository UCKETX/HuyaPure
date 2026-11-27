package engine

import (
	"testing"

	"github.com/UCKETX/HuyaPure/server/internal/huya"
	"github.com/UCKETX/HuyaPure/server/internal/store"
)

func TestSmartHoardingAndExpiryRescue(t *testing.T) {
	now := int64(1800000000)
	nextSF := now + 5*86400
	badges := []*huya.FanBadge{
		{PID: 1001, PresenterNick: "星河小虎", Level: 20, Score: 10000, NextScore: 15000, Quota: 5600, QuotaUsed: 0, SuperFan: true, SFDayActive: false, NextSFDayEpoch: nextSF},
	}
	bag := &huya.BagHuliangSummary{
		NormalItemType: 4,
		NormalName:     "虎粮",
		NormalCount:    500,
		ServerTimeSec:  now,
		NormalBatches: []huya.FreeHuliangBatch{
			{Count: 80, ExpireSec: now + 12*3600},  // expires in 12h (urgent -> bypass cap!)
			{Count: 420, ExpireSec: now + 14*86400}, // lasts past next SFDay -> hoard!
		},
	}
	cfg := store.DefaultAccountConfig()
	cfg.AutoSendHuliang = true
	cfg.SmartSuperFanDay = true
	cfg.SendMaxCount = 50 // lower than urgent 80 -> urgent 80 must bypass cap!

	dec := BuildSendPlan(cfg, bag, badges)
	if len(dec.Plans) != 1 || dec.Plans[0].Count != 80 {
		t.Fatalf("expected urgent rescue of 80 bypassing cap 50, got %+v", dec.Plans)
	}
	if dec.HoardedNormal != 420 {
		t.Fatalf("expected 420 hoarded, got %d", dec.HoardedNormal)
	}
}

func TestAllFourDistributionModes(t *testing.T) {
	now := int64(1800000000)
	badges := []*huya.FanBadge{
		{PID: 1, PresenterNick: "A", Level: 20, Score: 9000, NextScore: 10000, Quota: 100, QuotaUsed: 70}, // rem=30, gap=1000
		{PID: 2, PresenterNick: "B", Level: 10, Score: 4800, NextScore: 5000, Quota: 500, QuotaUsed: 0},  // rem=500, gap=200
		{PID: 3, PresenterNick: "C", Level: 5, Score: 100, NextScore: 500, Quota: 500, QuotaUsed: 0},     // rem=500, gap=400
	}
	bag := &huya.BagHuliangSummary{
		NormalItemType: 4,
		NormalName:     "虎粮",
		NormalCount:    100,
		ServerTimeSec:  now,
	}

	// 1. Even: 34, 33, 33
	cfg := store.DefaultAccountConfig()
	cfg.AutoSendHuliang = true
	cfg.SmartSuperFanDay = false
	cfg.SendDistributeMode = store.DistributeEven
	dec := BuildSendPlan(cfg, bag, badges)
	if len(dec.Plans) != 3 || dec.Plans[0].Count != 34 || dec.Plans[1].Count != 33 || dec.Plans[2].Count != 33 {
		t.Fatalf("even mismatch: %+v", dec.Plans)
	}

	// 2. FillQuota: A gets 30 (fills rem 30), B gets 70
	cfg.SendDistributeMode = store.DistributeFillQuota
	dec = BuildSendPlan(cfg, bag, badges)
	if len(dec.Plans) != 2 || dec.Plans[0].TargetPID != 1 || dec.Plans[0].Count != 30 || dec.Plans[1].TargetPID != 2 || dec.Plans[1].Count != 70 {
		t.Fatalf("fill_quota mismatch: %+v", dec.Plans)
	}

	// 3. NearestUpgrade: B has smallest gap (200) -> gets all 100
	cfg.SendDistributeMode = store.DistributeNearestUpgrade
	dec = BuildSendPlan(cfg, bag, badges)
	if len(dec.Plans) != 1 || dec.Plans[0].TargetPID != 2 || dec.Plans[0].Count != 100 {
		t.Fatalf("nearest_upgrade mismatch: %+v", dec.Plans)
	}

	// 4. CustomRatio (3:1 -> 75, 25)
	cfg.SendDistributeMode = store.DistributeCustomRatio
	cfg.SendTargets = []store.SendTarget{
		{PID: 1, Name: "A", Weight: 3},
		{PID: 2, Name: "B", Weight: 1},
	}
	dec = BuildSendPlan(cfg, bag, badges)
	if len(dec.Plans) != 2 || dec.Plans[0].Count != 75 || dec.Plans[1].Count != 25 {
		t.Fatalf("custom_ratio mismatch: %+v", dec.Plans)
	}
}

func TestSuperFanHuliangSentIndependentlyAndTargetsSuperFanAnchor(t *testing.T) {
	now := int64(1800000000)
	badges := []*huya.FanBadge{
		{PID: 2176998321, PresenterNick: "陈子豪", Level: 19, SuperFan: false},
		{PID: 1199667880631, PresenterNick: "芒果树-小水", Level: 10, SuperFan: true},
	}
	bag := &huya.BagHuliangSummary{
		NormalItemType:   4,
		NormalName:       "虎粮",
		NormalCount:      20,
		SuperTotalCount:  10,
		SuperDisplayName: "超粉虎粮",
		ServerTimeSec:    now,
		SuperItems: []huya.FreeHuliangItem{
			{ItemType: 20699, Name: "超粉虎粮", Count: 10, IsSuper: true, ExpireSec: now + 18*3600},
		},
	}
	cfg := store.DefaultAccountConfig()
	cfg.AutoSendHuliang = false // normal send OFF
	cfg.SendSuperHuliang = true // superfan send ON
	// Even if SendTargets only lists a non-SuperFan anchor, 超粉虎粮 must route to the SuperFan anchor.
	cfg.SendTargets = []store.SendTarget{{PID: 2176998321, Name: "陈子豪", Weight: 1}}

	dec := BuildSendPlan(cfg, bag, badges)
	if len(dec.Plans) != 1 {
		t.Fatalf("expected 1 superfan plan, got %+v", dec.Plans)
	}
	if !dec.Plans[0].IsSuper || dec.Plans[0].ItemType != 20699 || dec.Plans[0].TargetPID != 1199667880631 || dec.Plans[0].Count != 10 {
		t.Fatalf("unexpected superfan plan: %+v", dec.Plans[0])
	}
}
