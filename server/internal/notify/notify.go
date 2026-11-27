package notify

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"strings"
	"time"

	"github.com/UCKETX/HuyaPure/server/internal/store"
)

type Notifier struct {
	Client *http.Client
}

func New() *Notifier {
	return &Notifier{
		Client: &http.Client{Timeout: 10 * time.Second},
	}
}

// Send dispatches a notification to every configured channel in NotifyConfig.
// Returns per-channel errors (if any).
func (n *Notifier) Send(ctx context.Context, cfg store.NotifyConfig, title, body string) []string {
	var errs []string
	if u := strings.TrimSpace(cfg.BarkURL); u != "" {
		if err := n.sendBark(ctx, u, title, body); err != nil {
			errs = append(errs, "Bark: "+err.Error())
		}
	}
	if strings.TrimSpace(cfg.TelegramBotToken) != "" && strings.TrimSpace(cfg.TelegramChatID) != "" {
		if err := n.sendTelegram(ctx, strings.TrimSpace(cfg.TelegramBotToken), strings.TrimSpace(cfg.TelegramChatID), title, body); err != nil {
			errs = append(errs, "Telegram: "+err.Error())
		}
	}
	if key := strings.TrimSpace(cfg.ServerChanKey); key != "" {
		if err := n.sendServerChan(ctx, key, title, body); err != nil {
			errs = append(errs, "Server酱: "+err.Error())
		}
	}
	if u := strings.TrimSpace(cfg.DingTalkWebhook); u != "" {
		if err := n.sendDingTalk(ctx, u, title, body); err != nil {
			errs = append(errs, "钉钉: "+err.Error())
		}
	}
	if u := strings.TrimSpace(cfg.FeishuWebhook); u != "" {
		if err := n.sendFeishu(ctx, u, title, body); err != nil {
			errs = append(errs, "飞书: "+err.Error())
		}
	}
	if u := strings.TrimSpace(cfg.CustomWebhookURL); u != "" {
		if err := n.postJSON(ctx, u, map[string]string{"title": title, "body": body}); err != nil {
			errs = append(errs, "Webhook: "+err.Error())
		}
	}
	return errs
}

func (n *Notifier) sendBark(ctx context.Context, baseURL, title, body string) error {
	endpoint := strings.TrimRight(baseURL, "/")
	payload := map[string]any{
		"title": title,
		"body":  body,
		"group": "虎伴",
	}
	return n.postJSON(ctx, endpoint, payload)
}

func (n *Notifier) sendTelegram(ctx context.Context, token, chatID, title, body string) error {
	endpoint := fmt.Sprintf("https://api.telegram.org/bot%s/sendMessage", token)
	return n.postJSON(ctx, endpoint, map[string]any{
		"chat_id": chatID,
		"text":    title + "\n\n" + body,
	})
}

func (n *Notifier) sendServerChan(ctx context.Context, sendKey, title, body string) error {
	endpoint := fmt.Sprintf("https://sctapi.ftqq.com/%s.send", url.PathEscape(sendKey))
	// Server酱 desp uses Markdown where single newlines collapse into spaces.
	mdBody := strings.ReplaceAll(strings.TrimSpace(body), "\n", "\n\n")
	form := url.Values{}
	form.Set("title", title)
	form.Set("desp", mdBody)
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, endpoint, strings.NewReader(form.Encode()))
	if err != nil {
		return err
	}
	req.Header.Set("Content-Type", "application/x-www-form-urlencoded")
	resp, err := n.Client.Do(req)
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	_, _ = io.Copy(io.Discard, io.LimitReader(resp.Body, 16*1024))
	if resp.StatusCode >= 300 {
		return fmt.Errorf("HTTP %d", resp.StatusCode)
	}
	return nil
}

func (n *Notifier) sendDingTalk(ctx context.Context, webhook, title, body string) error {
	return n.postJSON(ctx, webhook, map[string]any{
		"msgtype": "text",
		"text":    map[string]string{"content": title + "\n\n" + body},
	})
}

func (n *Notifier) sendFeishu(ctx context.Context, webhook, title, body string) error {
	return n.postJSON(ctx, webhook, map[string]any{
		"msg_type": "text",
		"content":  map[string]string{"text": title + "\n\n" + body},
	})
}

func (n *Notifier) postJSON(ctx context.Context, endpoint string, payload any) error {
	raw, err := json.Marshal(payload)
	if err != nil {
		return err
	}
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, endpoint, bytes.NewReader(raw))
	if err != nil {
		return err
	}
	req.Header.Set("Content-Type", "application/json")
	resp, err := n.Client.Do(req)
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	respBody, _ := io.ReadAll(io.LimitReader(resp.Body, 16*1024))
	if resp.StatusCode >= 300 {
		return fmt.Errorf("HTTP %d: %s", resp.StatusCode, string(respBody))
	}
	return nil
}
