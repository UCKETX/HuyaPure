package huya

import (
	"encoding/base64"
	"encoding/binary"
	"testing"

	"github.com/UCKETX/HuyaPure/server/internal/jce"
)

func TestPackHyTokenLayout(t *testing.T) {
	rawBiz := []byte("sample-raw-biztoken-bytes-0123456789")
	b64Biz := base64.StdEncoding.EncodeToString(rawBiz)

	packed, err := PackHyToken(1000012345678, "5008", "dev1234", "5008", b64Biz, 1795770000)
	if err != nil {
		t.Fatalf("PackHyToken failed: %v", err)
	}

	outer, err := base64.StdEncoding.DecodeString(packed)
	if err != nil {
		t.Fatalf("packed token is not valid std base64: %v", err)
	}
	if len(outer) < 4 {
		t.Fatalf("outer too short: %d", len(outer))
	}
	outerTotalLen := binary.LittleEndian.Uint16(outer[0:2])
	innerLen := binary.LittleEndian.Uint16(outer[2:4])
	if int(outerTotalLen) != int(innerLen)+2 {
		t.Fatalf("outerLen=%d want innerLen+2=%d", outerTotalLen, innerLen+2)
	}
	if len(outer) != 4+int(innerLen) {
		t.Fatalf("len(outer)=%d want %d", len(outer), 4+int(innerLen))
	}

	inner := outer[4:]
	if inner[0] != 1 {
		t.Fatalf("version=%d want 1", inner[0])
	}
	if got := binary.LittleEndian.Uint32(inner[1:5]); got != 3 {
		t.Fatalf("kind=%d want 3", got)
	}
	if got := binary.LittleEndian.Uint64(inner[5:13]); got != 1000012345678 {
		t.Fatalf("uid=%d want 1000012345678", got)
	}
	nonce := binary.LittleEndian.Uint64(inner[13:21])
	if nonce>>16 != 1795770000 {
		t.Fatalf("nonce sec=%d want 1795770000", nonce>>16)
	}

	off := 21
	readVarStr := func() []byte {
		l := int(binary.LittleEndian.Uint16(inner[off : off+2]))
		off += 2
		b := inner[off : off+l]
		off += l
		return b
	}
	if got := string(readVarStr()); got != "5008" {
		t.Fatalf("appId=%q want 5008", got)
	}
	if got := string(readVarStr()); got != "dev1234" {
		t.Fatalf("deviceId=%q want dev1234", got)
	}
	if got := string(readVarStr()); got != "5008" {
		t.Fatalf("bizAppId=%q want 5008", got)
	}
	if got := string(readVarStr()); got != string(rawBiz) {
		t.Fatalf("rawBiz mismatch")
	}
	if got := binary.LittleEndian.Uint32(inner[off : off+4]); got != 0 {
		t.Fatalf("trailing uint32=%d want 0", got)
	}
	if off+4 != len(inner) {
		t.Fatalf("unexpected trailing bytes: off=%d len=%d", off+4, len(inner))
	}
}

func TestCredentialsEncodeUserIdMobileVsWeb(t *testing.T) {
	mob := Credentials{
		UID:        123456,
		AppID:      MobileAppID,
		BizToken:   base64.StdEncoding.EncodeToString([]byte("rawtok")),
		GUID:       "guid_abc",
		DeviceInfo: MobileDeviceName,
		QIMEI:      "0123456789abcdef0123456789abcdef0123",
	}
	if !mob.IsMobile() || !mob.IsValid() {
		t.Fatalf("expected valid mobile credentials")
	}
	sv := jce.ParseFields(mob.EncodeUserId())
	if sv.GetInt(5, 0) != TokenTypeHyToken {
		t.Fatalf("iTokenType=%d want %d", sv.GetInt(5, 0), TokenTypeHyToken)
	}
	if sv.GetString(2, "") == "" {
		t.Fatalf("expected non-empty packed sToken for mobile auth")
	}
	if sv.GetString(4, "") != "" {
		t.Fatalf("expected empty sCookie for mobile auth")
	}
	if sv.GetString(3, "") != DefaultHuYaUA {
		t.Fatalf("sHuYaUA=%q want %q", sv.GetString(3, ""), DefaultHuYaUA)
	}

	web := Credentials{
		UID:    123456,
		Cookie: "yyuid=123456; udb_biztoken=abc",
		Token:  "should_be_cleared",
	}
	if web.IsMobile() || !web.IsValid() {
		t.Fatalf("expected valid web credentials")
	}
	wsv := jce.ParseFields(web.EncodeUserId())
	if wsv.GetString(2, "") != "" {
		t.Fatalf("web auth must keep sToken empty to avoid WUP ret=905")
	}
	if wsv.GetString(4, "") != web.Cookie {
		t.Fatalf("expected sCookie to be preserved")
	}
}

func TestNormalizeMobile(t *testing.T) {
	cases := map[string]string{
		"13800138000":      "13800138000",
		"+86 138-0013-8000": "13800138000",
		"008613800138000":  "13800138000",
	}
	for in, want := range cases {
		if got := NormalizeMobile(in); got != want {
			t.Fatalf("NormalizeMobile(%q) = %q, want %q", in, got, want)
		}
	}
}
