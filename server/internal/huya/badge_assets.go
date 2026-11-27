package huya

import (
	"context"
	"strconv"
	"strings"
	"time"
	"unicode/utf8"

	"github.com/UCKETX/HuyaPure/server/internal/jce"
)

const (
	badgeAssetCacheTTL = 6 * time.Hour
	badgeAssetUA       = "6" // 3x Retina (60px tall) official Huya CDN tier
	badgeMaxLevelDef   = 52
)

// BadgeRenderInfo holds Huya's official layered CDN URLs to render an authentic fan badge
// (standard, extinguished, SuperFan V/V年/V+/V+年, Guard shield, or custom anchor emblem).
type BadgeRenderInfo struct {
	Custom       bool   `json:"custom,omitempty"`
	Size         int    `json:"size"`                // 2 (1-2 chars) | 3 (3+ chars)
	HasSuffix    bool   `json:"hasSuffix,omitempty"` // true when right-side SuperFan V or Guard shield is present
	FloorURL     string `json:"floorUrl"`
	IconURL      string `json:"iconUrl,omitempty"`
	LevelURL     string `json:"levelUrl,omitempty"`
	VFlagURL     string `json:"vFlagUrl,omitempty"`
	GuardIconURL string `json:"guardIconUrl,omitempty"` // Official Huya 180x60 right-aligned Guard shield PNG (11..13)
}

// CustomBadgeAsset describes one anchor's custom fan badge emblem from vBadgeBase.
type CustomBadgeAsset struct {
	IconURL       string
	Tier2MinLevel int
	Tier3MinLevel int
}

// BadgeAssetCatalog stores URL templates and custom anchor emblem mappings returned by
// wupui.getCustomBadgeBaseInfo.
type BadgeAssetCatalog struct {
	LevelURL       string
	SFURL          string
	FloorURL       string
	NewSFURL       string
	NewFloorURL    string
	IdentityURL    string
	NewIdentityURL string
	MaxLevel       int
	CustomBadges   map[int64]CustomBadgeAsset
}

// QueryBadgeAssetCatalog fetches live badge templates and all custom anchor badge emblems
// via wupui.getCustomBadgeBaseInfo(iUpgrades=1).
func (a *API) QueryBadgeAssetCatalog(ctx context.Context, creds Credentials) (*BadgeAssetCatalog, error) {
	req := jce.NewWriter()
	req.WriteStructRaw(0, creds.EncodeUserId())
	req.WriteInt(1, 1) // iUpgrades = 1

	rsp, _, err := a.Wup.Call(ctx, "wupui", "getCustomBadgeBaseInfo", "tReq", req.Bytes(), creds.Cookie)
	if err != nil {
		return nil, err
	}

	cat := &BadgeAssetCatalog{
		MaxLevel:     int(rsp.GetInt(2, badgeMaxLevelDef)),
		CustomBadges: make(map[int64]CustomBadgeAsset),
	}
	if cat.MaxLevel <= 0 {
		cat.MaxLevel = badgeMaxLevelDef
	}

	if t0 := rsp.GetStruct(0); t0 != nil {
		cat.LevelURL = normalizeBadgeHTTPS(t0.GetString(0, ""))
		cat.SFURL = normalizeBadgeHTTPS(t0.GetString(1, ""))
		cat.FloorURL = normalizeBadgeHTTPS(t0.GetString(2, ""))
		cat.NewSFURL = normalizeBadgeHTTPS(t0.GetString(4, ""))
		cat.NewFloorURL = normalizeBadgeHTTPS(t0.GetString(5, ""))

		for _, elem := range t0.GetList(3) {
			raw, ok := elem.([]byte)
			if !ok || len(raw) == 0 {
				continue
			}
			item := jce.ParseFields(raw)
			iconURL := normalizeBadgeHTTPS(item.GetString(1, ""))
			if iconURL == "" {
				continue
			}
			tier2 := 14
			if s2 := item.GetStruct(2); s2 != nil {
				if lv := int(s2.GetInt(0, 0)); lv > 0 {
					tier2 = lv
				}
			}
			tier3 := 21
			if s3 := item.GetStruct(3); s3 != nil {
				if lv := int(s3.GetInt(0, 0)); lv > 0 {
					tier3 = lv
				}
			}
			asset := CustomBadgeAsset{
				IconURL:       iconURL,
				Tier2MinLevel: tier2,
				Tier3MinLevel: tier3,
			}
			for _, idAny := range item.GetList(0) {
				var bid int64
				switch v := idAny.(type) {
				case int64:
					bid = v
				case int32:
					bid = int64(v)
				case int:
					bid = int64(v)
				}
				if bid > 0 {
					cat.CustomBadges[bid] = asset
				}
			}
		}
	}

	if t1 := rsp.GetStruct(1); t1 != nil {
		cat.IdentityURL = normalizeBadgeHTTPS(t1.GetString(0, ""))
		cat.NewIdentityURL = normalizeBadgeHTTPS(t1.GetString(2, ""))
	}

	return cat, nil
}

// GetBadgeAssetCatalog returns a cached BadgeAssetCatalog, refreshing from Huya every 6 hours.
func (a *API) GetBadgeAssetCatalog(ctx context.Context, creds Credentials) (*BadgeAssetCatalog, error) {
	a.badgeCatMu.Lock()
	if a.badgeCat != nil && time.Since(a.badgeCatAt) < badgeAssetCacheTTL {
		cat := a.badgeCat
		a.badgeCatMu.Unlock()
		return cat, nil
	}
	a.badgeCatMu.Unlock()

	cat, err := a.QueryBadgeAssetCatalog(ctx, creds)
	if err != nil {
		// Return stale cache if available on transient failure.
		a.badgeCatMu.Lock()
		stale := a.badgeCat
		a.badgeCatMu.Unlock()
		if stale != nil {
			return stale, nil
		}
		return nil, err
	}

	a.badgeCatMu.Lock()
	a.badgeCat = cat
	a.badgeCatAt = time.Now()
	a.badgeCatMu.Unlock()
	return cat, nil
}

// PopulateBadgeRenderInfo attaches authentic Huya fan badge asset URLs onto each FanBadge.
// Best-effort: silently skips if the catalog cannot be fetched.
func (a *API) PopulateBadgeRenderInfo(ctx context.Context, creds Credentials, badges []*FanBadge) {
	if len(badges) == 0 {
		return
	}
	cat, err := a.GetBadgeAssetCatalog(ctx, creds)
	if err != nil || cat == nil {
		return
	}
	for _, b := range badges {
		if b == nil {
			continue
		}
		b.Render = ResolveBadgeRender(cat, b)
	}
}

// ResolveBadgeRender builds exact Retina CDN URLs for one FanBadge from Huya's catalog.
func ResolveBadgeRender(cat *BadgeAssetCatalog, b *FanBadge) *BadgeRenderInfo {
	if cat == nil || b == nil {
		return nil
	}
	maxLv := cat.MaxLevel
	if maxLv <= 0 {
		maxLv = badgeMaxLevelDef
	}
	lv := b.Level
	if lv < 1 {
		lv = 1
	} else if lv > maxLv {
		lv = maxLv
	}

	size := computeBadgeSizeBucket(b.BadgeName)
	sizeStr := strconv.Itoa(size)
	lvStr := strconv.Itoa(lv)

	sfIdentity := 0
	if b.FansIdentity >= 1 && b.FansIdentity <= 4 {
		sfIdentity = b.FansIdentity
	} else if b.SuperFan {
		sfIdentity = 1
	}
	guardIdentity := 0
	if b.GuardLevel >= 1 && b.GuardLevel <= 3 {
		guardIdentity = 10 + b.GuardLevel
	} else if b.FansIdentity >= 11 && b.FansIdentity <= 13 {
		guardIdentity = b.FansIdentity
	}
	var guardIconURL string
	if guardIdentity >= 11 && guardIdentity <= 13 {
		gTpl := cat.SFURL
		if gTpl == "" {
			gTpl = cat.NewSFURL
		}
		guardIconURL = applyBadgeTemplate(gTpl, map[string]string{
			"<size>":   "3",
			"<ua>":     badgeAssetUA,
			"<sfflag>": strconv.Itoa(guardIdentity),
		})
	}

	// 1) Custom anchor fan badge (定制粉丝牌) when active and registered in vBadgeBase.
	if b.Extinguished == 0 && b.BadgeID > 0 {
		if custom, ok := cat.CustomBadges[b.BadgeID]; ok && custom.IconURL != "" {
			sfMark := "0"
			if sfIdentity > 0 {
				sfMark = "1"
			}
			floorTpl := cat.NewFloorURL
			if floorTpl == "" {
				floorTpl = cat.FloorURL
			}
			floorURL := applyBadgeTemplate(floorTpl, map[string]string{
				"<size>":   sizeStr,
				"<ua>":     badgeAssetUA,
				"<status>": "1",
				"<sfmark>": sfMark,
				"<level>":  lvStr,
			})
			tier2Min := custom.Tier2MinLevel
			if tier2Min <= 0 {
				tier2Min = 14
			}
			iconType := "1"
			if lv >= tier2Min {
				iconType = "2"
			}
			iconURL := applyBadgeTemplate(custom.IconURL, map[string]string{
				"<ua>":   badgeAssetUA,
				"<type>": iconType,
			})
			levelURL := applyBadgeTemplate(cat.LevelURL, map[string]string{
				"<ua>":    badgeAssetUA,
				"<level>": lvStr,
			})
			var vFlagURL string
			if sfIdentity > 0 {
				sfTpl := cat.SFURL
				if size >= 3 && cat.NewSFURL != "" {
					sfTpl = cat.NewSFURL
				}
				vFlagURL = applyBadgeTemplate(sfTpl, map[string]string{
					"<size>":   sizeStr,
					"<ua>":     badgeAssetUA,
					"<sfflag>": strconv.Itoa(sfIdentity),
				})
			}
			if floorURL != "" {
				return &BadgeRenderInfo{
					Custom:       true,
					Size:         size,
					HasSuffix:    vFlagURL != "",
					FloorURL:     floorURL,
					IconURL:      iconURL,
					LevelURL:     levelURL,
					VFlagURL:     vFlagURL,
					GuardIconURL: guardIconURL,
				}
			}
		}
	}

	// 2) Standard Huya fan badge (normal / SuperFan / Annual SuperFan / SuperFan Plus / Guard / Extinguished).
	dark := "0"
	identity := 0
	if b.Extinguished != 0 {
		dark = "1"
	} else if sfIdentity > 0 {
		identity = sfIdentity
	} else if guardIdentity > 0 {
		identity = guardIdentity
	}

	var floorURL string
	if cat.NewIdentityURL != "" {
		floorURL = applyBadgeTemplate(cat.NewIdentityURL, map[string]string{
			"<size>":     sizeStr,
			"<ua>":       badgeAssetUA,
			"<identity>": strconv.Itoa(identity),
			"<dark>":     dark,
			"<level>":    lvStr,
			".name":      ".png",
		})
	} else if cat.IdentityURL != "" {
		floorURL = applyBadgeTemplate(cat.IdentityURL, map[string]string{
			"<identity>": strconv.Itoa(identity),
			"<dark>":     dark,
			"<level>":    lvStr,
			".name":      ".png",
		})
	}
	if floorURL == "" {
		return nil
	}
	return &BadgeRenderInfo{
		Custom:       false,
		Size:         size,
		HasSuffix:    identity != 0,
		FloorURL:     floorURL,
		GuardIconURL: guardIconURL,
	}
}

func computeBadgeSizeBucket(name string) int {
	trimmed := strings.TrimSpace(name)
	if trimmed == "" {
		return 2
	}
	// Count visual width: CJK/non-ASCII = 2 units, ASCII = 1 unit. <=4 units fits size=2 (1-2 Chinese chars).
	units := 0
	for _, r := range trimmed {
		if r > 0x7F {
			units += 2
		} else {
			units += 1
		}
	}
	if units <= 4 && utf8.RuneCountInString(trimmed) <= 4 {
		return 2
	}
	return 3
}

func applyBadgeTemplate(tpl string, repl map[string]string) string {
	if tpl == "" {
		return ""
	}
	out := tpl
	for k, v := range repl {
		out = strings.ReplaceAll(out, k, v)
	}
	return out
}

func normalizeBadgeHTTPS(raw string) string {
	s := strings.TrimSpace(raw)
	if s == "" {
		return ""
	}
	if strings.HasPrefix(s, "//") {
		return "https:" + s
	}
	if strings.HasPrefix(s, "http://") {
		return "https://" + strings.TrimPrefix(s, "http://")
	}
	return s
}
