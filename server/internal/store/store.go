package store

import (
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"sync"
	"time"

	"github.com/UCKETX/HuyaPure/server/internal/huya"
)

const (
	DistributeEven           = 0
	DistributeFillQuota      = 1
	DistributeNearestUpgrade = 2
	DistributeCustomRatio    = 3
)

type SendTarget struct {
	PID    int64  `json:"pid"`
	Name   string `json:"name"`
	Weight int    `json:"weight"`
}

type AccountConfig struct {
	AutoClaimEnabled    bool         `json:"autoClaimEnabled"`
	AutoClaimHour       int          `json:"autoClaimHour"`
	AutoClaimMinute     int          `json:"autoClaimMinute"`
	AutoAdHuliang       bool         `json:"autoAdHuliang"`
	AutoFansCheckin     bool         `json:"autoFansCheckin"`
	AutoClaimWelfare    bool         `json:"autoClaimWelfare"`
	AutoSendHuliang     bool         `json:"autoSendHuliang"`
	SendSuperHuliang    bool         `json:"sendSuperHuliang"`
	SmartSuperFanDay    bool         `json:"smartSuperFanDay"`
	SendTargets         []SendTarget `json:"sendTargets"`
	SendDistributeMode  int          `json:"sendDistributeMode"`
	SendMaxCount        int          `json:"sendMaxCount"` // 0 = unlimited
	NotifyOnRun         bool         `json:"notifyOnRun"`
	NotifyOnSuperFanDay bool         `json:"notifyOnSuperFanDay"`
}

func DefaultAccountConfig() AccountConfig {
	return AccountConfig{
		AutoClaimEnabled:    true,
		AutoClaimHour:       1,
		AutoClaimMinute:     0,
		AutoAdHuliang:       true,
		AutoFansCheckin:     true,
		AutoClaimWelfare:    true,
		AutoSendHuliang:     false,
		SendSuperHuliang:    true,
		SmartSuperFanDay:    true,
		SendTargets:         []SendTarget{},
		SendDistributeMode:  DistributeFillQuota,
		SendMaxCount:        0,
		NotifyOnRun:         true,
		NotifyOnSuperFanDay: true,
	}
}

type RunRecord struct {
	Time       int64  `json:"time"` // unix ms
	Date       string `json:"date"`
	Trigger    string `json:"trigger"` // manual | schedule | retry | rescue
	Success    bool   `json:"success"`
	Message    string `json:"message"`
	AdClaimed  int    `json:"adClaimed"`
	CheckinOK  int    `json:"checkinOk"`
	CheckinAll int    `json:"checkinAll"`
	SentNormal int    `json:"sentNormal"`
	SentSuper  int    `json:"sentSuper"`
}

type AccountState struct {
	Running             bool              `json:"running"`
	LastRunTime         int64             `json:"lastRunTime"` // unix ms
	LastRunDate         string            `json:"lastRunDate"`
	LastRunSuccess      bool              `json:"lastRunSuccess"`
	LastRunMessage      string            `json:"lastRunMessage"`
	TodayClaimedDay     string            `json:"todayClaimedDay"`
	TodayClaimedCount   int               `json:"todayClaimedCount"`
	TodayCheckinDay     string            `json:"todayCheckinDay"`
	TodayCheckinCount   int               `json:"todayCheckinCount"`
	TodayCheckinTotal   int               `json:"todayCheckinTotal"`
	TodaySentDay        string            `json:"todaySentDay"`
	TodaySentCount      int               `json:"todaySentCount"`
	FreeHuliangCount    int               `json:"freeHuliangCount"`
	FreeExpiring24h     int               `json:"freeExpiring24h"`
	FreeEarliestExpire  int64             `json:"freeEarliestExpire"`
	SuperHuliangCount   int               `json:"superHuliangCount"`
	SuperHuliangName    string            `json:"superHuliangName"`
	SFDayActiveSummary  string            `json:"sfDayActiveSummary"`
	NextScheduleTime    int64             `json:"nextScheduleTime"` // unix ms
	NextRetryTime       int64             `json:"nextRetryTime"`    // unix ms
	NextRescueTime      int64             `json:"nextRescueTime"`   // unix ms
	BadgesUpdateTime    int64             `json:"badgesUpdateTime"` // unix ms
	Badges              []*huya.FanBadge  `json:"badges"`
	History             []RunRecord       `json:"history"`
}

type Account struct {
	ID          string           `json:"id"`
	Label       string           `json:"label"`
	NickName    string           `json:"nickName,omitempty"`
	Avatar      string           `json:"avatar,omitempty"`
	Credentials huya.Credentials `json:"credentials"`
	Config      AccountConfig    `json:"config"`
	State       AccountState     `json:"state"`
	CreatedAt   int64            `json:"createdAt"`
}

// AccountPublicView strips sensitive raw cookies/tokens for API responses.
type AccountPublicView struct {
	ID        string        `json:"id"`
	Label     string        `json:"label"`
	NickName  string        `json:"nickName,omitempty"`
	Avatar    string        `json:"avatar,omitempty"`
	UID       int64         `json:"uid"`
	LoggedIn  bool          `json:"loggedIn"`
	AuthMode  string        `json:"authMode"` // "mobile" (5008) | "web" (5002)
	Config    AccountConfig `json:"config"`
	State     AccountState  `json:"state"`
	CreatedAt int64         `json:"createdAt"`
}

func (a *Account) PublicView() AccountPublicView {
	mode := "web"
	if a.Credentials.IsMobile() {
		mode = "mobile"
	}
	return AccountPublicView{
		ID:        a.ID,
		Label:     a.Label,
		NickName:  a.NickName,
		Avatar:    a.Avatar,
		UID:       a.Credentials.UID,
		LoggedIn:  a.Credentials.IsValid(),
		AuthMode:  mode,
		Config:    a.Config,
		State:     a.State,
		CreatedAt: a.CreatedAt,
	}
}

// IsDefaultAccountLabel reports whether label looks like an auto-generated passport/UID fallback
// rather than a user-customized remark (e.g. "", "虎牙 12345", "12345", "hy_263328861").
func IsDefaultAccountLabel(label string, uid int64) bool {
	s := strings.TrimSpace(label)
	if s == "" {
		return true
	}
	uidStr := strconv.FormatInt(uid, 10)
	if s == uidStr || s == "虎牙 "+uidStr || s == "UID:"+uidStr {
		return true
	}
	lower := strings.ToLower(s)
	if strings.HasPrefix(lower, "hy_") && len(lower) > 3 {
		allDigits := true
		for i := 3; i < len(lower); i++ {
			if lower[i] < '0' || lower[i] > '9' {
				allDigits = false
				break
			}
		}
		if allDigits {
			return true
		}
	}
	return false
}

// ApplyHuyaProfile stores the account's live Huya nickname and avatar, and updates Label
// automatically when the user has not set a custom remark (or when Label tracked the previous Huya nickname).
func ApplyHuyaProfile(a *Account, nick, avatar string) {
	if a == nil {
		return
	}
	nick = strings.TrimSpace(nick)
	avatar = strings.TrimSpace(avatar)
	prevNick := strings.TrimSpace(a.NickName)
	if avatar != "" {
		a.Avatar = avatar
	}
	if nick != "" {
		if IsDefaultAccountLabel(a.Label, a.Credentials.UID) || (prevNick != "" && a.Label == prevNick) {
			a.Label = nick
		}
		a.NickName = nick
	}
}

type NotifyConfig struct {
	BarkURL          string `json:"barkUrl"`          // e.g. https://api.day.app/YOUR_KEY
	TelegramBotToken string `json:"telegramBotToken"`
	TelegramChatID   string `json:"telegramChatId"`
	ServerChanKey    string `json:"serverChanKey"`    // SCT...
	DingTalkWebhook  string `json:"dingTalkWebhook"`
	FeishuWebhook    string `json:"feishuWebhook"`
	CustomWebhookURL string `json:"customWebhookUrl"`
}

type PersistData struct {
	Accounts []*Account   `json:"accounts"`
	Notify   NotifyConfig `json:"notify"`
}

// Store provides thread-safe atomic JSON file storage.
type Store struct {
	mu       sync.RWMutex
	filePath string
	data     PersistData
}

func Open(dataDir string) (*Store, error) {
	if err := os.MkdirAll(dataDir, 0o755); err != nil {
		return nil, err
	}
	s := &Store{
		filePath: filepath.Join(dataDir, "state.json"),
		data: PersistData{
			Accounts: []*Account{},
		},
	}
	if raw, err := os.ReadFile(s.filePath); err == nil && len(raw) > 0 {
		if err := json.Unmarshal(raw, &s.data); err != nil {
			return nil, fmt.Errorf("corrupt state.json: %w", err)
		}
		// Reset transient running flags after restart.
		for _, acc := range s.data.Accounts {
			acc.State.Running = false
		}
	}
	return s, nil
}

func (s *Store) saveLocked() error {
	raw, err := json.MarshalIndent(s.data, "", "  ")
	if err != nil {
		return err
	}
	tmp := s.filePath + ".tmp"
	if err := os.WriteFile(tmp, raw, 0o600); err != nil {
		return err
	}
	return os.Rename(tmp, s.filePath)
}

func (s *Store) ListPublic() []AccountPublicView {
	s.mu.RLock()
	defer s.mu.RUnlock()
	out := make([]AccountPublicView, 0, len(s.data.Accounts))
	for _, a := range s.data.Accounts {
		out = append(out, a.PublicView())
	}
	return out
}

func (s *Store) ListIDs() []string {
	s.mu.RLock()
	defer s.mu.RUnlock()
	out := make([]string, 0, len(s.data.Accounts))
	for _, a := range s.data.Accounts {
		out = append(out, a.ID)
	}
	return out
}

func (s *Store) GetClone(id string) (*Account, bool) {
	s.mu.RLock()
	defer s.mu.RUnlock()
	for _, a := range s.data.Accounts {
		if a.ID == id {
			cp := cloneAccount(a)
			return cp, true
		}
	}
	return nil, false
}

// UpsertResult describes the outcome of UpsertByUID.
type UpsertResult struct {
	Account    *Account
	Created    bool // true if a brand-new account was appended; false if an existing UID matched
	KeptMobile bool // true if an existing 5008 mobile account was protected from being downgraded by a Web login
}

func (s *Store) UpsertByUID(label string, creds huya.Credentials) (UpsertResult, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	for _, a := range s.data.Accounts {
		if a.Credentials.UID == creds.UID {
			keptMobile := false
			if a.Credentials.IsMobile() && !creds.IsMobile() {
				// Never silently downgrade a working Android 5008 account to a Web Cookie session.
				keptMobile = true
			} else {
				a.Credentials = creds
			}
			if label != "" && !keptMobile {
				a.Label = label
			}
			if err := s.saveLocked(); err != nil {
				return UpsertResult{}, err
			}
			return UpsertResult{
				Account:    cloneAccount(a),
				Created:    false,
				KeptMobile: keptMobile,
			}, nil
		}
	}
	if label == "" {
		label = fmt.Sprintf("虎牙 %d", creds.UID)
	}
	acc := &Account{
		ID:          fmt.Sprintf("acc_%d", creds.UID),
		Label:       label,
		Credentials: creds,
		Config:      DefaultAccountConfig(),
		State: AccountState{
			FreeHuliangCount:  -1,
			SuperHuliangCount: -1,
			Badges:            []*huya.FanBadge{},
			History:           []RunRecord{},
		},
		CreatedAt: time.Now().UnixMilli(),
	}
	s.data.Accounts = append(s.data.Accounts, acc)
	if err := s.saveLocked(); err != nil {
		return UpsertResult{}, err
	}
	return UpsertResult{
		Account: cloneAccount(acc),
		Created: true,
	}, nil
}

func (s *Store) UpdateAccount(id string, fn func(a *Account) error) (*Account, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	for _, a := range s.data.Accounts {
		if a.ID == id {
			if err := fn(a); err != nil {
				return nil, err
			}
			if err := s.saveLocked(); err != nil {
				return nil, err
			}
			return cloneAccount(a), nil
		}
	}
	return nil, fmt.Errorf("account not found: %s", id)
}

func (s *Store) DeleteAccount(id string) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	for i, a := range s.data.Accounts {
		if a.ID == id {
			s.data.Accounts = append(s.data.Accounts[:i], s.data.Accounts[i+1:]...)
			return s.saveLocked()
		}
	}
	return fmt.Errorf("account not found: %s", id)
}

func (s *Store) GetNotify() NotifyConfig {
	s.mu.RLock()
	defer s.mu.RUnlock()
	return s.data.Notify
}

func (s *Store) SetNotify(cfg NotifyConfig) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.data.Notify = cfg
	return s.saveLocked()
}

func cloneAccount(a *Account) *Account {
	raw, _ := json.Marshal(a)
	var cp Account
	_ = json.Unmarshal(raw, &cp)
	return &cp
}
