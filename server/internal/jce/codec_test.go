package jce

import "testing"

func TestRoundTripStructAndUniPacket(t *testing.T) {
	inner := NewWriter()
	inner.WriteLong(0, 1234567890123)
	inner.WriteString(1, "guid-test")
	inner.WriteString(2, "token-abc")
	inner.WriteString(3, "webh5&261127&websocket")
	inner.WriteString(4, "yyuid=123; udb_biztoken=xyz")
	inner.WriteInt(5, 0)
	inner.WriteStringMap(8, map[string]string{"rso": "package"})
	inner.WriteLongList(9, []int64{100001, 100002})

	encoded := EncodeUniPacketV3("wupui", "getLiveRoomProfileForAudience", "tReq", inner.Bytes(), 42)
	pkt, err := DecodeUniPacketV3(encoded)
	if err != nil {
		t.Fatalf("DecodeUniPacketV3 failed: %v", err)
	}
	if pkt.Version != 3 || pkt.RequestID != 42 || pkt.ServantName != "wupui" || pkt.FuncName != "getLiveRoomProfileForAudience" {
		t.Fatalf("unexpected header: %+v", pkt)
	}
	raw, ok := pkt.Data["tReq"]
	if !ok {
		t.Fatalf("missing tReq key in Data: %v", pkt.Data)
	}
	outer := ParseFields(raw)
	s := outer.GetStruct(0)
	if s == nil {
		t.Fatal("failed to unwrap inner struct")
	}
	if got := s.GetLong(0, 0); got != 1234567890123 {
		t.Fatalf("tag 0 mismatch: got %d", got)
	}
	if got := s.GetString(2, ""); got != "token-abc" {
		t.Fatalf("tag 2 mismatch: got %q", got)
	}
	if got := s.GetStringMap(8)["rso"]; got != "package" {
		t.Fatalf("tag 8 map mismatch: got %q", got)
	}
	if list := s.GetList(9); len(list) != 2 || list[0].(int64) != 100001 || list[1].(int64) != 100002 {
		t.Fatalf("tag 9 list mismatch: %+v", list)
	}
}
