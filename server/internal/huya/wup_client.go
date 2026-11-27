package huya

import (
	"bytes"
	"context"
	"crypto/rand"
	"encoding/hex"
	"fmt"
	"io"
	"net/http"
	"sync/atomic"
	"time"

	"github.com/UCKETX/HuyaPure/server/internal/jce"
)

const (
	DefaultWupEndpoint     = "https://wup.huya.com/"
	DefaultHuYaUA          = "adr&13.5.62&huya&35"
	DefaultWebHuYaUA       = "webh5&2611270000&websocket"
	DefaultUserAgent       = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
	DefaultMobileUserAgent = "Dalvik/2.1.0 (Linux; U; Android 15; SM-S9280 Build/AP3A.240905.015.A2)"
)

// Credentials holds Huya authentication data needed to build HUYA.UserId.
type Credentials struct {
	UID               int64  `json:"uid"`
	AppID             string `json:"appId,omitempty"`             // "5008" for Android mobile, "5002" for Web
	Cookie            string `json:"cookie,omitempty"`            // Web Cookie auth (5002)
	BizToken          string `json:"bizToken,omitempty"`          // Base64 raw UDB biztoken (packed on demand via PackHyToken)
	Cred              string `json:"cred,omitempty"`              // Base64 raw UDB cred (for silent 5008 auto-refresh)
	BizTokenUpdatedAt int64  `json:"bizTokenUpdatedAt,omitempty"` // unix ms
	Token             string `json:"token,omitempty"`             // Optional pre-packed sToken
	GUID              string `json:"guid,omitempty"`
	HuYaUA            string `json:"huyaUa,omitempty"`
	TokenType         int32  `json:"tokenType,omitempty"`
	DeviceInfo        string `json:"deviceInfo,omitempty"`
	QIMEI             string `json:"qimei,omitempty"`
}

func (c Credentials) IsMobile() bool {
	return c.UID > 0 && c.AppID == MobileAppID && c.BizToken != ""
}

func (c Credentials) IsValid() bool {
	return c.UID > 0 && (c.IsMobile() || c.Cookie != "" || c.Token != "")
}

// EncodeUserId serializes HUYA.UserId inner JCE fields:
//
//	tag 0: lUid (long)
//	tag 1: sGuid (string)
//	tag 2: sToken (string)
//	tag 3: sHuYaUA (string)
//	tag 4: sCookie (string)
//	tag 5: iTokenType (int)
//	tag 6: sDeviceInfo (string)
//	tag 7: sQIMEI (string)
func (c Credentials) EncodeUserId() []byte {
	guid := c.GUID
	if guid == "" {
		guid = RandomHex(16)
	}
	ua := c.HuYaUA
	token := c.Token
	cookie := c.Cookie
	tokenType := c.TokenType

	if c.IsMobile() {
		// Mobile Android 5008 auth: pack fresh sToken with iTokenType=2, no sCookie.
		if packed, err := PackHyToken(uint64(c.UID), c.AppID, guid, c.AppID, c.BizToken, uint64(time.Now().Unix())); err == nil {
			token = packed
			tokenType = TokenTypeHyToken
			cookie = ""
		}
		if ua == "" || ua == DefaultWebHuYaUA {
			ua = DefaultHuYaUA
		}
	} else {
		if ua == "" {
			ua = DefaultWebHuYaUA
		}
		// IMPORTANT: When authenticating with Web cookies (sCookie), sToken MUST be empty;
		// setting a raw Web udb_biztoken in sToken causes wupui to reject with WUP ret=905.
		if cookie != "" {
			token = ""
		}
	}

	w := jce.NewWriter()
	w.WriteLong(0, c.UID)
	w.WriteString(1, guid)
	w.WriteString(2, token)
	w.WriteString(3, ua)
	w.WriteString(4, cookie)
	w.WriteInt(5, tokenType)
	if c.DeviceInfo != "" {
		w.WriteString(6, c.DeviceInfo)
	}
	if c.QIMEI != "" {
		w.WriteString(7, c.QIMEI)
	}
	return w.Bytes()
}

// WupClient invokes Huya TAF/WUP v3 RPCs over HTTPS.
type WupClient struct {
	Endpoint   string
	HTTPClient *http.Client
	reqCounter atomic.Int32
}

func NewWupClient() *WupClient {
	return &WupClient{
		Endpoint: DefaultWupEndpoint,
		HTTPClient: &http.Client{
			Timeout: 15 * time.Second,
		},
	}
}

// Call sends a raw WUP v3 request and returns the unwrapped response struct view + WUP return code.
func (c *WupClient) Call(
	ctx context.Context,
	servant, fn, reqKey string,
	reqStructBody []byte,
	cookieHeader string,
) (*jce.StructView, int32, error) {
	reqID := c.reqCounter.Add(1)
	payload := jce.EncodeUniPacketV3(servant, fn, reqKey, reqStructBody, reqID)

	endpoint := c.Endpoint
	if endpoint == "" {
		endpoint = DefaultWupEndpoint
	}

	var lastErr error
	for attempt := 0; attempt < 2; attempt++ {
		if attempt > 0 {
			select {
			case <-ctx.Done():
				return nil, 0, ctx.Err()
			case <-time.After(300 * time.Millisecond):
			}
		}

		req, err := http.NewRequestWithContext(ctx, http.MethodPost, endpoint, bytes.NewReader(payload))
		if err != nil {
			return nil, 0, err
		}
		if cookieHeader != "" {
			req.Header.Set("Content-Type", "application/octet-stream")
			req.Header.Set("User-Agent", DefaultUserAgent)
			req.Header.Set("Origin", "https://www.huya.com")
			req.Header.Set("Referer", "https://www.huya.com/")
			req.Header.Set("Cookie", cookieHeader)
		} else {
			req.Header.Set("Content-Type", "application/multipart-formdata")
			req.Header.Set("User-Agent", DefaultMobileUserAgent)
			req.Header.Set("app_src", "huya")
		}

		resp, err := c.HTTPClient.Do(req)
		if err != nil {
			lastErr = err
			continue
		}
		respBytes, readErr := io.ReadAll(io.LimitReader(resp.Body, 8*1024*1024))
		resp.Body.Close()
		if readErr != nil {
			lastErr = readErr
			continue
		}
		if resp.StatusCode < 200 || resp.StatusCode >= 300 {
			lastErr = fmt.Errorf("wup %s.%s HTTP %d", servant, fn, resp.StatusCode)
			continue
		}
		pkt, err := jce.DecodeUniPacketV3(respBytes)
		if err != nil {
			lastErr = fmt.Errorf("decode %s.%s: %w", servant, fn, err)
			continue
		}
		inner, _, wupRet, err := pkt.ExtractResponseStruct()
		if err != nil {
			return nil, wupRet, fmt.Errorf("%s.%s: %w", servant, fn, err)
		}
		return jce.ParseFields(inner), wupRet, nil
	}
	return nil, 0, lastErr
}

func RandomHex(numBytes int) string {
	b := make([]byte, numBytes)
	_, _ = rand.Read(b)
	return hex.EncodeToString(b)
}
