package huya

import "testing"

func TestResolveBadgeRender(t *testing.T) {
	cat := &BadgeAssetCatalog{
		LevelURL:       "https://fileserver.cdn.huya.com/level/<ua>_<level>.png",
		SFURL:          "https://livewebbs2.msstatic.com/sf_<ua>_<sfflag>.png",
		NewSFURL:       "https://fileserver.cdn.huya.com/newsf/<size>_<ua>_<sfflag>.png",
		NewFloorURL:    "https://fileserver.cdn.huya.com/floor/<size>_<ua>_<status>_<sfmark>_<level>.webp",
		NewIdentityURL: "https://fileserver.cdn.huya.com/ident/<size>_<ua>_<identity>_<dark>_<level>.name",
		MaxLevel:       52,
		CustomBadges: map[int64]CustomBadgeAsset{
			2176998321: {
				IconURL:       "https://livewebbs2.msstatic.com/icon_<ua>_<type>.webp",
				Tier2MinLevel: 14,
				Tier3MinLevel: 21,
			},
		},
	}

	// 1) Custom anchor badge (豪粉 Lv.19)
	custom := ResolveBadgeRender(cat, &FanBadge{
		BadgeID:   2176998321,
		BadgeName: "豪粉",
		Level:     19,
	})
	if custom == nil || !custom.Custom || custom.Size != 2 || custom.HasSuffix {
		t.Fatalf("unexpected custom badge render: %+v", custom)
	}
	if custom.FloorURL != "https://fileserver.cdn.huya.com/floor/2_6_1_0_19.webp" {
		t.Fatalf("unexpected custom floor: %s", custom.FloorURL)
	}
	if custom.IconURL != "https://livewebbs2.msstatic.com/icon_6_2.webp" {
		t.Fatalf("unexpected custom icon: %s", custom.IconURL)
	}
	if custom.LevelURL != "https://fileserver.cdn.huya.com/level/6_19.png" {
		t.Fatalf("unexpected custom level: %s", custom.LevelURL)
	}

	// 2) Standard SuperFan badge (糖水 Lv.10 超粉)
	sf := ResolveBadgeRender(cat, &FanBadge{
		BadgeID:      1199667880631,
		BadgeName:    "糖水",
		Level:        10,
		SuperFan:     true,
		FansIdentity: 1,
	})
	if sf == nil || sf.Custom || sf.Size != 2 || !sf.HasSuffix {
		t.Fatalf("unexpected sf badge render: %+v", sf)
	}
	if sf.FloorURL != "https://fileserver.cdn.huya.com/ident/2_6_1_0_10.png" {
		t.Fatalf("unexpected sf floor: %s", sf.FloorURL)
	}

	// 3) Standard 3-char Guard badge (小虎牙 Lv.12 超级守护)
	guard := ResolveBadgeRender(cat, &FanBadge{
		BadgeName:  "小虎牙",
		Level:      12,
		GuardLevel: 2,
	})
	if guard == nil || guard.Size != 3 || !guard.HasSuffix {
		t.Fatalf("unexpected guard badge render: %+v", guard)
	}
	if guard.FloorURL != "https://fileserver.cdn.huya.com/ident/3_6_12_0_12.png" {
		t.Fatalf("unexpected guard floor: %s", guard.FloorURL)
	}
	if guard.GuardIconURL != "https://livewebbs2.msstatic.com/sf_6_12.png" {
		t.Fatalf("unexpected guard icon url: %s", guard.GuardIconURL)
	}

	// 4) Extinguished badge drops custom/identity into dark=1
	ext := ResolveBadgeRender(cat, &FanBadge{
		BadgeID:      2176998321,
		BadgeName:    "豪粉",
		Level:        19,
		Extinguished: 1,
	})
	if ext == nil || ext.Custom || ext.HasSuffix {
		t.Fatalf("unexpected extinguished badge render: %+v", ext)
	}
	if ext.FloorURL != "https://fileserver.cdn.huya.com/ident/2_6_0_1_19.png" {
		t.Fatalf("unexpected extinguished floor: %s", ext.FloorURL)
	}
}
