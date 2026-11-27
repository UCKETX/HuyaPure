package huya

import (
	"context"
	"crypto/rand"
	"fmt"
	"math/big"
	"sort"
	"strings"
	"sync"
	"time"

	"github.com/UCKETX/HuyaPure/server/internal/jce"
)

const (
	BagHuliangAppID            = 8
	BagSource                  = 4
	BagPropsListTabID          = -10000
	BagHuliangPlacementBizType = 1
	BagHuliangPlacementID      = "Package_HuLiang_Ad"

	ReqMaskUserWithPopularity = 3
	OpFGBoxReceive            = 3
	CommWelfareOpReceive      = 2

	NormalHuliangItemType = 4
)

// FanBadge mirrors Huya's BadgeInfo + SuperFansConfig + GuardPresenterInfo.
type FanBadge struct {
	PID               int64  `json:"pid"`
	BadgeID           int64  `json:"badgeId"`
	BadgeName         string `json:"badgeName"`
	PresenterNick     string `json:"nick"`
	PresenterAvatar   string `json:"avatar,omitempty"`
	RoomID            int32  `json:"roomId,omitempty"`
	Level             int    `json:"level"`
	Score             int    `json:"score"`
	NextScore         int    `json:"nextScore"`
	DayScore          int    `json:"dayScore"`
	Quota             int    `json:"quota"`
	QuotaUsed         int    `json:"quotaUsed"`
	Extinguished      int    `json:"extinguished"`
	BadgeType         int    `json:"badgeType"`
	CustomBadgeFlag   int    `json:"customBadgeFlag"`
	SuperFan          bool   `json:"superFan"`
	SFType            int    `json:"sfType"`       // 0=none, 1=超粉, 2=年费超粉
	SFVariety         int    `json:"sfVariety"`    // 0=normal, 1=超粉Plus
	SFLevel           int    `json:"sfLevel"`
	FansIdentity      int    `json:"fansIdentity"` // 1=超粉, 2=年费超粉, 3=超粉Plus, 4=年费超粉Plus
	FaithName         string `json:"faithName,omitempty"`
	GuardLevel        int    `json:"guardLevel"` // 0=none, 1=初爱守护, 2=超级守护, 3=至尊守护
	GuardName         string `json:"guardName,omitempty"`
	GuardType         int    `json:"guardType,omitempty"`
	GuardAccompanyDay int    `json:"guardAccompanyDay,omitempty"`
	GuardEndTime      int64  `json:"guardEndTime,omitempty"`
	SFDayActive       bool   `json:"sfDayActive"`
	SFDay             int    `json:"sfDay"`
	SFDayScoreTimes   int    `json:"sfDayScoreTimes"`
	SFDayQuotaTimes   int    `json:"sfDayQuotaTimes"`
	SFDayLeftSec      int              `json:"sfDayLeftSec"`
	NextSFDayEpoch    int64            `json:"nextSfDayEpoch"`
	CheckinState      string           `json:"checkinState,omitempty"` // success | already | failed
	Render            *BadgeRenderInfo `json:"render,omitempty"`
}

type FreeHuliangBatch struct {
	Count     int   `json:"count"`
	ExpireSec int64 `json:"expireSec"` // 0 = no expiration
}

type FreeHuliangItem struct {
	ItemType  int    `json:"itemType"`
	Name      string `json:"name"`
	Count     int    `json:"count"`
	IsSuper   bool   `json:"isSuper"`
	ExpireSec int64  `json:"expireSec"`
}

type BagHuliangSummary struct {
	NormalItemType      int                `json:"normalItemType"`
	NormalName          string             `json:"normalName"`
	NormalCount         int                `json:"normalCount"`
	NormalBatches       []FreeHuliangBatch `json:"normalBatches"`
	ServerTimeSec       int64              `json:"serverTimeSec"`
	Expiring24h         int                `json:"expiring24h"`
	EarliestExpireSec   int64              `json:"earliestExpireSec"`
	DetailExpireInDay   int64              `json:"detailExpireInDay"`
	SuperItems          []FreeHuliangItem  `json:"superItems"`
	SuperTotalCount     int                `json:"superTotalCount"`
	SuperDisplayName    string             `json:"superDisplayName"`
}

func (b *BagHuliangSummary) CountNormalExpiringBy(deadlineSec int64) int {
	if b == nil || b.NormalCount <= 0 || deadlineSec <= 0 {
		return 0
	}
	total := 0
	for _, batch := range b.NormalBatches {
		if batch.Count > 0 && batch.ExpireSec > 0 && batch.ExpireSec <= deadlineSec {
			total += batch.Count
		}
	}
	if total == 0 && b.DetailExpireInDay > 0 && deadlineSec >= b.ServerTimeSec+24*3600 {
		total = int(b.DetailExpireInDay)
	}
	if total > b.NormalCount {
		total = b.NormalCount
	}
	return total
}

type CommonWelfareItem struct {
	PID        int64  `json:"pid"`
	BusinessID int64  `json:"businessId"`
	WelfareID  int64  `json:"welfareId"`
	Title      string `json:"title"`
	Desc       string `json:"desc"`
	Status     int    `json:"status"` // 1 = claimable
}

type FansGroupTaskInfo struct {
	RetCode        int32
	DayState       int32 // 1 = claimable daily 10 Huliang box
	CommonWelfares []CommonWelfareItem
}

type SuperFansConfig struct {
	SFFlag          int
	SFDay           int
	SFDayQuotaTimes int
	SFDayScoreTimes int
	SFDayActive     bool
	BeLeftTime      int
	SFType          int
	RoomID          int32
	PresenterNick   string
	AvatarURL       string
}

type UserProfile struct {
	NickName  string
	AvatarURL string
}

type GuardInfo struct {
	Level        int
	GuardType    int
	AccompanyDay int
	EndTime      int64
}

type AdPanelResult struct {
	WupRet         int32
	AdTaskID       string
	AdType         int32
	NeedWatchTime  int32
	DisplayCount   int32
	MaxRewardCount int32
	SlotAdRaw      []byte
	Ext            map[string]string
	Toast          string
	ServerContext  string
}

// BagAdPlacement mirrors HUYA.PropsListPlacement + HUYA.HuLiangADReplacementInfo
// returned by wupui.getPropsListPlacement (iTabId = -10000).
// Huya requires calling wupui.notifyPropsListPlacement when Status <= 0 to activate
// the daily Package_HuLiang_Ad task before wupui.getCommAdPannel (iAppId=8) will succeed.
type BagAdPlacement struct {
	ID            string
	Name          string
	Button        string
	BizType       int32
	Count         int32
	RemainSec     int64
	Status        int32 // 0=unactivated (needs notify), 1=active, 2/3=finished
	FinishText    string
	Text          string
	ServerContext string
}

// API wraps all Huya WUP RPC operations for a given WupClient.
type API struct {
	Wup        *WupClient
	badgeCatMu sync.Mutex
	badgeCat   *BadgeAssetCatalog
	badgeCatAt time.Time
}

func NewAPI(wup *WupClient) *API {
	if wup == nil {
		wup = NewWupClient()
	}
	return &API{Wup: wup}
}

func GuardLevelName(level int) string {
	switch level {
	case 1:
		return "初爱守护"
	case 2:
		return "超级守护"
	case 3:
		return "至尊守护"
	default:
		return ""
	}
}

// QueryFanBadges invokes liveui.queryBadgeInfoList.
func (a *API) QueryFanBadges(ctx context.Context, creds Credentials) ([]*FanBadge, error) {
	req := jce.NewWriter()
	req.WriteStructRaw(0, creds.EncodeUserId())
	req.WriteLong(1, creds.UID) // lToUid
	req.WriteInt(2, 0)          // iType
	req.WriteLong(3, 0)         // lPid
	req.WriteInt(4, 0)          // iSFanFlag

	rsp, _, err := a.Wup.Call(ctx, "liveui", "queryBadgeInfoList", "tReq", req.Bytes(), creds.Cookie)
	if err != nil {
		return nil, err
	}
	rawList := rsp.GetList(0) // vBadgeInfo
	nowSec := time.Now().Unix()
	seen := make(map[int64]bool)
	out := make([]*FanBadge, 0, len(rawList))

	for _, elem := range rawList {
		b, ok := elem.([]byte)
		if !ok {
			continue
		}
		v := jce.ParseFields(b)
		var chUID int64
		var roomID int32
		if ch := v.GetStruct(14); ch != nil {
			chUID = ch.GetLong(6, 0)
			roomID = ch.GetInt(8, 0)
		}
		badgeUID := v.GetLong(0, 0)
		badgeID := v.GetLong(1, 0)
		pid := chUID
		if pid <= 0 && badgeUID > 0 && badgeUID != creds.UID {
			pid = badgeUID
		}
		if pid <= 0 || seen[pid] {
			continue
		}
		seen[pid] = true

		nick := v.GetString(2, "")
		if nick == "" {
			nick = fmt.Sprintf("%d", pid)
		}
		badgeName := v.GetString(3, "")
		level := int(v.GetInt(4, 0))
		score := int(v.GetInt(6, 0))
		nextScore := int(v.GetInt(7, 0))
		quotaUsed := int(v.GetInt(8, 0))
		quota := int(v.GetInt(9, 0))
		baseQuota := int(v.GetInt(20, 0))
		if quota <= 0 {
			quota = baseQuota
		}
		avatar := v.GetString(15, "")
		badgeType := int(v.GetInt(17, 0))
		customFlag := int(v.GetInt(22, 0))
		dayScore := int(v.GetInt(24, 0))
		extinguished := int(v.GetInt(26, 0))

		var faithName string
		if faith := v.GetStruct(18); faith != nil {
			faithName = faith.GetString(0, "")
		}

		var sfType, sfVariety, sfLevel, sfFlag int
		if sf := v.GetStruct(19); sf != nil {
			expiredTS := sf.GetLong(0, 0)
			sfFlag = int(sf.GetInt(1, 0))
			annualTS := sf.GetLong(2, 0)
			sfVariety = int(sf.GetInt(3, 0))
			sfLevel = int(sf.GetInt(6, 0))
			activeByExpiry := expiredTS == 0 || expiredTS > nowSec
			if activeByExpiry && (sfFlag > 0 || sfLevel > 0) {
				if annualTS > nowSec || sfFlag == 2 {
					sfType = 2
				} else {
					sfType = 1
				}
			}
		}

		var extIdentity int
		if ext := v.GetStruct(25); ext != nil {
			extIdentity = int(ext.GetInt(1, 0))
		}

		fansIdentity := 0
		if sfType == 1 {
			if sfVariety != 0 {
				fansIdentity = 3
			} else {
				fansIdentity = 1
			}
		} else if sfType == 2 {
			if sfVariety != 0 {
				fansIdentity = 4
			} else {
				fansIdentity = 2
			}
		} else if extIdentity > 0 {
			fansIdentity = extIdentity
			switch extIdentity {
			case 1:
				sfType, sfVariety = 1, 0
			case 3:
				sfType, sfVariety = 1, 1
			case 2:
				sfType, sfVariety = 2, 0
			case 4:
				sfType, sfVariety = 2, 1
			}
		}
		superFan := sfType > 0 || sfFlag > 0 || sfLevel > 0 || (fansIdentity >= 1 && fansIdentity <= 4)

		out = append(out, &FanBadge{
			PID:             pid,
			BadgeID:         badgeID,
			BadgeName:       badgeName,
			PresenterNick:   nick,
			PresenterAvatar: avatar,
			RoomID:          roomID,
			Level:           level,
			Score:           score,
			NextScore:       nextScore,
			DayScore:        dayScore,
			Quota:           quota,
			QuotaUsed:       quotaUsed,
			Extinguished:    extinguished,
			BadgeType:       badgeType,
			CustomBadgeFlag: customFlag,
			SuperFan:        superFan,
			SFType:          sfType,
			SFVariety:       sfVariety,
			SFLevel:         sfLevel,
			FansIdentity:    fansIdentity,
			FaithName:       faithName,
		})
	}

	sort.Slice(out, func(i, j int) bool {
		if out[i].Extinguished != out[j].Extinguished {
			return out[i].Extinguished < out[j].Extinguished
		}
		if out[i].Level != out[j].Level {
			return out[i].Level > out[j].Level
		}
		return out[i].Score > out[j].Score
	})
	return out, nil
}

// FansSign invokes wupui.setFansSign for a single presenter.
// Returns state ("success" | "already" | "failed") and detail message.
func (a *API) FansSign(ctx context.Context, creds Credentials, pid int64) (string, string) {
	req := jce.NewWriter()
	req.WriteStructRaw(0, creds.EncodeUserId())
	req.WriteLong(1, pid)

	rsp, wupRet, err := a.Wup.Call(ctx, "wupui", "setFansSign", "tReq", req.Bytes(), creds.Cookie)
	if err != nil {
		return "failed", err.Error()
	}
	code := rsp.GetInt(0, -1)
	msg := rsp.GetString(1, "")
	if code == 0 && wupRet == 0 {
		if msg == "" {
			msg = "打卡成功"
		}
		return "success", msg
	}
	if strings.Contains(msg, "已") || strings.Contains(msg, "重复") {
		return "already", msg
	}
	return "failed", fmt.Sprintf("code=%d %s", code, msg)
}

// QueryFansGroupTaskInfo invokes wupui.queryFansGroupTaskInfo.
func (a *API) QueryFansGroupTaskInfo(ctx context.Context, creds Credentials, pid int64) (*FansGroupTaskInfo, error) {
	req := jce.NewWriter()
	req.WriteStructRaw(0, creds.EncodeUserId())
	req.WriteLong(1, pid)
	req.WriteInt(2, ReqMaskUserWithPopularity)

	rsp, _, err := a.Wup.Call(ctx, "wupui", "queryFansGroupTaskInfo", "tReq", req.Bytes(), creds.Cookie)
	if err != nil {
		return nil, err
	}
	info := &FansGroupTaskInfo{
		RetCode: rsp.GetInt(0, 0),
	}
	if dayWelfare := rsp.GetStruct(7); dayWelfare != nil {
		info.DayState = dayWelfare.GetInt(0, 0)
	}
	for _, elem := range rsp.GetList(9) {
		b, ok := elem.([]byte)
		if !ok {
			continue
		}
		item := jce.ParseFields(b)
		info.CommonWelfares = append(info.CommonWelfares, CommonWelfareItem{
			PID:        pid,
			BusinessID: item.GetLong(0, 0),
			WelfareID:  item.GetLong(1, 0),
			Title:      item.GetString(2, ""),
			Desc:       item.GetString(3, ""),
			Status:     int(item.GetInt(5, 0)),
		})
	}
	return info, nil
}

// OperateFansBoxReceive claims the daily 10 free Huliang box via wupui.operateFansBox.
func (a *API) OperateFansBoxReceive(ctx context.Context, creds Credentials, pid int64) (bool, int, string) {
	req := jce.NewWriter()
	req.WriteStructRaw(0, creds.EncodeUserId())
	req.WriteLong(1, pid)
	req.WriteInt(2, OpFGBoxReceive)

	rsp, _, err := a.Wup.Call(ctx, "wupui", "operateFansBox", "tReq", req.Bytes(), creds.Cookie)
	if err != nil {
		return false, 0, err.Error()
	}
	retCode := rsp.GetInt(0, -1)
	itemCount := int(rsp.GetInt(5, 0))
	return retCode == 0, itemCount, fmt.Sprintf("iRetCode=%d, itemCount=%d", retCode, itemCount)
}

// ReceiveCommonWelfare claims a fan-group common/superfan welfare via wupui.queryFGCommonWelfare.
func (a *API) ReceiveCommonWelfare(ctx context.Context, creds Credentials, item CommonWelfareItem) (bool, string) {
	req := jce.NewWriter()
	req.WriteStructRaw(0, creds.EncodeUserId())
	req.WriteLong(1, item.PID)
	req.WriteInt(2, CommWelfareOpReceive)
	req.WriteLong(3, item.BusinessID)
	req.WriteLongList(4, []int64{item.WelfareID})

	rsp, _, err := a.Wup.Call(ctx, "wupui", "queryFGCommonWelfare", "tReq", req.Bytes(), creds.Cookie)
	if err != nil {
		return false, err.Error()
	}
	code := rsp.GetInt(0, -1)
	msg := rsp.GetString(1, "")
	return code == 0, fmt.Sprintf("iCode=%d %s", code, msg)
}

// DailyUserSignIn invokes wupui.oldUserBackSign.
func (a *API) DailyUserSignIn(ctx context.Context, creds Credentials) (string, string) {
	req := jce.NewWriter()
	req.WriteStructRaw(0, creds.EncodeUserId())
	req.WriteInt(1, 2) // iFrom = 2

	rsp, _, err := a.Wup.Call(ctx, "wupui", "oldUserBackSign", "tReq", req.Bytes(), creds.Cookie)
	if err != nil {
		return "skip", err.Error()
	}
	des := rsp.GetString(0, "")
	dayNum := rsp.GetInt(1, 0)
	udbCode := rsp.GetInt(3, -1)
	if udbCode == 0 && dayNum > 0 {
		return "success", fmt.Sprintf("第%d天", dayNum)
	}
	if strings.Contains(des, "已") || strings.Contains(des, "重复") {
		return "already", des
	}
	return "skip", fmt.Sprintf("code=%d %s", udbCode, des)
}

// QuerySuperFansConfig queries SuperFansConfig & room profile via wupui.getLiveRoomProfileForAudience.
func (a *API) QuerySuperFansConfig(ctx context.Context, creds Credentials, presenterUID int64, roomID int32) (*SuperFansConfig, error) {
	req := jce.NewWriter()
	req.WriteStructRaw(0, creds.EncodeUserId())
	req.WriteLong(1, presenterUID)
	req.WriteInt(2, roomID)
	req.WriteString(3, "")

	rsp, _, err := a.Wup.Call(ctx, "wupui", "getLiveRoomProfileForAudience", "tReq", req.Bytes(), creds.Cookie)
	if err != nil {
		return nil, err
	}
	out := &SuperFansConfig{}
	rp := rsp.GetStruct(0)
	if rp == nil {
		return out, nil
	}
	out.RoomID = rp.GetInt(0, 0)
	out.PresenterNick = rp.GetString(3, "")
	out.AvatarURL = rp.GetString(4, "")
	badgeRsp := rp.GetStruct(14)
	if badgeRsp == nil {
		return out, nil
	}
	cfg := badgeRsp.GetStruct(7)
	if cfg == nil {
		return out, nil
	}
	out.SFFlag = int(cfg.GetInt(0, 0))
	out.SFDay = int(cfg.GetInt(1, 0))
	out.SFDayQuotaTimes = int(cfg.GetInt(2, 0))
	out.SFDayScoreTimes = int(cfg.GetInt(3, 0))
	bIs := cfg.GetBool(4, false)
	iIs := cfg.GetInt(6, 0)
	out.SFDayActive = bIs || iIs == 1
	if out.SFDayActive && out.SFDayScoreTimes <= 0 {
		out.SFDayScoreTimes = 4
	}
	if left := int(cfg.GetInt(7, 0)); left > 0 {
		out.BeLeftTime = left
	}
	out.SFType = int(cfg.GetInt(8, 0))
	return out, nil
}

// QueryUserProfile fetches the logged-in account's Huya nickname and avatar URL
// via wupui.getLiveRoomProfileForAudience(selfUID, 0), which works for both 5008 mobile and 5002 web credentials.
func (a *API) QueryUserProfile(ctx context.Context, creds Credentials) (*UserProfile, error) {
	if creds.UID <= 0 {
		return nil, fmt.Errorf("无效 UID")
	}
	cfg, err := a.QuerySuperFansConfig(ctx, creds, creds.UID, 0)
	if err != nil {
		return nil, err
	}
	return &UserProfile{
		NickName:  strings.TrimSpace(cfg.PresenterNick),
		AvatarURL: strings.TrimSpace(cfg.AvatarURL),
	}, nil
}

// ComputeNextSFDayEpoch computes the Unix timestamp (00:00 local) of the next SuperFan Day.
func ComputeNextSFDayEpoch(sfDay int, now time.Time) int64 {
	loc := now.Location()
	if sfDay >= 1 && sfDay <= 31 {
		today := time.Date(now.Year(), now.Month(), now.Day(), 0, 0, 0, 0, loc)
		daysInMonth := func(y int, m time.Month) int {
			return time.Date(y, m+1, 0, 0, 0, 0, 0, loc).Day()
		}
		d := sfDay
		if maxD := daysInMonth(today.Year(), today.Month()); d > maxD {
			d = maxD
		}
		target := time.Date(today.Year(), today.Month(), d, 0, 0, 0, 0, loc)
		if !target.After(today) {
			nextMonth := time.Date(today.Year(), today.Month()+1, 1, 0, 0, 0, 0, loc)
			d2 := sfDay
			if maxD2 := daysInMonth(nextMonth.Year(), nextMonth.Month()); d2 > maxD2 {
				d2 = maxD2
			}
			target = time.Date(nextMonth.Year(), nextMonth.Month(), d2, 0, 0, 0, 0, loc)
		}
		return target.Unix()
	}
	if sfDay >= 20200101 {
		y := sfDay / 10000
		m := (sfDay / 100) % 100
		d := sfDay % 100
		if y >= 2020 && y <= 2100 && m >= 1 && m <= 12 && d >= 1 && d <= 31 {
			return time.Date(y, time.Month(m), d, 0, 0, 0, 0, loc).Unix()
		}
	}
	return 0
}

// QueryGuardInfo invokes liveui.GetOneUserGuardian.
func (a *API) QueryGuardInfo(ctx context.Context, creds Credentials, pid int64) (*GuardInfo, error) {
	req := jce.NewWriter()
	req.WriteStructRaw(0, creds.EncodeUserId())
	req.WriteLong(1, pid)

	rsp, _, err := a.Wup.Call(ctx, "liveui", "GetOneUserGuardian", "tReq", req.Bytes(), creds.Cookie)
	if err != nil {
		return nil, err
	}
	return &GuardInfo{
		Level:        int(rsp.GetInt(2, 0)),
		EndTime:      rsp.GetLong(3, 0),
		GuardType:    int(rsp.GetInt(8, 0)),
		AccompanyDay: int(rsp.GetInt(10, 0)),
	}, nil
}

// QueryBagHuliang invokes wupui.getPackageGift and extracts ONLY free (iIsPay==0) batches with exact lExpireTime.
// Passing SuperFan anchor PIDs in hintPIDs is essential because Huya hides SuperFan-scoped items
// (e.g. itemType=20699 超粉虎粮) when lPid=0 or when lPid is not a SuperFan room.
func (a *API) QueryBagHuliang(ctx context.Context, creds Credentials, hintPIDs ...int64) (*BagHuliangSummary, error) {
	seenPID := make(map[int64]bool, len(hintPIDs))
	var pids []int64
	for _, p := range hintPIDs {
		if p > 0 && !seenPID[p] {
			seenPID[p] = true
			pids = append(pids, p)
		}
	}
	if len(pids) == 0 {
		return a.queryBagHuliangForPID(ctx, creds, 0)
	}

	summary, err := a.queryBagHuliangForPID(ctx, creds, pids[0])
	if err != nil {
		return nil, err
	}
	// If the first SuperFan PID already exposed SuperItems (shared 20699 pool), we're done in 1 RPC.
	// Otherwise probe up to 4 additional SuperFan PIDs and merge any newly discovered SuperItem types.
	if summary.SuperTotalCount == 0 && len(pids) > 1 {
		seenTypes := make(map[int]bool)
		limit := min(len(pids), 5)
		for _, pid := range pids[1:limit] {
			extra, extraErr := a.queryBagHuliangForPID(ctx, creds, pid)
			if extraErr != nil || extra == nil {
				continue
			}
			for _, it := range extra.SuperItems {
				if it.Count <= 0 || seenTypes[it.ItemType] {
					continue
				}
				seenTypes[it.ItemType] = true
				summary.SuperItems = append(summary.SuperItems, it)
				summary.SuperTotalCount += it.Count
				if summary.SuperDisplayName == "" {
					summary.SuperDisplayName = it.Name
				}
			}
			if summary.SuperTotalCount > 0 {
				break
			}
		}
	}
	return summary, nil
}

func (a *API) queryBagHuliangForPID(ctx context.Context, creds Credentials, pid int64) (*BagHuliangSummary, error) {
	req := jce.NewWriter()
	req.WriteStructRaw(0, creds.EncodeUserId())
	req.WriteLong(1, pid) // lPid (0=global only; SuperFan anchor PID also returns room-scoped 超粉虎粮)

	rsp, _, err := a.Wup.Call(ctx, "wupui", "getPackageGift", "tReq", req.Bytes(), creds.Cookie)
	if err != nil {
		return nil, err
	}
	summary := &BagHuliangSummary{
		NormalItemType: NormalHuliangItemType,
		NormalName:     "虎粮",
		ServerTimeSec:  time.Now().Unix(),
	}
	if srvTime := rsp.GetLong(1, 0); srvTime > 0 {
		summary.ServerTimeSec = srvTime
	}

	var normalTotal, superTotal int64
	for _, elem := range rsp.GetList(0) {
		b, ok := elem.([]byte)
		if !ok {
			continue
		}
		detail := jce.ParseFields(b)
		var itemType int
		var name string
		if simple := detail.GetStruct(0); simple != nil {
			itemType = int(simple.GetInt(0, 0))
			name = simple.GetString(3, "")
		}
		detailExpireInDay := detail.GetLong(2, 0)
		if detailExpireInDay < 0 {
			detailExpireInDay = 0
		}
		detailMostEarly := normalizeUint32Sec(detail.GetLong(3, 0))

		var freeCnt int64
		var itemBatches []FreeHuliangBatch
		var earliestItemExpire int64

		for _, subElem := range detail.GetList(4) {
			sb, ok := subElem.([]byte)
			if !ok {
				continue
			}
			sub := jce.ParseFields(sb)

			var cnt, expSec int64
			if legacyCnt := sub.GetLong(6, 0); legacyCnt > 0 {
				// Legacy PackageItem layout: tag 1=iItemType, tag 4=lExpireTime, tag 5=iIsPay, tag 6=lItemCount
				if itemType <= 0 {
					itemType = int(sub.GetInt(1, 0))
				}
				// Strictly ignore paid items (iIsPay != 0).
				if sub.GetInt(5, 0) != 0 {
					continue
				}
				cnt = legacyCnt
				expSec = normalizeUint32Sec(sub.GetLong(4, 0))
			} else if leftCnt := sub.GetLong(1, 0); leftCnt > 0 {
				// Current HUYA.LeftShowInfo layout: tag 0=lMinSecond, tag 1=lItemCount
				cnt = leftCnt
				expSec = normalizeUint32Sec(sub.GetLong(0, 0))
			} else {
				continue
			}

			freeCnt += cnt
			itemBatches = append(itemBatches, FreeHuliangBatch{
				Count:     int(cnt),
				ExpireSec: expSec,
			})
			if expSec > 0 && (earliestItemExpire == 0 || expSec < earliestItemExpire) {
				earliestItemExpire = expSec
			}
		}
		if freeCnt == 0 {
			if totalTag5 := detail.GetLong(5, 0); totalTag5 > 0 {
				freeCnt = totalTag5
				if detailMostEarly > 0 {
					itemBatches = append(itemBatches, FreeHuliangBatch{
						Count:     int(totalTag5),
						ExpireSec: detailMostEarly,
					})
				}
			}
		}
		if earliestItemExpire == 0 && detailMostEarly > 0 {
			earliestItemExpire = detailMostEarly
		}
		if freeCnt <= 0 {
			continue
		}

		isSuper := strings.Contains(name, "超粉虎粮") ||
			strings.Contains(name, "超级虎粮") ||
			(strings.Contains(name, "虎粮") && itemType > 0 && itemType != NormalHuliangItemType)
		isNormal := !isSuper && (itemType == NormalHuliangItemType || name == "虎粮")

		if isSuper && itemType > 0 {
			label := name
			if label == "" {
				label = "超粉虎粮"
			}
			summary.SuperItems = append(summary.SuperItems, FreeHuliangItem{
				ItemType:  itemType,
				Name:      label,
				Count:     int(freeCnt),
				IsSuper:   true,
				ExpireSec: earliestItemExpire,
			})
			superTotal += freeCnt
			if summary.SuperDisplayName == "" {
				summary.SuperDisplayName = label
			}
		} else if isNormal {
			if itemType > 0 {
				summary.NormalItemType = itemType
			}
			if name != "" {
				summary.NormalName = name
			}
			normalTotal += freeCnt
			summary.NormalBatches = append(summary.NormalBatches, itemBatches...)
			summary.DetailExpireInDay += detailExpireInDay
			if earliestItemExpire > 0 && (summary.EarliestExpireSec == 0 || earliestItemExpire < summary.EarliestExpireSec) {
				summary.EarliestExpireSec = earliestItemExpire
			}
		}
	}

	sort.Slice(summary.NormalBatches, func(i, j int) bool {
		ea := summary.NormalBatches[i].ExpireSec
		eb := summary.NormalBatches[j].ExpireSec
		if ea == 0 {
			ea = 1<<62 - 1
		}
		if eb == 0 {
			eb = 1<<62 - 1
		}
		return ea < eb
	})
	summary.NormalCount = int(normalTotal)
	summary.SuperTotalCount = int(superTotal)
	summary.Expiring24h = summary.CountNormalExpiringBy(summary.ServerTimeSec + 24*3600)
	return summary, nil
}

// SendFreeHuliang sends FREE backpack 虎粮 ONLY via wupui.sendGift.
// Hard-locked to SendGiftPayPloy{iPayPloy=2, lPayCount=0, lNewCoinDeduction=0} so coins/beans are NEVER spent.
func (a *API) SendFreeHuliang(ctx context.Context, creds Credentials, itemType int, targetPID int64, count int) (bool, string) {
	if count <= 0 || targetPID <= 0 || itemType <= 0 {
		return false, "参数无效"
	}

	// Hard-locked PACKAGE_PAY_PLOY: iPayPloy=2, lPayCount=0, lNewCoinDeduction=0
	payPloy := jce.NewWriter()
	payPloy.WriteInt(0, 2)  // iPayPloy = 2 (package only)
	payPloy.WriteLong(1, 0) // lPayCount = 0 (NEVER pay beans)
	payPloy.WriteLong(2, 0) // lNewCoinDeduction = 0 (NEVER deduct coins)

	req := jce.NewWriter()
	req.WriteStructRaw(0, creds.EncodeUserId())
	req.WriteLong(1, targetPID)
	req.WriteLong(2, targetPID)
	req.WriteInt(3, int32(itemType))
	req.WriteInt(4, int32(count))
	req.WriteString(5, generateLocalPayID())
	req.WriteString(6, "gift_panel_package_vertical")
	req.WriteStructRaw(7, payPloy.Bytes())
	req.WriteString(10, "")

	rsp, wupRet, err := a.Wup.Call(ctx, "wupui", "sendGift", "tReq", req.Bytes(), creds.Cookie)
	if err != nil {
		return false, err.Error()
	}
	payCode := rsp.GetInt(1, -1)
	var toast string
	if ti := rsp.GetStruct(3); ti != nil {
		toast = ti.GetString(1, "")
	}
	if payCode == 0 && wupRet == 0 {
		if toast == "" {
			toast = "赠送成功"
		}
		return true, toast
	}
	if toast != "" {
		return false, fmt.Sprintf("code=%d, %s", payCode, toast)
	}
	return false, fmt.Sprintf("code=%d (wupRet=%d)", payCode, wupRet)
}

// QueryBagAdPlacement invokes wupui.getPropsListPlacement (iTabId=-10000) to fetch
// the gift-bag Package_HuLiang_Ad placement and its HuLiangADReplacementInfo status.
func (a *API) QueryBagAdPlacement(ctx context.Context, creds Credentials, pid int64) (*BagAdPlacement, error) {
	adCreds := creds
	if adCreds.HuYaUA == "" || strings.HasPrefix(adCreds.HuYaUA, "webh5") {
		adCreds.HuYaUA = DefaultHuYaUA
	}
	req := jce.NewWriter()
	req.WriteStructRaw(0, adCreds.EncodeUserId())
	req.WriteLong(1, pid)
	req.WriteInt(2, BagPropsListTabID)

	rsp, _, err := a.Wup.Call(ctx, "wupui", "getPropsListPlacement", "tReq", req.Bytes(), adCreds.Cookie)
	if err != nil {
		return nil, err
	}
	for _, elem := range rsp.GetList(0) {
		b, ok := elem.([]byte)
		if !ok {
			continue
		}
		pl := jce.ParseFields(b)
		id := pl.GetString(0, "")
		bizType := pl.GetInt(7, 0)
		if id != BagHuliangPlacementID && bizType != BagHuliangPlacementBizType {
			continue
		}
		out := &BagAdPlacement{
			ID:      id,
			Name:    pl.GetString(1, ""),
			Button:  pl.GetString(3, ""),
			BizType: bizType,
		}
		if rawV := pl.GetBytes(8); len(rawV) > 0 {
			info := jce.ParseFields(rawV)
			out.Count = info.GetInt(0, 0)
			out.RemainSec = info.GetLong(1, 0)
			out.Status = info.GetInt(2, 0)
			out.FinishText = info.GetString(3, "")
			out.ServerContext = info.GetString(4, "")
			out.Text = info.GetString(5, "")
		}
		return out, nil
	}
	return nil, nil
}

// ActivateBagAdPlacement invokes wupui.notifyPropsListPlacement to activate the
// daily gift-bag Huliang ad placement (transitions server-side iStatus 0 -> 1).
func (a *API) ActivateBagAdPlacement(ctx context.Context, creds Credentials, pid int64, bizType int32, placementID string) error {
	adCreds := creds
	if adCreds.HuYaUA == "" || strings.HasPrefix(adCreds.HuYaUA, "webh5") {
		adCreds.HuYaUA = DefaultHuYaUA
	}
	if bizType == 0 {
		bizType = BagHuliangPlacementBizType
	}
	if placementID == "" {
		placementID = BagHuliangPlacementID
	}
	req := jce.NewWriter()
	req.WriteStructRaw(0, adCreds.EncodeUserId())
	req.WriteLong(1, pid)
	req.WriteInt(2, bizType)
	req.WriteString(3, placementID)

	_, wupRet, err := a.Wup.Call(ctx, "wupui", "notifyPropsListPlacement", "tReq", req.Bytes(), adCreds.Cookie)
	if err != nil {
		return err
	}
	if wupRet != 0 {
		return fmt.Errorf("notifyPropsListPlacement wupRet=%d", wupRet)
	}
	return nil
}

// EnsureBagAdPlacementActive queries the gift-bag Huliang ad placement and, if it is
// in unactivated state (Status <= 0), calls notifyPropsListPlacement so subsequent
// wupui.getCommAdPannel (iAppId=8) calls succeed instead of returning wupRet=914.
func (a *API) EnsureBagAdPlacementActive(ctx context.Context, creds Credentials, pid int64) (*BagAdPlacement, error) {
	pl, err := a.QueryBagAdPlacement(ctx, creds, pid)
	if err != nil {
		// Best-effort fallback activation if placement query failed transiently.
		_ = a.ActivateBagAdPlacement(ctx, creds, pid, BagHuliangPlacementBizType, BagHuliangPlacementID)
		return nil, err
	}
	if pl == nil {
		return nil, nil
	}
	if pl.Status <= 0 {
		if actErr := a.ActivateBagAdPlacement(ctx, creds, pid, pl.BizType, pl.ID); actErr != nil {
			return pl, actErr
		}
		pl.Status = 1
	}
	return pl, nil
}

// GetBagAdPanel invokes wupui.getCommAdPannel to check reward-ad Huliang availability.
func (a *API) GetBagAdPanel(ctx context.Context, creds Credentials, reqID string) (*AdPanelResult, error) {
	// Use Android UA for mobile reward-ad panel endpoint
	adCreds := creds
	if adCreds.HuYaUA == "" || strings.HasPrefix(adCreds.HuYaUA, "webh5") {
		adCreds.HuYaUA = DefaultHuYaUA
	}
	req := jce.NewWriter()
	req.WriteStructRaw(0, adCreds.EncodeUserId())
	req.WriteInt(1, BagHuliangAppID)
	req.WriteStringMap(2, map[string]string{})
	req.WriteInt(3, 0)
	req.WriteLong(4, 0)
	req.WriteInt(5, BagSource)
	req.WriteString(6, reqID)
	req.WriteStringMap(7, map[string]string{})
	req.WriteStringMap(8, map[string]string{"rso": "package"})

	rsp, wupRet, err := a.Wup.Call(ctx, "wupui", "getCommAdPannel", "tReq", req.Bytes(), adCreds.Cookie)
	if err != nil {
		return nil, err
	}
	res := &AdPanelResult{
		WupRet:        wupRet,
		Toast:         rsp.GetString(3, ""),
		ServerContext: rsp.GetString(4, ""),
	}
	if task := rsp.GetStruct(2); task != nil {
		res.AdTaskID = task.GetString(0, "")
		res.AdType = task.GetInt(1, 0)
		res.SlotAdRaw = task.GetBytes(3)
		res.NeedWatchTime = task.GetInt(4, 15)
		res.Ext = task.GetStringMap(5)
		res.DisplayCount = task.GetInt(8, 0)
		res.MaxRewardCount = task.GetInt(9, 0)
	}
	return res, nil
}

// ReportAdClick invokes wupui.onClickCommRewardAdTaskEvent.
func (a *API) ReportAdClick(ctx context.Context, creds Credentials, reqID string, ext map[string]string, slotAdRaw []byte) {
	adCreds := creds
	if adCreds.HuYaUA == "" || strings.HasPrefix(adCreds.HuYaUA, "webh5") {
		adCreds.HuYaUA = DefaultHuYaUA
	}
	req := jce.NewWriter()
	req.WriteStructRaw(0, adCreds.EncodeUserId())
	if ext == nil {
		ext = map[string]string{}
	}
	req.WriteStringMap(1, ext)
	req.WriteString(2, reqID)
	if len(slotAdRaw) > 0 {
		req.WriteStructRaw(3, slotAdRaw)
	}
	_, _, _ = a.Wup.Call(ctx, "wupui", "onClickCommRewardAdTaskEvent", "tReq", req.Bytes(), adCreds.Cookie)
}

// AwardBagAdTask invokes wupui.awardCommAdTask.
func (a *API) AwardBagAdTask(ctx context.Context, creds Credentials, adTaskID, reqID string, ext map[string]string, watchSec int32) (bool, string) {
	adCreds := creds
	if adCreds.HuYaUA == "" || strings.HasPrefix(adCreds.HuYaUA, "webh5") {
		adCreds.HuYaUA = DefaultHuYaUA
	}
	req := jce.NewWriter()
	req.WriteStructRaw(0, adCreds.EncodeUserId())
	req.WriteString(1, adTaskID)
	req.WriteStructRaw(2, nil) // empty AdSdkInfoReq
	if ext == nil {
		ext = map[string]string{}
	}
	req.WriteStringMap(3, ext)
	if watchSec <= 0 {
		watchSec = 15
	}
	req.WriteInt(4, watchSec)
	req.WriteString(5, reqID)

	rsp, wupRet, err := a.Wup.Call(ctx, "wupui", "awardCommAdTask", "tReq", req.Bytes(), adCreds.Cookie)
	if err != nil {
		return false, err.Error()
	}
	toast := rsp.GetString(0, "")
	if wupRet == 0 {
		return true, toast
	}
	return false, fmt.Sprintf("wupRet=%d %s", wupRet, toast)
}

func normalizeUint32Sec(raw int64) int64 {
	if raw < 0 {
		return raw & 0xFFFFFFFF
	}
	return raw
}

// generateLocalPayID matches Huya SendGiftService.generateLocalPayId():
// yyyyMMddHHmmss + "002010" + 12 random uppercase alphanumeric characters.
func generateLocalPayID() string {
	const charset = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
	var sb strings.Builder
	sb.WriteString(time.Now().Format("20060102150405"))
	sb.WriteString("002010")
	max := big.NewInt(int64(len(charset)))
	for i := 0; i < 12; i++ {
		n, err := rand.Int(rand.Reader, max)
		if err != nil {
			sb.WriteByte('A')
		} else {
			sb.WriteByte(charset[n.Int64()])
		}
	}
	return sb.String()
}
