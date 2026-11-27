package store

import (
	"testing"

	"github.com/UCKETX/HuyaPure/server/internal/huya"
)

func TestMultiAccountUpsertAndDowngradeProtection(t *testing.T) {
	dir := t.TempDir()
	st, err := Open(dir)
	if err != nil {
		t.Fatalf("Open failed: %v", err)
	}

	// 1. Add first account with Mobile 5008 credentials.
	res1, err := st.UpsertByUID("主号", huya.Credentials{
		UID:      10001,
		AppID:    huya.MobileAppID,
		BizToken: "biz-token-1",
		Cred:     "cred-1",
		GUID:     "guid-1",
	})
	if err != nil {
		t.Fatalf("UpsertByUID acc1 failed: %v", err)
	}
	if !res1.Created || res1.KeptMobile {
		t.Fatalf("expected Created=true KeptMobile=false, got %+v", res1)
	}

	// 2. Add second account with a different UID — must NOT overwrite acc1.
	res2, err := st.UpsertByUID("小号", huya.Credentials{
		UID:      20002,
		AppID:    huya.MobileAppID,
		BizToken: "biz-token-2",
		Cred:     "cred-2",
		GUID:     "guid-2",
	})
	if err != nil {
		t.Fatalf("UpsertByUID acc2 failed: %v", err)
	}
	if !res2.Created || res2.KeptMobile {
		t.Fatalf("expected Created=true KeptMobile=false, got %+v", res2)
	}
	if got := len(st.ListPublic()); got != 2 {
		t.Fatalf("expected 2 accounts, got %d", got)
	}

	// 3. Scanning QR / pasting Web Cookie for UID 10001 must NOT downgrade its 5008 credentials.
	res3, err := st.UpsertByUID("web-overwrite-attempt", huya.Credentials{
		UID:    10001,
		Cookie: "udb_uid=10001; udb_biztoken=webtok",
		GUID:   "guid-web",
	})
	if err != nil {
		t.Fatalf("UpsertByUID downgrade attempt failed: %v", err)
	}
	if res3.Created || !res3.KeptMobile {
		t.Fatalf("expected Created=false KeptMobile=true, got %+v", res3)
	}
	if !res3.Account.Credentials.IsMobile() || res3.Account.Credentials.BizToken != "biz-token-1" {
		t.Fatalf("expected 5008 credentials preserved, got %+v", res3.Account.Credentials)
	}
	if res3.Account.Label != "主号" {
		t.Fatalf("expected label preserved on blocked downgrade, got %q", res3.Account.Label)
	}

	// 4. Reload from disk and confirm both accounts persist intact.
	st2, err := Open(dir)
	if err != nil {
		t.Fatalf("Re-open failed: %v", err)
	}
	list := st2.ListPublic()
	if len(list) != 2 {
		t.Fatalf("expected 2 accounts after reload, got %d", len(list))
	}
	if list[0].UID != 10001 || list[0].AuthMode != "mobile" || list[1].UID != 20002 || list[1].AuthMode != "mobile" {
		t.Fatalf("unexpected persisted accounts: %+v", list)
	}
}

func TestApplyHuyaProfileReplacesDefaultLabelsOnly(t *testing.T) {
	a := &Account{
		Label:       "hy_263328861",
		Credentials: huya.Credentials{UID: 1000012345678},
	}
	ApplyHuyaProfile(a, "晴金黄", "https://example.com/a.png")
	if a.Label != "晴金黄" || a.NickName != "晴金黄" || a.Avatar != "https://example.com/a.png" {
		t.Fatalf("expected default hy_ passport replaced by Huya nick, got label=%q nick=%q", a.Label, a.NickName)
	}

	// If user changes their Huya nickname later while Label still tracks NickName, update Label too.
	ApplyHuyaProfile(a, "晴金黄2", "")
	if a.Label != "晴金黄2" || a.NickName != "晴金黄2" {
		t.Fatalf("expected tracked nick updated, got label=%q nick=%q", a.Label, a.NickName)
	}

	// If user manually renames account to a custom remark, keep Label while updating NickName.
	a.Label = "主号"
	ApplyHuyaProfile(a, "晴金黄3", "")
	if a.Label != "主号" || a.NickName != "晴金黄3" {
		t.Fatalf("expected custom label preserved, got label=%q nick=%q", a.Label, a.NickName)
	}
}
