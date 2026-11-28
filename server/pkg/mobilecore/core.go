package mobilecore

import (
	"context"
	"encoding/json"
	"fmt"
	"log"
	"net"
	"net/http"
	"os"
	"strconv"
	"strings"
	"sync"
	"time"
	_ "time/tzdata"

	"github.com/UCKETX/HuyaPure/server/internal/engine"
	"github.com/UCKETX/HuyaPure/server/internal/huya"
	"github.com/UCKETX/HuyaPure/server/internal/notify"
	"github.com/UCKETX/HuyaPure/server/internal/scheduler"
	"github.com/UCKETX/HuyaPure/server/internal/store"
	"github.com/UCKETX/HuyaPure/server/internal/web"
)

var (
	mu       sync.Mutex
	instance *Instance
)

type Instance struct {
	Port      int
	DataDir   string
	Store     *store.Store
	Runner    *engine.Runner
	Scheduler *scheduler.Scheduler
	HTTP      *http.Server
	Listener  net.Listener
}

// SetupMobileTimezone applies TZ (using Go's embedded time/tzdata on Android/minimal
// systems that lack /usr/share/zoneinfo) or HUBAN_TZ_OFFSET_SEC as fallback so daily
// date boundaries and scheduled alarm times match the device's local timezone.
func SetupMobileTimezone() {
	if tz := strings.TrimSpace(os.Getenv("TZ")); tz != "" {
		if loc, err := time.LoadLocation(tz); err == nil && loc != nil {
			time.Local = loc
			return
		}
	}
	if raw := strings.TrimSpace(os.Getenv("HUBAN_TZ_OFFSET_SEC")); raw != "" {
		if sec, err := strconv.Atoi(raw); err == nil && sec >= -14*3600 && sec <= 14*3600 {
			time.Local = time.FixedZone("Local", sec)
		}
	}
}

// SetupMobileDNS configures net.DefaultResolver when running on Android (where
// /etc/resolv.conf does not exist for CGO_ENABLED=0 Linux binaries) or whenever
// HUBAN_DNS_SERVERS is explicitly provided (comma-separated IP or IP:port list).
func SetupMobileDNS() {
	raw := strings.TrimSpace(os.Getenv("HUBAN_DNS_SERVERS"))
	needsFallback := raw != ""
	if !needsFallback {
		if _, err := os.Stat("/etc/resolv.conf"); err != nil {
			needsFallback = true
		}
	}
	if !needsFallback {
		return
	}

	var servers []string
	for _, item := range strings.Split(raw, ",") {
		item = strings.TrimSpace(item)
		if item == "" {
			continue
		}
		if _, _, err := net.SplitHostPort(item); err != nil {
			item = net.JoinHostPort(item, "53")
		}
		servers = append(servers, item)
	}
	// Always append reliable China + global public DNS fallbacks at the end.
	servers = append(servers, "223.5.5.5:53", "119.29.29.29:53", "8.8.8.8:53")

	net.DefaultResolver = &net.Resolver{
		PreferGo: true,
		Dial: func(ctx context.Context, network, _ string) (net.Conn, error) {
			d := &net.Dialer{Timeout: 4 * time.Second}
			var lastErr error
			for _, addr := range servers {
				conn, err := d.DialContext(ctx, network, addr)
				if err == nil {
					return conn, nil
				}
				lastErr = err
			}
			return nil, lastErr
		},
	}
}

// Start launches the embedded Huban HTTP + engine runtime on a loopback port.
// If already running with the same dataDir, returns the existing port.
func Start(dataDir, version string) (int, error) {
	mu.Lock()
	defer mu.Unlock()

	if instance != nil {
		return instance.Port, nil
	}
	SetupMobileTimezone()
	SetupMobileDNS()

	if strings.TrimSpace(version) == "" {
		version = "1.7.0-mobile"
	}
	st, err := store.Open(dataDir)
	if err != nil {
		return 0, fmt.Errorf("open store: %w", err)
	}

	wup := huya.NewWupClient()
	api := huya.NewAPI(wup)
	qrMgr := huya.NewQRLoginManager()
	mobileAuth := huya.NewMobileAuthClient()
	thirdMgr := huya.NewThirdLoginManager(mobileAuth)
	notifier := notify.New()
	runner := engine.NewRunner(api, st, notifier)
	runner.MobileAuth = mobileAuth
	sched := scheduler.New(st, runner)
	sched.Start()

	go func() {
		for _, id := range st.ListIDs() {
			acc, ok := st.GetClone(id)
			if !ok || !acc.Credentials.IsValid() {
				continue
			}
			if acc.NickName == "" || store.IsDefaultAccountLabel(acc.Label, acc.Credentials.UID) {
				ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
				runner.SyncUserProfile(ctx, id)
				cancel()
			}
			ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
			runner.BackfillBadgeAssets(ctx, id)
			cancel()
		}
	}()

	srv := web.NewServer(web.Deps{
		Version:    version,
		Password:   "",
		Store:      st,
		QRManager:  qrMgr,
		ThirdLogin: thirdMgr,
		MobileAuth: mobileAuth,
		Runner:     runner,
		Scheduler:  sched,
		Notifier:   notifier,
	})

	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		sched.Stop()
		return 0, fmt.Errorf("listen loopback: %w", err)
	}
	port := ln.Addr().(*net.TCPAddr).Port

	httpSrv := &http.Server{
		Handler:           srv.Handler(),
		ReadHeaderTimeout: 10 * time.Second,
	}
	go func() {
		if err := httpSrv.Serve(ln); err != nil && err != http.ErrServerClosed {
			log.Printf("[MobileCore] HTTP serve error: %v", err)
		}
	}()

	instance = &Instance{
		Port:      port,
		DataDir:   dataDir,
		Store:     st,
		Runner:    runner,
		Scheduler: sched,
		HTTP:      httpSrv,
		Listener:  ln,
	}
	log.Printf("[MobileCore] started on 127.0.0.1:%d (data=%s)", port, dataDir)
	return port, nil
}

// Port returns the active loopback port, or 0 if not started.
func Port() int {
	mu.Lock()
	defer mu.Unlock()
	if instance == nil {
		return 0
	}
	return instance.Port
}

// Stop shuts down the embedded runtime cleanly.
func Stop() {
	mu.Lock()
	inst := instance
	instance = nil
	mu.Unlock()
	if inst == nil {
		return
	}
	inst.Scheduler.Stop()
	ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
	defer cancel()
	_ = inst.HTTP.Shutdown(ctx)
}

// RunAllSync runs all logged-in accounts synchronously (for iOS AppIntents / Shortcuts
// and background triggers) and returns a JSON report including formatted notifications.
func RunAllSync(trigger string, onlyEnabled bool) string {
	mu.Lock()
	inst := instance
	mu.Unlock()
	if inst == nil {
		return `{"ok":false,"error":"核心引擎未启动"}`
	}
	trigger = strings.TrimSpace(trigger)
	if trigger == "" {
		trigger = "schedule"
	}

	type item struct {
		AccountID       string `json:"accountId"`
		Label           string `json:"label"`
		Success         bool   `json:"success"`
		Message         string `json:"message"`
		Title           string `json:"title,omitempty"`
		Body            string `json:"body,omitempty"`
		CooldownSeconds int    `json:"cooldownSeconds"`
	}

	ids := inst.Store.ListIDs()
	results := make([]item, 0, len(ids))
	for _, id := range ids {
		acc, ok := inst.Store.GetClone(id)
		if !ok || !acc.Credentials.IsValid() {
			continue
		}
		if onlyEnabled && !acc.Config.AutoClaimEnabled {
			continue
		}
		ctx, cancel := context.WithTimeout(context.Background(), 3*time.Minute)
		res, err := inst.Runner.RunAccount(ctx, id, trigger)
		cancel()
		if err != nil {
			results = append(results, item{
				AccountID: id,
				Label:     acc.Label,
				Success:   false,
				Message:   err.Error(),
			})
			continue
		}
		inst.Scheduler.ScheduleAfterRun(id, res)
		results = append(results, item{
			AccountID:       id,
			Label:           acc.Label,
			Success:         res.Success,
			Message:         res.Message,
			Title:           res.NotifyTitle,
			Body:            res.NotifyBody,
			CooldownSeconds: res.CooldownSeconds,
		})
	}
	raw, _ := json.Marshal(map[string]any{
		"ok":      true,
		"count":   len(results),
		"results": results,
	})
	return string(raw)
}
