package huya

import (
	"context"
	"encoding/base64"
	"encoding/json"
	"fmt"
	"io"
	"log"
	"net/http"
	"net/http/cookiejar"
	"net/url"
	"regexp"
	"strconv"
	"strings"
	"sync"
	"time"
)

const (
	udb3BaseURL    = "https://udb3lgn.huya.com"
	qqLoginJumpURL = "https://graph.qq.com/oauth2.0/login_jump"
	qqPtAppID      = "716027609"
	qqPtDaid       = "383"
)

var (
	reWxQRPath      = regexp.MustCompile(`/connect/qrcode/([a-zA-Z0-9_-]+)`)
	reWxErrCode     = regexp.MustCompile(`wx_errcode\s*=\s*(\d+)`)
	reWxCode        = regexp.MustCompile(`wx_code\s*=\s*'([^']*)'`)
	rePtuiCB        = regexp.MustCompile(`ptuiCB\((.*)\)`)
	rePtuiArg       = regexp.MustCompile(`'([^']*)'`)
	reUdbExchange   = regexp.MustCompile(`setCookie\(\s*["']udb_exchange\w*["']\s*,\s*["']([^"']+)["']`)
	reQQOAuthInput  = regexp.MustCompile(`name=["']api_choose["'][^>]*value=["'](\d+)["']`)
	reQQCallbackURL = regexp.MustCompile(`(https?://udb3lgn\.huya\.com/web/v2/callback[^"'\s<]+)`)
)

// ThirdAuthStrategy represents one UDB second-factor strategy (4=image, 8=SMS, 16=token, or dataType=4 SafeAuth iframe).
type ThirdAuthStrategy struct {
	Strategy      int    `json:"strategy"`
	DataType      int    `json:"dataType,omitempty"`
	SelectTitle   string `json:"selectTitle,omitempty"`
	PromptContent string `json:"promptContent,omitempty"`
	ImageData     string `json:"imageData,omitempty"`
	SafeAuthURL   string `json:"safeAuthUrl,omitempty"`
}

// ThirdAuthChallenge is returned to the UI when Huya requires second-factor verification (returnCode 10030 / 10039 / 70001).
type ThirdAuthChallenge struct {
	SessionID     string              `json:"sessionId"`
	UID           int64               `json:"uid,omitempty"`
	Mode          string              `json:"mode"` // "safe_auth" | "code"
	Message       string              `json:"message"`
	SafeAuthURL   string              `json:"safeAuthUrl,omitempty"`
	SafeAuthTitle string              `json:"safeAuthTitle,omitempty"`
	HasCallback   bool                `json:"hasCallback,omitempty"`
	Strategies    []ThirdAuthStrategy `json:"strategies,omitempty"`
}

// ThirdLoginSession tracks an in-flight WeChat or QQ QR OAuth login into Huya UDB.
type ThirdLoginSession struct {
	ID         string    `json:"sessionId"`
	Provider   string    `json:"provider"` // "wx" | "qq"
	ImageData  string    `json:"imageData"`
	CreatedAt  time.Time `json:"createdAt"`
	jar        *cookiejar.Jar
	httpClient *http.Client

	// WeChat state (driven by an uninterrupted background long-poll goroutine)
	wxUUID     string
	wxCallback string
	wxLastCode int
	wxCancel   context.CancelFunc
	wxMu       sync.Mutex
	wxState    *QRPollResult

	// QQ state
	qqShowURL      string
	qqXLoginURL    string
	qqClientID     string
	qqRedirectURI  string
	qqResponseType string
	qqScope        string
	qqState        string
	qqOpenAPIs     string
	qqLoginSig     string
	qqPtQRToken    string

	// Second-factor UDB auth state (returnCode 10030 / 10039 / 70001)
	authMu          sync.Mutex
	authUID         int64
	authSessionData string
	authCallbackURL string
	authStrategies  []ThirdAuthStrategy
	authSuccessMsg  string
}

// ThirdLoginManager manages concurrent WeChat & QQ OAuth QR login sessions.
type ThirdLoginManager struct {
	MobileAuth *MobileAuthClient

	mu       sync.Mutex
	sessions map[string]*ThirdLoginSession
}

func NewThirdLoginManager(mobileAuth *MobileAuthClient) *ThirdLoginManager {
	return &ThirdLoginManager{
		MobileAuth: mobileAuth,
		sessions:   make(map[string]*ThirdLoginSession),
	}
}

// StartSession initializes a WeChat ("wx") or QQ ("qq") QR login session against udb3lgn.huya.com.
func (m *ThirdLoginManager) StartSession(ctx context.Context, provider string) (*ThirdLoginSession, error) {
	m.cleanupExpired()
	provider = strings.ToLower(strings.TrimSpace(provider))
	switch provider {
	case "wx", "wechat":
		return m.startWeChat(ctx)
	case "qq":
		return m.startQQ(ctx)
	default:
		return nil, fmt.Errorf("不支持的第三方登录方式: %s", provider)
	}
}

func (m *ThirdLoginManager) startWeChat(ctx context.Context) (*ThirdLoginSession, error) {
	jar, err := cookiejar.New(nil)
	if err != nil {
		return nil, err
	}
	client := &http.Client{Jar: jar, Timeout: 15 * time.Second}

	signinURL := buildUDB3SigninURL("wx")
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, signinURL, nil)
	if err != nil {
		return nil, err
	}
	req.Header.Set("User-Agent", DefaultUserAgent)
	req.Header.Set("Referer", "https://www.huya.com/")

	resp, err := client.Do(req)
	if err != nil {
		return nil, fmt.Errorf("请求微信登录入口失败: %w", err)
	}
	bodyBytes, readErr := io.ReadAll(io.LimitReader(resp.Body, 512*1024))
	resp.Body.Close()
	if readErr != nil {
		return nil, fmt.Errorf("读取微信登录页失败: %w", readErr)
	}

	finalURL := resp.Request.URL
	callbackURL := finalURL.Query().Get("redirect_uri")
	if callbackURL == "" {
		return nil, fmt.Errorf("未能获取微信回调地址 (final=%s)", finalURL.Host)
	}

	match := reWxQRPath.FindStringSubmatch(string(bodyBytes))
	if len(match) < 2 {
		return nil, fmt.Errorf("未能解析微信登录二维码标识")
	}
	uuid := match[1]

	qrReq, err := http.NewRequestWithContext(ctx, http.MethodGet, "https://open.weixin.qq.com/connect/qrcode/"+uuid, nil)
	if err != nil {
		return nil, err
	}
	qrReq.Header.Set("User-Agent", DefaultUserAgent)
	qrReq.Header.Set("Referer", finalURL.String())
	qrResp, err := client.Do(qrReq)
	if err != nil {
		return nil, fmt.Errorf("拉取微信二维码图片失败: %w", err)
	}
	imgBytes, imgErr := io.ReadAll(io.LimitReader(qrResp.Body, 512*1024))
	qrResp.Body.Close()
	if imgErr != nil || qrResp.StatusCode != http.StatusOK || len(imgBytes) == 0 {
		return nil, fmt.Errorf("微信二维码图片响应异常 HTTP %d", qrResp.StatusCode)
	}

	bgCtx, cancel := context.WithTimeout(context.Background(), 4*time.Minute+15*time.Second)
	sess := &ThirdLoginSession{
		ID:         RandomHex(12),
		Provider:   "wx",
		ImageData:  "data:image/png;base64," + base64.StdEncoding.EncodeToString(imgBytes),
		CreatedAt:  time.Now(),
		jar:        jar,
		httpClient: client,
		wxUUID:     uuid,
		wxCallback: strings.ReplaceAll(callbackURL, "&amp;", "&"),
		wxCancel:   cancel,
		wxState:    &QRPollResult{Status: "waiting", Stage: 0, Message: "请使用微信扫描二维码"},
	}
	m.mu.Lock()
	m.sessions[sess.ID] = sess
	m.mu.Unlock()
	go m.runWeChatPollLoop(bgCtx, sess)
	return sess, nil
}

func (m *ThirdLoginManager) startQQ(ctx context.Context) (*ThirdLoginSession, error) {
	jar, err := cookiejar.New(nil)
	if err != nil {
		return nil, err
	}
	client := &http.Client{Jar: jar, Timeout: 15 * time.Second}

	signinURL := buildUDB3SigninURL("qq")
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, signinURL, nil)
	if err != nil {
		return nil, err
	}
	req.Header.Set("User-Agent", DefaultUserAgent)
	req.Header.Set("Referer", "https://www.huya.com/")

	resp, err := client.Do(req)
	if err != nil {
		return nil, fmt.Errorf("请求 QQ 登录入口失败: %w", err)
	}
	bodyBytes, readErr := io.ReadAll(io.LimitReader(resp.Body, 512*1024))
	resp.Body.Close()
	if readErr != nil {
		return nil, fmt.Errorf("读取 QQ 授权页失败: %w", readErr)
	}

	showURL := resp.Request.URL
	q := showURL.Query()
	clientID := q.Get("client_id")
	if clientID == "" {
		clientID = "101406359"
	}
	redirectURI := q.Get("redirect_uri")
	if redirectURI == "" {
		redirectURI = udb3BaseURL + "/web/v2/callback"
	}
	respType := q.Get("response_type")
	if respType == "" {
		respType = "code"
	}
	scope := q.Get("scope")
	stateParam := q.Get("state")

	var apiIDs []string
	for _, sm := range reQQOAuthInput.FindAllStringSubmatch(string(bodyBytes), -1) {
		if len(sm) >= 2 && sm[1] != "" {
			apiIDs = append(apiIDs, sm[1])
		}
	}
	if len(apiIDs) == 0 {
		apiIDs = []string{"1010"}
	}

	// Step 2: Load xlogin page to obtain pt_login_sig cookie.
	xloginParams := url.Values{}
	xloginParams.Set("appid", qqPtAppID)
	xloginParams.Set("daid", qqPtDaid)
	xloginParams.Set("style", "33")
	xloginParams.Set("login_text", "登录")
	xloginParams.Set("hide_title_bar", "1")
	xloginParams.Set("hide_border", "1")
	xloginParams.Set("target", "self")
	xloginParams.Set("s_url", qqLoginJumpURL)
	xloginParams.Set("pt_3rd_aid", clientID)
	xloginURL := "https://xui.ptlogin2.qq.com/cgi-bin/xlogin?" + xloginParams.Encode()

	xreq, err := http.NewRequestWithContext(ctx, http.MethodGet, xloginURL, nil)
	if err != nil {
		return nil, err
	}
	xreq.Header.Set("User-Agent", DefaultUserAgent)
	xreq.Header.Set("Referer", showURL.String())
	xresp, err := client.Do(xreq)
	if err != nil {
		return nil, fmt.Errorf("初始化 QQ 登录会话失败: %w", err)
	}
	_, _ = io.Copy(io.Discard, io.LimitReader(xresp.Body, 128*1024))
	xresp.Body.Close()

	ptURL, _ := url.Parse("https://ssl.ptlogin2.qq.com/")
	loginSig := findCookieValue(jar, ptURL, "pt_login_sig")

	// Step 3: Fetch ptqrshow PNG + qrsig cookie.
	qrURL := fmt.Sprintf(
		"https://ssl.ptlogin2.qq.com/ptqrshow?appid=%s&e=2&l=M&s=3&d=72&v=4&t=0.%d&daid=%s&pt_3rd_aid=%s",
		qqPtAppID, time.Now().UnixNano()%1000000000, qqPtDaid, url.QueryEscape(clientID),
	)
	qrReq, err := http.NewRequestWithContext(ctx, http.MethodGet, qrURL, nil)
	if err != nil {
		return nil, err
	}
	qrReq.Header.Set("User-Agent", DefaultUserAgent)
	qrReq.Header.Set("Referer", xloginURL)
	qrResp, err := client.Do(qrReq)
	if err != nil {
		return nil, fmt.Errorf("拉取 QQ 二维码失败: %w", err)
	}
	imgBytes, imgErr := io.ReadAll(io.LimitReader(qrResp.Body, 512*1024))
	qrResp.Body.Close()
	if imgErr != nil || qrResp.StatusCode != http.StatusOK || len(imgBytes) == 0 {
		return nil, fmt.Errorf("QQ 二维码图片响应异常 HTTP %d", qrResp.StatusCode)
	}

	qrsig := findCookieValue(jar, ptURL, "qrsig")
	if qrsig == "" {
		return nil, fmt.Errorf("QQ 登录未返回 qrsig 凭据")
	}

	sess := &ThirdLoginSession{
		ID:             RandomHex(12),
		Provider:       "qq",
		ImageData:      "data:image/png;base64," + base64.StdEncoding.EncodeToString(imgBytes),
		CreatedAt:      time.Now(),
		jar:            jar,
		httpClient:     client,
		qqShowURL:      showURL.String(),
		qqXLoginURL:    xloginURL,
		qqClientID:     clientID,
		qqRedirectURI:  redirectURI,
		qqResponseType: respType,
		qqScope:        scope,
		qqState:        stateParam,
		qqOpenAPIs:     strings.Join(apiIDs, "_"),
		qqLoginSig:     loginSig,
		qqPtQRToken:    strconv.Itoa(qqHash33(qrsig)),
	}
	m.mu.Lock()
	m.sessions[sess.ID] = sess
	m.mu.Unlock()
	return sess, nil
}

// Poll checks the status of an active WeChat or QQ QR login session.
func (m *ThirdLoginManager) Poll(ctx context.Context, sessionID string) (*QRPollResult, error) {
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

	switch sess.Provider {
	case "wx":
		return m.pollWeChat(ctx, sess)
	case "qq":
		return m.pollQQ(ctx, sess)
	default:
		return &QRPollResult{Status: "failed", Stage: -1, Message: "未知登录方式"}, nil
	}
}

func (m *ThirdLoginManager) setWeChatState(sess *ThirdLoginSession, res *QRPollResult) {
	sess.wxMu.Lock()
	sess.wxState = res
	sess.wxMu.Unlock()
}

func (m *ThirdLoginManager) runWeChatPollLoop(ctx context.Context, sess *ThirdLoginSession) {
	longPollClient := &http.Client{
		Jar:     sess.jar,
		Timeout: 35 * time.Second,
	}
	for {
		if ctx.Err() != nil {
			return
		}
		// Official WeChat qrconnect long-poll path: /connect/l/qrconnect (NOT /connect/l/uuid)
		pollURL := fmt.Sprintf("https://lp.open.weixin.qq.com/connect/l/qrconnect?uuid=%s&_=%d",
			url.QueryEscape(sess.wxUUID), time.Now().UnixMilli())
		if sess.wxLastCode > 0 {
			pollURL += fmt.Sprintf("&last=%d", sess.wxLastCode)
		}

		req, err := http.NewRequestWithContext(ctx, http.MethodGet, pollURL, nil)
		if err != nil {
			m.setWeChatState(sess, &QRPollResult{Status: "failed", Stage: -1, Message: "构造微信轮询请求失败"})
			return
		}
		req.Header.Set("User-Agent", DefaultUserAgent)
		req.Header.Set("Referer", "https://open.weixin.qq.com/")

		resp, err := longPollClient.Do(req)
		if err != nil {
			if ctx.Err() != nil {
				return
			}
			// Transient network/timeout during long-poll: retry cleanly
			select {
			case <-ctx.Done():
				return
			case <-time.After(1 * time.Second):
			}
			continue
		}
		bodyBytes, readErr := io.ReadAll(io.LimitReader(resp.Body, 64*1024))
		resp.Body.Close()
		if readErr != nil {
			if ctx.Err() != nil {
				return
			}
			select {
			case <-ctx.Done():
				return
			case <-time.After(1 * time.Second):
			}
			continue
		}
		body := string(bodyBytes)

		errCode := 408
		if sm := reWxErrCode.FindStringSubmatch(body); len(sm) >= 2 {
			if n, convErr := strconv.Atoi(sm[1]); convErr == nil {
				errCode = n
			}
		}
		log.Printf("[ThirdLogin:wx] session=%s wx_errcode=%d", sess.ID[:6], errCode)

		switch errCode {
		case 408:
			// Timeout with no status change; keep waiting and re-issue long-poll.
			select {
			case <-ctx.Done():
				return
			case <-time.After(300 * time.Millisecond):
			}
		case 404:
			sess.wxLastCode = 404
			m.setWeChatState(sess, &QRPollResult{Status: "scanned", Stage: 1, Message: "已扫码，请在微信上点击确认登录"})
			select {
			case <-ctx.Done():
				return
			case <-time.After(100 * time.Millisecond):
			}
		case 403:
			sess.wxLastCode = 403
			m.setWeChatState(sess, &QRPollResult{Status: "cancelled", Stage: 7, Message: "已在微信取消登录"})
			return
		case 402, 500:
			m.setWeChatState(sess, &QRPollResult{Status: "expired", Stage: 5, Message: "微信二维码已过期，请刷新"})
			return
		case 405:
			codeMatch := reWxCode.FindStringSubmatch(body)
			if len(codeMatch) < 2 || codeMatch[1] == "" {
				m.setWeChatState(sess, &QRPollResult{Status: "failed", Stage: -1, Message: "微信未返回授权码，请重试"})
				return
			}
			m.setWeChatState(sess, &QRPollResult{Status: "scanned", Stage: 1, Message: "微信已确认，正在完成虎牙登录..."})
			sep := "?"
			if strings.Contains(sess.wxCallback, "?") {
				sep = "&"
			}
			cbURL := sess.wxCallback + sep + "code=" + url.QueryEscape(codeMatch[1])
			res, cbErr := m.finishUDBThirdCallback(ctx, sess, cbURL, "微信登录成功", false)
			if cbErr != nil {
				m.setWeChatState(sess, &QRPollResult{Status: "failed", Stage: -1, Message: "微信回调失败：" + cbErr.Error()})
			} else {
				m.setWeChatState(sess, res)
			}
			return
		default:
			select {
			case <-ctx.Done():
				return
			case <-time.After(1 * time.Second):
			}
		}
	}
}

func (m *ThirdLoginManager) pollWeChat(_ context.Context, sess *ThirdLoginSession) (*QRPollResult, error) {
	sess.wxMu.Lock()
	res := sess.wxState
	sess.wxMu.Unlock()
	if res == nil {
		return &QRPollResult{Status: "waiting", Stage: 0, Message: "请使用微信扫描二维码"}, nil
	}
	cp := *res
	// Remove session once terminal result is delivered to caller (keep alive during need_auth!).
	if cp.Status == "confirmed" || cp.Status == "expired" || cp.Status == "cancelled" || cp.Status == "failed" {
		m.Remove(sess.ID)
	}
	return &cp, nil
}

func (m *ThirdLoginManager) pollQQ(ctx context.Context, sess *ThirdLoginSession) (*QRPollResult, error) {
	params := url.Values{}
	params.Set("u1", qqLoginJumpURL)
	params.Set("ptqrtoken", sess.qqPtQRToken)
	params.Set("ptredirect", "0")
	params.Set("h", "1")
	params.Set("t", "1")
	params.Set("g", "1")
	params.Set("from_ui", "1")
	params.Set("ptlang", "2052")
	params.Set("action", fmt.Sprintf("0-0-%d", time.Now().UnixMilli()))
	params.Set("js_ver", "24090516")
	params.Set("js_type", "1")
	params.Set("login_sig", sess.qqLoginSig)
	params.Set("pt_uistyle", "40")
	params.Set("aid", qqPtAppID)
	params.Set("daid", qqPtDaid)
	params.Set("pt_3rd_aid", sess.qqClientID)

	pollURL := "https://ssl.ptlogin2.qq.com/ptqrlogin?" + params.Encode()
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, pollURL, nil)
	if err != nil {
		return nil, err
	}
	req.Header.Set("User-Agent", DefaultUserAgent)
	req.Header.Set("Referer", sess.qqXLoginURL)

	resp, err := sess.httpClient.Do(req)
	if err != nil {
		return nil, err
	}
	bodyBytes, readErr := io.ReadAll(io.LimitReader(resp.Body, 64*1024))
	resp.Body.Close()
	if readErr != nil {
		return nil, readErr
	}

	code, jumpURL, msg, _ := parsePtuiCB(string(bodyBytes))
	switch code {
	case "66":
		return &QRPollResult{Status: "waiting", Stage: 0, Message: "请使用手机 QQ 扫描二维码"}, nil
	case "67":
		return &QRPollResult{Status: "scanned", Stage: 1, Message: "已扫码，请在手机 QQ 上确认登录"}, nil
	case "65":
		m.Remove(sess.ID)
		return &QRPollResult{Status: "expired", Stage: 5, Message: "QQ 二维码已过期，请刷新"}, nil
	case "68":
		m.Remove(sess.ID)
		return &QRPollResult{Status: "cancelled", Stage: 7, Message: "已在手机 QQ 取消登录"}, nil
	case "0":
		if jumpURL == "" {
			m.Remove(sess.ID)
			return &QRPollResult{Status: "failed", Stage: -1, Message: "QQ 登录缺少跳转凭据，请重试"}, nil
		}
		return m.completeQQAuthorize(ctx, sess, jumpURL)
	default:
		if msg == "" {
			msg = fmt.Sprintf("QQ 登录状态异常 (%s)", code)
		}
		m.Remove(sess.ID)
		return &QRPollResult{Status: "failed", Stage: -1, Message: msg}, nil
	}
}

func (m *ThirdLoginManager) completeQQAuthorize(ctx context.Context, sess *ThirdLoginSession, checkSigURL string) (*QRPollResult, error) {
	// Step 1: Follow ptlogin check_sig to populate graph.qq.com p_skey cookie.
	sigReq, err := http.NewRequestWithContext(ctx, http.MethodGet, checkSigURL, nil)
	if err != nil {
		return nil, err
	}
	sigReq.Header.Set("User-Agent", DefaultUserAgent)
	sigReq.Header.Set("Referer", sess.qqXLoginURL)
	sigResp, err := sess.httpClient.Do(sigReq)
	if err != nil {
		m.Remove(sess.ID)
		return &QRPollResult{Status: "failed", Stage: -1, Message: "QQ 校验会话失败：" + err.Error()}, nil
	}
	_, _ = io.Copy(io.Discard, io.LimitReader(sigResp.Body, 128*1024))
	sigResp.Body.Close()

	graphURL, _ := url.Parse("https://graph.qq.com/")
	pSkey := findCookieValue(sess.jar, graphURL, "p_skey")
	if pSkey == "" {
		pSkey = findCookieValue(sess.jar, graphURL, "skey")
	}

	// Step 2: Submit QQ Connect OAuth2 authorization form to obtain Huya callback URL with code.
	form := url.Values{}
	form.Set("response_type", sess.qqResponseType)
	form.Set("client_id", sess.qqClientID)
	form.Set("redirect_uri", sess.qqRedirectURI)
	form.Set("scope", sess.qqScope)
	form.Set("state", sess.qqState)
	form.Set("switch", "")
	form.Set("from_ptlogin", "1")
	form.Set("src", "1")
	form.Set("update_auth", "1")
	form.Set("openapi", sess.qqOpenAPIs)
	form.Set("g_tk", strconv.Itoa(qqGTK(pSkey)))
	form.Set("auth_time", strconv.FormatInt(time.Now().UnixMilli(), 10))
	form.Set("ui", RandomHex(16))

	noRedirectClient := &http.Client{
		Jar:     sess.jar,
		Timeout: 15 * time.Second,
		CheckRedirect: func(req *http.Request, via []*http.Request) error {
			return http.ErrUseLastResponse
		},
	}
	authReq, err := http.NewRequestWithContext(ctx, http.MethodPost, "https://graph.qq.com/oauth2.0/authorize", strings.NewReader(form.Encode()))
	if err != nil {
		return nil, err
	}
	authReq.Header.Set("Content-Type", "application/x-www-form-urlencoded")
	authReq.Header.Set("User-Agent", DefaultUserAgent)
	authReq.Header.Set("Origin", "https://graph.qq.com")
	authReq.Header.Set("Referer", sess.qqShowURL)

	authResp, err := noRedirectClient.Do(authReq)
	if err != nil {
		m.Remove(sess.ID)
		return &QRPollResult{Status: "failed", Stage: -1, Message: "QQ 授权回调失败：" + err.Error()}, nil
	}
	authBodyBytes, _ := io.ReadAll(io.LimitReader(authResp.Body, 256*1024))
	authResp.Body.Close()

	callbackURL := authResp.Header.Get("Location")
	var jsonRes struct {
		Ret      int    `json:"ret"`
		Callback string `json:"callback"`
		Msg      string `json:"msg"`
	}
	if callbackURL == "" {
		if err := json.Unmarshal(authBodyBytes, &jsonRes); err == nil && jsonRes.Callback != "" {
			callbackURL = jsonRes.Callback
		}
	}
	if callbackURL == "" {
		normalized := strings.ReplaceAll(string(authBodyBytes), `\/`, `/`)
		normalized = strings.ReplaceAll(normalized, `&amp;`, `&`)
		if sm := reQQCallbackURL.FindStringSubmatch(normalized); len(sm) >= 2 {
			callbackURL = sm[1]
		}
	}
	if callbackURL == "" {
		m.Remove(sess.ID)
		log.Printf("[ThirdLogin:qq] authorize HTTP %d no callback (ret=%d msg=%q)", authResp.StatusCode, jsonRes.Ret, jsonRes.Msg)
		errMsg := "QQ 授权未返回虎牙回调地址，请重试"
		if jsonRes.Msg != "" {
			errMsg = "QQ 授权失败：" + jsonRes.Msg
		}
		return &QRPollResult{Status: "failed", Stage: -1, Message: errMsg}, nil
	}
	log.Printf("[ThirdLogin:qq] session=%s got Huya callback, completing UDB login", sess.ID[:6])
	return m.finishUDBThirdCallback(ctx, sess, callbackURL, "QQ 登录成功", true)
}

func (m *ThirdLoginManager) finishUDBThirdCallback(ctx context.Context, sess *ThirdLoginSession, callbackURL, successMsg string, removeNow bool) (*QRPollResult, error) {
	shouldRemove := removeNow
	defer func() {
		if shouldRemove {
			m.Remove(sess.ID)
		}
	}()

	cbReq, err := http.NewRequestWithContext(ctx, http.MethodGet, callbackURL, nil)
	if err != nil {
		return nil, err
	}
	cbReq.Header.Set("User-Agent", DefaultUserAgent)
	cbReq.Header.Set("Referer", "https://www.huya.com/")

	cbResp, err := sess.httpClient.Do(cbReq)
	if err != nil {
		return &QRPollResult{Status: "failed", Stage: -1, Message: "虎牙回调验证失败：" + err.Error()}, nil
	}
	cbBodyBytes, readErr := io.ReadAll(io.LimitReader(cbResp.Body, 512*1024))
	cbResp.Body.Close()
	if readErr != nil {
		return &QRPollResult{Status: "failed", Stage: -1, Message: "读取虎牙回调失败：" + readErr.Error()}, nil
	}

	exchangeRaw := ""
	if sm := reUdbExchange.FindStringSubmatch(string(cbBodyBytes)); len(sm) >= 2 {
		if dec, uErr := url.QueryUnescape(sm[1]); uErr == nil {
			exchangeRaw = dec
		} else {
			exchangeRaw = sm[1]
		}
	}

	var exchange udbThirdPayload
	if exchangeRaw != "" {
		if err := json.Unmarshal([]byte(exchangeRaw), &exchange); err != nil {
			return &QRPollResult{Status: "failed", Stage: -1, Message: "解析虎牙回调数据失败"}, nil
		}
		if res, handled := m.handleSecondAuthIfNeeded(sess, &exchange, successMsg); handled {
			shouldRemove = false
			return res, nil
		}
		if exchange.ReturnCode != 0 {
			reason := strings.TrimSpace(exchange.Description)
			if reason == "" {
				reason = strings.TrimSpace(exchange.Message)
			}
			if reason == "" {
				reason = fmt.Sprintf("错误码 %d（可能需要先在虎牙官网/App 完成该账号绑定）", exchange.ReturnCode)
			}
			log.Printf("[ThirdLogin:%s] callback error returnCode=%d strategys=%d desc=%q",
				sess.Provider, exchange.ReturnCode, len(exchange.Data.Strategys), reason)
			return &QRPollResult{Status: "failed", Stage: -1, Message: "虎牙授权失败：" + reason}, nil
		}
	}

	return m.finalizeUDBLoginData(ctx, sess, &exchange, cbResp.StatusCode, len(exchangeRaw), successMsg)
}

type flexInt int

func (f *flexInt) UnmarshalJSON(b []byte) error {
	s := strings.TrimSpace(string(b))
	if s == "" || s == "null" {
		*f = 0
		return nil
	}
	if len(s) >= 2 && s[0] == '"' && s[len(s)-1] == '"' {
		var str string
		if err := json.Unmarshal(b, &str); err != nil {
			return err
		}
		str = strings.TrimSpace(str)
		if str == "" {
			*f = 0
			return nil
		}
		n, err := strconv.Atoi(str)
		if err != nil {
			return err
		}
		*f = flexInt(n)
		return nil
	}
	var n int
	if err := json.Unmarshal(b, &n); err == nil {
		*f = flexInt(n)
		return nil
	}
	var fl float64
	if err := json.Unmarshal(b, &fl); err != nil {
		return err
	}
	*f = flexInt(int(fl))
	return nil
}

type udbStrategyRaw struct {
	Strategy      flexInt `json:"strategy"`
	DataType      flexInt `json:"dataType"`
	SelectTitle   string  `json:"selectTitle"`
	UID           any     `json:"uid"`
	Data          string  `json:"data"`
	PromptContent string  `json:"promptContent"`
	SessionData   string  `json:"sessionData"`
}

type udbThirdPayload struct {
	ReturnCode  flexInt `json:"returnCode"`
	Message     string  `json:"message"`
	Description string  `json:"description"`
	Data        struct {
		UID           any              `json:"uid"`
		BizToken      string           `json:"biztoken"`
		Cred          string           `json:"cred"`
		NickName      string           `json:"nickName"`
		Passport      string           `json:"passport"`
		SessionData   string           `json:"sessionData"`
		CallbackURL   string           `json:"callbackUrl"`
		Strategys     []udbStrategyRaw `json:"strategys"`
		DomainURLList []string         `json:"domainUrlList"`
	} `json:"data"`
}

func isOfficialHuyaSafeURL(raw string) bool {
	u, err := url.Parse(strings.TrimSpace(raw))
	if err != nil || u.Scheme != "https" || u.Hostname() == "" {
		return false
	}
	h := strings.ToLower(u.Hostname())
	allowed := []string{"huya.com", "yy.com", "huyakeji.com", "msstatic.com"}
	for _, dom := range allowed {
		if h == dom || strings.HasSuffix(h, "."+dom) {
			return true
		}
	}
	return false
}

func appendQueryParam(rawURL, key, val string) string {
	sep := "?"
	if strings.Contains(rawURL, "?") {
		sep = "&"
	}
	return rawURL + sep + url.QueryEscape(key) + "=" + url.QueryEscape(val)
}

func setUDBSessionCookie(jar *cookiejar.Jar, sessionData string) {
	if jar == nil || strings.TrimSpace(sessionData) == "" {
		return
	}
	u, err := url.Parse("https://udb3lgn.huya.com/")
	if err != nil {
		return
	}
	jar.SetCookies(u, []*http.Cookie{{
		Name:   "udb_wession",
		Value:  strings.TrimSpace(sessionData),
		Domain: ".huya.com",
		Path:   "/",
	}})
}

func (m *ThirdLoginManager) handleSecondAuthIfNeeded(sess *ThirdLoginSession, payload *udbThirdPayload, successMsg string) (*QRPollResult, bool) {
	rc := int(payload.ReturnCode)
	if rc != 10030 && rc != 10039 && rc != 70001 && rc != 2001 && rc != 60043 {
		return nil, false
	}

	sessionData := strings.TrimSpace(payload.Data.SessionData)
	callbackURL := strings.TrimSpace(payload.Data.CallbackURL)
	if callbackURL != "" && !isOfficialHuyaSafeURL(callbackURL) {
		callbackURL = ""
	}
	uid := parseFlexibleInt64(payload.Data.UID)
	for _, st := range payload.Data.Strategys {
		if uid <= 0 {
			uid = parseFlexibleInt64(st.UID)
		}
		if sessionData == "" && strings.TrimSpace(st.SessionData) != "" {
			sessionData = strings.TrimSpace(st.SessionData)
		}
	}
	if sessionData != "" {
		setUDBSessionCookie(sess.jar, sessionData)
	}

	// 1) Official Huya SafeAuth iframe strategy (dataType == 4, used by Huya's ThirdLogin.prototype._openSafeAuth).
	for _, st := range payload.Data.Strategys {
		dt := int(st.DataType)
		safeURL := strings.TrimSpace(st.Data)
		if dt == 4 && isOfficialHuyaSafeURL(safeURL) {
			title := strings.TrimSpace(st.SelectTitle)
			if title == "" {
				title = "虎牙安全验证"
			}
			msg := strings.TrimSpace(payload.Description)
			if msg == "" || strings.HasPrefix(msg, "http://") || strings.HasPrefix(msg, "https://") {
				msg = "虎牙要求进一步安全认证，请在下方完成验证"
			}
			sess.authMu.Lock()
			sess.authUID = uid
			sess.authSessionData = sessionData
			sess.authCallbackURL = callbackURL
			sess.authStrategies = []ThirdAuthStrategy{{
				Strategy:    int(st.Strategy),
				DataType:    dt,
				SelectTitle: title,
				SafeAuthURL: safeURL,
			}}
			sess.authSuccessMsg = successMsg
			sess.CreatedAt = time.Now()
			sess.authMu.Unlock()

			log.Printf("[ThirdLogin:%s] returnCode=%d requires SafeAuth iframe strategy=%d dataType=%d hasCallback=%v",
				sess.Provider, rc, int(st.Strategy), dt, callbackURL != "")
			return &QRPollResult{
				Status:  "need_auth",
				Stage:   9,
				Message: msg,
				UID:     uid,
				Auth: &ThirdAuthChallenge{
					SessionID:     sess.ID,
					UID:           uid,
					Mode:          "safe_auth",
					Message:       msg,
					SafeAuthURL:   safeURL,
					SafeAuthTitle: title,
					HasCallback:   callbackURL != "",
					Strategies:    sess.authStrategies,
				},
			}, true
		}
	}

	// 2) URL-in-description SafeAuth / ThirdBind / Upgrade iframe (returnCode 10030 / 2001 / 60043).
	descURL := strings.TrimSpace(payload.Description)
	if isOfficialHuyaSafeURL(descURL) {
		title := "虎牙安全验证"
		msg := "虎牙要求安全验证，请在下方完成验证"
		if rc == 2001 {
			title = "绑定虎牙账号"
			msg = "该微信/QQ 需先完成虎牙账号绑定"
		} else if rc == 60043 {
			title = "账号安全升级"
			msg = "虎牙提示需完成账号安全升级后登录"
		}
		sess.authMu.Lock()
		sess.authUID = uid
		sess.authSessionData = sessionData
		sess.authCallbackURL = callbackURL
		sess.authStrategies = nil
		sess.authSuccessMsg = successMsg
		sess.CreatedAt = time.Now()
		sess.authMu.Unlock()

		log.Printf("[ThirdLogin:%s] returnCode=%d requires description-URL iframe hasCallback=%v",
			sess.Provider, rc, callbackURL != "")
		return &QRPollResult{
			Status:  "need_auth",
			Stage:   9,
			Message: msg,
			UID:     uid,
			Auth: &ThirdAuthChallenge{
				SessionID:     sess.ID,
				UID:           uid,
				Mode:          "safe_auth",
				Message:       msg,
				SafeAuthURL:   descURL,
				SafeAuthTitle: title,
				HasCallback:   callbackURL != "",
			},
		}, true
	}

	// 3) Classic UDB second-factor code strategies (4=image, 8=SMS, 16=YY token).
	if rc != 10030 && rc != 10039 && rc != 70001 {
		return nil, false
	}
	var strategies []ThirdAuthStrategy
	var seenMeta []string
	for _, st := range payload.Data.Strategys {
		code := int(st.Strategy)
		seenMeta = append(seenMeta, fmt.Sprintf("s=%d/dt=%d", code, int(st.DataType)))
		if code != 4 && code != 8 && code != 16 {
			continue
		}
		item := ThirdAuthStrategy{
			Strategy:      code,
			DataType:      int(st.DataType),
			PromptContent: strings.TrimSpace(st.PromptContent),
		}
		if code == 4 {
			item.ImageData = strings.TrimSpace(st.Data)
		}
		strategies = append(strategies, item)
	}
	if sessionData == "" || len(strategies) == 0 {
		log.Printf("[ThirdLogin:%s] returnCode=%d unhandled strategys=[%s] hasSession=%v hasCallback=%v",
			sess.Provider, rc, strings.Join(seenMeta, ","), sessionData != "", callbackURL != "")
		return nil, false
	}

	sess.authMu.Lock()
	sess.authUID = uid
	sess.authSessionData = sessionData
	sess.authCallbackURL = callbackURL
	sess.authStrategies = strategies
	sess.authSuccessMsg = successMsg
	sess.CreatedAt = time.Now() // extend session window for second-factor verification
	sess.authMu.Unlock()

	msg := strings.TrimSpace(payload.Description)
	if msg == "" {
		msg = "需要进一步安全验证"
	}
	log.Printf("[ThirdLogin:%s] returnCode=%d requires second auth uid=%d strategies=%d",
		sess.Provider, rc, uid, len(strategies))

	return &QRPollResult{
		Status:  "need_auth",
		Stage:   9,
		Message: msg,
		UID:     uid,
		Auth: &ThirdAuthChallenge{
			SessionID:  sess.ID,
			UID:        uid,
			Mode:       "code",
			Message:    msg,
			Strategies: strategies,
		},
	}, true
}

// CompleteSafeAuth finishes a third-party login after the user completes Huya's official SafeAuth iframe
// and the iframe emits postMessage { eventType: "udbSafeAuth", param: { data: { authId } } }.
func (m *ThirdLoginManager) CompleteSafeAuth(ctx context.Context, sessionID, authID string) (*QRPollResult, error) {
	m.mu.Lock()
	sess, ok := m.sessions[sessionID]
	m.mu.Unlock()
	if !ok {
		return nil, fmt.Errorf("验证会话已过期，请重新扫码")
	}

	authID = strings.TrimSpace(authID)
	sess.authMu.Lock()
	callbackURL := sess.authCallbackURL
	successMsg := sess.authSuccessMsg
	sess.authMu.Unlock()
	if successMsg == "" {
		successMsg = "第三方登录成功"
	}

	// If Huya did not provide a callbackUrl (e.g. description-URL SafeAuth), completing the iframe
	// clears the risk-control flag and the user simply scans the QR code once more.
	if callbackURL == "" {
		m.Remove(sess.ID)
		return &QRPollResult{
			Status:  "re_scan",
			Stage:   0,
			Message: "安全验证已通过，请重新扫码完成登录",
		}, nil
	}
	if authID == "" {
		return nil, fmt.Errorf("未收到虎牙安全验证凭据 (authId)，请重试")
	}
	nextURL := appendQueryParam(callbackURL, "authId", authID)
	log.Printf("[ThirdLogin:%s] session=%s SafeAuth completed, finishing Huya callback", sess.Provider, sess.ID[:min(6, len(sess.ID))])
	return m.finishUDBThirdCallback(ctx, sess, nextURL, successMsg, true)
}

// SendAuthCode triggers an SMS OTP (/web/v2/smscode) or refreshes an image challenge (/web/v2/imagecode)
// for a third-party session awaiting UDB second-factor verification.
func (m *ThirdLoginManager) SendAuthCode(ctx context.Context, sessionID string, strategy int) (map[string]any, error) {
	m.mu.Lock()
	sess, ok := m.sessions[sessionID]
	m.mu.Unlock()
	if !ok {
		return nil, fmt.Errorf("验证会话已过期，请重新扫码")
	}

	sess.authMu.Lock()
	uid := sess.authUID
	sessionData := sess.authSessionData
	sess.authMu.Unlock()
	if sessionData == "" {
		return nil, fmt.Errorf("缺少二次验证会话，请重新扫码")
	}

	var path, uri string
	switch strategy {
	case 8:
		path, uri = "/web/v2/smscode", "40020"
	case 4:
		path, uri = "/web/v2/imagecode", "40022"
	default:
		return nil, fmt.Errorf("不支持的验证码方式: %d", strategy)
	}

	var resp udbResponse
	if err := postUDB(ctx, sess.httpClient, path, udbEnvelope{
		URI:       uri,
		Version:   udbVersion,
		Context:   "WB---",
		AppID:     udbAppID,
		LCID:      udbLCID,
		ByPass:    udbByPass,
		SDID:      "0",
		RequestID: strconv.FormatInt(time.Now().UnixMilli(), 10),
		Data: map[string]string{
			"uid":         strconv.FormatInt(uid, 10),
			"sessionData": sessionData,
		},
	}, &resp); err != nil {
		return nil, err
	}

	var data struct {
		SessionData string           `json:"sessionData"`
		Data        string           `json:"data"`
		Image       string           `json:"image"`
		Strategys   []udbStrategyRaw `json:"strategys"`
	}
	_ = json.Unmarshal(resp.Data, &data)
	if strings.TrimSpace(data.SessionData) != "" {
		sess.authMu.Lock()
		sess.authSessionData = strings.TrimSpace(data.SessionData)
		sess.authMu.Unlock()
	}

	if resp.ReturnCode != 0 {
		reason := strings.TrimSpace(resp.Description)
		if reason == "" {
			reason = strings.TrimSpace(resp.Message)
		}
		if reason == "" {
			reason = fmt.Sprintf("错误码 %d", resp.ReturnCode)
		}
		return nil, fmt.Errorf("获取验证码失败：%s", reason)
	}

	img := strings.TrimSpace(data.Data)
	if img == "" {
		img = strings.TrimSpace(data.Image)
	}
	if img == "" && len(data.Strategys) > 0 {
		img = strings.TrimSpace(data.Strategys[0].Data)
	}
	if strategy == 8 {
		return map[string]any{"message": "二次验证短信已发送至绑定手机，请查收"}, nil
	}
	return map[string]any{"message": "验证码已刷新", "imageData": img}, nil
}

// VerifyAuthCode submits the user-entered second-factor code (SMS / image / token) to finish UDB login.
func (m *ThirdLoginManager) VerifyAuthCode(ctx context.Context, sessionID string, strategy int, authCode string) (*QRPollResult, error) {
	m.mu.Lock()
	sess, ok := m.sessions[sessionID]
	m.mu.Unlock()
	if !ok {
		return nil, fmt.Errorf("验证会话已过期，请重新扫码")
	}

	authCode = strings.TrimSpace(authCode)
	if authCode == "" {
		return nil, fmt.Errorf("请输入验证码")
	}

	sess.authMu.Lock()
	uid := sess.authUID
	sessionData := sess.authSessionData
	successMsg := sess.authSuccessMsg
	sess.authMu.Unlock()
	if sessionData == "" {
		return nil, fmt.Errorf("缺少二次验证会话，请重新扫码")
	}
	if successMsg == "" {
		successMsg = "第三方登录成功"
	}

	var path, uri string
	switch strategy {
	case 4:
		path, uri = "/web/v2/antiViolentLogin", "40001"
	case 8:
		path, uri = "/web/v2/secondAuthLogin", "40003"
	case 16:
		path, uri = "/web/v2/secondAuthLogin", "40005"
	default:
		return nil, fmt.Errorf("不支持的验证策略: %d", strategy)
	}

	var rawResp udbResponse
	if err := postUDB(ctx, sess.httpClient, path, udbEnvelope{
		URI:       uri,
		Version:   udbVersion,
		Context:   "WB---",
		AppID:     udbAppID,
		LCID:      udbLCID,
		ByPass:    udbByPass,
		SDID:      "0",
		RequestID: strconv.FormatInt(time.Now().UnixMilli(), 10),
		Data: map[string]string{
			"strategy":    strconv.Itoa(strategy),
			"uid":         strconv.FormatInt(uid, 10),
			"authcode":    authCode,
			"sessionData": sessionData,
			"remember":    "1",
		},
	}, &rawResp); err != nil {
		return nil, err
	}

	var payload udbThirdPayload
	payload.ReturnCode = flexInt(rawResp.ReturnCode)
	payload.Message = rawResp.Message
	payload.Description = rawResp.Description
	_ = json.Unmarshal(rawResp.Data, &payload.Data)

	if res, handled := m.handleSecondAuthIfNeeded(sess, &payload, successMsg); handled {
		return res, nil
	}
	if payload.ReturnCode != 0 {
		reason := strings.TrimSpace(payload.Description)
		if reason == "" {
			reason = strings.TrimSpace(payload.Message)
		}
		if reason == "" {
			reason = fmt.Sprintf("错误码 %d", payload.ReturnCode)
		}
		return &QRPollResult{Status: "need_auth", Stage: 9, Message: "验证未通过：" + reason}, nil
	}

	defer m.Remove(sess.ID)
	return m.finalizeUDBLoginData(ctx, sess, &payload, http.StatusOK, len(rawResp.Data), successMsg)
}

func (m *ThirdLoginManager) finalizeUDBLoginData(ctx context.Context, sess *ThirdLoginSession, exchange *udbThirdPayload, httpStatus, payloadLen int, successMsg string) (*QRPollResult, error) {
	// Follow domainUrlList so .huya.com cookies are populated in the session cookie jar.
	for _, u := range exchange.Data.DomainURLList {
		if strings.HasPrefix(u, "http://") || strings.HasPrefix(u, "https://") {
			r, rErr := http.NewRequestWithContext(ctx, http.MethodGet, u, nil)
			if rErr == nil {
				r.Header.Set("User-Agent", DefaultUserAgent)
				r.Header.Set("Referer", "https://www.huya.com/")
				if resp, doErr := sess.httpClient.Do(r); doErr == nil {
					_, _ = io.Copy(io.Discard, io.LimitReader(resp.Body, 64*1024))
					resp.Body.Close()
				}
			}
		}
	}

	huyaURL, _ := url.Parse("https://www.huya.com/")
	cookies := sess.jar.Cookies(huyaURL)
	cookieMap := make(map[string]string, len(cookies)+4)
	for _, ck := range cookies {
		if ck.Value != "" {
			cookieMap[ck.Name] = ck.Value
		}
	}

	uid := parseFlexibleInt64(exchange.Data.UID)
	if uid <= 0 {
		uid = parseFlexibleInt64(cookieMap["udb_uid"])
	}
	if uid <= 0 {
		uid = parseFlexibleInt64(cookieMap["yyuid"])
	}
	log.Printf("[ThirdLogin:%s] finalize HTTP %d payloadLen=%d returnCode=%d uid=%d cookies=%d hasCred=%v",
		sess.Provider, httpStatus, payloadLen, exchange.ReturnCode, uid, len(cookieMap), exchange.Data.Cred != "" || cookieMap["udb_cred"] != "")
	if uid <= 0 {
		return &QRPollResult{Status: "failed", Stage: -1, Message: "未获取到虎牙 UID，请确认该微信/QQ 已绑定虎牙账号"}, nil
	}

	uidStr := strconv.FormatInt(uid, 10)
	if cookieMap["udb_uid"] == "" {
		cookieMap["udb_uid"] = uidStr
	}
	if cookieMap["yyuid"] == "" {
		cookieMap["yyuid"] = uidStr
	}
	if exchange.Data.BizToken != "" && cookieMap["udb_biztoken"] == "" {
		cookieMap["udb_biztoken"] = exchange.Data.BizToken
	}
	if exchange.Data.Passport != "" && cookieMap["udb_passport"] == "" {
		cookieMap["udb_passport"] = exchange.Data.Passport
	}

	credB64 := strings.TrimSpace(exchange.Data.Cred)
	if credB64 == "" {
		rawCred := cookieMap["udb_cred"]
		if rawCred == "" {
			for _, hostURL := range []string{"https://udb3lgn.huya.com/", "https://udblgn.huya.com/"} {
				if u, err := url.Parse(hostURL); err == nil {
					if v := findCookieValue(sess.jar, u, "udb_cred"); v != "" {
						rawCred = v
						break
					}
				}
			}
		}
		if rawCred != "" {
			if dec, err := url.QueryUnescape(rawCred); err == nil {
				credB64 = dec
			} else {
				credB64 = rawCred
			}
		}
	}

	cookieParts := make([]string, 0, len(cookieMap))
	for k, v := range cookieMap {
		cookieParts = append(cookieParts, k+"="+v)
	}
	guid := RandomHex(16)
	creds := &Credentials{
		UID:    uid,
		Cookie: strings.Join(cookieParts, "; "),
		Token:  "",
		GUID:   guid,
		HuYaUA: DefaultWebHuYaUA,
	}

	// Best-effort auto-upgrade: if Huya returned a persistent udb_cred, try exchanging it
	// for Android 5008 credentials so the account also gets 12h silent auto-renewal.
	if credB64 != "" && m.MobileAuth != nil {
		uCtx, cancel := context.WithTimeout(ctx, 8*time.Second)
		upgraded, uErr := m.MobileAuth.RefreshMobileToken(uCtx, Credentials{
			UID:  uid,
			Cred: credB64,
			GUID: guid,
		})
		cancel()
		if uErr == nil && upgraded.IsMobile() {
			upgraded.Cookie = creds.Cookie
			creds = &upgraded
			successMsg += "（已自动开启长期续期）"
		} else if uErr != nil {
			log.Printf("[ThirdLogin:%s] 5008 cred upgrade skipped: %v", sess.Provider, uErr)
		}
	}

	return &QRPollResult{
		Status:      "confirmed",
		Stage:       2,
		Message:     successMsg,
		UID:         uid,
		NickName:    strings.TrimSpace(exchange.Data.NickName),
		Credentials: creds,
	}, nil
}

func (m *ThirdLoginManager) Remove(sessionID string) {
	m.mu.Lock()
	if s, ok := m.sessions[sessionID]; ok {
		if s.wxCancel != nil {
			s.wxCancel()
		}
		delete(m.sessions, sessionID)
	}
	m.mu.Unlock()
}

func (m *ThirdLoginManager) cleanupExpired() {
	m.mu.Lock()
	defer m.mu.Unlock()
	cutoff := time.Now().Add(-5 * time.Minute)
	for id, s := range m.sessions {
		if s.CreatedAt.Before(cutoff) {
			if s.wxCancel != nil {
				s.wxCancel()
			}
			delete(m.sessions, id)
		}
	}
}

func buildUDB3SigninURL(provider string) string {
	params := url.Values{}
	params.Set("uri", "30003")
	params.Set("requestId", strconv.FormatInt(time.Now().UnixMilli(), 10))
	params.Set("page", "https://www.huya.com/")
	params.Set("appid", udbAppID)
	params.Set("byPass", udbByPass)
	params.Set("domain", "huya.com")
	params.Set("domainList", "huya.com")
	params.Set("exchange", "0")
	params.Set("remember", "1")
	params.Set("writeCookie", "1")
	params.Set("type", provider)
	params.Set("style", "1")
	params.Set("win", "1")
	return udb3BaseURL + "/web/v2/signin?" + params.Encode()
}

func findCookieValue(jar http.CookieJar, u *url.URL, name string) string {
	if jar == nil || u == nil {
		return ""
	}
	for _, ck := range jar.Cookies(u) {
		if ck.Name == name && ck.Value != "" {
			return ck.Value
		}
	}
	return ""
}

func qqHash33(s string) int {
	e := 0
	for i := 0; i < len(s); i++ {
		e += (e << 5) + int(s[i])
	}
	return e & 0x7fffffff
}

func qqGTK(skey string) int {
	h := 5381
	for i := 0; i < len(skey); i++ {
		h += (h << 5) + int(skey[i])
	}
	return h & 0x7fffffff
}

func parsePtuiCB(raw string) (code, jumpURL, msg, nick string) {
	sm := rePtuiCB.FindStringSubmatch(raw)
	if len(sm) < 2 {
		return "", "", "", ""
	}
	args := rePtuiArg.FindAllStringSubmatch(sm[1], -1)
	get := func(i int) string {
		if i < len(args) && len(args[i]) >= 2 {
			return args[i][1]
		}
		return ""
	}
	return get(0), get(2), get(4), get(5)
}
