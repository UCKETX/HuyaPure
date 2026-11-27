package huya

import (
	"encoding/json"
	"net/http/cookiejar"
	"net/url"
	"testing"
)

func TestQQHelpers(t *testing.T) {
	if got := qqHash33("abc"); got <= 0 {
		t.Fatalf("unexpected qqHash33: %d", got)
	}
	if got := qqGTK("@123456789"); got <= 0 {
		t.Fatalf("unexpected qqGTK: %d", got)
	}
	code, jump, msg, nick := parsePtuiCB(`ptuiCB('0','0','https://ssl.ptlogin2.graph.qq.com/check_sig?foo=1','0','登录成功！', '测试用户')`)
	if code != "0" || jump != "https://ssl.ptlogin2.graph.qq.com/check_sig?foo=1" || msg != "登录成功！" || nick != "测试用户" {
		t.Fatalf("unexpected parsePtuiCB result: code=%q jump=%q msg=%q nick=%q", code, jump, msg, nick)
	}
}

func TestHandleSecondAuthChallenge(t *testing.T) {
	mgr := NewThirdLoginManager(nil)
	sess := &ThirdLoginSession{ID: "sess123456", Provider: "wx"}
	var payload udbThirdPayload
	payload.ReturnCode = 10030
	payload.Description = "需要进一步认证"
	payload.Data.SessionData = "enc-session-token"
	payload.Data.Strategys = []udbStrategyRaw{
		{Strategy: 8, UID: float64(12345678), PromptContent: "138****1234"},
	}

	res, handled := mgr.handleSecondAuthIfNeeded(sess, &payload, "微信登录成功")
	if !handled {
		t.Fatal("expected 10030 with strategy=8 to be handled as need_auth")
	}
	if res.Status != "need_auth" || res.Auth == nil || res.Auth.Mode != "code" || len(res.Auth.Strategies) != 1 {
		t.Fatalf("unexpected need_auth result: %+v", res)
	}
	if res.Auth.Strategies[0].Strategy != 8 || res.Auth.Strategies[0].PromptContent != "138****1234" {
		t.Fatalf("unexpected strategy: %+v", res.Auth.Strategies[0])
	}
	if sess.authUID != 12345678 || sess.authSessionData != "enc-session-token" {
		t.Fatalf("session auth state not saved: uid=%d hasSession=%v", sess.authUID, sess.authSessionData != "")
	}
}

func TestHandleSecondAuthSafeAuthIframe(t *testing.T) {
	jar, _ := cookiejar.New(nil)
	mgr := NewThirdLoginManager(nil)
	sess := &ThirdLoginSession{ID: "sesssafe01", Provider: "wx", jar: jar}

	raw := `{
		"returnCode": "10030",
		"description": "需要进一步认证",
		"data": {
			"sessionData": "wession-xyz",
			"callbackUrl": "https://udb3lgn.huya.com/web/v2/callback?state=abc",
			"strategys": [
				{
					"strategy": "256",
					"dataType": "4",
					"selectTitle": "安全验证",
					"data": "https://aq.huya.com/safe/auth.html?token=123",
					"uid": "99887766"
				}
			]
		}
	}`
	var payload udbThirdPayload
	if err := json.Unmarshal([]byte(raw), &payload); err != nil {
		t.Fatalf("unmarshal string returnCode/dataType failed: %v", err)
	}

	res, handled := mgr.handleSecondAuthIfNeeded(sess, &payload, "微信登录成功")
	if !handled {
		t.Fatal("expected dataType=4 SafeAuth to be handled")
	}
	if res.Status != "need_auth" || res.Auth == nil || res.Auth.Mode != "safe_auth" {
		t.Fatalf("unexpected SafeAuth result: %+v", res)
	}
	if res.Auth.SafeAuthURL != "https://aq.huya.com/safe/auth.html?token=123" || !res.Auth.HasCallback {
		t.Fatalf("unexpected SafeAuth fields: %+v", res.Auth)
	}
	if sess.authCallbackURL != "https://udb3lgn.huya.com/web/v2/callback?state=abc" {
		t.Fatalf("callbackURL not saved: %q", sess.authCallbackURL)
	}
	u, _ := url.Parse("https://udb3lgn.huya.com/")
	if got := findCookieValue(jar, u, "udb_wession"); got != "wession-xyz" {
		t.Fatalf("expected udb_wession cookie to be set, got %q", got)
	}
	if isOfficialHuyaSafeURL("https://evil.example.com/auth") {
		t.Fatal("must reject non-Huya safe auth URL")
	}
	if got := appendQueryParam("https://udb3lgn.huya.com/web/v2/callback?state=1", "authId", "a+b"); got != "https://udb3lgn.huya.com/web/v2/callback?state=1&authId=a%2Bb" {
		t.Fatalf("unexpected appendQueryParam: %q", got)
	}
}
