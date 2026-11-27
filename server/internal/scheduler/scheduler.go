package scheduler

import (
	"context"
	"log"
	"sync"
	"time"

	"github.com/UCKETX/HuyaPure/server/internal/engine"
	"github.com/UCKETX/HuyaPure/server/internal/store"
)

// Scheduler manages per-account daily timers, cooldown retries, and expiry-rescue (-30m) alarms.
type Scheduler struct {
	Store  *store.Store
	Runner *engine.Runner

	mu           sync.Mutex
	dailyTimers  map[string]*time.Timer
	retryTimers  map[string]*time.Timer
	rescueTimers map[string]*time.Timer
	rootCtx      context.Context
	cancel       context.CancelFunc
}

func New(st *store.Store, runner *engine.Runner) *Scheduler {
	ctx, cancel := context.WithCancel(context.Background())
	return &Scheduler{
		Store:        st,
		Runner:       runner,
		dailyTimers:  make(map[string]*time.Timer),
		retryTimers:  make(map[string]*time.Timer),
		rescueTimers: make(map[string]*time.Timer),
		rootCtx:      ctx,
		cancel:       cancel,
	}
}

// Start initializes schedules for all persisted accounts.
func (s *Scheduler) Start() {
	for _, id := range s.Store.ListIDs() {
		s.RescheduleAccount(id)
	}
}

// Stop cancels all active timers cleanly.
func (s *Scheduler) Stop() {
	s.cancel()
	s.mu.Lock()
	defer s.mu.Unlock()
	for _, t := range s.dailyTimers {
		t.Stop()
	}
	for _, t := range s.retryTimers {
		t.Stop()
	}
	for _, t := range s.rescueTimers {
		t.Stop()
	}
	s.dailyTimers = make(map[string]*time.Timer)
	s.retryTimers = make(map[string]*time.Timer)
	s.rescueTimers = make(map[string]*time.Timer)
}

// RescheduleAccount recomputes the next daily trigger and expiry rescue timer for one account.
func (s *Scheduler) RescheduleAccount(accountID string) {
	s.mu.Lock()
	if t, ok := s.dailyTimers[accountID]; ok {
		t.Stop()
		delete(s.dailyTimers, accountID)
	}
	if t, ok := s.rescueTimers[accountID]; ok {
		t.Stop()
		delete(s.rescueTimers, accountID)
	}
	s.mu.Unlock()

	acc, ok := s.Store.GetClone(accountID)
	if !ok || !acc.Credentials.IsValid() || !acc.Config.AutoClaimEnabled {
		_, _ = s.Store.UpdateAccount(accountID, func(a *store.Account) error {
			a.State.NextScheduleTime = 0
			a.State.NextRescueTime = 0
			return nil
		})
		return
	}

	nextDaily := computeNextDailyTime(time.Now(), acc.Config.AutoClaimHour, acc.Config.AutoClaimMinute)
	delay := time.Until(nextDaily)
	if delay < time.Second {
		delay = time.Second
	}

	s.mu.Lock()
	s.dailyTimers[accountID] = time.AfterFunc(delay, func() {
		s.executeScheduled(accountID, "schedule")
	})
	s.mu.Unlock()

	// Check if an expiry-rescue alarm (-30 min before earliest expiring free Huliang) is needed before nextDaily.
	var rescueTime time.Time
	if acc.Config.AutoSendHuliang && acc.State.FreeEarliestExpire > 0 {
		exp := time.Unix(acc.State.FreeEarliestExpire, 0)
		candidate := exp.Add(-30 * time.Minute)
		now := time.Now()
		if candidate.After(now.Add(1*time.Minute)) && candidate.Before(nextDaily.Add(-5*time.Minute)) {
			rescueTime = candidate
			s.mu.Lock()
			s.rescueTimers[accountID] = time.AfterFunc(time.Until(rescueTime), func() {
				s.executeScheduled(accountID, "rescue")
			})
			s.mu.Unlock()
		}
	}

	_, _ = s.Store.UpdateAccount(accountID, func(a *store.Account) error {
		a.State.NextScheduleTime = nextDaily.UnixMilli()
		if !rescueTime.IsZero() {
			a.State.NextRescueTime = rescueTime.UnixMilli()
		} else {
			a.State.NextRescueTime = 0
		}
		return nil
	})
	log.Printf("[Scheduler] account=%s nextDaily=%s rescue=%v",
		acc.Label, nextDaily.Format("2006-01-02 15:04"), !rescueTime.IsZero())
}

// ScheduleAfterRun handles post-run cooldown retry & expiry rescue scheduling.
func (s *Scheduler) ScheduleAfterRun(accountID string, res engine.RunResult) {
	s.mu.Lock()
	if t, ok := s.retryTimers[accountID]; ok {
		t.Stop()
		delete(s.retryTimers, accountID)
	}
	s.mu.Unlock()

	if res.CooldownSeconds > 0 {
		retryAt := time.Now().Add(time.Duration(res.CooldownSeconds+10) * time.Second)
		s.mu.Lock()
		s.retryTimers[accountID] = time.AfterFunc(time.Until(retryAt), func() {
			s.executeScheduled(accountID, "retry")
		})
		s.mu.Unlock()
		_, _ = s.Store.UpdateAccount(accountID, func(a *store.Account) error {
			a.State.NextRetryTime = retryAt.UnixMilli()
			return nil
		})
		log.Printf("[Scheduler] account=%s scheduled cooldown retry at %s",
			accountID, retryAt.Format("15:04:05"))
	} else {
		_, _ = s.Store.UpdateAccount(accountID, func(a *store.Account) error {
			a.State.NextRetryTime = 0
			return nil
		})
	}
	s.RescheduleAccount(accountID)
}

func (s *Scheduler) executeScheduled(accountID, trigger string) {
	if s.rootCtx.Err() != nil {
		return
	}
	ctx, cancel := context.WithTimeout(s.rootCtx, 3*time.Minute)
	defer cancel()
	log.Printf("[Scheduler] firing trigger=%s for account=%s", trigger, accountID)
	res, err := s.Runner.RunAccount(ctx, accountID, trigger)
	if err != nil {
		log.Printf("[Scheduler] trigger=%s account=%s error: %v", trigger, accountID, err)
		s.RescheduleAccount(accountID)
		return
	}
	s.ScheduleAfterRun(accountID, res)
}

func computeNextDailyTime(now time.Time, hour, minute int) time.Time {
	if hour < 0 || hour > 23 {
		hour = 8
	}
	if minute < 0 || minute > 59 {
		minute = 30
	}
	candidate := time.Date(now.Year(), now.Month(), now.Day(), hour, minute, 0, 0, now.Location())
	if !candidate.After(now.Add(5 * time.Second)) {
		candidate = candidate.AddDate(0, 0, 1)
	}
	return candidate
}
