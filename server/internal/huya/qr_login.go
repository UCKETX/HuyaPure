package huya

import (
	"bytes"
	"context"
	"encoding/base64"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/http/cookiejar"
	"net/url"
	"strconv"
	"strings"
	"sync"
	"time"
)

const (
	udbBaseURL = "https://udblgn.huya.com"
	udbAppID   = "5002"
	udbVersion = "2.2"
	udbLCID    = "2052"
	udbByPass  = "3"
)

// QRSession tracks an in-flight Huya App QR login session.
type QRSession struct {
	ID         string    `json:"sessionId"`
	QRID       string    `json:"qrId"`
	ImageData  string    `json:"imageData"` // data:image/png;base64,...
	CreatedAt  time.Time `json:"createdAt"`
	jar        *cookiejar.Jar
	httpClient *http.Client
	sdid       string
}

// QRPollResult represents the current state of a QR or third-party OAuth login session.
type QRPollResult struct {
	Status      string              `json:"status"` // waiting | scanned | need_auth | confirmed | expired | cancelled | failed
	Stage       int                 `json:"stage"`
	Message     string              `json:"message"`
	UID         int64               `json:"uid,omitempty"`
	NickName    string              `json:"nickName,omitempty"`
	Auth        *ThirdAuthChallenge `json:"auth,omitempty"`
	Credentials *Credentials        `json:"-"`
}

// QRLoginManager manages concurrent QR login sessions in memory.
type QRLoginManager struct {
	mu       sync.Mutex
	sessions map[string]*QRSession
}

func NewQRLoginManager() *QRLoginManager {
	return &QRLoginManager{
		sessions: make(map[string]*QRSession),
	}
}

type udbEnvelope struct {
	URI         string `json:"uri"`
	Version     string `json:"version"`
	Context     string `json:"context"`
	AppID       string `json:"appId"`
	LCID        string `json:"lcid"`
	ByPass      string `json:"byPass"`
	SDID        string `json:"sdid"`
	RequestID   string `json:"requestId"`
	Data        any    `json:"data"`
}

type udbResponse struct {
	URI         int             `json:"uri"`
	ReturnCode  int             `json:"returnCode"`
	Message     string          `json:"message"`
	Description string          `json:"description"`
	Data        json.RawMessage `json:"data"`
}

// StartSession requests a fresh qrId + QR PNG image from udblgn.huya.com.
func (m *QRLoginManager) StartSession(ctx context.Context) (*QRSession, error) {
	m.cleanupExpired()

	jar, err := cookiejar.New(nil)
	if err != nil {
		return nil, err
	}
	client := &http.Client{
		Jar:     jar,
		Timeout: 12 * time.Second,
	}
	sdid := RandomHex(6)

	// Step 1: POST /qrLgn/getQrId
	var qrResp udbResponse
	if err := postUDB(ctx, client, "/qrLgn/getQrId", udbEnvelope{
		URI:       "70001",
		Version:   udbVersion,
		Context:   "WB---",
		AppID:     udbAppID,
		LCID:      udbLCID,
		ByPass:    udbByPass,
		SDID:      sdid,
		RequestID: strconv.FormatInt(time.Now().UnixMilli(), 10),
		Data:      map[string]any{},
	}, &qrResp); err != nil {
		return nil, fmt.Errorf("getQrId failed: %w", err)
	}
	if qrResp.ReturnCode != 0 {
		return nil, fmt.Errorf("getQrId error (%d): %s %s", qrResp.ReturnCode, qrResp.Message, qrResp.Description)
	}
	var qrData struct {
		QRID string `json:"qrId"`
	}
	if err := json.Unmarshal(qrResp.Data, &qrData); err != nil || qrData.QRID == "" {
		return nil, fmt.Errorf("invalid getQrId payload: %s", string(qrResp.Data))
	}

	// Step 2: GET /qrLgn/getQrImg?k=<qrId> (also sets web_qrlogin_confirm_id cookie in jar!)
	imgReq, err := http.NewRequestWithContext(ctx, http.MethodGet,
		udbBaseURL+"/qrLgn/getQrImg?k="+url.QueryEscape(qrData.QRID), nil)
	if err != nil {
		return nil, err
	}
	imgReq.Header.Set("User-Agent", DefaultUserAgent)
	imgReq.Header.Set("Referer", "https://www.huya.com/")
	imgResp, err := client.Do(imgReq)
	if err != nil {
		return nil, fmt.Errorf("getQrImg failed: %w", err)
	}
	imgBytes, readErr := io.ReadAll(io.LimitReader(imgResp.Body, 512*1024))
	imgResp.Body.Close()
	if readErr != nil || imgResp.StatusCode != http.StatusOK || len(imgBytes) == 0 {
		return nil, fmt.Errorf("getQrImg HTTP %d (%v)", imgResp.StatusCode, readErr)
	}

	sess := &QRSession{
		ID:         RandomHex(12),
		QRID:       qrData.QRID,
		ImageData:  "data:image/png;base64," + base64.StdEncoding.EncodeToString(imgBytes),
		CreatedAt:  time.Now(),
		jar:        jar,
		httpClient: client,
		sdid:       sdid,
	}

	m.mu.Lock()
	m.sessions[sess.ID] = sess
	m.mu.Unlock()
	return sess, nil
}

// Poll checks the status of an active QR login session via /qrLgn/tryQrLogin.
func (m *QRLoginManager) Poll(ctx context.Context, sessionID string) (*QRPollResult, error) {
	m.mu.Lock()
	sess, ok := m.sessions[sessionID]
	m.mu.Unlock()
	if !ok {
		return &QRPollResult{Status: "expired", Stage: 5, Message: "登录会话不存在或已过期，请刷新二维码"}, nil
	}
	if time.Since(sess.CreatedAt) > 4*time.Minute {
		m.Remove(sessionID)
		return &QRPollResult{Status: "expired", Stage: 5, Message: "二维码已过期，请刷新"}, nil
	}

	var resp udbResponse
	err := postUDB(ctx, sess.httpClient, "/qrLgn/tryQrLogin", udbEnvelope{
		URI:       "70003",
		Version:   udbVersion,
		Context:   "WB---",
		AppID:     udbAppID,
		LCID:      udbLCID,
		ByPass:    udbByPass,
		SDID:      sess.sdid,
		RequestID: strconv.FormatInt(time.Now().UnixMilli(), 10),
		Data: map[string]string{
			"qrId":     sess.QRID,
			"remember": "1",
			"behavior": "",
			"page":     "https://www.huya.com/",
		},
	}, &resp)
	if err != nil {
		return nil, err
	}
	if resp.ReturnCode != 0 {
		msg := resp.Description
		if msg == "" {
			msg = resp.Message
		}
		return &QRPollResult{Status: "failed", Stage: -1, Message: fmt.Sprintf("UDB 错误(%d): %s", resp.ReturnCode, msg)}, nil
	}

	var data struct {
		Stage         int      `json:"stage"`
		UID           any      `json:"uid"`
		YYUID         any      `json:"yyuid"`
		NickName      string   `json:"nickName"`
		BizToken      string   `json:"biztoken"`
		Passport      string   `json:"passport"`
		DomainURLList []string `json:"domainUrlList"`
	}
	if err := json.Unmarshal(resp.Data, &data); err != nil {
		return nil, fmt.Errorf("parse tryQrLogin data: %w", err)
	}

	switch data.Stage {
	case 0, 4:
		return &QRPollResult{Status: "waiting", Stage: data.Stage, Message: "请使用虎牙直播 App 扫描二维码"}, nil
	case 1:
		return &QRPollResult{Status: "scanned", Stage: 1, Message: "已扫码，请在虎牙 App 上确认登录"}, nil
	case 2, 3:
		// Confirmed! Follow any domainUrlList callback URLs so the cookie jar receives all .huya.com auth cookies.
		for _, u := range data.DomainURLList {
			if strings.HasPrefix(u, "http://") || strings.HasPrefix(u, "https://") {
				req, reqErr := http.NewRequestWithContext(ctx, http.MethodGet, u, nil)
				if reqErr == nil {
					req.Header.Set("User-Agent", DefaultUserAgent)
					req.Header.Set("Referer", "https://www.huya.com/")
					if r, doErr := sess.httpClient.Do(req); doErr == nil {
						_, _ = io.Copy(io.Discard, io.LimitReader(r.Body, 64*1024))
						r.Body.Close()
					}
				}
			}
		}

		// Collect cookies for https://www.huya.com
		huyaURL, _ := url.Parse("https://www.huya.com/")
		cookies := sess.jar.Cookies(huyaURL)
		cookieMap := make(map[string]string, len(cookies)+4)
		for _, ck := range cookies {
			if ck.Value != "" {
				cookieMap[ck.Name] = ck.Value
			}
		}

		uid := parseFlexibleInt64(data.UID)
		if uid <= 0 {
			uid = parseFlexibleInt64(cookieMap["udb_uid"])
		}
		if uid <= 0 {
			uid = parseFlexibleInt64(data.YYUID)
		}
		if uid <= 0 {
			uid = parseFlexibleInt64(cookieMap["yyuid"])
		}

		if uid > 0 {
			uidStr := strconv.FormatInt(uid, 10)
			if cookieMap["yyuid"] == "" {
				cookieMap["yyuid"] = uidStr
			}
			if cookieMap["udb_uid"] == "" {
				cookieMap["udb_uid"] = uidStr
			}
		}
		if data.BizToken != "" && cookieMap["udb_biztoken"] == "" {
			cookieMap["udb_biztoken"] = data.BizToken
		}
		if data.Passport != "" && cookieMap["udb_passdata"] == "" {
			cookieMap["udb_passdata"] = data.Passport
		}

		cookieParts := make([]string, 0, len(cookieMap))
		for k, v := range cookieMap {
			cookieParts = append(cookieParts, k+"="+v)
		}
		cookieStr := strings.Join(cookieParts, "; ")

		creds := &Credentials{
			UID:    uid,
			Cookie: cookieStr,
			Token:  "", // Web auth uses sCookie; non-empty sToken triggers WUP ret=905
			GUID:   RandomHex(16),
			HuYaUA: DefaultWebHuYaUA,
		}
		m.Remove(sessionID)
		return &QRPollResult{
			Status:      "confirmed",
			Stage:       data.Stage,
			Message:     "扫码登录成功",
			UID:         uid,
			NickName:    data.NickName,
			Credentials: creds,
		}, nil
	case 5:
		m.Remove(sessionID)
		return &QRPollResult{Status: "expired", Stage: 5, Message: "二维码已过期，请刷新"}, nil
	case 6:
		m.Remove(sessionID)
		return &QRPollResult{Status: "expired", Stage: 6, Message: "二维码已失效，请刷新"}, nil
	case 7:
		m.Remove(sessionID)
		return &QRPollResult{Status: "cancelled", Stage: 7, Message: "已在手机端取消登录"}, nil
	case 8:
		m.Remove(sessionID)
		return &QRPollResult{Status: "failed", Stage: 8, Message: "扫码账号异常"}, nil
	default:
		return &QRPollResult{Status: "failed", Stage: data.Stage, Message: fmt.Sprintf("未知扫码状态 (%d)", data.Stage)}, nil
	}
}

func (m *QRLoginManager) Remove(sessionID string) {
	m.mu.Lock()
	delete(m.sessions, sessionID)
	m.mu.Unlock()
}

func (m *QRLoginManager) cleanupExpired() {
	m.mu.Lock()
	defer m.mu.Unlock()
	cutoff := time.Now().Add(-5 * time.Minute)
	for id, s := range m.sessions {
		if s.CreatedAt.Before(cutoff) {
			delete(m.sessions, id)
		}
	}
}

// ParseCookieCredentials builds Credentials from a raw browser Cookie string (e.g. copied from huya.com).
func ParseCookieCredentials(rawCookie string) (Credentials, error) {
	trimmed := strings.TrimSpace(rawCookie)
	if trimmed == "" {
		return Credentials{}, fmt.Errorf("Cookie 不能为空")
	}
	kv := make(map[string]string)
	for _, part := range strings.Split(trimmed, ";") {
		part = strings.TrimSpace(part)
		if part == "" {
			continue
		}
		eq := strings.IndexByte(part, '=')
		if eq <= 0 {
			continue
		}
		k := strings.TrimSpace(part[:eq])
		v := strings.TrimSpace(part[eq+1:])
		if k != "" && v != "" {
			kv[k] = v
		}
	}

	var uid int64
	// Prefer udb_uid (authoritative UDB login UID paired with udb_biztoken) before yyuid,
	// because yyuid can occasionally remain stale after switching accounts in a browser.
	for _, key := range []string{"udb_uid", "yyuid", "lUid", "uid"} {
		if v := kv[key]; v != "" {
			if n, err := strconv.ParseInt(v, 10, 64); err == nil && n > 0 {
				uid = n
				break
			}
		}
	}
	if uid <= 0 {
		return Credentials{}, fmt.Errorf("未在 Cookie 中找到 yyuid 或 udb_uid，请确认已登录虎牙网页版后完整复制 Cookie")
	}
	return Credentials{
		UID:    uid,
		Cookie: trimmed,
		Token:  "", // Web auth uses sCookie; non-empty sToken triggers WUP ret=905
		GUID:   RandomHex(16),
		HuYaUA: DefaultWebHuYaUA,
	}, nil
}

func postUDB(ctx context.Context, client *http.Client, path string, body any, out any) error {
	raw, err := json.Marshal(body)
	if err != nil {
		return err
	}
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, udbBaseURL+path, bytes.NewReader(raw))
	if err != nil {
		return err
	}
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("User-Agent", DefaultUserAgent)
	req.Header.Set("Origin", "https://www.huya.com")
	req.Header.Set("Referer", "https://www.huya.com/")

	resp, err := client.Do(req)
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	respBytes, err := io.ReadAll(io.LimitReader(resp.Body, 256*1024))
	if err != nil {
		return err
	}
	if resp.StatusCode < 200 || resp.StatusCode >= 300 {
		return fmt.Errorf("HTTP %d: %s", resp.StatusCode, string(respBytes))
	}
	return json.Unmarshal(respBytes, out)
}

func parseFlexibleInt64(v any) int64 {
	switch x := v.(type) {
	case float64:
		return int64(x)
	case int64:
		return x
	case string:
		n, _ := strconv.ParseInt(strings.TrimSpace(x), 10, 64)
		return n
	default:
		return 0
	}
}
