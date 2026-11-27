package huya

import (
	"context"
	"io"
	"net/http"
	"net/http/httptest"
	"os"
	"strings"
	"testing"
	"time"

	"github.com/UCKETX/HuyaPure/server/internal/jce"
)

func TestParseCookieCredentials(t *testing.T) {
	c, err := ParseCookieCredentials("foo=bar; yyuid=12345678; udb_biztoken=tok_abc")
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if c.UID != 12345678 || c.Token != "" || !strings.Contains(c.Cookie, "udb_biztoken=tok_abc") {
		t.Fatalf("unexpected creds: %+v", c)
	}
	if _, err := ParseCookieCredentials("foo=bar"); err == nil {
		t.Fatal("expected error when yyuid missing")
	}
}

func TestGenerateLocalPayID(t *testing.T) {
	id := generateLocalPayID()
	if len(id) != 14+6+12 {
		t.Fatalf("unexpected payId length %d: %q", len(id), id)
	}
	if id[14:20] != "002010" {
		t.Fatalf("unexpected payId middle marker: %q", id)
	}
}

func TestEnsureBagAdPlacementActive(t *testing.T) {
	var calledFuncs []string
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		body, _ := io.ReadAll(r.Body)
		pkt, err := jce.DecodeUniPacketV3(body)
		if err != nil {
			t.Fatalf("decode req: %v", err)
		}
		calledFuncs = append(calledFuncs, pkt.FuncName)

		var rspInner []byte
		switch pkt.FuncName {
		case "getPropsListPlacement":
			info := jce.NewWriter()
			info.WriteInt(0, 40)
			info.WriteLong(1, 21600)
			info.WriteInt(2, 0) // unactivated
			info.WriteString(3, "已结束,明日再来")
			info.WriteString(4, "inspire_task_id=279|status=0")
			info.WriteString(5, "看广告可得")

			pl := jce.NewWriter()
			pl.WriteString(0, BagHuliangPlacementID)
			pl.WriteString(1, "虎粮礼包")
			pl.WriteString(3, "看广告赚虎粮")
			pl.WriteInt(7, BagHuliangPlacementBizType)
			pl.WriteBytes(8, info.Bytes())

			rsp := jce.NewWriter()
			rsp.WriteStructList(0, [][]byte{pl.Bytes()})
			rspInner = rsp.Bytes()
		case "notifyPropsListPlacement":
			rsp := jce.NewWriter()
			rsp.WriteString(0, "")
			rspInner = rsp.Bytes()
		case "getPackageGift":
			simple := jce.NewWriter()
			simple.WriteInt(0, NormalHuliangItemType)
			simple.WriteInt(1, 100)
			simple.WriteString(3, "虎粮")

			b1 := jce.NewWriter()
			b1.WriteLong(0, 1795854360)
			b1.WriteLong(1, 50)
			b2 := jce.NewWriter()
			b2.WriteLong(0, 1796032860)
			b2.WriteLong(1, 40)

			detail := jce.NewWriter()
			detail.WriteStructRaw(0, simple.Bytes())
			detail.WriteLong(2, 50)
			detail.WriteLong(3, 1795854360)
			detail.WriteStructList(4, [][]byte{b1.Bytes(), b2.Bytes()})
			detail.WriteLong(5, 90)

			rsp := jce.NewWriter()
			rsp.WriteStructList(0, [][]byte{detail.Bytes()})
			rsp.WriteLong(1, 1795773700)
			rspInner = rsp.Bytes()
		default:
			t.Fatalf("unexpected func %s", pkt.FuncName)
		}

		// Encode UniPacket v3 response with tRsp + ret=0
		rspWrap := jce.NewWriter()
		rspWrap.WriteStructRaw(0, rspInner)
		retWrap := jce.NewWriter()
		retWrap.WriteInt(0, 0)

		sbuf := jce.NewWriter()
		sbuf.WriteBytesMap(0, map[string][]byte{
			"tRsp": rspWrap.Bytes(),
			"":     retWrap.Bytes(),
		})

		rp := jce.NewWriter()
		rp.WriteShort(1, 3)
		rp.WriteInt(4, pkt.RequestID)
		rp.WriteString(5, pkt.ServantName)
		rp.WriteString(6, pkt.FuncName)
		rp.WriteBytes(7, sbuf.Bytes())

		raw := rp.Bytes()
		out := make([]byte, 4+len(raw))
		total := uint32(len(out))
		out[0] = byte(total >> 24)
		out[1] = byte(total >> 16)
		out[2] = byte(total >> 8)
		out[3] = byte(total)
		copy(out[4:], raw)
		_, _ = w.Write(out)
	}))
	defer srv.Close()

	wup := NewWupClient()
	wup.Endpoint = srv.URL
	api := NewAPI(wup)

	creds := Credentials{
		UID:      123456,
		AppID:    MobileAppID,
		BizToken: "YWJjZGVm",
		GUID:     "0123456789abcdef0123456789abcdef",
	}
	pl, err := api.EnsureBagAdPlacementActive(context.Background(), creds, 0)
	if err != nil {
		t.Fatalf("EnsureBagAdPlacementActive failed: %v", err)
	}
	if pl == nil || pl.ID != BagHuliangPlacementID || pl.Status != 1 || pl.Count != 40 {
		t.Fatalf("unexpected placement: %+v", pl)
	}
	if len(calledFuncs) != 2 || calledFuncs[0] != "getPropsListPlacement" || calledFuncs[1] != "notifyPropsListPlacement" {
		t.Fatalf("unexpected call sequence: %v", calledFuncs)
	}

	bag, err := api.QueryBagHuliang(context.Background(), creds)
	if err != nil {
		t.Fatalf("QueryBagHuliang failed: %v", err)
	}
	if bag.NormalCount != 90 || bag.Expiring24h != 50 || len(bag.NormalBatches) != 2 {
		t.Fatalf("unexpected bag summary: %+v", bag)
	}
}

// TestLiveHuyaWupAndQR verifies our Go JCE/UniPacket v3 wire format against live Huya servers
// (anonymous public room query + live QR session generation). Runs when HUBAN_LIVE_TEST=1.
func TestLiveHuyaWupAndQR(t *testing.T) {
	if os.Getenv("HUBAN_LIVE_TEST") != "1" {
		t.Skip("set HUBAN_LIVE_TEST=1 to run live Huya network smoke tests")
	}
	ctx, cancel := context.WithTimeout(context.Background(), 20*time.Second)
	defer cancel()

	// 1. Live QR login session + poll
	qrMgr := NewQRLoginManager()
	sess, err := qrMgr.StartSession(ctx)
	if err != nil {
		t.Fatalf("StartSession failed: %v", err)
	}
	if sess.QRID == "" || !strings.HasPrefix(sess.ImageData, "data:image/png;base64,") {
		t.Fatalf("unexpected QR session: id=%q imgLen=%d", sess.QRID, len(sess.ImageData))
	}
	poll, err := qrMgr.Poll(ctx, sess.ID)
	if err != nil {
		t.Fatalf("Poll failed: %v", err)
	}
	if poll.Status != "waiting" {
		t.Fatalf("expected waiting status, got %+v", poll)
	}
	t.Logf("Live QR verified: qrId=%s stage=%d (%s)", sess.QRID, poll.Stage, poll.Message)

	// 2. Live WUP v3 call: wupui.getLiveRoomProfileForAudience (public room 660000)
	api := NewAPI(nil)
	cfg, err := api.QuerySuperFansConfig(ctx, Credentials{UID: 0, GUID: RandomHex(16)}, 0, 660000)
	if err != nil {
		t.Fatalf("Live WUP getLiveRoomProfileForAudience failed: %v", err)
	}
	t.Logf("Live WUP verified: room=%d nick=%q sfDay=%d active=%v scoreTimes=%d",
		cfg.RoomID, cfg.PresenterNick, cfg.SFDay, cfg.SFDayActive, cfg.SFDayScoreTimes)
}
