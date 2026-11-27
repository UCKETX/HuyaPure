package jce

import (
	"bytes"
	"encoding/binary"
	"errors"
	"fmt"
	"math"
)

// TAF / Tars / JCE wire types.
const (
	TypeByte        = 0
	TypeShort       = 1
	TypeInt         = 2
	TypeLong        = 3
	TypeFloat       = 4
	TypeDouble      = 5
	TypeString1     = 6
	TypeString4     = 7
	TypeMap         = 8
	TypeList        = 9
	TypeStructBegin = 10
	TypeStructEnd   = 11
	TypeZeroTag     = 12
	TypeSimpleList  = 13
)

// Writer encodes TAF/JCE binary payloads.
type Writer struct {
	buf bytes.Buffer
}

func NewWriter() *Writer {
	return &Writer{}
}

func (w *Writer) writeHead(tag int, typ byte) {
	if tag < 15 {
		w.buf.WriteByte(byte((tag&0x0F)<<4) | (typ & 0x0F))
	} else {
		w.buf.WriteByte(0xF0 | (typ & 0x0F))
		w.buf.WriteByte(byte(tag & 0xFF))
	}
}

func (w *Writer) WriteBool(tag int, v bool) {
	if v {
		w.WriteInt8(tag, 1)
	} else {
		w.WriteInt8(tag, 0)
	}
}

func (w *Writer) WriteInt8(tag int, v int8) {
	if v == 0 {
		w.writeHead(tag, TypeZeroTag)
		return
	}
	w.writeHead(tag, TypeByte)
	w.buf.WriteByte(byte(v))
}

func (w *Writer) WriteShort(tag int, v int16) {
	if v >= math.MinInt8 && v <= math.MaxInt8 {
		w.WriteInt8(tag, int8(v))
		return
	}
	w.writeHead(tag, TypeShort)
	var b [2]byte
	binary.BigEndian.PutUint16(b[:], uint16(v))
	w.buf.Write(b[:])
}

func (w *Writer) WriteInt(tag int, v int32) {
	if v >= math.MinInt16 && v <= math.MaxInt16 {
		w.WriteShort(tag, int16(v))
		return
	}
	w.writeHead(tag, TypeInt)
	var b [4]byte
	binary.BigEndian.PutUint32(b[:], uint32(v))
	w.buf.Write(b[:])
}

func (w *Writer) WriteLong(tag int, v int64) {
	if v >= math.MinInt32 && v <= math.MaxInt32 {
		w.WriteInt(tag, int32(v))
		return
	}
	w.writeHead(tag, TypeLong)
	var b [8]byte
	binary.BigEndian.PutUint64(b[:], uint64(v))
	w.buf.Write(b[:])
}

func (w *Writer) WriteString(tag int, s string) {
	data := []byte(s)
	if len(data) <= 255 {
		w.writeHead(tag, TypeString1)
		w.buf.WriteByte(byte(len(data)))
	} else {
		w.writeHead(tag, TypeString4)
		var b [4]byte
		binary.BigEndian.PutUint32(b[:], uint32(len(data)))
		w.buf.Write(b[:])
	}
	w.buf.Write(data)
}

// WriteBytes writes a JCE SimpleList of bytes (vector<char> / byte[]).
func (w *Writer) WriteBytes(tag int, data []byte) {
	w.writeHead(tag, TypeSimpleList)
	w.writeHead(0, TypeByte)
	w.WriteInt(0, int32(len(data)))
	w.buf.Write(data)
}

// WriteStructRaw writes a nested JCE struct from its pre-encoded inner fields.
func (w *Writer) WriteStructRaw(tag int, innerFields []byte) {
	w.writeHead(tag, TypeStructBegin)
	if len(innerFields) > 0 {
		w.buf.Write(innerFields)
	}
	w.writeHead(0, TypeStructEnd)
}

func (w *Writer) WriteLongList(tag int, list []int64) {
	w.writeHead(tag, TypeList)
	w.WriteInt(0, int32(len(list)))
	for _, v := range list {
		w.WriteLong(0, v)
	}
}

func (w *Writer) WriteStringList(tag int, list []string) {
	w.writeHead(tag, TypeList)
	w.WriteInt(0, int32(len(list)))
	for _, v := range list {
		w.WriteString(0, v)
	}
}

func (w *Writer) WriteStructList(tag int, items [][]byte) {
	w.writeHead(tag, TypeList)
	w.WriteInt(0, int32(len(items)))
	for _, item := range items {
		w.WriteStructRaw(0, item)
	}
}

func (w *Writer) WriteStringMap(tag int, m map[string]string) {
	w.writeHead(tag, TypeMap)
	w.WriteInt(0, int32(len(m)))
	for k, v := range m {
		w.WriteString(0, k)
		w.WriteString(1, v)
	}
}

func (w *Writer) WriteBytesMap(tag int, m map[string][]byte) {
	w.writeHead(tag, TypeMap)
	w.WriteInt(0, int32(len(m)))
	for k, v := range m {
		w.WriteString(0, k)
		w.WriteBytes(1, v)
	}
}

func (w *Writer) Bytes() []byte {
	out := make([]byte, w.buf.Len())
	copy(out, w.buf.Bytes())
	return out
}

// StructView holds decoded JCE struct fields keyed by tag.
type StructView struct {
	Fields map[int]any
}

func (s *StructView) GetInt(tag int, def int32) int32 {
	if s == nil {
		return def
	}
	if v, ok := s.Fields[tag]; ok {
		if n, ok := v.(int64); ok {
			return int32(n)
		}
	}
	return def
}

func (s *StructView) GetLong(tag int, def int64) int64 {
	if s == nil {
		return def
	}
	if v, ok := s.Fields[tag]; ok {
		if n, ok := v.(int64); ok {
			return n
		}
	}
	return def
}

func (s *StructView) GetBool(tag int, def bool) bool {
	if s == nil {
		return def
	}
	if v, ok := s.Fields[tag]; ok {
		if n, ok := v.(int64); ok {
			return n != 0
		}
	}
	return def
}

func (s *StructView) GetString(tag int, def string) string {
	if s == nil {
		return def
	}
	if v, ok := s.Fields[tag]; ok {
		if str, ok := v.(string); ok {
			return str
		}
	}
	return def
}

func (s *StructView) GetBytes(tag int) []byte {
	if s == nil {
		return nil
	}
	if v, ok := s.Fields[tag]; ok {
		if b, ok := v.([]byte); ok {
			return b
		}
	}
	return nil
}

func (s *StructView) GetStruct(tag int) *StructView {
	b := s.GetBytes(tag)
	if b == nil {
		return nil
	}
	return ParseFields(b)
}

func (s *StructView) GetList(tag int) []any {
	if s == nil {
		return nil
	}
	if v, ok := s.Fields[tag]; ok {
		if list, ok := v.([]any); ok {
			return list
		}
	}
	return nil
}

func (s *StructView) GetMap(tag int) map[any]any {
	if s == nil {
		return nil
	}
	if v, ok := s.Fields[tag]; ok {
		if m, ok := v.(map[any]any); ok {
			return m
		}
	}
	return nil
}

func (s *StructView) GetStringMap(tag int) map[string]string {
	raw := s.GetMap(tag)
	if raw == nil {
		return nil
	}
	out := make(map[string]string, len(raw))
	for k, v := range raw {
		ks, ok1 := k.(string)
		vs, ok2 := v.(string)
		if ok1 && ok2 {
			out[ks] = vs
		}
	}
	return out
}

// Reader decodes TAF/JCE binary payloads.
type Reader struct {
	buf []byte
	pos int
}

// ParseFields decodes raw inner fields of a JCE struct.
func ParseFields(data []byte) *StructView {
	r := &Reader{buf: data}
	return r.readStructFields(false)
}

func (r *Reader) remaining() int {
	return len(r.buf) - r.pos
}

func (r *Reader) readHead() (tag int, typ byte, ok bool) {
	if r.pos >= len(r.buf) {
		return 0, 0, false
	}
	b := r.buf[r.pos]
	r.pos++
	typ = b & 0x0F
	tag = int((b >> 4) & 0x0F)
	if tag == 15 {
		if r.pos >= len(r.buf) {
			return 0, 0, false
		}
		tag = int(r.buf[r.pos])
		r.pos++
	}
	return tag, typ, true
}

func (r *Reader) readStructFields(stopAtEnd bool) *StructView {
	view := &StructView{Fields: make(map[int]any)}
	for r.pos < len(r.buf) {
		tag, typ, ok := r.readHead()
		if !ok {
			break
		}
		if typ == TypeStructEnd {
			if stopAtEnd {
				break
			}
			continue
		}
		val := r.readValue(typ)
		view.Fields[tag] = val
	}
	return view
}

func (r *Reader) readValue(typ byte) any {
	switch typ {
	case TypeZeroTag:
		return int64(0)
	case TypeByte:
		if r.remaining() < 1 {
			r.pos = len(r.buf)
			return int64(0)
		}
		v := int8(r.buf[r.pos])
		r.pos++
		return int64(v)
	case TypeShort:
		if r.remaining() < 2 {
			r.pos = len(r.buf)
			return int64(0)
		}
		v := int16(binary.BigEndian.Uint16(r.buf[r.pos : r.pos+2]))
		r.pos += 2
		return int64(v)
	case TypeInt:
		if r.remaining() < 4 {
			r.pos = len(r.buf)
			return int64(0)
		}
		v := int32(binary.BigEndian.Uint32(r.buf[r.pos : r.pos+4]))
		r.pos += 4
		return int64(v)
	case TypeLong:
		if r.remaining() < 8 {
			r.pos = len(r.buf)
			return int64(0)
		}
		v := int64(binary.BigEndian.Uint64(r.buf[r.pos : r.pos+8]))
		r.pos += 8
		return v
	case TypeFloat:
		if r.remaining() < 4 {
			r.pos = len(r.buf)
			return float32(0)
		}
		v := math.Float32frombits(binary.BigEndian.Uint32(r.buf[r.pos : r.pos+4]))
		r.pos += 4
		return v
	case TypeDouble:
		if r.remaining() < 8 {
			r.pos = len(r.buf)
			return float64(0)
		}
		v := math.Float64frombits(binary.BigEndian.Uint64(r.buf[r.pos : r.pos+8]))
		r.pos += 8
		return v
	case TypeString1:
		if r.remaining() < 1 {
			r.pos = len(r.buf)
			return ""
		}
		n := int(r.buf[r.pos])
		r.pos++
		return r.readStringN(n)
	case TypeString4:
		if r.remaining() < 4 {
			r.pos = len(r.buf)
			return ""
		}
		n := int(int32(binary.BigEndian.Uint32(r.buf[r.pos : r.pos+4])))
		r.pos += 4
		return r.readStringN(n)
	case TypeStructBegin:
		start := r.pos
		r.skipToStructEnd()
		end := r.pos - 1
		if end < start {
			return []byte{}
		}
		cp := make([]byte, end-start)
		copy(cp, r.buf[start:end])
		return cp
	case TypeList:
		size := int(r.readTaggedLong())
		if size < 0 {
			size = 0
		}
		capHint := size
		if capHint > 256 {
			capHint = 256
		}
		list := make([]any, 0, capHint)
		for i := 0; i < size && r.pos < len(r.buf); i++ {
			_, etyp, ok := r.readHead()
			if !ok {
				break
			}
			list = append(list, r.readValue(etyp))
		}
		return list
	case TypeMap:
		size := int(r.readTaggedLong())
		if size < 0 {
			size = 0
		}
		m := make(map[any]any, min(size, 64))
		for i := 0; i < size && r.pos < len(r.buf); i++ {
			_, ktyp, ok1 := r.readHead()
			if !ok1 {
				break
			}
			k := r.readValue(ktyp)
			_, vtyp, ok2 := r.readHead()
			if !ok2 {
				break
			}
			v := r.readValue(vtyp)
			m[k] = v
		}
		return m
	case TypeSimpleList:
		_, _, ok := r.readHead() // element type head (always TypeByte)
		if !ok {
			return []byte{}
		}
		size := int(r.readTaggedLong())
		if size <= 0 {
			return []byte{}
		}
		if size > r.remaining() {
			size = r.remaining()
		}
		raw := make([]byte, size)
		copy(raw, r.buf[r.pos:r.pos+size])
		r.pos += size
		return raw
	default:
		return nil
	}
}

func (r *Reader) readTaggedLong() int64 {
	_, typ, ok := r.readHead()
	if !ok {
		return 0
	}
	if n, ok := r.readValue(typ).(int64); ok {
		return n
	}
	return 0
}

func (r *Reader) skipToStructEnd() {
	for r.pos < len(r.buf) {
		_, typ, ok := r.readHead()
		if !ok || typ == TypeStructEnd {
			return
		}
		r.readValue(typ)
	}
}

func (r *Reader) readStringN(n int) string {
	if n <= 0 {
		return ""
	}
	if n > r.remaining() {
		n = r.remaining()
	}
	s := string(r.buf[r.pos : r.pos+n])
	r.pos += n
	return s
}

// UniPacket represents a TAF/WUP v3 RPC request/response packet.
type UniPacket struct {
	Version     int16
	PacketType  int8
	MessageType int32
	RequestID   int32
	ServantName string
	FuncName    string
	Data        map[string][]byte
	Timeout     int32
	Context     map[string]string
	Status      map[string]string
}

// EncodeUniPacketV3 builds a length-prefixed WUP v3 packet ready to POST to wup.huya.com.
func EncodeUniPacketV3(servant, fn, reqKey string, reqStructBody []byte, reqID int32) []byte {
	if reqKey == "" {
		reqKey = "tReq"
	}
	wrap := NewWriter()
	wrap.WriteStructRaw(0, reqStructBody)

	dataMap := map[string][]byte{
		reqKey: wrap.Bytes(),
	}
	sBufWriter := NewWriter()
	sBufWriter.WriteBytesMap(0, dataMap)

	pkt := NewWriter()
	pkt.WriteShort(1, 3) // iVersion = 3 (TUP/WUP v3)
	pkt.WriteInt8(2, 0)  // cPacketType
	pkt.WriteInt(3, 0)   // iMessageType
	pkt.WriteInt(4, reqID)
	pkt.WriteString(5, servant)
	pkt.WriteString(6, fn)
	pkt.WriteBytes(7, sBufWriter.Bytes())
	pkt.WriteInt(8, 0) // iTimeout
	pkt.WriteStringMap(9, map[string]string{})
	pkt.WriteStringMap(10, map[string]string{})

	body := pkt.Bytes()
	out := make([]byte, 4+len(body))
	binary.BigEndian.PutUint32(out[:4], uint32(len(out)))
	copy(out[4:], body)
	return out
}

// DecodeUniPacketV3 parses a length-prefixed WUP v3 response packet.
func DecodeUniPacketV3(raw []byte) (*UniPacket, error) {
	if len(raw) < 4 {
		return nil, fmt.Errorf("wup packet too short: %d bytes", len(raw))
	}
	totalLen := int(binary.BigEndian.Uint32(raw[:4]))
	payload := raw[4:]
	if totalLen >= 4 && totalLen <= len(raw) {
		payload = raw[4:totalLen]
	}

	view := ParseFields(payload)
	pkt := &UniPacket{
		Version:     int16(view.GetInt(1, 0)),
		PacketType:  int8(view.GetInt(2, 0)),
		MessageType: view.GetInt(3, 0),
		RequestID:   view.GetInt(4, 0),
		ServantName: view.GetString(5, ""),
		FuncName:    view.GetString(6, ""),
		Timeout:     view.GetInt(8, 0),
		Context:     view.GetStringMap(9),
		Status:      view.GetStringMap(10),
		Data:        make(map[string][]byte),
	}

	sBuf := view.GetBytes(7)
	if len(sBuf) > 0 {
		bufView := ParseFields(sBuf)
		if rawMap := bufView.GetMap(0); rawMap != nil {
			for k, v := range rawMap {
				ks, ok1 := k.(string)
				vb, ok2 := v.([]byte)
				if ok1 && ok2 {
					pkt.Data[ks] = vb
				}
			}
		}
	}
	return pkt, nil
}

// ExtractResponseStruct unwraps the WUP return code ("") and primary response struct
// (checking tRsp / rsp / tResp / resp or first non-empty key).
func (p *UniPacket) ExtractResponseStruct() (innerStruct []byte, matchedKey string, wupRet int32, err error) {
	if p == nil || len(p.Data) == 0 {
		return nil, "", 0, errors.New("empty UniPacket data")
	}
	if retBytes, ok := p.Data[""]; ok && len(retBytes) > 0 {
		retView := ParseFields(retBytes)
		wupRet = retView.GetInt(0, 0)
	}
	var rawPayload []byte
	for _, cand := range []string{"tRsp", "rsp", "tResp", "resp"} {
		if v, ok := p.Data[cand]; ok && len(v) > 0 {
			matchedKey = cand
			rawPayload = v
			break
		}
	}
	if rawPayload == nil {
		for k, v := range p.Data {
			if k != "" && len(v) > 0 {
				matchedKey = k
				rawPayload = v
				break
			}
		}
	}
	if wupRet != 0 && rawPayload == nil {
		return nil, "", wupRet, fmt.Errorf("WUP ret=%d", wupRet)
	}
	if rawPayload == nil {
		return nil, "", wupRet, errors.New("response struct missing in UniPacket")
	}
	outer := ParseFields(rawPayload)
	if inner := outer.GetBytes(0); inner != nil {
		return inner, matchedKey, wupRet, nil
	}
	return rawPayload, matchedKey, wupRet, nil
}
