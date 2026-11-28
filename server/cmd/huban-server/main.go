package main

import (
	"context"
	"flag"
	"fmt"
	"log"
	"net"
	"net/http"
	"os"
	"os/signal"
	"strings"
	"syscall"
	"time"

	"github.com/UCKETX/HuyaPure/server/internal/engine"
	"github.com/UCKETX/HuyaPure/server/internal/huya"
	"github.com/UCKETX/HuyaPure/server/internal/notify"
	"github.com/UCKETX/HuyaPure/server/internal/scheduler"
	"github.com/UCKETX/HuyaPure/server/internal/store"
	"github.com/UCKETX/HuyaPure/server/internal/web"
	"github.com/UCKETX/HuyaPure/server/pkg/mobilecore"
)

var Version = "1.7.0-server"

func main() {
	var (
		addr     = flag.String("addr", envOr("HUBAN_ADDR", ":8686"), "HTTP listen address")
		dataDir  = flag.String("data", envOr("HUBAN_DATA_DIR", "./data"), "Persistent data directory")
		password = flag.String("password", os.Getenv("HUBAN_PASSWORD"), "Optional Web UI access password")
	)
	flag.Parse()
	mobilecore.SetupMobileTimezone()
	mobilecore.SetupMobileDNS()

	st, err := store.Open(*dataDir)
	if err != nil {
		log.Fatalf("Failed to open data store (%s): %v", *dataDir, err)
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

	// Backfill real Huya nicknames/avatars and authentic fan badge assets on startup.
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
		Version:    Version,
		Password:   *password,
		Store:      st,
		QRManager:  qrMgr,
		ThirdLogin: thirdMgr,
		MobileAuth: mobileAuth,
		Runner:     runner,
		Scheduler:  sched,
		Notifier:   notifier,
	})

	ln, err := net.Listen("tcp", *addr)
	if err != nil {
		log.Fatalf("Failed to listen on %s: %v", *addr, err)
	}
	boundPort := ln.Addr().(*net.TCPAddr).Port

	httpSrv := &http.Server{
		Handler:           srv.Handler(),
		ReadHeaderTimeout: 10 * time.Second,
	}

	go func() {
		displayAddr := ln.Addr().String()
		if strings.HasPrefix(displayAddr, "[::]:") {
			displayAddr = fmt.Sprintf("localhost:%d", boundPort)
		}
		fmt.Printf("HUBAN_READY_PORT=%d\n", boundPort)
		log.Printf("虎伴 Server (%s) listening on http://%s (data=%s, auth=%v)",
			Version, displayAddr, *dataDir, *password != "")
		if err := httpSrv.Serve(ln); err != nil && err != http.ErrServerClosed {
			log.Fatalf("HTTP server error: %v", err)
		}
	}()

	sigCh := make(chan os.Signal, 1)
	signal.Notify(sigCh, syscall.SIGINT, syscall.SIGTERM)
	<-sigCh

	log.Println("Shutting down 虎伴 Server...")
	sched.Stop()
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	_ = httpSrv.Shutdown(ctx)
}

func envOr(key, fallback string) string {
	if v := os.Getenv(key); v != "" {
		return v
	}
	return fallback
}
