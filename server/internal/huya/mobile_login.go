package huya

import (
	"bytes"
	"context"
	"crypto/md5"
	"crypto/tls"
	"encoding/base64"
	"encoding/hex"
	"fmt"
	"io"
	"net/http"
	"strings"
	"sync/atomic"
	"time"

	"github.com/UCKETX/HuyaPure/server/internal/jce"
)

const (
	// Huya Android official UDB identity (from com.duowan.kiwi AndroidManifest HY_APPID / HY_APPKEY).
	MobileAppID      = "5008"
	MobileAppKey     = "865a4924a40897ac1fcfe6b4c2cbb0e3"
	MobileAppVersion = "13.5.62"
	MobileChannel    = "dev"
	MobileDeviceName = "SM-S9280"
	MobileOSVersion  = "15"

	udbServantName      = "huyaudbwebui"
	udbPathSendSMS      = "/open/sms/login/code"
	udbPathSMSLogin     = "/open/sms/login"
	udbPathCredLogin    = "/open/hy/credLogin"
	udbFuncSendSMS      = "hyMobileSendSms"
	udbFuncCredLogin    = "hycredLogin"
	udbWupDataKey       = "_wup_data"
	udbMobileTokenTTL   = 12 * time.Hour
)

// MobileLoginResult is returned after a successful Android 5008 SMS login.
type MobileLoginResult struct {
	UID         int64
	Passport    string
	Credentials Credentials
}

// MobileAuthClient speaks Huya Android's native WUP UDB protocol (appId=5008)
// directly against https://udblgn.huya.com/open/...
type MobileAuthClient struct {
	BaseURL    string
	HTTPClient *http.Client
	reqSeq     atomic.Int32
}

func NewMobileAuthClient() *MobileAuthClient {
	return &MobileAuthClient{
		BaseURL: udbBaseURL,
		HTTPClient: &http.Client{
			Timeout: 15 * time.Second,
			Transport: &http.Transport{
				TLSClientConfig:     &tls.Config{NextProtos: []string{"http/1.1"}},
				TLSHandshakeTimeout: 10 * time.Second,
				ForceAttemptHTTP2:   false,
			},
		},
	}
}

func mobileAppSign() string {
	sum := md5.Sum([]byte(MobileAppID + "_" + MobileAppVersion + "_" + MobileAppKey))
	return hex.EncodeToString(sum[:])
}

func buildUDBRequestHeader(deviceID string) []byte {
	hdr := jce.NewWriter()
	hdr.WriteInt(0, 0)
	hdr.WriteString(1, "1.0")
	hdr.WriteString(2, fmt.Sprintf("%d", time.Now().UnixMilli()))
	hdr.WriteString(3, MobileAppID)
	hdr.WriteInt(4, 2052)
	hdr.WriteString(5, deviceID)
	hdr.WriteString(6, "")
	hdr.WriteString(7, "")
	hdr.WriteString(8, "")
	hdr.WriteString(9, "")
	return hdr.Bytes()
}

func buildUDBProtoInfo() []byte {
	p := jce.NewWriter()
	p.WriteString(0, mobileAppSign())
	p.WriteString(1, MobileAppVersion)
	p.WriteString(2, "")
	p.WriteString(3, "2052")
	p.WriteString(4, "127.0.0.1")
	p.WriteString(5, MobileChannel)
	p.WriteString(6, "CN")
	return p.Bytes()
}

func buildUDBDeviceInfo(deviceID string) []byte {
	d := jce.NewWriter()
	d.WriteInt(0, 1) // Android terminal type
	d.WriteString(1, MobileOSVersion)
	d.WriteString(2, MobileDeviceName)
	d.WriteString(3, "1080*2400")
	d.WriteString(4, deviceID)
	d.WriteString(6, "WIFI")
	d.WriteString(7, "")
	d.WriteString(8, "")
	return d.Bytes()
}

// NormalizeMobile strips spaces/dashes and normalizes mainland China mobile inputs.
func NormalizeMobile(raw string) string {
	s := strings.TrimSpace(raw)
	s = strings.ReplaceAll(s, " ", "")
	s = strings.ReplaceAll(s, "-", "")
	s = strings.TrimPrefix(s, "+86")
	s = strings.TrimPrefix(s, "0086")
	return s
}

// SendMobileSMS requests a Huya Android (5008) login verification SMS for the given phone number.
// Returns the deviceID to reuse during LoginWithMobileSMS.
func (c *MobileAuthClient) SendMobileSMS(ctx context.Context, mobile, deviceID string) (string, error) {
	phone := NormalizeMobile(mobile)
	if len(phone) < 7 {
		return "", fmt.Errorf("请输入有效的手机号码")
	}
	if deviceID == "" {
		deviceID = RandomHex(16)
	}

	req := jce.NewWriter()
	req.WriteStructRaw(0, buildUDBRequestHeader(deviceID))
	req.WriteStructRaw(1, buildUDBProtoInfo())
	req.WriteStructRaw(2, buildUDBDeviceInfo(deviceID))
	req.WriteString(3, phone)
	req.WriteString(4, "")
	req.WriteBytes(5, []byte{})
	req.WriteInt(6, 0)
	req.WriteInt(7, 0)
	req.WriteStringMap(8, map[string]string{})

	sv, err := c.callUDB(ctx, udbPathSendSMS, udbFuncSendSMS, req.Bytes())
	if err != nil {
		return deviceID, err
	}
	if err := checkUDBRespHeader(sv); err != nil {
		return deviceID, err
	}
	return deviceID, nil
}

// LoginWithMobileSMS verifies the SMS code and exchanges it for genuine Android 5008
// udb_biztoken + udb_cred credentials.
func (c *MobileAuthClient) LoginWithMobileSMS(ctx context.Context, mobile, smsCode, deviceID string) (*MobileLoginResult, error) {
	phone := NormalizeMobile(mobile)
	code := strings.TrimSpace(smsCode)
	if len(phone) < 7 {
		return nil, fmt.Errorf("请输入有效的手机号码")
	}
	if len(code) < 4 {
		return nil, fmt.Errorf("请输入短信验证码")
	}
	if deviceID == "" {
		deviceID = RandomHex(16)
	}

	req := jce.NewWriter()
	req.WriteStructRaw(0, buildUDBRequestHeader(deviceID))
	req.WriteStructRaw(1, buildUDBProtoInfo())
	req.WriteStructRaw(2, buildUDBDeviceInfo(deviceID))
	req.WriteString(3, phone)
	req.WriteString(4, code)
	req.WriteStringList(5, []string{MobileAppID})
	req.WriteInt8(6, 1) // remember / issue udb_cred
	req.WriteStringMap(7, map[string]string{})
	req.WriteBytes(8, []byte{})
	req.WriteString(9, "")
	req.WriteStringMap(10, map[string]string{})
	req.WriteInt(11, 0)
	req.WriteLong(12, 0)
	req.WriteString(13, "")

	sv, err := c.callUDB(ctx, udbPathSMSLogin, udbFuncSendSMS, req.Bytes())
	if err != nil {
		return nil, err
	}
	if err := checkUDBRespHeader(sv); err != nil {
		return nil, err
	}
	return parseAppLoginData(sv, deviceID)
}

// RefreshMobileToken exchanges a stored Android 5008 udb_cred for a fresh 5008 biztoken
// via /open/hy/credLogin so mobile sessions stay alive indefinitely without re-login.
func (c *MobileAuthClient) RefreshMobileToken(ctx context.Context, creds Credentials) (Credentials, error) {
	if creds.UID <= 0 || creds.Cred == "" {
		return creds, fmt.Errorf("缺少移动端持久化凭据 (cred)")
	}
	credRaw, err := DecodeBase64Flexible(creds.Cred)
	if err != nil {
		return creds, fmt.Errorf("解析 cred 失败: %w", err)
	}
	deviceID := creds.GUID
	if deviceID == "" {
		deviceID = RandomHex(16)
	}

	ext := jce.NewWriter()
	ext.WriteString(0, "")
	ext.WriteString(1, "")
	ext.WriteString(2, "")
	ext.WriteString(3, "")
	ext.WriteString(4, "")

	req := jce.NewWriter()
	req.WriteStructRaw(0, buildUDBRequestHeader(deviceID))
	req.WriteStructRaw(1, buildUDBProtoInfo())
	req.WriteStructRaw(2, buildUDBDeviceInfo(deviceID))
	req.WriteLong(3, creds.UID)
	req.WriteBytes(4, credRaw)
	req.WriteBytes(5, []byte{})
	req.WriteStringList(6, []string{MobileAppID})
	req.WriteInt8(7, 1)
	req.WriteBytes(8, []byte{})
	req.WriteStructRaw(9, ext.Bytes())
	req.WriteInt(10, 0)
	req.WriteString(11, MobileAppID)
	req.WriteLong(12, time.Now().Unix())

	sv, err := c.callUDB(ctx, udbPathCredLogin, udbFuncCredLogin, req.Bytes())
	if err != nil {
		return creds, err
	}
	if err := checkUDBRespHeader(sv); err != nil {
		return creds, err
	}
	res, err := parseAppLoginData(sv, deviceID)
	if err != nil {
		return creds, err
	}

	updated := creds
	if res.Credentials.BizToken != "" {
		updated.BizToken = res.Credentials.BizToken
		updated.BizTokenUpdatedAt = time.Now().UnixMilli()
	}
	if res.Credentials.Cred != "" {
		updated.Cred = res.Credentials.Cred
	}
	updated.AppID = MobileAppID
	updated.TokenType = TokenTypeHyToken
	if updated.HuYaUA == "" || strings.HasPrefix(updated.HuYaUA, "webh5") {
		updated.HuYaUA = DefaultHuYaUA
	}
	if updated.DeviceInfo == "" {
		updated.DeviceInfo = MobileDeviceName
	}
	if updated.QIMEI == "" {
		updated.QIMEI = RandomHex(18)
	}
	return updated, nil
}

func (c *MobileAuthClient) callUDB(ctx context.Context, path, fn string, reqBody []byte) (*jce.StructView, error) {
	base := c.BaseURL
	if base == "" {
		base = udbBaseURL
	}
	reqID := c.reqSeq.Add(1)
	pktBytes := jce.EncodeUniPacketV3(udbServantName, fn, udbWupDataKey, reqBody, reqID)

	var lastErr error
	for attempt := 0; attempt < 2; attempt++ {
		if attempt > 0 {
			select {
			case <-ctx.Done():
				return nil, ctx.Err()
			case <-time.After(350 * time.Millisecond):
			}
		}
		hreq, err := http.NewRequestWithContext(ctx, http.MethodPost, base+path, bytes.NewReader(pktBytes))
		if err != nil {
			return nil, err
		}
		hreq.Header.Set("Content-Type", "application/x-wup")
		hreq.Header.Set("Connection", "Keep-Alive")
		hreq.Header.Set("User-Agent", DefaultMobileUserAgent)

		resp, err := c.HTTPClient.Do(hreq)
		if err != nil {
			lastErr = err
			continue
		}
		rb, readErr := io.ReadAll(io.LimitReader(resp.Body, 2*1024*1024))
		resp.Body.Close()
		if readErr != nil {
			lastErr = readErr
			continue
		}
		if resp.StatusCode < 200 || resp.StatusCode >= 300 {
			lastErr = fmt.Errorf("UDB %s HTTP %d", path, resp.StatusCode)
			continue
		}
		pkt, err := jce.DecodeUniPacketV3(rb)
		if err != nil {
			lastErr = fmt.Errorf("decode UDB %s: %w", path, err)
			continue
		}
		inner, _, wupRet, err := pkt.ExtractResponseStruct()
		if err != nil {
			return nil, fmt.Errorf("UDB %s (wupRet=%d): %w", path, wupRet, err)
		}
		return jce.ParseFields(inner), nil
	}
	return nil, lastErr
}

func checkUDBRespHeader(sv *jce.StructView) error {
	rh := sv.GetStruct(0)
	if rh == nil {
		return fmt.Errorf("UDB 响应缺少状态头")
	}
	code := rh.GetInt(3, 0)
	if code == 0 {
		return nil
	}
	errKey := rh.GetString(4, "")
	desc := rh.GetString(5, "")
	if desc == "" {
		desc = errKey
	}
	if code == 10030 {
		return fmt.Errorf("虎牙风控要求进一步安全验证（错误码 10030），建议先在手机虎牙 App 登录一次或改用扫码登录")
	}
	return fmt.Errorf("UDB 登录失败 (%d): %s", code, desc)
}

func parseAppLoginData(sv *jce.StructView, deviceID string) (*MobileLoginResult, error) {
	loginData := sv.GetStruct(1)
	if loginData == nil {
		return nil, fmt.Errorf("UDB 响应缺少 AppLoginData")
	}
	uid := loginData.GetLong(0, 0)
	if uid <= 0 {
		uid = loginData.GetLong(1, 0)
	}
	if uid <= 0 {
		return nil, fmt.Errorf("UDB 响应未返回有效 UID")
	}
	passport := loginData.GetString(2, "")
	credRaw := loginData.GetBytes(3)
	var credB64 string
	if len(credRaw) > 0 {
		credB64 = base64.StdEncoding.EncodeToString(credRaw)
	}

	var bizTokenB64 string
	for _, elem := range loginData.GetList(4) {
		b, ok := elem.([]byte)
		if !ok {
			continue
		}
		bt := jce.ParseFields(b)
		app := bt.GetString(0, "")
		rawTok := bt.GetBytes(2)
		if len(rawTok) == 0 {
			continue
		}
		if app == MobileAppID {
			bizTokenB64 = base64.StdEncoding.EncodeToString(rawTok)
			break
		}
		if bizTokenB64 == "" {
			bizTokenB64 = base64.StdEncoding.EncodeToString(rawTok)
		}
	}
	if bizTokenB64 == "" {
		return nil, fmt.Errorf("UDB 响应未返回移动端 biztoken")
	}

	return &MobileLoginResult{
		UID:      uid,
		Passport: passport,
		Credentials: Credentials{
			UID:               uid,
			AppID:             MobileAppID,
			BizToken:          bizTokenB64,
			Cred:              credB64,
			BizTokenUpdatedAt: time.Now().UnixMilli(),
			TokenType:         TokenTypeHyToken,
			GUID:              deviceID,
			HuYaUA:            DefaultHuYaUA,
			DeviceInfo:        MobileDeviceName,
			QIMEI:             RandomHex(18),
		},
	}, nil
}

// NeedsMobileTokenRefresh reports whether a mobile 5008 credential should proactively refresh its biztoken.
func (c Credentials) NeedsMobileTokenRefresh(now time.Time) bool {
	if !c.IsMobile() || c.Cred == "" {
		return false
	}
	if c.BizTokenUpdatedAt <= 0 {
		return true
	}
	return now.Sub(time.UnixMilli(c.BizTokenUpdatedAt)) >= udbMobileTokenTTL
}
