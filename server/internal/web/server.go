package web

import (
	"context"
	"crypto/sha256"
	"crypto/subtle"
	"embed"
	"encoding/hex"
	"encoding/json"
	"io/fs"
	"log"
	"net/http"
	"strings"
	"sync"
	"time"

	"github.com/UCKETX/HuyaPure/server/internal/engine"
	"github.com/UCKETX/HuyaPure/server/internal/huya"
	"github.com/UCKETX/HuyaPure/server/internal/notify"
	"github.com/UCKETX/HuyaPure/server/internal/scheduler"
	"github.com/UCKETX/HuyaPure/server/internal/store"
)

type LocalNotification struct {
	AccountID string `json:"accountId"`
	Title     string `json:"title"`
	Body      string `json:"body"`
	Time      int64  `json:"time"`
}

//go:embed static/*
var staticFS embed.FS

type Deps struct {
	Version    string
	Password   string
	Store      *store.Store
	QRManager  *huya.QRLoginManager
	ThirdLogin *huya.ThirdLoginManager
	MobileAuth *huya.MobileAuthClient
	Runner     *engine.Runner
	Scheduler  *scheduler.Scheduler
	Notifier   *notify.Notifier
}

type Server struct {
	deps      Deps
	authToken string

	notifMu sync.Mutex
	notifs  []LocalNotification
}

func NewServer(deps Deps) *Server {
	var tok string
	if deps.Password != "" {
		h := sha256.Sum256([]byte("huban-server-auth:" + deps.Password))
		tok = hex.EncodeToString(h[:])
	}
	s := &Server{deps: deps, authToken: tok}
	if deps.Runner != nil {
		prev := deps.Runner.OnNotify
		deps.Runner.OnNotify = func(accountID, title, body string) {
			s.pushLocalNotification(accountID, title, body)
			if prev != nil {
				prev(accountID, title, body)
			}
		}
	}
	return s
}

func (s *Server) pushLocalNotification(accountID, title, body string) {
	s.notifMu.Lock()
	defer s.notifMu.Unlock()
	s.notifs = append(s.notifs, LocalNotification{
		AccountID: accountID,
		Title:     title,
		Body:      body,
		Time:      time.Now().UnixMilli(),
	})
	if len(s.notifs) > 30 {
		s.notifs = s.notifs[len(s.notifs)-30:]
	}
}

func (s *Server) Handler() http.Handler {
	mux := http.NewServeMux()

	// Auth endpoints (always accessible)
	mux.HandleFunc("GET /api/health", s.handleHealth)
	mux.HandleFunc("POST /api/auth/login", s.handleAuthLogin)

	// Protected API endpoints
	mux.HandleFunc("GET /api/status", s.requireAuth(s.handleStatus))
	mux.HandleFunc("POST /api/qr/start", s.requireAuth(s.handleQRStart))
	mux.HandleFunc("GET /api/qr/poll", s.requireAuth(s.handleQRPoll))
	mux.HandleFunc("POST /api/third/start", s.requireAuth(s.handleThirdStart))
	mux.HandleFunc("GET /api/third/poll", s.requireAuth(s.handleThirdPoll))
	mux.HandleFunc("POST /api/third/auth/code", s.requireAuth(s.handleThirdAuthCode))
	mux.HandleFunc("POST /api/third/auth/verify", s.requireAuth(s.handleThirdAuthVerify))
	mux.HandleFunc("POST /api/mobile/sms/send", s.requireAuth(s.handleMobileSendSMS))
	mux.HandleFunc("POST /api/mobile/sms/login", s.requireAuth(s.handleMobileSMSLogin))
	mux.HandleFunc("POST /api/accounts/cookie", s.requireAuth(s.handleAddCookie))
	mux.HandleFunc("PUT /api/accounts/{id}/config", s.requireAuth(s.handleUpdateConfig))
	mux.HandleFunc("DELETE /api/accounts/{id}", s.requireAuth(s.handleDeleteAccount))
	mux.HandleFunc("POST /api/accounts/{id}/run", s.requireAuth(s.handleRunAccount))
	mux.HandleFunc("POST /api/accounts/run-all", s.requireAuth(s.handleRunAllAccounts))
	mux.HandleFunc("POST /api/accounts/{id}/refresh", s.requireAuth(s.handleRefreshAccount))
	mux.HandleFunc("POST /api/accounts/{id}/send", s.requireAuth(s.handleManualSend))
	mux.HandleFunc("PUT /api/notify", s.requireAuth(s.handleSaveNotify))
	mux.HandleFunc("POST /api/notify/test", s.requireAuth(s.handleTestNotify))
	mux.HandleFunc("GET /api/notifications/drain", s.requireAuth(s.handleDrainNotifications))

	// Embedded SPA static files
	sub, err := fs.Sub(staticFS, "static")
	if err != nil {
		log.Fatalf("embed staticFS error: %v", err)
	}
	fileServer := http.FileServer(http.FS(sub))
	mux.Handle("GET /", fileServer)

	return mux
}

func (s *Server) requireAuth(next http.HandlerFunc) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		if s.authToken == "" {
			next(w, r)
			return
		}
		if ck, err := r.Cookie("huban_auth"); err == nil && subtle.ConstantTimeCompare([]byte(ck.Value), []byte(s.authToken)) == 1 {
			next(w, r)
			return
		}
		hdr := strings.TrimSpace(r.Header.Get("X-Huban-Password"))
		if hdr != "" && subtle.ConstantTimeCompare([]byte(hdr), []byte(s.deps.Password)) == 1 {
			next(w, r)
			return
		}
		writeJSON(w, http.StatusUnauthorized, map[string]any{
			"ok":           false,
			"authRequired": true,
			"error":        "需要输入访问密码",
		})
	}
}

func (s *Server) handleHealth(w http.ResponseWriter, _ *http.Request) {
	writeJSON(w, http.StatusOK, map[string]any{
		"ok":           true,
		"version":      s.deps.Version,
		"authRequired": s.authToken != "",
	})
}

func (s *Server) handleAuthLogin(w http.ResponseWriter, r *http.Request) {
	var body struct {
		Password string `json:"password"`
	}
	if err := json.NewDecoder(r.Body).Decode(&body); err != nil {
		writeErr(w, http.StatusBadRequest, "请求格式错误")
		return
	}
	if s.deps.Password != "" && subtle.ConstantTimeCompare([]byte(body.Password), []byte(s.deps.Password)) != 1 {
		writeErr(w, http.StatusUnauthorized, "访问密码错误")
		return
	}
	http.SetCookie(w, &http.Cookie{
		Name:     "huban_auth",
		Value:    s.authToken,
		Path:     "/",
		HttpOnly: true,
		SameSite: http.SameSiteLaxMode,
		MaxAge:   30 * 24 * 3600,
	})
	writeJSON(w, http.StatusOK, map[string]any{"ok": true})
}

func (s *Server) handleStatus(w http.ResponseWriter, _ *http.Request) {
	writeJSON(w, http.StatusOK, map[string]any{
		"ok":         true,
		"version":    s.deps.Version,
		"serverTime": time.Now().UnixMilli(),
		"accounts":   s.deps.Store.ListPublic(),
		"notify":     s.deps.Store.GetNotify(),
	})
}

func (s *Server) handleQRStart(w http.ResponseWriter, r *http.Request) {
	sess, err := s.deps.QRManager.StartSession(r.Context())
	if err != nil {
		writeErr(w, http.StatusBadGateway, "获取登录二维码失败："+err.Error())
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{
		"ok":        true,
		"sessionId": sess.ID,
		"qrId":      sess.QRID,
		"imageData": sess.ImageData,
	})
}

func (s *Server) resolveHuyaProfile(ctx context.Context, creds huya.Credentials) *huya.UserProfile {
	if s.deps.Runner == nil || s.deps.Runner.API == nil || creds.UID <= 0 {
		return nil
	}
	pCtx, cancel := context.WithTimeout(ctx, 5*time.Second)
	defer cancel()
	prof, err := s.deps.Runner.API.QueryUserProfile(pCtx, creds)
	if err != nil {
		return nil
	}
	return prof
}

func (s *Server) handleQRPoll(w http.ResponseWriter, r *http.Request) {
	sessionID := strings.TrimSpace(r.URL.Query().Get("sessionId"))
	if sessionID == "" {
		writeErr(w, http.StatusBadRequest, "缺少 sessionId")
		return
	}
	res, err := s.deps.QRManager.Poll(r.Context(), sessionID)
	if err != nil {
		writeErr(w, http.StatusBadGateway, "查询扫码状态失败："+err.Error())
		return
	}
	if res.Status == "confirmed" && res.Credentials != nil && res.Credentials.IsValid() {
		prof := s.resolveHuyaProfile(r.Context(), *res.Credentials)
		label := strings.TrimSpace(res.NickName)
		if prof != nil && prof.NickName != "" {
			label = prof.NickName
		}
		up, err := s.deps.Store.UpsertByUID(label, *res.Credentials)
		if err != nil {
			writeErr(w, http.StatusInternalServerError, "保存账号失败："+err.Error())
			return
		}
		acc := up.Account
		if prof != nil && (prof.NickName != "" || prof.AvatarURL != "") {
			if updated, uErr := s.deps.Store.UpdateAccount(acc.ID, func(a *store.Account) error {
				store.ApplyHuyaProfile(a, prof.NickName, prof.AvatarURL)
				return nil
			}); uErr == nil && updated != nil {
				acc = updated
			}
		}
		s.deps.Scheduler.RescheduleAccount(acc.ID)
		// Trigger initial badge & bag sync in background so Web UI populates immediately.
		go func(id string) {
			ctx, cancel := context.WithTimeout(context.Background(), 45*time.Second)
			defer cancel()
			if _, syncErr := s.deps.Runner.RefreshBadgesAndBag(ctx, id); syncErr != nil {
				log.Printf("[QR] initial sync for %s error: %v", id, syncErr)
			} else {
				s.deps.Scheduler.RescheduleAccount(id)
			}
		}(acc.ID)
		msg := res.Message
		if up.KeptMobile {
			msg = "该账号已使用长期登录，已保留原登录状态未覆盖；如需添加新号请先在虎牙 App 切换另一个账号后再扫码"
		} else if !up.Created {
			msg = "扫码成功，已更新该账号登录状态"
		} else {
			msg = "新账号已添加，正在同步数据..."
		}
		writeJSON(w, http.StatusOK, map[string]any{
			"ok":         true,
			"status":     res.Status,
			"stage":      res.Stage,
			"message":    msg,
			"created":    up.Created,
			"keptMobile": up.KeptMobile,
			"account":    acc.PublicView(),
		})
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{
		"ok":      true,
		"status":  res.Status,
		"stage":   res.Stage,
		"message": res.Message,
	})
}

func (s *Server) handleThirdStart(w http.ResponseWriter, r *http.Request) {
	if s.deps.ThirdLogin == nil {
		writeErr(w, http.StatusInternalServerError, "第三方登录管理器未初始化")
		return
	}
	var body struct {
		Provider string `json:"provider"`
	}
	if err := json.NewDecoder(r.Body).Decode(&body); err != nil {
		writeErr(w, http.StatusBadRequest, "请求格式错误")
		return
	}
	sess, err := s.deps.ThirdLogin.StartSession(r.Context(), body.Provider)
	if err != nil {
		writeErr(w, http.StatusBadGateway, "获取登录二维码失败："+err.Error())
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{
		"ok":        true,
		"sessionId": sess.ID,
		"provider":  sess.Provider,
		"imageData": sess.ImageData,
	})
}

func (s *Server) handleThirdPoll(w http.ResponseWriter, r *http.Request) {
	if s.deps.ThirdLogin == nil {
		writeErr(w, http.StatusInternalServerError, "第三方登录管理器未初始化")
		return
	}
	sessionID := strings.TrimSpace(r.URL.Query().Get("sessionId"))
	if sessionID == "" {
		writeErr(w, http.StatusBadRequest, "缺少 sessionId")
		return
	}
	res, err := s.deps.ThirdLogin.Poll(r.Context(), sessionID)
	if err != nil {
		writeErr(w, http.StatusBadGateway, "查询登录状态失败："+err.Error())
		return
	}
	if res.Status == "confirmed" && res.Credentials != nil && res.Credentials.IsValid() {
		s.respondThirdConfirmed(w, r, res)
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{
		"ok":      true,
		"status":  res.Status,
		"stage":   res.Stage,
		"message": res.Message,
		"auth":    res.Auth,
	})
}

func (s *Server) respondThirdConfirmed(w http.ResponseWriter, r *http.Request, res *huya.QRPollResult) {
	prof := s.resolveHuyaProfile(r.Context(), *res.Credentials)
	label := strings.TrimSpace(res.NickName)
	if prof != nil && prof.NickName != "" {
		label = prof.NickName
	}
	up, err := s.deps.Store.UpsertByUID(label, *res.Credentials)
	if err != nil {
		writeErr(w, http.StatusInternalServerError, "保存账号失败："+err.Error())
		return
	}
	acc := up.Account
	if prof != nil && (prof.NickName != "" || prof.AvatarURL != "") {
		if updated, uErr := s.deps.Store.UpdateAccount(acc.ID, func(a *store.Account) error {
			store.ApplyHuyaProfile(a, prof.NickName, prof.AvatarURL)
			return nil
		}); uErr == nil && updated != nil {
			acc = updated
		}
	}
	s.deps.Scheduler.RescheduleAccount(acc.ID)
	go func(id string) {
		ctx, cancel := context.WithTimeout(context.Background(), 45*time.Second)
		defer cancel()
		if _, syncErr := s.deps.Runner.RefreshBadgesAndBag(ctx, id); syncErr != nil {
			log.Printf("[ThirdLogin] initial sync for %s error: %v", id, syncErr)
		} else {
			s.deps.Scheduler.RescheduleAccount(id)
		}
	}(acc.ID)

	msg := res.Message
	if up.KeptMobile {
		msg = "该账号已使用长期登录，已保留原登录状态未覆盖；如需添加新号请切换另一个微信/QQ"
	} else if !up.Created {
		msg = res.Message + "（已更新该账号登录状态）"
	}
	writeJSON(w, http.StatusOK, map[string]any{
		"ok":         true,
		"status":     res.Status,
		"stage":      res.Stage,
		"message":    msg,
		"created":    up.Created,
		"keptMobile": up.KeptMobile,
		"account":    acc.PublicView(),
	})
}

func (s *Server) handleThirdAuthCode(w http.ResponseWriter, r *http.Request) {
	if s.deps.ThirdLogin == nil {
		writeErr(w, http.StatusInternalServerError, "第三方登录管理器未初始化")
		return
	}
	var body struct {
		SessionID string `json:"sessionId"`
		Strategy  int    `json:"strategy"`
	}
	if err := json.NewDecoder(r.Body).Decode(&body); err != nil {
		writeErr(w, http.StatusBadRequest, "请求格式错误")
		return
	}
	out, err := s.deps.ThirdLogin.SendAuthCode(r.Context(), strings.TrimSpace(body.SessionID), body.Strategy)
	if err != nil {
		writeErr(w, http.StatusBadGateway, err.Error())
		return
	}
	out["ok"] = true
	writeJSON(w, http.StatusOK, out)
}

func (s *Server) handleThirdAuthVerify(w http.ResponseWriter, r *http.Request) {
	if s.deps.ThirdLogin == nil {
		writeErr(w, http.StatusInternalServerError, "第三方登录管理器未初始化")
		return
	}
	var body struct {
		SessionID string `json:"sessionId"`
		Strategy  int    `json:"strategy"`
		Code      string `json:"code"`
		AuthID    string `json:"authId"`
		SafeAuth  bool   `json:"safeAuth"`
	}
	if err := json.NewDecoder(r.Body).Decode(&body); err != nil {
		writeErr(w, http.StatusBadRequest, "请求格式错误")
		return
	}
	var (
		res *huya.QRPollResult
		err error
	)
	if body.SafeAuth || strings.TrimSpace(body.AuthID) != "" {
		res, err = s.deps.ThirdLogin.CompleteSafeAuth(r.Context(), strings.TrimSpace(body.SessionID), strings.TrimSpace(body.AuthID))
	} else {
		res, err = s.deps.ThirdLogin.VerifyAuthCode(r.Context(), strings.TrimSpace(body.SessionID), body.Strategy, body.Code)
	}
	if err != nil {
		writeErr(w, http.StatusBadGateway, err.Error())
		return
	}
	if res.Status == "confirmed" && res.Credentials != nil && res.Credentials.IsValid() {
		s.respondThirdConfirmed(w, r, res)
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{
		"ok":      true,
		"status":  res.Status,
		"stage":   res.Stage,
		"message": res.Message,
		"auth":    res.Auth,
	})
}

func (s *Server) handleMobileSendSMS(w http.ResponseWriter, r *http.Request) {
	var body struct {
		Mobile   string `json:"mobile"`
		DeviceID string `json:"deviceId"`
	}
	if err := json.NewDecoder(r.Body).Decode(&body); err != nil {
		writeErr(w, http.StatusBadRequest, "请求格式错误")
		return
	}
	if s.deps.MobileAuth == nil {
		writeErr(w, http.StatusInternalServerError, "移动端认证客户端未初始化")
		return
	}
	// Always mint a fresh deviceID per SMS verification flow so adding a second account
	// never reuses an existing account's Android device GUID (which would kick the first account).
	deviceID, err := s.deps.MobileAuth.SendMobileSMS(r.Context(), body.Mobile, "")
	if err != nil {
		writeErr(w, http.StatusBadGateway, err.Error())
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{
		"ok":       true,
		"deviceId": deviceID,
		"message":  "短信验证码已发送，请查收",
	})
}

func (s *Server) handleMobileSMSLogin(w http.ResponseWriter, r *http.Request) {
	var body struct {
		Mobile   string `json:"mobile"`
		Code     string `json:"code"`
		DeviceID string `json:"deviceId"`
		Label    string `json:"label"`
	}
	if err := json.NewDecoder(r.Body).Decode(&body); err != nil {
		writeErr(w, http.StatusBadRequest, "请求格式错误")
		return
	}
	if s.deps.MobileAuth == nil {
		writeErr(w, http.StatusInternalServerError, "移动端认证客户端未初始化")
		return
	}
	res, err := s.deps.MobileAuth.LoginWithMobileSMS(r.Context(), body.Mobile, body.Code, body.DeviceID)
	if err != nil {
		writeErr(w, http.StatusBadGateway, err.Error())
		return
	}
	prof := s.resolveHuyaProfile(r.Context(), res.Credentials)
	label := strings.TrimSpace(body.Label)
	if label == "" {
		if prof != nil && prof.NickName != "" {
			label = prof.NickName
		} else {
			label = strings.TrimSpace(res.Passport)
		}
	}
	up, err := s.deps.Store.UpsertByUID(label, res.Credentials)
	if err != nil {
		writeErr(w, http.StatusInternalServerError, "保存账号失败："+err.Error())
		return
	}
	acc := up.Account
	if prof != nil && (prof.NickName != "" || prof.AvatarURL != "") {
		if updated, uErr := s.deps.Store.UpdateAccount(acc.ID, func(a *store.Account) error {
			store.ApplyHuyaProfile(a, prof.NickName, prof.AvatarURL)
			return nil
		}); uErr == nil && updated != nil {
			acc = updated
		}
	}
	s.deps.Scheduler.RescheduleAccount(acc.ID)
	go func(id string) {
		ctx, cancel := context.WithTimeout(context.Background(), 45*time.Second)
		defer cancel()
		if _, syncErr := s.deps.Runner.RefreshBadgesAndBag(ctx, id); syncErr != nil {
			log.Printf("[MobileSMS] initial sync for %s error: %v", id, syncErr)
		} else {
			s.deps.Scheduler.RescheduleAccount(id)
		}
	}(acc.ID)

	msg := "新账号已添加（长期登录），正在同步..."
	if !up.Created {
		msg = "账号登录状态已更新为长期登录（保留原配置）"
	}
	writeJSON(w, http.StatusOK, map[string]any{
		"ok":      true,
		"message": msg,
		"created": up.Created,
		"account": acc.PublicView(),
	})
}

func (s *Server) handleAddCookie(w http.ResponseWriter, r *http.Request) {
	var body struct {
		Label  string `json:"label"`
		Cookie string `json:"cookie"`
	}
	if err := json.NewDecoder(r.Body).Decode(&body); err != nil {
		writeErr(w, http.StatusBadRequest, "请求格式错误")
		return
	}
	creds, err := huya.ParseCookieCredentials(body.Cookie)
	if err != nil {
		writeErr(w, http.StatusBadRequest, err.Error())
		return
	}
	prof := s.resolveHuyaProfile(r.Context(), creds)
	label := strings.TrimSpace(body.Label)
	if label == "" && prof != nil && prof.NickName != "" {
		label = prof.NickName
	}
	up, err := s.deps.Store.UpsertByUID(label, creds)
	if err != nil {
		writeErr(w, http.StatusInternalServerError, "保存账号失败："+err.Error())
		return
	}
	acc := up.Account
	if prof != nil && (prof.NickName != "" || prof.AvatarURL != "") {
		if updated, uErr := s.deps.Store.UpdateAccount(acc.ID, func(a *store.Account) error {
			store.ApplyHuyaProfile(a, prof.NickName, prof.AvatarURL)
			return nil
		}); uErr == nil && updated != nil {
			acc = updated
		}
	}
	s.deps.Scheduler.RescheduleAccount(acc.ID)
	go func(id string) {
		ctx, cancel := context.WithTimeout(context.Background(), 45*time.Second)
		defer cancel()
		_, _ = s.deps.Runner.RefreshBadgesAndBag(ctx, id)
		s.deps.Scheduler.RescheduleAccount(id)
	}(acc.ID)

	msg := "新账号已添加，正在同步..."
	if up.KeptMobile {
		msg = "该账号已使用长期登录，已保留原登录状态未覆盖；如需添加新号请粘贴另一个账号的 Cookie"
	} else if !up.Created {
		msg = "Cookie 已更新（保留原配置）"
	}
	writeJSON(w, http.StatusOK, map[string]any{
		"ok":         true,
		"message":    msg,
		"created":    up.Created,
		"keptMobile": up.KeptMobile,
		"account":    acc.PublicView(),
	})
}

func (s *Server) handleUpdateConfig(w http.ResponseWriter, r *http.Request) {
	id := r.PathValue("id")
	var body struct {
		Label  *string              `json:"label"`
		Config *store.AccountConfig `json:"config"`
	}
	if err := json.NewDecoder(r.Body).Decode(&body); err != nil {
		writeErr(w, http.StatusBadRequest, "配置格式错误")
		return
	}
	acc, err := s.deps.Store.UpdateAccount(id, func(a *store.Account) error {
		if body.Label != nil && strings.TrimSpace(*body.Label) != "" {
			a.Label = strings.TrimSpace(*body.Label)
		}
		if body.Config != nil {
			c := *body.Config
			if c.AutoClaimHour < 0 || c.AutoClaimHour > 23 {
				c.AutoClaimHour = 8
			}
			if c.AutoClaimMinute < 0 || c.AutoClaimMinute > 59 {
				c.AutoClaimMinute = 30
			}
			if c.SendMaxCount < 0 {
				c.SendMaxCount = 0
			}
			if c.SendDistributeMode < 0 || c.SendDistributeMode > 3 {
				c.SendDistributeMode = store.DistributeFillQuota
			}
			a.Config = c
		}
		return nil
	})
	if err != nil {
		writeErr(w, http.StatusNotFound, err.Error())
		return
	}
	s.deps.Scheduler.RescheduleAccount(id)
	writeJSON(w, http.StatusOK, map[string]any{
		"ok":      true,
		"account": acc.PublicView(),
	})
}

func (s *Server) handleDeleteAccount(w http.ResponseWriter, r *http.Request) {
	id := r.PathValue("id")
	if err := s.deps.Store.DeleteAccount(id); err != nil {
		writeErr(w, http.StatusNotFound, err.Error())
		return
	}
	s.deps.Scheduler.RescheduleAccount(id)
	writeJSON(w, http.StatusOK, map[string]any{"ok": true})
}

func (s *Server) handleRunAccount(w http.ResponseWriter, r *http.Request) {
	id := r.PathValue("id")
	if _, ok := s.deps.Store.GetClone(id); !ok {
		writeErr(w, http.StatusNotFound, "账号不存在")
		return
	}
	if s.deps.Runner.IsRunning(id) {
		writeErr(w, http.StatusConflict, "该账号任务正在执行中，请稍候")
		return
	}
	go func() {
		ctx, cancel := context.WithTimeout(context.Background(), 3*time.Minute)
		defer cancel()
		res, err := s.deps.Runner.RunAccount(ctx, id, "manual")
		if err != nil {
			log.Printf("[API] RunAccount %s error: %v", id, err)
			return
		}
		s.deps.Scheduler.ScheduleAfterRun(id, res)
	}()
	writeJSON(w, http.StatusAccepted, map[string]any{
		"ok":      true,
		"message": "任务已启动",
	})
}

func (s *Server) handleRefreshAccount(w http.ResponseWriter, r *http.Request) {
	id := r.PathValue("id")
	acc, err := s.deps.Runner.RefreshBadgesAndBag(r.Context(), id)
	if err != nil {
		writeErr(w, http.StatusBadGateway, err.Error())
		return
	}
	s.deps.Scheduler.RescheduleAccount(id)
	writeJSON(w, http.StatusOK, map[string]any{
		"ok":      true,
		"account": acc.PublicView(),
	})
}

func (s *Server) handleManualSend(w http.ResponseWriter, r *http.Request) {
	id := r.PathValue("id")
	var body struct {
		PID   int64 `json:"pid"`
		Super bool  `json:"super"`
		Count int   `json:"count"`
	}
	if err := json.NewDecoder(r.Body).Decode(&body); err != nil {
		writeErr(w, http.StatusBadRequest, "参数格式错误")
		return
	}
	msg, err := s.deps.Runner.ManualSendGift(r.Context(), id, body.PID, body.Super, body.Count)
	if err != nil {
		writeErr(w, http.StatusBadRequest, err.Error())
		return
	}
	acc, _ := s.deps.Store.GetClone(id)
	var pub any
	if acc != nil {
		pub = acc.PublicView()
	}
	writeJSON(w, http.StatusOK, map[string]any{
		"ok":      true,
		"message": msg,
		"account": pub,
	})
}

func (s *Server) handleSaveNotify(w http.ResponseWriter, r *http.Request) {
	var cfg store.NotifyConfig
	if err := json.NewDecoder(r.Body).Decode(&cfg); err != nil {
		writeErr(w, http.StatusBadRequest, "通知配置格式错误")
		return
	}
	if err := s.deps.Store.SetNotify(cfg); err != nil {
		writeErr(w, http.StatusInternalServerError, err.Error())
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"ok": true, "notify": cfg})
}

func (s *Server) handleRunAllAccounts(w http.ResponseWriter, r *http.Request) {
	var body struct {
		Trigger     string `json:"trigger"`
		OnlyEnabled bool   `json:"onlyEnabled"`
	}
	_ = json.NewDecoder(r.Body).Decode(&body)
	trigger := strings.TrimSpace(body.Trigger)
	if trigger == "" {
		trigger = "schedule"
	}

	type itemResult struct {
		AccountID       string `json:"accountId"`
		Label           string `json:"label"`
		Success         bool   `json:"success"`
		Message         string `json:"message"`
		Title           string `json:"title,omitempty"`
		Body            string `json:"body,omitempty"`
		CooldownSeconds int    `json:"cooldownSeconds"`
	}

	ids := s.deps.Store.ListIDs()
	results := make([]itemResult, 0, len(ids))
	for _, id := range ids {
		acc, ok := s.deps.Store.GetClone(id)
		if !ok || !acc.Credentials.IsValid() {
			continue
		}
		if body.OnlyEnabled && !acc.Config.AutoClaimEnabled {
			continue
		}
		runCtx, cancel := context.WithTimeout(r.Context(), 3*time.Minute)
		res, err := s.deps.Runner.RunAccount(runCtx, id, trigger)
		cancel()
		if err != nil {
			results = append(results, itemResult{
				AccountID: id,
				Label:     acc.Label,
				Success:   false,
				Message:   err.Error(),
			})
			continue
		}
		s.deps.Scheduler.ScheduleAfterRun(id, res)
		results = append(results, itemResult{
			AccountID:       id,
			Label:           acc.Label,
			Success:         res.Success,
			Message:         res.Message,
			Title:           res.NotifyTitle,
			Body:            res.NotifyBody,
			CooldownSeconds: res.CooldownSeconds,
		})
	}
	writeJSON(w, http.StatusOK, map[string]any{
		"ok":       true,
		"count":    len(results),
		"results":  results,
		"accounts": s.deps.Store.ListPublic(),
	})
}

func (s *Server) handleDrainNotifications(w http.ResponseWriter, _ *http.Request) {
	s.notifMu.Lock()
	out := s.notifs
	s.notifs = nil
	s.notifMu.Unlock()
	if out == nil {
		out = []LocalNotification{}
	}
	writeJSON(w, http.StatusOK, map[string]any{
		"ok":            true,
		"notifications": out,
	})
}

func (s *Server) handleTestNotify(w http.ResponseWriter, r *http.Request) {
	cfg := s.deps.Store.GetNotify()
	body := "✅ 推送通道连接正常\n📅 每日任务结果与超粉日提醒将通过此通道送达"
	errs := s.deps.Notifier.Send(r.Context(), cfg, "虎伴 Server 测试推送", body)
	if len(errs) > 0 {
		writeErr(w, http.StatusBadGateway, strings.Join(errs, "；"))
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"ok": true, "message": "测试推送已发送"})
}

func writeJSON(w http.ResponseWriter, status int, v any) {
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(v)
}

func writeErr(w http.ResponseWriter, status int, msg string) {
	writeJSON(w, status, map[string]any{"ok": false, "error": msg})
}
